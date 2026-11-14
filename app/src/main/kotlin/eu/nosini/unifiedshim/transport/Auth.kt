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
 * Each proof covers a role label plus both nonces, so a proof obtained from one
 * side (or one connection) can never be replayed as the other side's proof.
 *
 * Wire (fixed-size, big nonce/proof):
 *   C→S  clientNonce[16]
 *   S→C  serverNonce[16] | HMAC(key, "server" | clientNonce | serverNonce)[32]
 *   C→S  HMAC(key, "client" | clientNonce | serverNonce)[32]
 */
object Auth {
    private const val NONCE = 16
    private const val PROOF = 32 // HMAC-SHA256
    private val rng = SecureRandom()
    private val SERVER_LABEL = "unifiedshim-auth-v2/server".toByteArray()
    private val CLIENT_LABEL = "unifiedshim-auth-v2/client".toByteArray()

    fun serverHandshake(context: Context, input: DataInputStream, output: DataOutputStream): Boolean = try {
        val key = sharedKey(context)
        val clientNonce = ByteArray(NONCE).also { input.readFully(it) }
        val serverNonce = ByteArray(NONCE).also { rng.nextBytes(it) }
        output.write(serverNonce); output.write(proof(key, SERVER_LABEL, clientNonce, serverNonce)); output.flush()
        val clientProof = ByteArray(PROOF).also { input.readFully(it) }
        constantTimeEquals(clientProof, proof(key, CLIENT_LABEL, clientNonce, serverNonce))
    } catch (e: Exception) {
        Logx.w("auth", "server handshake failed: ${e.message}"); false
    }

    fun clientHandshake(context: Context, input: DataInputStream, output: DataOutputStream): Boolean {
        return try {
            val key = sharedKey(context)
            val clientNonce = ByteArray(NONCE).also { rng.nextBytes(it) }
            output.write(clientNonce); output.flush()
            val serverNonce = ByteArray(NONCE).also { input.readFully(it) }
            val serverProof = ByteArray(PROOF).also { input.readFully(it) }
            if (!constantTimeEquals(serverProof, proof(key, SERVER_LABEL, clientNonce, serverNonce))) {
                false
            } else {
                output.write(proof(key, CLIENT_LABEL, clientNonce, serverNonce)); output.flush()
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

    private fun proof(key: ByteArray, label: ByteArray, clientNonce: ByteArray, serverNonce: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(label); update(clientNonce); update(serverNonce)
            doFinal()
        }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)
}
