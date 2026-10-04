package eu.nosini.unifiedshim.transport

import android.content.Context
import android.content.pm.PackageManager
import eu.nosini.unifiedshim.common.Logx
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mutual challenge-response run before the framed protocol, replacing the
 * SO_PEERCRED check we had on unix sockets (TCP has no peer credentials).
 *
 * Both instances are the same signed APK, so they share a key derived from the
 * app's signing certificate. Each side proves possession of that key over fresh
 * nonces. NOTE: the signing cert is not a true secret — an app with package
 * visibility to us could read it — so this is a weaker anchor than peercred. It
 * is bound to 127.0.0.1 and the high-value payloads are E2E-encrypted; this is a
 * deliberate, isolated trade for cross-profile reachability.
 *
 * Wire (fixed-size, big nonce/proof):
 *   C→S  clientNonce[16]
 *   S→C  serverNonce[16] | proof(clientNonce)[32]
 *   C→S  proof(serverNonce)[32]
 */
object Auth {
    private const val NONCE = 16
    private const val PROOF = 32 // HMAC-SHA256
    private val rng = SecureRandom()

    fun serverHandshake(context: Context, input: DataInputStream, output: DataOutputStream): Boolean = try {
        val key = sharedKey(context)
        val clientNonce = ByteArray(NONCE).also { input.readFully(it) }
        val serverNonce = ByteArray(NONCE).also { rng.nextBytes(it) }
        output.write(serverNonce); output.write(hmac(key, clientNonce)); output.flush()
        val serverProof = ByteArray(PROOF).also { input.readFully(it) }
        constantTimeEquals(serverProof, hmac(key, serverNonce))
    } catch (e: Exception) {
        Logx.w("auth", "server handshake failed: ${e.message}"); false
    }

    fun clientHandshake(context: Context, input: DataInputStream, output: DataOutputStream): Boolean {
        return try {
            val key = sharedKey(context)
            val clientNonce = ByteArray(NONCE).also { rng.nextBytes(it) }
            output.write(clientNonce); output.flush()
            val serverNonce = ByteArray(NONCE).also { input.readFully(it) }
            val clientProof = ByteArray(PROOF).also { input.readFully(it) }
            if (!constantTimeEquals(clientProof, hmac(key, clientNonce))) {
                false
            } else {
                output.write(hmac(key, serverNonce)); output.flush()
                true
            }
        } catch (e: Exception) {
            Logx.w("auth", "client handshake failed: ${e.message}")
            false
        }
    }

    private fun sharedKey(context: Context): ByteArray {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signer = info.signingInfo!!.apkContentsSigners.first().toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(signer)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)
}
