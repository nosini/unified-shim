package com.tigerlake.unifiedshim.owner

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.common.Notifications
import com.tigerlake.unifiedshim.role.Role
import com.tigerlake.unifiedshim.transport.BridgeLink
import com.tigerlake.unifiedshim.transport.loopback.LoopbackServer
import com.tigerlake.unifiedshim.transport.protocol.Frame
import com.tigerlake.unifiedshim.transport.protocol.Protocol

/**
 * Optional owner-side foreground service (auto-provision). OFF by default — it is
 * the only persistent component in the owner profile, so it exists solely to let a
 * distributor nudge the event-driven owner the instant a registration changes,
 * removing the "open the owner app once" manual step.
 *
 * It hosts a loopback listener; a distributor connects, handshakes, and sends a
 * NUDGE carrying its own bridge port. The owner learns that port (auth already
 * proved the peer is our own signed APK in another profile) and reconciles it.
 * Modeled on [com.tigerlake.unifiedshim.distributor.DistributorService].
 */
class OwnerListenerService : Service() {

    private lateinit var server: LoopbackServer

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Notifications.NOTIF_ID_OWNER,
            Notifications.build(this, "Auto-provision listener active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        running = true
        val port = Config(this).ownerListenerPort
        Logx.i("ownerL", "binding auto-provision listener on :$port")
        server = LoopbackServer(
            context = this,
            port = port,
            onFrame = { frame, link -> onServerFrame(frame, link) },
            onLink = { Logx.i("ownerL", "distributor connected, awaiting HELLO") },
            onLinkClosed = { Logx.d("ownerL", "distributor link closed") },
        )
        server.start()
    }

    /**
     * The distributor (client) speaks HELLO first. We validate version + role
     * (expect PRIVATE — mirror of DistributorService.onServerFrame, which expects
     * OWNER), reply HELLO_ACK, then handle NUDGE. Nothing else is expected here.
     */
    private fun onServerFrame(frame: Frame, link: BridgeLink) {
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
            Protocol.NUDGE -> handleNudge(frame, link)
            else -> Logx.w("ownerL", "unexpected frame type=${frame.type}")
        }
    }

    private fun handleNudge(frame: Frame, link: BridgeLink) {
        val port = Protocol.parseJson(frame).optInt("port", -1)
        if (port !in Config.PORT_RANGE) {
            Logx.w("ownerL", "nudge with invalid port=$port")
            link.close()
            return
        }
        // Auth already proved the peer is our own signed APK in another profile,
        // so auto-learning its port is safe and removes manual owner-side config.
        // The read-modify-write is locked: two distributors can nudge concurrently
        // (each connection has its own reader thread) and must not lose a port.
        synchronized(learnLock) {
            val cfg = Config(this)
            if (port !in cfg.ownerBridgePorts) {
                cfg.ownerBridgePorts = cfg.ownerBridgePorts + port
                Logx.i("ownerL", "learned distributor port :$port from nudge")
            }
        }
        link.close()
        // Reconcile off the socket reader thread so it isn't blocked on the round-trip.
        val ctx = applicationContext
        Thread({ OwnerBridge.reconcilePort(ctx, port) }, "owner-nudge-reconcile")
            .apply { isDaemon = true }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private val learnLock = Any()

        /** Best-effort: whether the listener FGS is currently up in this process. */
        @Volatile var running = false
            private set

        fun ensureStarted(context: Context) {
            context.startForegroundService(Intent(context, OwnerListenerService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OwnerListenerService::class.java))
        }

        /** Stop then re-start so the listener rebinds on a newly configured port. */
        fun restart(context: Context) {
            context.stopService(Intent(context, OwnerListenerService::class.java))
            ensureStarted(context)
        }
    }
}
