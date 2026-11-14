package eu.nosini.unifiedshim

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import eu.nosini.unifiedshim.common.Notifications
import eu.nosini.unifiedshim.distributor.DistributorService
import eu.nosini.unifiedshim.distributor.LinkActivity
import eu.nosini.unifiedshim.distributor.RegisterReceiver
import eu.nosini.unifiedshim.owner.OwnerListenerService
import eu.nosini.unifiedshim.owner.OwnerPushReceiver
import eu.nosini.unifiedshim.role.Role
import eu.nosini.unifiedshim.role.RoleDetector

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        gateComponentsForRole(this)
    }

    companion object {
        /** Enable only the components belonging to the detected (or forced) role. */
        fun gateComponentsForRole(context: Context) {
            val owner = RoleDetector.detect(context) == Role.OWNER
            setComponentEnabled(context, RegisterReceiver::class.java, !owner)
            setComponentEnabled(context, LinkActivity::class.java, !owner)
            setComponentEnabled(context, DistributorService::class.java, !owner)
            setComponentEnabled(context, OwnerPushReceiver::class.java, owner)
            // Optional owner-side auto-provision listener: owner-role only (it's gated
            // further by ownerListenerEnabled at start time; disabling here just keeps
            // the wrong-profile stack un-triggerable, like OwnerPushReceiver).
            setComponentEnabled(context, OwnerListenerService::class.java, owner)
        }

        private fun setComponentEnabled(context: Context, cls: Class<*>, enabled: Boolean) {
            val desired = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                          else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val component = ComponentName(context, cls)
            // Idempotent: only write when the state actually differs, to avoid churning
            // PackageManager settings on every process start.
            if (context.packageManager.getComponentEnabledSetting(component) != desired) {
                context.packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
