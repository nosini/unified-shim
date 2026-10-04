package eu.nosini.unifiedshim.owner

import android.content.Context
import android.content.Intent
import eu.nosini.unifiedshim.common.Logx
import org.unifiedpush.android.connector.MessagingReceiver

/**
 * Owner-side UnifiedPush connector. Registered with the ntfy app, this is what
 * the ntfy app wakes when a push arrives. The connector maps its internal token
 * back to the *instance* we registered — which is the private side's token — so
 * every callback already carries the right key to forward across the socket.
 *
 * The forwarding work blocks on a localhost round-trip, so we move the connector's
 * dispatch off the main thread with goAsync() while keeping the process alive.
 */
class OwnerPushReceiver : MessagingReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                super.onReceive(context, intent)
            } catch (e: Exception) {
                Logx.e("owner", "receiver error", e)
            } finally {
                pending.finish()
            }
        }.apply { isDaemon = true }.start()
    }

    override fun onMessage(context: Context, message: ByteArray, instance: String) {
        Logx.i("owner", "ntfy push for instance=$instance ${message.size}B")
        OwnerBridge.deliver(context, token = instance, payload = message)
    }

    override fun onNewEndpoint(context: Context, endpoint: String, instance: String) {
        Logx.i("owner", "ntfy endpoint for instance=$instance")
        OwnerBridge.onEndpoint(context, token = instance, endpoint = endpoint)
    }

    override fun onUnregistered(context: Context, instance: String) {
        OwnerBridge.onUnregistered(context, token = instance)
    }

    override fun onRegistrationFailed(context: Context, instance: String) {
        Logx.w("owner", "ntfy registration failed for instance=$instance")
    }
}
