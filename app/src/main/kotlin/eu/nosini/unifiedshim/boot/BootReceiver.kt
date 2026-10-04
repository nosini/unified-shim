package eu.nosini.unifiedshim.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import eu.nosini.unifiedshim.common.Logx
import eu.nosini.unifiedshim.role.ServiceLauncher

/**
 * Restarts the role-appropriate foreground service after boot or app update.
 * Fires independently per profile (the private space must be unlocked for its
 * copy to run).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Logx.i("boot", "received ${intent.action}")
        ServiceLauncher.startForRole(context)
    }
}
