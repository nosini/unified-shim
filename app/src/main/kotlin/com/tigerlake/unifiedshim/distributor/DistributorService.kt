package com.tigerlake.unifiedshim.distributor

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
 * Private-space foreground service. Owns the loopback listener and the
 * [DistributorController]. Must stay foregrounded: the private space cannot be
 * woken cross-profile, so the listener has to remain bound.
 */
class DistributorService : Service() {

    private lateinit var controller: DistributorController
    private lateinit var server: LoopbackServer

    override fun onCreate() {
        super.onCreate()
        startForeground(
            Notifications.NOTIF_ID,
            Notifications.build(this, "Push bridge active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        controller = DistributorController(EndpointStore.get(this), AppMessenger(this))
        server = LoopbackServer(
            context = this,
            port = Config.BRIDGE_PORT,
            onFrame = { frame, link -> onServerFrame(frame, link) },
            // The owner connects per push and disconnects — link up/down is normal
            // and frequent, so we keep the notification text stable rather than flap.
            onLink = { Logx.i("dist", "owner connected, awaiting HELLO") },
            onLinkClosed = { link -> controller.onOwnerLinkClosed(link) },
        )
        server.start()
    }

    /**
     * The owner (client) speaks HELLO first. We validate version + role, reply
     * HELLO_ACK, then hand the link to the controller. Everything else is routed
     * straight through.
     */
    private fun onServerFrame(frame: Frame, link: BridgeLink) {
        if (frame.type == Protocol.HELLO) {
            val o = Protocol.parseJson(frame)
            val version = o.optInt("version", -1)
            val role = o.optString("role")
            val ok = version == Protocol.VERSION && role == Role.OWNER.name
            link.send(Protocol.helloAck(ok, if (ok) null else "version/role mismatch"))
            if (ok) {
                controller.onOwnerLinkReady(link)
            } else {
                Logx.w("dist", "rejecting owner HELLO version=$version role=$role")
                link.close()
            }
            return
        }
        controller.onFrame(frame, link)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            UnifiedPush.ACTION_REGISTER -> {
                val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)
                val app = intent.getStringExtra(UnifiedPush.EXTRA_APPLICATION)
                if (token != null && app != null) controller.onRegister(token, app)
            }
            UnifiedPush.ACTION_UNREGISTER -> {
                intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)?.let { controller.onUnregister(it) }
            }
            UnifiedPush.ACTION_MESSAGE_ACK -> {
                // App-level ack. We already acked the owner on receipt; ignore.
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun ensureStarted(context: Context) {
            context.startForegroundService(Intent(context, DistributorService::class.java))
        }
    }
}
