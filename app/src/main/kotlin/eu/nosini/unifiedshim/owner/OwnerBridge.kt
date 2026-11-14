package eu.nosini.unifiedshim.owner

import android.content.Context
import eu.nosini.unifiedshim.common.Config
import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.role.Role
import eu.nosini.unifiedshim.transport.BridgeLink
import eu.nosini.unifiedshim.transport.ClientHello
import eu.nosini.unifiedshim.transport.loopback.LoopbackClient
import eu.nosini.unifiedshim.transport.protocol.Frame
import eu.nosini.unifiedshim.transport.protocol.Protocol
import org.unifiedpush.android.connector.UnifiedPush
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val msgIdRng = SecureRandom()
private fun nextMsgId(): Long = msgIdRng.nextLong() and Long.MAX_VALUE

private const val HELLO_TIMEOUT_MS = 3_000L
private const val REGS_TIMEOUT_MS = 1_500L
private const val ACK_TIMEOUT_MS = 5_000L
private const val CONNECT_TIMEOUT_MS = 3_000
private const val SWEEP_CONNECT_TIMEOUT_MS = 750
private const val SWEEP_PRUNE_AFTER = 5
private const val MAX_PUSH_ATTEMPTS = 5

/**
 * Owner-side coordinator. The owner shim is an event-driven UnifiedPush
 * *connector* registered with the ntfy app; it never opens its own ntfy
 * connection. Each entry point opens a short-lived connection to an always-up
 * distributor listener, reconciles the registration snapshot the distributor
 * pushes, and streams back endpoints / pushes.
 *
 * There may be more than one distributor profile (private space + work profile),
 * each on its own loopback port. Every token belongs to exactly one distributor;
 * we learn the mapping from the port whose snapshot the token appears in and
 * route strictly by it. Token-directed calls open a session to the one owning
 * port; if the port is not yet known we reconcile across *all* configured ports,
 * which binds the mapping and flushes.
 *
 * Sessions are serialized per port, so a push to a known port never waits behind
 * a full sweep of the other ports. Sweep requests themselves are coalesced.
 */
object OwnerBridge {

    private val portLocks = ConcurrentHashMap<Int, Any>()
    private val sweepLock = Any()
    private var sweeping = false
    private var rerunSweep = false
    private val sweepFailures = ConcurrentHashMap<Int, Int>()

    private fun portLock(port: Int): Any = portLocks.getOrPut(port) { Any() }

    /** Inbound push from the ntfy app. Durably enqueued first, then flushed. */
    fun deliver(context: Context, token: String, payload: ByteArray) {
        val msgId = nextMsgId()
        OwnerStore.get(context).enqueue(OwnerStore.PendingPush(msgId, token, payload))
        routeToken(context, token)
    }

    /** A (new) endpoint minted by the ntfy app for one of our instances. */
    fun onEndpoint(context: Context, token: String, endpoint: String) {
        OwnerStore.get(context).putEndpoint(token, endpoint)
        routeToken(context, token) { session -> session.sendEndpoint(token, endpoint) }
    }

    /** The ntfy app released one of our instances. */
    fun onUnregistered(context: Context, token: String) {
        // Resolve the owning port before forget() clears the mapping.
        val port = OwnerStore.get(context).portFor(token)
        OwnerStore.get(context).forget(token)
        val sent = port != null &&
            runSession(context, port, fullReconcile = false) { session -> session.sendDeprovisioned(token) } != null
        if (!sent) reconcileAllPorts(context)
    }

    /** The ntfy app rejected a registration; relay it to the app via its distributor. */
    fun registrationFailed(context: Context, token: String, reason: String = "INTERNAL_ERROR") {
        routeToken(context, token) { session -> session.sendRegistrationFailed(token, reason) }
    }

    /** Manual sync (owner app opened): reconcile pending registrations. */
    fun reconcile(context: Context, onDone: () -> Unit = {}) {
        reconcileAllPorts(context, onDone = onDone)
    }

    /** Reconcile a single distributor port. Used by the optional auto-provision
     *  listener when a distributor nudges us, so we don't sweep every port. */
    fun reconcilePort(context: Context, port: Int) {
        runSession(context, port)
    }

    // ---- routing ----

    /** Route a token-directed op: session to its owning port if known, else a full
     *  reconcile across all ports (which binds the mapping and flushes). */
    private fun routeToken(context: Context, token: String, block: (Session) -> Unit = {}) {
        val port = OwnerStore.get(context).portFor(token)
        if (port != null) {
            val regs = runSession(
                context, port,
                expectedToken = token,
                fullReconcile = false,
            ) { block(it) }
            if (regs?.active?.contains(token) == true) return
        }
        reconcileAllPorts(context, expectedToken = token, block = block)
    }

