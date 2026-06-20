package com.tigerlake.unifiedshim.role

import android.content.Context
import android.os.Process
import android.os.UserManager
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.common.Logx

/**
 * Per-profile role detection.
 *
 * The owner / system user always has serial number 0. Anything else (the private
 * space is just another profile) is treated as the distributor side. A config
 * override exists for devices where the heuristic is wrong or for testing two
 * non-owner profiles.
 */
object RoleDetector {

    fun detect(context: Context): Role {
        Config(context).forcedRole?.let {
            Logx.i("role", "forced role = $it")
            return it
        }
        val role = if (isOwnerUser(context)) Role.OWNER else Role.PRIVATE
        Logx.i("role", "detected role = $role (uid=${Process.myUid()})")
        return role
    }

    private fun isOwnerUser(context: Context): Boolean {
        val um = context.getSystemService(Context.USER_SERVICE) as UserManager
        return um.getSerialNumberForUser(Process.myUserHandle()) == 0L
    }
}
