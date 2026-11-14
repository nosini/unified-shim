package eu.nosini.unifiedshim.distributor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.nosini.unifiedshim.common.Config
import eu.nosini.unifiedshim.common.Logx

/**
 * Manifest receiver whose intent-filter for REGISTER advertises this app as a
 * UnifiedPush distributor (this is how connector apps discover it).
 *
 * Broadcasts are forwarded into [DistributorService] via startForegroundService
 * so the long-lived listener + controller own all state. The receiver itself
 * stays stateless — it may be invoked before the service exists.
 */
class RegisterReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Logx.i("recv", "$action token=${intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)}")

        // Backstop: persist NEW registrations directly to the shared store BEFORE
        // forwarding. If the FGS start below fails (background start restriction),
        // the pending registration still survives and the owner's next reconcile
        // (on boot / when the user opens the app) provisions it. UNREGISTER is left
        // entirely to the controller so deprovision/notify runs exactly once.
        val store = EndpointStore.get(context)
        when (action) {
            UnifiedPush.ACTION_REGISTER -> {
                val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)
                val app = UnifiedPush.applicationOf(intent)
                if (token != null) {
                    if (app != null) {
                        store.putPending(token, app)
                    } else {
                        Logx.w("recv", "REGISTER token=$token has no resolvable app package")
                    }
                }
            }
        }

        val forward = Intent(context, DistributorService::class.java).apply {
            this.action = action
            putExtras(intent)
        }
        try {
            // Normally the distributor FGS is already running (started at boot / by
            // the user), so this just delivers onStartCommand. The cold case can hit
            // the background FGS-start restriction; remember it and retry from the
            // next app open (or the next boot).
            context.startForegroundService(forward)
        } catch (e: Exception) {
            Logx.w("recv", "could not start distributor service: ${e.message}; will retry on next app open")
            // The controller isn't running, so record the deprovision here; the owner
            // releases it on its next reconcile.
            if (action == UnifiedPush.ACTION_UNREGISTER) {
                intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)?.let { store.markDeprovision(it) }
            }
            Config(context).distributorStartPending = true
        }
    }
}
