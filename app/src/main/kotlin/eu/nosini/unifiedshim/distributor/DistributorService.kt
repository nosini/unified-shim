package eu.nosini.unifiedshim.distributor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import eu.nosini.unifiedshim.common.Config
import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.common.Notifications
import eu.nosini.unifiedshim.role.Role
import eu.nosini.unifiedshim.transport.BridgeLink
import eu.nosini.unifiedshim.transport.acceptHello
import eu.nosini.unifiedshim.transport.loopback.LoopbackServer
import eu.nosini.unifiedshim.transport.protocol.Frame
import eu.nosini.unifiedshim.transport.protocol.Protocol
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Private-space foreground service. Owns the loopback listener and the
 * [DistributorController]. Must stay foregrounded: the private space cannot be
 * woken cross-profile, so the listener has to remain bound.
 */
class DistributorService : Service() {

    private lateinit var controller: DistributorController
    private lateinit var server: LoopbackServer

    private val helloOk: MutableSet<BridgeLink> =
        Collections.newSetFromMap(ConcurrentHashMap<BridgeLink, Boolean>())
    private val helloRejected: MutableSet<BridgeLink> =
        Collections.newSetFromMap(ConcurrentHashMap<BridgeLink, Boolean>())

    override fun onCreate() {
        super.onCreate()
        Config(this).distributorStartPending = false
        startForeground(
            Notifications.NOTIF_ID,
            Notifications.build(this, "Push bridge active"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        running = true
        val store = EndpointStore.get(this)
        controller = DistributorController(store, AppMessenger(this), nudgeOwner = ::nudgeOwnerAsync)
        server = LoopbackServer(
            context = this,
            // Candidate order = the persisted last-bound port first, then the rest of
            // the scan range. Reclaiming the remembered port keeps assignments STABLE
            // across reboots so ports don't swap with boot order: first-ever, the
            // private space gets 28472, the work profile finds it taken and lands on
            // 28473, and both persist their result here.
            candidates = { (listOf(Config(this).bridgePort) + Config.PORT_SCAN_RANGE).distinct() },
            onFrame = { frame, link -> onServerFrame(frame, link) },
            // The owner connects per push and disconnects — link up/down is normal
            // and frequent, so we keep the notification text stable rather than flap.
            onLink = { Logx.i("dist", "owner connected, awaiting HELLO") },
            onLinkClosed = { link ->
                helloOk.remove(link)
                helloRejected.remove(link)
                controller.onOwnerLinkClosed(link)
            },
            onBound = { boundPort ->
                // Persist the auto-assigned port and ALWAYS announce it on every
                // successful bind: that is what tells the owner this profile's current
                // port (replaces the old pending-work-only startup nudge).
                Config(this).bridgePort = boundPort
                Logx.i("dist", "bound loopback listener on :$boundPort; announcing to owner")
                nudgeOwnerAsync()
            },
        )
        server.start()
    }

    /** Fire OwnerNudger off the main thread — it does a blocking loopback round-trip. */
    private fun nudgeOwnerAsync() {
        Thread({ OwnerNudger.nudge(this) }, "owner-nudge").apply { isDaemon = true }.start()
    }

    /**
     * The owner (client) speaks HELLO first. Validate version + role, reply
     * HELLO_ACK, then hand the link to the controller. Nothing else is routed until
     * that handshake succeeds.
     */
    private fun onServerFrame(frame: Frame, link: BridgeLink) {
        if (link in helloRejected) return
        if (frame.type == Protocol.HELLO) {
            if (acceptHello(frame, link, Role.OWNER.name)) {
                helloOk.add(link)
                controller.onOwnerLinkReady(link)
            } else {
                helloRejected.add(link)
                link.close()
            }
            return
        }
        if (link !in helloOk) {
            Logx.w("dist", "frame type=${frame.type} before HELLO; dropping")
            link.close()
            return
        }
        controller.onFrame(frame, link)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            UnifiedPush.ACTION_REGISTER -> {
                val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)
                if (token != null) {
                    val app = UnifiedPush.applicationOf(intent)
                    if (app != null) {
                        controller.onRegister(token, app)
                    } else {
                        Logx.w("dist", "REGISTER token=$token has no resolvable app package")
                        // A previously seen registration still gives us a target for
                        // the spec-mandated failure reply.
                        controller.onRegistrationFailed(token, "INTERNAL_ERROR")
                    }
                }
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
        running = false
        server.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** Best-effort: whether the FGS is currently up in this process (status UI). */
        @Volatile var running = false
            private set

        fun ensureStarted(context: Context) {
            context.startForegroundService(Intent(context, DistributorService::class.java))
        }
    }
}