    /**
     * Sweep every candidate distributor port sequentially: the learned ports plus the
     * default scan range (announces can be missed — owner app closed, FGS off — so we
     * also scan). Excludes the owner's own listener port so we never dial ourselves.
     * Sweep probes use a short connect timeout; concurrent requests are coalesced.
     */
    private fun reconcileAllPorts(
        context: Context,
        expectedToken: String? = null,
        block: (Session) -> Unit = {},
        onDone: () -> Unit = {},
    ) {
        val alreadyRunning = synchronized(sweepLock) {
            if (sweeping) {
                // The running sweep goes around once more, so pushes/endpoints queued
                // after it passed a port are still flushed.
                rerunSweep = true
                true
            } else {
                sweeping = true
                false
            }
        }
        if (alreadyRunning) {
            Logx.d("owner", "sweep already running; coalescing request")
            onDone()
            return
        }
        try {
            do {
                synchronized(sweepLock) { rerunSweep = false }
                sweepOnce(context, expectedToken, block)
            } while (synchronized(sweepLock) { rerunSweep })
        } finally {
            synchronized(sweepLock) { sweeping = false }
            onDone()
        }
    }

    private fun sweepOnce(context: Context, expectedToken: String?, block: (Session) -> Unit) {
        run {
            val cfg = Config(context)
            val ownerListenerPort = cfg.ownerListenerPort
            val known = cfg.ownerBridgePorts.filter { it != ownerListenerPort }
            val ports = (known + Config.PORT_SCAN_RANGE).distinct() - ownerListenerPort
            val seen = mutableSetOf<String>()
            val answered = mutableSetOf<Int>()
            for (port in ports) {
                val regs = runSession(
                    context, port,
                    expectedToken = expectedToken,
                    fullReconcile = true,
                    connectTimeoutMs = SWEEP_CONNECT_TIMEOUT_MS,
                    onSnapshot = { snapshot -> seen += snapshot.active },
                ) { block(it) }
                if (regs != null) {
                    answered.add(port)
                    sweepFailures.remove(port)
                } else if (port !in Config.PORT_SCAN_RANGE) {
                    val failures = (sweepFailures[port] ?: 0) + 1
                    sweepFailures[port] = failures
                    if (failures >= SWEEP_PRUNE_AFTER) {
                        Config.removeOwnerBridgePort(context, port)
                        sweepFailures.remove(port)
                        Logx.i("owner", "pruned unreachable distributor port :$port after $failures sweeps")
                    }
                }
            }
            // Only prune a stale token when every known port answered AND the port it
            // was bound to answered without it; a locked/stopped profile's tokens (bound
            // to a port that did not answer) are never dropped.
            if (expectedToken == null && known.isNotEmpty() && known.all { it in answered }) {
                val store = OwnerStore.get(context)
                for (token in store.knownTokens()) {
                    val owner = store.portFor(token) ?: continue
                    if (owner in answered && token !in seen) {
                        Logx.i("owner", "pruning token=$token: absent from every distributor snapshot")
                        unregisterToken(context, token)
                    }
                }
            }
        }
    }

    // ---- session machinery ----

