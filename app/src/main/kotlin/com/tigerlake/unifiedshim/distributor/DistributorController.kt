package com.tigerlake.unifiedshim.distributor

import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.transport.BridgeLink
import com.tigerlake.unifiedshim.transport.protocol.Frame
import com.tigerlake.unifiedshim.transport.protocol.Protocol

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
) {
    @Volatile private var link: BridgeLink? = null

    // ---- owner link lifecycle ----

    /** Called after HELLO/HELLO_ACK completes for the owner link. */
    fun onOwnerLinkReady(l: BridgeLink) {
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
        // If the owner happens to be connected, nudge it; otherwise this is picked
        // up the next time the owner runs and reconciles.
        link?.let { sendSnapshot(it) }
    }

    fun onUnregister(token: String) {
        val reg = store.markDeprovision(token) ?: return
        messenger.unregistered(reg.app, token)
        link?.let { sendSnapshot(it) }
    }

    // ---- frames from the owner link ----

    fun onFrame(frame: Frame, l: BridgeLink) {
        when (frame.type) {
            Protocol.ENDPOINT -> handleEndpoint(frame)
            Protocol.DEPROVISIONED -> {
                store.confirmDeprovisioned(Protocol.parseJson(frame).getString("token"))
            }
            Protocol.PUSH -> handlePush(frame, l)
            else -> Logx.w("dist", "unexpected frame type=${frame.type}")
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
        // Ack the owner immediately so it can release the ntfy-app message.
        l.send(Protocol.pushAck(push.msgId))
        val reg = store.byToken(push.token)
        if (reg == null) {
            Logx.w("dist", "push for unknown token=${push.token}")
            return
        }
        messenger.message(reg.app, reg.token, push.payload, push.msgId)
    }
}
