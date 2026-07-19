package com.tigerlake.unifiedshim.distributor

import android.content.Context
import android.os.Process
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.role.Role
import com.tigerlake.unifiedshim.transport.loopback.LoopbackClient
import com.tigerlake.unifiedshim.transport.protocol.Protocol
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        val ackLatch = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val link = LoopbackClient.connectOnce(
            context, ownerPort,
            onFrame = { frame, _ ->
                if (frame.type == Protocol.HELLO_ACK) {
                    ok.set(Protocol.parseJson(frame).optBoolean("ok", false))
                    ackLatch.countDown()
                }
            },
            onClosed = { ackLatch.countDown() },
        )
        if (link == null) {
            Logx.d("nudge", "owner listener unreachable on :$ownerPort")
            return
        }
        try {
            link.send(Protocol.hello(Role.PRIVATE.name, Process.myUid() % 100_000, Process.myUid() / 100_000))
            if (!ackLatch.await(ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS) || !ok.get()) {
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
