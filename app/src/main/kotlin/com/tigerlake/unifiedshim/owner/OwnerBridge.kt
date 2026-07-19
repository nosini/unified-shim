package com.tigerlake.unifiedshim.owner

import android.content.Context
import android.os.Process
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.role.Role
import com.tigerlake.unifiedshim.transport.BridgeLink
import com.tigerlake.unifiedshim.transport.loopback.LoopbackClient
import com.tigerlake.unifiedshim.transport.protocol.Frame
import com.tigerlake.unifiedshim.transport.protocol.Protocol
import org.unifiedpush.android.connector.UnifiedPush
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

private val msgIdSeq = AtomicLong(System.currentTimeMillis())
private const val HELLO_TIMEOUT_MS = 3_000L
private const val REGS_TIMEOUT_MS = 1_500L
private const val ACK_TIMEOUT_MS = 5_000L

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
 * All work is serialized through one lock so sessions never overlap — every call
 * here runs synchronously on a wakeup (broadcast receiver / boot / UI thread off
 * the main looper).
 */
object OwnerBridge {

    private val lock = Any()

    /** Inbound push from the ntfy app. Durably enqueued first, then flushed. */
    fun deliver(context: Context, token: String, payload: ByteArray) {
        val msgId = msgIdSeq.incrementAndGet()
        OwnerStore.get(context).enqueue(OwnerStore.PendingPush(msgId, token, payload))
        synchronized(lock) { routeToken(context, token) }
    }

    /** A (new) endpoint minted by the ntfy app for one of our instances. */
    fun onEndpoint(context: Context, token: String, endpoint: String) {
        OwnerStore.get(context).putEndpoint(token, endpoint)
        synchronized(lock) { routeToken(context, token) { session -> session.sendEndpoint(token, endpoint) } }
    }

    /** The ntfy app released one of our instances. */
    fun onUnregistered(context: Context, token: String) {
        // Resolve the owning port before forget() clears the mapping.
        val port = OwnerStore.get(context).portFor(token)
        OwnerStore.get(context).forget(token)
        synchronized(lock) {
            if (port != null) runSession(context, port) { session -> session.sendDeprovisioned(token) }
            else reconcileAllPorts(context)
        }
    }

    /** Manual sync (owner app opened): reconcile pending registrations. */
    fun reconcile(context: Context) {
        synchronized(lock) { reconcileAllPorts(context) }
    }

    /** Reconcile a single distributor port. Used by the optional auto-provision
     *  listener when a distributor nudges us, so we don't sweep every port. */
    fun reconcilePort(context: Context, port: Int) {
        synchronized(lock) { runSession(context, port) }
    }

    // ---- routing ----

    /** Route a token-directed op: session to its owning port if known, else a full
     *  reconcile across all ports (which binds the mapping and flushes). Caller
     *  holds [lock]. */
    private inline fun routeToken(context: Context, token: String, crossinline block: (Session) -> Unit = {}) {
        val port = OwnerStore.get(context).portFor(token)
        if (port != null) runSession(context, port) { block(it) }
        else reconcileAllPorts(context)
    }

    /**
     * Sweep every candidate distributor port sequentially: the learned ports plus the
     * default scan range (announces can be missed — owner app closed, FGS off — so we
     * also scan). Excludes the owner's own listener port so we never dial ourselves.
     * Dead ports fast-fail on connect (loopback refusal is immediate) — [openSession]
     * returns null and [runSession] returns, which is the skip path. Caller holds
     * [lock] so the single-session invariant holds across the whole sweep.
     */
    private fun reconcileAllPorts(context: Context) {
        val ownerListenerPort = Config(context).ownerListenerPort
        val ports = (Config(context).ownerBridgePorts + Config.PORT_SCAN_RANGE).distinct() - ownerListenerPort
        for (port in ports) runSession(context, port)
    }

    // ---- session machinery ----

    private inline fun runSession(context: Context, port: Int, block: (Session) -> Unit = {}) {
        val session = openSession(context, port) ?: return
        // Handshake succeeded → a real, signed distributor lives on this port; learn
        // it so later reconciles dial it directly even if we found it only by scanning.
        Config.addOwnerBridgePort(context, port)
        try {
            val gotSnapshot = session.regsLatch.await(REGS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val regs = if (gotSnapshot) session.registrations else null
            if (regs != null) {
                applyReconcile(context, session, port, regs)
            } else {
                Logx.w("owner", "no registration snapshot from :$port within ${REGS_TIMEOUT_MS}ms; skipping reconcile+flush this session")
            }
            // Ground truth for what THIS distributor owns is its snapshot, not the
            // token→port map — so flush only tokens in regs.active, and nothing at all
            // without a snapshot (see [flushOutbox]).
            flushOutbox(context, session, regs?.active?.toSet() ?: emptySet())
            block(session)
        } finally {
            session.close()
        }
    }

    private fun openSession(context: Context, port: Int): Session? {
        val session = Session()
        val link = LoopbackClient.connectOnce(context, port, session::onFrame, session::onClosed)
            ?: return null
        session.link = link
        link.send(Protocol.hello(Role.OWNER.name, Process.myUid() % 100_000, Process.myUid() / 100_000))
        if (!session.helloAck.await(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !session.ok) {
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
            runCatching { UnifiedPush.unregisterApp(context, token) }
            store.forget(token)
            session.sendDeprovisioned(token)
        }
    }

    /**
     * Send queued pushes on the live link, removing each only when acked. Abort on
     * the first failure/closed link so a dead link can't burn one timeout per item.
     *
     * Correctness is gated by snapshot membership, NOT the token→port map: only tokens
     * in [activeTokens] (this session's `REGISTRATIONS.active`) are flushed, and if no
     * snapshot arrived [activeTokens] is empty so nothing is flushed. With
     * auto-assigned ports a remembered port can be re-occupied by a *different*
     * distributor profile (first-boot race before persistence, or a foreign app
     * squatting a port forcing a move), and the map might still point a token here. A
     * distributor acks-and-drops unknown tokens, so a mis-flush would falsely ack and
     * permanently lose the push. The snapshot is the only ground truth for what this
     * distributor actually owns.
     */
    private fun flushOutbox(context: Context, session: Session, activeTokens: Set<String>) {
        val store = OwnerStore.get(context)
        for (p in store.peekPending()) {
            if (!session.link.isOpen) break
            if (p.token !in activeTokens) continue
            if (session.sendPushAndAwait(p.msgId, p.token, p.payload)) store.removePending(p.msgId)
            else break
        }
    }

    private class Session {
        lateinit var link: BridgeLink
        val helloAck = CountDownLatch(1)
        val regsLatch = CountDownLatch(1)
        @Volatile var ok = false
        @Volatile var registrations: Protocol.Registrations? = null
        private val ackLatches = ConcurrentHashMap<Long, CountDownLatch>()

        fun onFrame(frame: Frame, l: BridgeLink) {
            when (frame.type) {
                Protocol.HELLO_ACK -> {
                    ok = Protocol.parseJson(frame).optBoolean("ok", false)
                    helloAck.countDown()
                }
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
            helloAck.countDown()
            regsLatch.countDown()
            ackLatches.values.forEach { it.countDown() }
        }

        fun sendEndpoint(token: String, endpoint: String) = link.send(Protocol.endpoint(token, endpoint))
        fun sendDeprovisioned(token: String) = link.send(Protocol.deprovisioned(token))

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
