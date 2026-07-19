package com.tigerlake.unifiedshim.distributor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.tigerlake.unifiedshim.common.Logx

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

        // Backstop: persist registration state directly to the shared store BEFORE
        // forwarding. If the FGS start below fails (background start restriction),
        // the pending registration still survives and the owner's next reconcile
        // (on boot / when the user opens the app) provisions it.
        val store = EndpointStore.get(context)
        when (action) {
            UnifiedPush.ACTION_REGISTER -> {
                val token = intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)
                val app = intent.getStringExtra(UnifiedPush.EXTRA_APPLICATION)
                if (token != null && app != null) store.putPending(token, app)
            }
            UnifiedPush.ACTION_UNREGISTER -> {
                intent.getStringExtra(UnifiedPush.EXTRA_TOKEN)?.let { store.markDeprovision(it) }
            }
        }

        val forward = Intent(context, DistributorService::class.java).apply {
            this.action = action
            putExtras(intent)
        }
        try {
            // Normally the distributor FGS is already running (started at boot / by
            // the user), so this just delivers onStartCommand. The cold case can hit
            // the background FGS-start restriction; the user re-opening the app or the
            // next boot recovers it.
            context.startForegroundService(forward)
        } catch (e: Exception) {
            Logx.w("recv", "could not start distributor service: ${e.message}")
            // FGS couldn't start (background restriction), but the state is already
            // persisted above. Best-effort nudge the optional owner listener so it
            // reconciles now instead of waiting for a manual owner-app open. goAsync
            // keeps the process alive across the blocking loopback round-trip.
            val pending = goAsync()
            Thread {
                try { OwnerNudger.nudge(context) } finally { pending.finish() }
            }.apply { isDaemon = true }.start()
        }
    }
}
