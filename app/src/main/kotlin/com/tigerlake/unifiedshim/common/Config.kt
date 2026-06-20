package com.tigerlake.unifiedshim.common

import android.content.Context
import com.tigerlake.unifiedshim.role.Role

/**
 * Small SharedPreferences-backed config. Owner side cares about the ntfy server;
 * both sides can force a role for testing on a device where the heuristic is wrong.
 */
class Config(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("unifiedshim", Context.MODE_PRIVATE)

    /** Override automatic role detection. null = auto. */
    var forcedRole: Role?
        get() = prefs.getString(KEY_FORCED_ROLE, null)?.let { runCatching { Role.valueOf(it) }.getOrNull() }
        set(v) = prefs.edit().apply {
            if (v == null) remove(KEY_FORCED_ROLE) else putString(KEY_FORCED_ROLE, v.name)
        }.apply()

    companion object {
        private const val KEY_FORCED_ROLE = "forced_role"

        /**
         * TCP loopback port for the cross-profile bridge. Loopback (not an abstract
         * unix socket) because SELinux MLS blocks cross-profile unix `connectto`;
         * loopback TCP crosses the shared netns. Fixed port, same in both profiles.
         */
        const val BRIDGE_PORT = 28472
    }
}
