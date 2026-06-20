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
        }
    }
}
