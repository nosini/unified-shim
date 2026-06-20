package com.tigerlake.unifiedshim.role

import android.content.Context
import com.tigerlake.unifiedshim.distributor.DistributorService

/**
 * The private side has an always-up foreground service to keep the listener
 * bound. The owner side is event-driven (woken by the ntfy app) and has no
 * persistent component, so there is nothing to start for it here.
 */
object ServiceLauncher {
    fun startForRole(context: Context) {
        if (RoleDetector.detect(context) == Role.PRIVATE) {
            DistributorService.ensureStarted(context)
        }
    }
}
