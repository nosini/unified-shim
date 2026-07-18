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

    /**
     * Distributor-side loopback port this profile binds. Two distributor profiles
     * (private space + work profile) share the device's loopback netns and can't
     * both bind the same port, so each is configured with a distinct one.
     */
    var bridgePort: Int
        get() = prefs.getInt(KEY_BRIDGE_PORT, BRIDGE_PORT).takeIf { it in PORT_RANGE } ?: BRIDGE_PORT
        set(v) = prefs.edit().putInt(KEY_BRIDGE_PORT, v.coerceIn(PORT_RANGE)).apply()

    /**
     * Owner-side list of distributor ports to talk to — one per distributor
     * profile. The owner opens a session per port and routes strictly by token.
     */
    var ownerBridgePorts: List<Int>
        get() = prefs.getString(KEY_OWNER_PORTS, null)
            ?.split(',')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.filter { it in PORT_RANGE }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(BRIDGE_PORT)
        set(v) {
            val clean = v.filter { it in PORT_RANGE }.distinct().ifEmpty { listOf(BRIDGE_PORT) }
            prefs.edit().putString(KEY_OWNER_PORTS, clean.joinToString(",")).apply()
        }

    companion object {
        private const val KEY_FORCED_ROLE = "forced_role"
        private const val KEY_BRIDGE_PORT = "bridge_port"
        private const val KEY_OWNER_PORTS = "owner_bridge_ports"

        /** Valid TCP port range for a user-configured bridge port. */
        val PORT_RANGE = 1024..65535

        /**
         * Default TCP loopback port for the cross-profile bridge. Loopback (not an
         * abstract unix socket) because SELinux MLS blocks cross-profile unix
         * `connectto`; loopback TCP crosses the shared netns. Overridable per profile
         * (see [bridgePort] / [ownerBridgePorts]) so multiple distributor profiles
         * can coexist.
         */
        const val BRIDGE_PORT = 28472
    }
}
