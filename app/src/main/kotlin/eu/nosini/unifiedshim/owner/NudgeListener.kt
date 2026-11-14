package eu.nosini.unifiedshim.owner

import android.content.Context
import eu.nosini.unifiedshim.common.Config
import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.role.Role
import eu.nosini.unifiedshim.transport.BridgeLink
import eu.nosini.unifiedshim.transport.acceptHello
import eu.nosini.unifiedshim.transport.loopback.LoopbackServer
import eu.nosini.unifiedshim.transport.protocol.Frame
import eu.nosini.unifiedshim.transport.protocol.Protocol
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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

    private val helloOk: MutableSet<BridgeLink> =
        Collections.newSetFromMap(ConcurrentHashMap<BridgeLink, Boolean>())
    private val helloRejected: MutableSet<BridgeLink> =
        Collections.newSetFromMap(ConcurrentHashMap<BridgeLink, Boolean>())

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

    private fun startServer(context: Context) {
        val port = Config(context).ownerListenerPort
        Logx.i("ownerL", "binding nudge listener on :$port")
        server = LoopbackServer(
            context = context,
            candidates = { listOf(port) },
            onFrame = { frame, link -> onServerFrame(context, frame, link) },
            onLink = { Logx.i("ownerL", "distributor connected, awaiting HELLO") },
            onLinkClosed = { link ->
                helloOk.remove(link)
                helloRejected.remove(link)
                Logx.d("ownerL", "distributor link closed")
            },
        ).also { it.start() }
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    /**
     * The distributor (client) speaks HELLO first. Validate version + role (expect
     * PRIVATE), reply HELLO_ACK, then handle NUDGE. Nothing else is routed until
     * that handshake succeeds.
     */
    private fun onServerFrame(context: Context, frame: Frame, link: BridgeLink) {
        if (link in helloRejected) return
        if (frame.type == Protocol.HELLO) {
            if (acceptHello(frame, link, Role.PRIVATE.name)) {
                helloOk.add(link)
            } else {
                helloRejected.add(link)
                link.close()
            }
            return
        }
        if (link !in helloOk) {
            Logx.w("ownerL", "frame type=${frame.type} before HELLO; dropping")
            link.close()
            return
        }
        when (frame.type) {
            Protocol.NUDGE -> handleNudge(context, frame, link)
            else -> Logx.w("ownerL", "unexpected frame type=${frame.type}")
        }
    }

    private fun handleNudge(context: Context, frame: Frame, link: BridgeLink) {
        val cfg = Config(context)
        val port = Protocol.parseJson(frame).optInt("port", -1)
        // Never learn our own listener (and never dial it), and ignore nonsense ports.
        if (port !in Config.PORT_RANGE || port == cfg.ownerListenerPort) {
            Logx.w("ownerL", "nudge with invalid port=$port")
            link.close()
            return
        }
        link.close()
        // Reconcile off the socket reader thread so it isn't blocked on the round-trip.
        // The port is only persisted after that distributor handshake succeeds.
        Thread({ OwnerBridge.reconcilePort(context, port) }, "owner-nudge-reconcile")
            .apply { isDaemon = true }.start()
    }
}
