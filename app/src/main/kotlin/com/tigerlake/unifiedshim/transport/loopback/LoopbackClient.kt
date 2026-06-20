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
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Owner-side one-shot connect to the private listener over TCP loopback.
 * The owner is event-driven, so it opens a connection, does its business, and
 * closes. Peer identity is established by the [Auth] handshake.
 */
object LoopbackClient {

    fun connectOnce(
        context: Context,
        port: Int,
        onFrame: (Frame, BridgeLink) -> Unit,
        onClosed: (BridgeLink) -> Unit,
    ): BridgeLink? {
        val sock = Socket()
        return try {
            sock.tcpNoDelay = true
            sock.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT)
            val input = DataInputStream(BufferedInputStream(sock.getInputStream()))
            val output = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))
            sock.soTimeout = AUTH_TIMEOUT
            if (!Auth.clientHandshake(context, input, output)) {
                Logx.w("client", "auth handshake failed")
                sock.close()
                return null
            }
            sock.soTimeout = 0
            Logx.i("client", "connected + authenticated to 127.0.0.1:$port")
            BridgeLink(input, output, sock, "127.0.0.1:$port", onFrame, onClosed).also { it.start() }
        } catch (e: Exception) {
            Logx.d("client", "connect failed: ${e.message}")
            runCatching { sock.close() }
            null
        }
    }

    private const val CONNECT_TIMEOUT = 3_000
    private const val AUTH_TIMEOUT = 5_000
}
