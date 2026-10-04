package eu.nosini.unifiedshim.distributor

import android.content.Context
import android.content.Intent
import eu.nosini.unifiedshim.common.Logx
import java.nio.charset.StandardCharsets

/**
 * Sends the standard UnifiedPush connector broadcasts to a registered app.
 * Every broadcast is explicitly targeted at the app's package so it is delivered
 * to a manifest-declared receiver and never leaks to other apps.
 */
class AppMessenger(private val context: Context) {

    fun newEndpoint(app: String, token: String, endpoint: String) {
        Logx.i("msg", "NEW_ENDPOINT -> $app token=$token")
        send(app, UnifiedPush.ACTION_NEW_ENDPOINT) {
            putExtra(UnifiedPush.EXTRA_TOKEN, token)
            putExtra(UnifiedPush.EXTRA_ENDPOINT, endpoint)
        }
    }

    fun registrationFailed(app: String, token: String, reason: String) {
        Logx.w("msg", "REGISTRATION_FAILED -> $app token=$token reason=$reason")
        send(app, UnifiedPush.ACTION_REGISTRATION_FAILED) {
            putExtra(UnifiedPush.EXTRA_TOKEN, token)
            putExtra(UnifiedPush.EXTRA_REASON, reason)
        }
    }

    fun unregistered(app: String, token: String) {
        Logx.i("msg", "UNREGISTERED -> $app token=$token")
        send(app, UnifiedPush.ACTION_UNREGISTERED) {
            putExtra(UnifiedPush.EXTRA_TOKEN, token)
        }
    }

    fun message(app: String, token: String, payload: ByteArray, msgId: Long) {
        Logx.i("msg", "MESSAGE -> $app token=$token ${payload.size}B id=$msgId")
        send(app, UnifiedPush.ACTION_MESSAGE) {
            putExtra(UnifiedPush.EXTRA_TOKEN, token)
            putExtra(UnifiedPush.EXTRA_BYTES_MESSAGE, payload)
            putExtra(UnifiedPush.EXTRA_MESSAGE, String(payload, StandardCharsets.UTF_8))
            putExtra(UnifiedPush.EXTRA_MESSAGE_ID, msgId.toString())
        }
    }

    private inline fun send(app: String, action: String, build: Intent.() -> Unit) {
        val intent = Intent(action).setPackage(app).apply(build)
        context.sendBroadcast(intent)
    }
}
