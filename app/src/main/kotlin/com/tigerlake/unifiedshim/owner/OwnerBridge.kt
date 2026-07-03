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
 * connection. Each entry point opens a short-lived connection to the always-up
 * private listener, reconciles the registration snapshot the private side pushes,
 * and streams back endpoints / pushes.
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
        withSession(context, default = Unit) { /* flush happens in withSession */ }
    }

    /** A (new) endpoint minted by the ntfy app for one of our instances. */
    fun onEndpoint(context: Context, token: String, endpoint: String) {
        OwnerStore.get(context).putEndpoint(token, endpoint)
        withSession(context, default = Unit) { session -> session.sendEndpoint(token, endpoint) }
    }

    /** The ntfy app released one of our instances. */
    fun onUnregistered(context: Context, token: String) {
        OwnerStore.get(context).forget(token)
        withSession(context, default = Unit) { session -> session.sendDeprovisioned(token) }
    }

    /** Manual sync (owner app opened): reconcile pending registrations. */
    fun reconcile(context: Context) {
        withSession(context, default = Unit) { /* reconcile already ran in withSession */ }
    }

    // ---- session machinery ----

    private inline fun <T> withSession(context: Context, default: T, block: (Session) -> T): T {
        synchronized(lock) {
            val session = openSession(context) ?: return default
            try {
                val gotSnapshot = session.regsLatch.await(REGS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (gotSnapshot) {
                    session.registrations?.let { applyReconcile(context, session, it) }
                } else {
                    Logx.w("owner", "no registration snapshot within ${REGS_TIMEOUT_MS}ms; skipping reconcile this session")
                }
                flushOutbox(context, session)
                return block(session)
            } finally {
                session.close()
            }
        }
    }

    private fun openSession(context: Context): Session? {
        val session = Session()
        val link = LoopbackClient.connectOnce(context, Config.BRIDGE_PORT, session::onFrame, session::onClosed)
            ?: return null
        session.link = link
        link.send(Protocol.hello(Role.OWNER.name, Process.myUid() % 100_000, Process.myUid() / 100_000))
        if (!session.helloAck.await(HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !session.ok) {
            Logx.w("owner", "handshake failed")
            link.close()
            return null
        }
        return session
    }

    /**
     * React to the private side's snapshot: register unknown tokens with the ntfy
     * app (their endpoints arrive later via onNewEndpoint), re-send endpoints we
     * already know, release deprovisioned tokens, and flush any queued pushes.
     */
    private fun applyReconcile(context: Context, session: Session, regs: Protocol.Registrations) {
        val store = OwnerStore.get(context)
        val distributorReady = UnifiedPush.getSavedDistributor(context) != null

        for (token in regs.active) {
            val endpoint = store.endpointFor(token)
            if (endpoint != null) {
                session.sendEndpoint(token, endpoint)
            } else if (distributorReady) {
                Logx.i("owner", "registering instance with ntfy app for token=$token")
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

    /** Send queued pushes on the live link, removing each only when acked. Abort on
     *  the first failure/closed link so a dead link can't burn one timeout per item. */
    private fun flushOutbox(context: Context, session: Session) {
        val store = OwnerStore.get(context)
        for (p in store.peekPending()) {
            if (!session.link.isOpen) break
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
