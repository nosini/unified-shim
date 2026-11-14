package eu.nosini.unifiedshim.distributor

import android.content.Context
import eu.nosini.unifiedshim.common.Config
import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.role.Role
import eu.nosini.unifiedshim.transport.ClientHello
import eu.nosini.unifiedshim.transport.loopback.LoopbackClient
import eu.nosini.unifiedshim.transport.protocol.Protocol

/**
 * Distributor-side one-shot nudge to the optional owner auto-provision listener.
 *
 * Best-effort by design: a dead/unconfigured/disabled owner listener must be
 * indistinguishable from the manual-mode status quo, so every failure is logged
 * at debug/info and swallowed. Does a blocking loopback round-trip — callers must
 * never invoke this on the main thread.
 */
object OwnerNudger {

    private const val ACK_TIMEOUT_MS = 3_000L

    fun nudge(context: Context) {
        val cfg = Config(context)
        val ownerPort = cfg.ownerNudgePort
        if (ownerPort !in Config.PORT_RANGE) {
            Logx.d("nudge", "owner nudging disabled (port=$ownerPort)")
            return
        }
        val hello = ClientHello(Role.PRIVATE.name, ACK_TIMEOUT_MS)
        val link = LoopbackClient.connectOnce(
            context, ownerPort,
            onFrame = { frame, _ -> hello.onFrame(frame) },
            onClosed = { hello.onClosed() },
        )
        if (link == null) {
            Logx.d("nudge", "owner listener unreachable on :$ownerPort")
            return
        }
        try {
            if (!hello.handshake(link)) {
                Logx.d("nudge", "no HELLO_ACK from owner listener on :$ownerPort")
                return
            }
            link.send(Protocol.nudge(cfg.bridgePort))
            Logx.i("nudge", "nudged owner listener :$ownerPort with bridge port ${cfg.bridgePort}")
        } finally {
            link.close()
        }
    }
}
