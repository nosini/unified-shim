package eu.nosini.unifiedshim

import android.app.Application
import android.content.ComponentName
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
        gateComponentsForRole()
    }

    private fun gateComponentsForRole() {
        val owner = RoleDetector.detect(this) == Role.OWNER
        setComponentEnabled(RegisterReceiver::class.java, !owner)
        setComponentEnabled(LinkActivity::class.java, !owner)
        setComponentEnabled(DistributorService::class.java, !owner)
        setComponentEnabled(OwnerPushReceiver::class.java, owner)
        // Optional owner-side auto-provision listener: owner-role only (it's gated
        // further by ownerListenerEnabled at start time; disabling here just keeps
        // the wrong-profile stack un-triggerable, like OwnerPushReceiver).
        setComponentEnabled(OwnerListenerService::class.java, owner)
    }

    private fun setComponentEnabled(cls: Class<*>, enabled: Boolean) {
        val desired = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                      else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val component = ComponentName(this, cls)
        // Idempotent: only write when the state actually differs, to avoid churning
        // PackageManager settings on every process start.
        if (packageManager.getComponentEnabledSetting(component) != desired) {
            packageManager.setComponentEnabledSetting(component, desired, PackageManager.DONT_KILL_APP)
        }
    }
}
