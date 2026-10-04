package eu.nosini.unifiedshim.role

import android.content.Context
import eu.nosini.unifiedshim.common.Config
import eu.nosini.unifiedshim.distributor.DistributorService
import eu.nosini.unifiedshim.owner.OwnerListenerService

/**
 * The private side has an always-up foreground service to keep the listener
 * bound. The owner side is normally event-driven (woken by the ntfy app) with no
 * persistent component — but if the optional auto-provision listener is enabled it
 * too has an FGS to (re)start here (e.g. on boot).
 */
object ServiceLauncher {
    fun startForRole(context: Context) {
        when (RoleDetector.detect(context)) {
            Role.PRIVATE -> DistributorService.ensureStarted(context)
            Role.OWNER -> if (Config(context).ownerListenerEnabled) OwnerListenerService.ensureStarted(context)
        }
    }
}
