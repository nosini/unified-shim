package com.tigerlake.unifiedshim.transport

import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.transport.protocol.Frame
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
