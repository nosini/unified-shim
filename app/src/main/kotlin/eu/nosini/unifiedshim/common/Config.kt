package eu.nosini.unifiedshim.common

import android.content.Context
import eu.nosini.unifiedshim.role.Role

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
     * Distributor-side loopback port this profile last bound. Two distributor
     * profiles (private space + work profile) share the device's loopback netns and
     * can't both bind the same port; port assignment is now automatic (the listener
     * walks [PORT_SCAN_RANGE] and binds the first free port), and this persists the
     * result so each profile reclaims its port across reboots.
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

    /**
     * Optional owner-side auto-provision listener. Off by default: enabling it adds
     * an always-on FGS in the owner profile (relaxing the "nothing persistent in the
     * owner profile" property), letting a distributor nudge the owner the instant a
     * registration changes instead of waiting for the user to open the owner app.
     */
    var ownerListenerEnabled: Boolean
        get() = prefs.getBoolean(KEY_OWNER_LISTENER_ENABLED, false)
        set(v) = prefs.edit().putBoolean(KEY_OWNER_LISTENER_ENABLED, v).apply()

    /** Loopback port the owner's auto-provision listener binds. */
    var ownerListenerPort: Int
        get() = prefs.getInt(KEY_OWNER_LISTENER_PORT, OWNER_LISTENER_PORT).takeIf { it in PORT_RANGE } ?: OWNER_LISTENER_PORT
        set(v) = prefs.edit().putInt(KEY_OWNER_LISTENER_PORT, v.coerceIn(PORT_RANGE)).apply()

    /**
     * Distributor-side: the owner's listener port to nudge on a registration change.
     * 0 (or any value outside [PORT_RANGE]) means nudging is disabled — manual
     * provisioning, the default status quo.
     */
    var ownerNudgePort: Int
        get() = prefs.getInt(KEY_OWNER_NUDGE_PORT, OWNER_LISTENER_PORT)
        set(v) = prefs.edit().putInt(KEY_OWNER_NUDGE_PORT, v).apply()

    companion object {
        /** Single lock guarding every read-modify-write of [ownerBridgePorts] (see
         *  [addOwnerBridgePort]). Distinct learners race here — NUDGE reader threads
         *  and OwnerBridge scan sessions — so all growth of the list goes through this
         *  one lock. The plain [ownerBridgePorts] setter is only invoked from inside
         *  [addOwnerBridgePort] (under this lock), so there is no unguarded writer. */
        private val ownerPortsLock = Any()

        /**
         * Learn a distributor port: add [port] to [ownerBridgePorts] if absent. The
         * only writer of the list, so the read-modify-write can't lose a concurrent
         * learn. Called by the NUDGE handler (announced port) and by OwnerBridge after
         * a successful scan handshake (discovered port). Stale learned ports are never
         * pruned — a moved distributor just fast-fails on connect each sweep.
         */
        fun addOwnerBridgePort(context: Context, port: Int) {
            if (port !in PORT_RANGE) return
            synchronized(ownerPortsLock) {
                val cfg = Config(context)
                if (port !in cfg.ownerBridgePorts) {
                    cfg.ownerBridgePorts = cfg.ownerBridgePorts + port
                    Logx.i("cfg", "learned distributor port :$port")
                }
            }
        }

        private const val KEY_FORCED_ROLE = "forced_role"
        private const val KEY_BRIDGE_PORT = "bridge_port"
        private const val KEY_OWNER_PORTS = "owner_bridge_ports"
        private const val KEY_OWNER_LISTENER_ENABLED = "owner_listener_enabled"
        private const val KEY_OWNER_LISTENER_PORT = "owner_listener_port"
        private const val KEY_OWNER_NUDGE_PORT = "owner_nudge_port"

        /** Valid TCP port range for a user-configured bridge port. */
        val PORT_RANGE = 1024..65535

        /** Gates cross-profile loopback on Android 17+ (API 37). Not in the public
         *  Manifest.permission class, hence the string literal. */
        const val PERM_INTERACT_ACROSS_USERS = "android.permission.INTERACT_ACROSS_USERS"

        /**
         * Default TCP loopback port for the cross-profile bridge. Loopback (not an
         * abstract unix socket) because SELinux MLS blocks cross-profile unix
         * `connectto`; loopback TCP crosses the shared netns. Overridable per profile
         * (see [bridgePort] / [ownerBridgePorts]) so multiple distributor profiles
         * can coexist.
         */
        const val BRIDGE_PORT = 28472

        /**
         * Default candidate range a distributor listener walks to auto-assign its
         * port. All profiles share one loopback netns, so a profile that fails to bind
         * a candidate (already taken) simply tries the next — the kernel is the
         * port-conflict arbiter. The owner scans this same range as the announce
         * fallback.
         */
        val PORT_SCAN_RANGE = BRIDGE_PORT..BRIDGE_PORT + 7

        /** Default port for the optional owner auto-provision listener (and thus the
         *  default distributor nudge target). Distinct from [BRIDGE_PORT] so the owner
         *  listener and a same-profile distributor listener never contend. */
        const val OWNER_LISTENER_PORT = 28471
    }
}
