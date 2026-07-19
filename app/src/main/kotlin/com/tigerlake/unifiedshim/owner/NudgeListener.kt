package com.tigerlake.unifiedshim.owner

import android.content.Context
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.role.Role
import com.tigerlake.unifiedshim.transport.BridgeLink
import com.tigerlake.unifiedshim.transport.loopback.LoopbackServer
import com.tigerlake.unifiedshim.transport.protocol.Frame
import com.tigerlake.unifiedshim.transport.protocol.Protocol

/**
 * Owner-side nudge listener. A distributor connects to [Config.ownerListenerPort],
 * handshakes (expects PRIVATE), and sends a NUDGE carrying its own bridge port; the
 * owner learns that port and reconciles it immediately.
 *
 * Holder-based lifecycle: two things may want the listener up — the optional
 * always-on FGS ([OwnerListenerService]) and the owner Activity (only while its
 * screen is open). Each acquires with a tag; the single [LoopbackServer] starts on
 * the first acquire and stops on the last release, so the two overlap on one server
 * with no double-bind and no early stop, in any order.
 */
object NudgeListener {

    private val lock = Any()
    private val holders = mutableSetOf<String>()
    private var server: LoopbackServer? = null

    /** Start the listener if not running; idempotent per holder. */
    fun acquire(context: Context, holder: String) {
        val app = context.applicationContext
        synchronized(lock) {
            val wasEmpty = holders.isEmpty()
            holders.add(holder)
            if (wasEmpty) startServer(app)
        }
    }

    /** Release this holder; stop the listener only when the last holder is gone. */
    fun release(holder: String) {
        synchronized(lock) {
            if (holders.remove(holder) && holders.isEmpty()) stopServer()
        }
    }

    /** Rebind after a listener-port change — only if currently running. */
    fun restart(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (holders.isEmpty()) return
            stopServer()
            startServer(app)
        }
    }

    private fun startServer(context: Context) {
        val port = Config(context).ownerListenerPort
        Logx.i("ownerL", "binding nudge listener on :$port")
        server = LoopbackServer(
            context = context,
            candidates = { listOf(port) },
            onFrame = { frame, link -> onServerFrame(context, frame, link) },
            onLink = { Logx.i("ownerL", "distributor connected, awaiting HELLO") },
            onLinkClosed = { Logx.d("ownerL", "distributor link closed") },
        ).also { it.start() }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    /**
     * The distributor (client) speaks HELLO first. Validate version + role (expect
     * PRIVATE), reply HELLO_ACK, then handle NUDGE. Nothing else is expected here.
     */
    private fun onServerFrame(context: Context, frame: Frame, link: BridgeLink) {
        when (frame.type) {
            Protocol.HELLO -> {
                val o = Protocol.parseJson(frame)
                val version = o.optInt("version", -1)
                val role = o.optString("role")
                val ok = version == Protocol.VERSION && role == Role.PRIVATE.name
                link.send(Protocol.helloAck(ok, if (ok) null else "version/role mismatch"))
                if (!ok) {
                    Logx.w("ownerL", "rejecting HELLO version=$version role=$role")
                    link.close()
                }
            }
            Protocol.NUDGE -> handleNudge(context, frame, link)
            else -> Logx.w("ownerL", "unexpected frame type=${frame.type}")
        }
    }

    private fun handleNudge(context: Context, frame: Frame, link: BridgeLink) {
        val port = Protocol.parseJson(frame).optInt("port", -1)
        if (port !in Config.PORT_RANGE) {
            Logx.w("ownerL", "nudge with invalid port=$port")
            link.close()
            return
        }
        // Auth already proved the peer is our own signed APK in another profile, so
        // auto-learning its port is safe. The RMW is centralized under Config's lock.
        Config.addOwnerBridgePort(context, port)
        link.close()
        // Reconcile off the socket reader thread so it isn't blocked on the round-trip.
        Thread({ OwnerBridge.reconcilePort(context, port) }, "owner-nudge-reconcile")
            .apply { isDaemon = true }.start()
    }
}
