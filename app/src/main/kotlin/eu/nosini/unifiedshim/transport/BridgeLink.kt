package eu.nosini.unifiedshim.transport

import android.os.Process
import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.transport.protocol.Frame
import eu.nosini.unifiedshim.transport.protocol.Protocol
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Server-side HELLO validation shared by the distributor and nudge listeners. */
fun acceptHello(frame: Frame, link: BridgeLink, expectedRole: String): Boolean {
    val o = Protocol.parseJson(frame)
    val version = o.optInt("version", -1)
    val role = o.optString("role")
    val ok = version == Protocol.VERSION && role == expectedRole
    link.send(Protocol.helloAck(ok, if (ok) null else "version/role mismatch"))
    if (!ok) Logx.w("hello", "rejecting HELLO version=$version role=$role expected=$expectedRole")
    return ok
}

/** Client-side HELLO exchange: send HELLO, then wait for the matching ack. */
class ClientHello(private val role: String, private val timeoutMs: Long) {
    private val ack = CountDownLatch(1)
    @Volatile private var accepted = false

    /** Feed every inbound frame; returns true when it consumed a HELLO_ACK. */
    fun onFrame(frame: Frame): Boolean {
        if (frame.type != Protocol.HELLO_ACK) return false
        accepted = Protocol.parseJson(frame).optBoolean("ok", false)
        ack.countDown()
        return true
    }

    fun onClosed() {
        ack.countDown()
    }

    fun handshake(link: BridgeLink): Boolean {
        link.send(Protocol.hello(role, Process.myUid() % 100_000, Process.myUid() / 100_000))
        return try {
            ack.await(timeoutMs, TimeUnit.MILLISECONDS) && accepted
        } catch (_: InterruptedException) {
            false
        }
    }
}

/**
 * An authenticated, bidirectional, framed connection. Stream-based so it works
 * over any transport — currently TCP loopback (see [loopback]). This is the only
 * object the controllers touch: the concrete transport is the rip-out seam.
 *
 * A single reader thread delivers frames via [onFrame]; [send] is synchronized so
 * any thread may write. [onClosed] fires exactly once.
 */
class BridgeLink(
    private val input: DataInputStream,
    private val output: DataOutputStream,
    private val closeable: Closeable,
    val peerLabel: String,
    private val onFrame: (Frame, BridgeLink) -> Unit,
    private val onClosed: (BridgeLink) -> Unit,
) {
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    val isOpen: Boolean get() = !closed.get()

    fun start() {
        Thread({ readLoop() }, "bridge-link-reader").apply { isDaemon = true; start() }
    }

    private fun readLoop() {
        try {
            while (!closed.get()) {
                val frame = Frame.read(input)
                onFrame(frame, this)
            }
        } catch (e: Exception) {
            if (!closed.get()) Logx.d("link", "reader ended: ${e.message}")
        } finally {
            close()
        }
    }

    fun send(frame: Frame) {
        if (closed.get()) return
        try {
            synchronized(writeLock) { frame.write(output) }
        } catch (e: Exception) {
            Logx.w("link", "send failed: ${e.message}")
            close()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { closeable.close() } catch (_: Exception) {}
        onClosed(this)
    }
}
