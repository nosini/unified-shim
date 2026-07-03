package com.tigerlake.unifiedshim.transport.loopback

import android.content.Context
import com.tigerlake.unifiedshim.common.Logx
import com.tigerlake.unifiedshim.transport.Auth
import com.tigerlake.unifiedshim.transport.BridgeLink
import com.tigerlake.unifiedshim.transport.protocol.Frame
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Private-side listener on TCP loopback.
 *
 * We use 127.0.0.1 rather than an abstract unix socket: SELinux MLS blocks
 * `connectto` on a cross-profile unix socket (the category pair encodes the
 * user), but the kernel network stack doesn't enforce that per-peer check, so
 * loopback TCP crosses the shared netns. Peer identity is established by the
 * [Auth] handshake instead of SO_PEERCRED.
 */
class LoopbackServer(
    private val context: Context,
    private val port: Int,
    private val onFrame: (Frame, BridgeLink) -> Unit,
    private val onLink: (BridgeLink) -> Unit,
    private val onLinkClosed: (BridgeLink) -> Unit,
) {
    private val running = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ bindAndAccept() }, "bridge-accept").apply { isDaemon = true; start() }
    }

    private fun bindAndAccept() {
        var backoff = MIN_REBIND_DELAY
        while (running.get()) {
            val ss = try {
                ServerSocket(port, BACKLOG, InetAddress.getByName("127.0.0.1"))
            } catch (e: Exception) {
                Logx.w("server", "bind 127.0.0.1:$port failed: ${e.message}; retry in ${backoff}ms")
                sleep(backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_REBIND_DELAY)
                continue
            }
            backoff = MIN_REBIND_DELAY
            serverSocket = ss
            Logx.i("server", "listening on 127.0.0.1:$port")
            acceptLoop(ss)
            runCatching { ss.close() }
        }
        Logx.i("server", "accept loop ended")
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val sock = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Logx.w("server", "accept failed: ${e.message}")
                return
            }
            // Authenticate on a worker so a slow/hostile peer can't stall accept().
            Thread({ setUpConnection(sock) }, "bridge-auth").apply { isDaemon = true; start() }
        }
    }

    private fun setUpConnection(sock: Socket) {
        try {
            sock.tcpNoDelay = true
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            sock.soTimeout = AUTH_TIMEOUT
            if (!Auth.serverHandshake(context, input, output)) {
                Logx.w("server", "peer failed auth from ${sock.inetAddress?.hostAddress}")
                sock.close()
                return
            }
            // Finite idle timeout + keepalive bound a dead-peer leak (owner process
            // killed without a clean FIN); the owner connects per-push and closes
            // within seconds, so it is never legitimately idle this long.
            sock.keepAlive = true
            sock.soTimeout = IDLE_TIMEOUT_MS
            val link = BridgeLink(input, output, sock, "127.0.0.1:${sock.port}", onFrame, onLinkClosed)
            Logx.i("server", "authenticated peer")
            onLink(link)
            link.start()
        } catch (e: Exception) {
            Logx.w("server", "connection setup failed: ${e.message}")
            runCatching { sock.close() }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) {}

    companion object {
        private const val BACKLOG = 8
        private const val AUTH_TIMEOUT = 5_000
        private const val MIN_REBIND_DELAY = 3_000L
        private const val MAX_REBIND_DELAY = 60_000L
        private const val IDLE_TIMEOUT_MS = 120_000
    }
}
