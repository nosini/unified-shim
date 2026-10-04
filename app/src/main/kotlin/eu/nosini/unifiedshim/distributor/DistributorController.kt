package eu.nosini.unifiedshim.distributor

import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.transport.BridgeLink
import eu.nosini.unifiedshim.transport.protocol.Frame
import eu.nosini.unifiedshim.transport.protocol.Protocol

/**
 * Distributor-side brain (private space). It is the always-up half: it owns the
 * registration table and waits for the owner shim to connect.
 *
 * When the owner connects it is handed the full registration snapshot
 * (`REGISTRATIONS`); the owner reconciles that against the ntfy app and streams
 * back `ENDPOINT`s and `PUSH`es. Pure logic — socket I/O is a [BridgeLink], app
 * I/O is an [AppMessenger].
 */
class DistributorController(
    private val store: EndpointStore,
    private val messenger: AppMessenger,
    // Best-effort nudge to the optional owner auto-provision listener. Injected so
    // the controller stays pure and the (blocking) round-trip runs off-thread in
    // the caller. Invoked only on registration-state changes — never in response to
    // owner-initiated frames — to avoid a nudge→reconcile→connect feedback loop.
    private val nudgeOwner: () -> Unit = {},
) {
    @Volatile private var link: BridgeLink? = null

    /**
     * Bounded LRU of msgIds we have already dispatched to the app. The owner's
     * outbox is at-least-once, so the same msgId can arrive twice (e.g. an ack
     * that landed after the owner's retry timeout). In-memory only: a re-delivery
     * across process death is acceptable and rare.
     */
    private val delivered = object : LinkedHashMap<Long, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, Boolean>): Boolean = size > 1024
    }

    @Synchronized private fun alreadyDelivered(msgId: Long): Boolean = delivered.containsKey(msgId)

    @Synchronized private fun markDelivered(msgId: Long) {
        delivered[msgId] = true
    }

    // ---- owner link lifecycle ----

    /** Called after HELLO/HELLO_ACK completes for the owner link. */
    fun onOwnerLinkReady(l: BridgeLink) {
        link?.takeIf { it !== l }?.close()
        link = l
        Logx.i("dist", "owner link ready; sending registration snapshot")
        sendSnapshot(l)
    }

    fun onOwnerLinkClosed(l: BridgeLink) {
        if (link === l) {
            link = null
            Logx.w("dist", "owner link closed")
        }
    }

    private fun sendSnapshot(l: BridgeLink) {
        l.send(Protocol.registrations(store.activeTokens(), store.deprovisionTokens()))
    }

    // ---- from RegisterReceiver ----

    fun onRegister(token: String, app: String) {
        val existing = store.byToken(token)
        if (existing != null && !existing.pending) {
            // Idempotent re-registration (connector re-announces on app start).
            messenger.newEndpoint(existing.app, token, existing.endpoint!!)
            return
        }
        store.putPending(token, app)
        // If the owner is already connected, its live snapshot reaches this reg — no
        // nudge needed. Otherwise nudge the optional owner listener so it reconciles
        // now; if that's disabled/unreachable it falls back to the next owner run.
        val l = link
        if (l != null) sendSnapshot(l) else nudgeOwner()
    }

    fun onUnregister(token: String) {
        val reg = store.markDeprovision(token) ?: return
        messenger.unregistered(reg.app, token)
        val l = link
        if (l != null) sendSnapshot(l) else nudgeOwner()
    }

    // ---- frames from the owner link ----

    fun onFrame(frame: Frame, l: BridgeLink) {
        when (frame.type) {
            Protocol.ENDPOINT -> handleEndpoint(frame)
            Protocol.DEPROVISIONED -> handleDeprovisioned(Protocol.parseJson(frame).getString("token"))
            Protocol.PUSH -> handlePush(frame, l)
            else -> Logx.w("dist", "unexpected frame type=${frame.type}")
        }
    }

    private fun handleDeprovisioned(token: String) {
        // Case (b): ntfy released an instance the registered app still wants -> reset to
        // pending so the next owner reconcile re-registers and a fresh NEW_ENDPOINT
        // reaches the app. Case (a): a genuine unregister confirmation -> clear it.
        if (store.resetToPending(token)) {
            Logx.i("dist", "endpoint released upstream for active token=$token; will re-provision")
        } else {
            store.confirmDeprovisioned(token)
        }
    }

    private fun handleEndpoint(frame: Frame) {
        val o = Protocol.parseJson(frame)
        val token = o.getString("token")
        val endpoint = o.getString("endpoint")
        val reg = store.byToken(token) ?: return // unregistered meanwhile
        if (store.setEndpoint(token, endpoint)) {
            messenger.newEndpoint(reg.app, token, endpoint)
        }
    }

    private fun handlePush(frame: Frame, l: BridgeLink) {
        val push = Protocol.parsePush(frame)
        // Duplicate re-delivery: re-ack so the owner stops retrying, but don't
        // broadcast again.
        if (alreadyDelivered(push.msgId)) {
            Logx.d("dist", "duplicate push msgId=${push.msgId}, re-acking")
            l.send(Protocol.pushAck(push.msgId))
            return
        }
        val reg = store.byToken(push.token)
        if (reg == null) {
            // Undeliverable (that app not/no-longer registered). Ack anyway so the
            // owner drops it instead of retrying forever.
            Logx.w("dist", "push for unknown token=${push.token}")
            l.send(Protocol.pushAck(push.msgId))
            return
        }
        // Dispatch first, then mark delivered and ack — acking before the broadcast
        // would let the owner drop the payload before we've handed it to the app.
        messenger.message(reg.app, reg.token, push.payload, push.msgId)
        markDelivered(push.msgId)
        l.send(Protocol.pushAck(push.msgId))
    }
}
