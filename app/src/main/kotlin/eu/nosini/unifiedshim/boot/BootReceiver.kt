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
        val action = intent.action ?: return
        Logx.i("boot", "received $action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> try {
                ServiceLauncher.startForRole(context)
            } catch (e: Exception) {
                Logx.e("boot", "could not start service after $action", e)
            }
            else -> Logx.d("boot", "ignoring $action")
        }
    }
}