    /** Open + handshake, apply one snapshot (when [fullReconcile]), flush, and run
     *  [block] if [expectedToken] is absent from the snapshot or present in it.
     *  Returns the snapshot, or null when the port did not connect/handshake/answer. */
    private fun runSession(
        context: Context,
        port: Int,
        expectedToken: String? = null,
        fullReconcile: Boolean = true,
        connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
        onSnapshot: (Protocol.Registrations) -> Unit = {},
        block: (Session) -> Unit = {},
    ): Protocol.Registrations? = synchronized(portLock(port)) {
        val session = openSession(context, port, connectTimeoutMs)
            ?: return@synchronized null
        // Handshake succeeded → a real, signed distributor lives on this port; learn
        // it so later reconciles dial it directly even if we found it only by scanning.
        Config.addOwnerBridgePort(context, port)
        try {
            val gotSnapshot = session.regsLatch.await(REGS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val regs = if (gotSnapshot) session.registrations else null
            if (regs == null) {
                Logx.w("owner", "no registration snapshot from :$port within ${REGS_TIMEOUT_MS}ms; skipping this session")
                return@synchronized null
            }
            onSnapshot(regs)
            if (fullReconcile) applyReconcile(context, session, port, regs)
            // Ground truth for what THIS distributor owns is its snapshot, not the
            // token→port map — so flush only tokens in regs.active.
            flushOutbox(context, session, regs.active.toSet())
            if (expectedToken == null || expectedToken in regs.active) block(session)
            regs
        } finally {
            session.close()
        }
    }

    private fun openSession(context: Context, port: Int, connectTimeoutMs: Int): Session? {
        val session = Session()
        val link = LoopbackClient.connectOnce(
            context, port, session::onFrame, session::onClosed, connectTimeoutMs,
        ) ?: return null
        session.link = link
        if (!session.hello.handshake(link)) {
            Logx.w("owner", "handshake failed on :$port")
            link.close()
            return null
        }
        return session
    }

    /**
     * React to a distributor's snapshot: bind every token in it to this port,
     * register unknown tokens with the ntfy app (their endpoints arrive later via
     * onNewEndpoint), re-send endpoints we already know, release deprovisioned
     * tokens, and flush any queued pushes. The snapshot is naturally port-scoped,
     * so endpoint re-sends and deprovisions here only touch this port's tokens.
     */
    private fun applyReconcile(context: Context, session: Session, port: Int, regs: Protocol.Registrations) {
        val store = OwnerStore.get(context)
        val distributorReady = UnifiedPush.getSavedDistributor(context) != null

        for (token in regs.active) {
            // Bind the token to the port that owns it (covers both already-known and
            // newly registered tokens) so the outbox flush routes it correctly.
            store.bindPort(token, port)
            val endpoint = store.endpointFor(token)
            if (endpoint != null) {
                session.sendEndpoint(token, endpoint)
            } else if (distributorReady) {
                Logx.i("owner", "registering instance with ntfy app for token=$token (:$port)")
                UnifiedPush.registerApp(context, token)
            } else {
                Logx.w("owner", "no ntfy distributor selected; cannot provision token=$token")
            }
        }
        for (token in regs.deprovision) {
            unregisterToken(context, token)
            session.sendDeprovisioned(token)
        }
    }

    private fun unregisterToken(context: Context, token: String) {
        runCatching { UnifiedPush.unregisterApp(context, token) }
        OwnerStore.get(context).forget(token)
    }

    /**
     * Send queued pushes on the live link. A push is dropped when it can never fit
     * in a frame or after [MAX_PUSH_ATTEMPTS] failed sends, so one bad push cannot
     * block the rest. Removals/attempt counts are persisted once per flush.
     *
     * Correctness is gated by snapshot membership, NOT the token→port map: only tokens
     * in [activeTokens] (this session's `REGISTRATIONS.active`) are flushed. With
     * auto-assigned ports a remembered port can be re-occupied by a *different*
     * distributor profile, and the map might still point a token here. A distributor
     * acks-and-drops unknown tokens, so a mis-flush would falsely ack and permanently
     * lose the push.
     */
    private fun flushOutbox(context: Context, session: Session, activeTokens: Set<String>) {
        val store = OwnerStore.get(context)
        val removed = mutableSetOf<Long>()
        val attempts = mutableMapOf<Long, Int>()
        try {
            for (p in store.peekPending()) {
                if (!session.link.isOpen) break
                if (p.token !in activeTokens) continue
                if (Protocol.pushBodySize(p.token, p.payload) > Frame.MAX_FRAME - 1) {
                    Logx.w("owner", "dropping oversized push msgId=${p.msgId} (${p.payload.size}B payload)")
                    removed.add(p.msgId)
                    continue
                }
                if (session.sendPushAndAwait(p.msgId, p.token, p.payload)) {
                    removed.add(p.msgId)
                } else {
                    val count = p.attempts + 1
                    attempts[p.msgId] = count
                    if (count >= MAX_PUSH_ATTEMPTS) {
                        Logx.w("owner", "dropping push msgId=${p.msgId} after $count failed attempts")
                        removed.add(p.msgId)
                    }
                    if (!session.link.isOpen) break
                }
            }
        } finally {
            store.finishFlush(removed, attempts)
        }
    }

    private class Session {
        lateinit var link: BridgeLink
        val hello = ClientHello(Role.OWNER.name, HELLO_TIMEOUT_MS)
        val regsLatch = CountDownLatch(1)
        @Volatile var registrations: Protocol.Registrations? = null
        private val ackLatches = ConcurrentHashMap<Long, CountDownLatch>()

        fun onFrame(frame: Frame, _link: BridgeLink) {
            if (hello.onFrame(frame)) return
            when (frame.type) {
                Protocol.REGISTRATIONS -> {
                    registrations = Protocol.parseRegistrations(frame)
                    regsLatch.countDown()
                }
                Protocol.PUSH_ACK -> {
                    ackLatches.remove(Protocol.parseJson(frame).getLong("msgId"))?.countDown()
                }
                else -> Logx.w("owner", "unexpected frame type=${frame.type}")
            }
        }

        fun onClosed(l: BridgeLink) {
            // Release anyone blocked on a latch so callers fail fast on a drop.
            hello.onClosed()
            regsLatch.countDown()
            ackLatches.values.forEach { it.countDown() }
        }

        fun sendEndpoint(token: String, endpoint: String) = link.send(Protocol.endpoint(token, endpoint))
        fun sendDeprovisioned(token: String) = link.send(Protocol.deprovisioned(token))
        fun sendRegistrationFailed(token: String, reason: String) =
            link.send(Protocol.registrationFailed(token, reason))

        fun sendPushAndAwait(msgId: Long, token: String, payload: ByteArray): Boolean {
            if (!link.isOpen) return false
            val latch = CountDownLatch(1)
            ackLatches[msgId] = latch
            link.send(Protocol.push(msgId, token, payload))
            val acked = latch.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) && ackLatches[msgId] == null
            ackLatches.remove(msgId)
            return acked
        }

        fun close() = link.close()
    }
}
