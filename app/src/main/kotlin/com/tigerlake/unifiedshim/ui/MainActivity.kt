package com.tigerlake.unifiedshim.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.distributor.DistributorService
import com.tigerlake.unifiedshim.distributor.EndpointStore
import com.tigerlake.unifiedshim.owner.OwnerBridge
import com.tigerlake.unifiedshim.owner.OwnerListenerService
import com.tigerlake.unifiedshim.owner.OwnerStore
import com.tigerlake.unifiedshim.role.Role
import com.tigerlake.unifiedshim.role.RoleDetector
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Status + control screen. Built in code to avoid layout resources. The owner
 * screen is where the user selects the ntfy app as distributor and triggers the
 * manual sync that provisions newly added accounts. A distributor profile (private
 * space or work profile) uses it to start the service and pick its bridge port.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var controls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(16)
            setPadding(pad, pad, pad, pad)
        }

        // targetSdk 35 forces edge-to-edge; without inset padding the top of the
        // content is clipped under the status bar / display cutout. Add the system
        // bar + cutout insets on top of the base 16dp content padding. Works down to
        // minSdk 29 via the androidx compat shim.
        val basePad = dp(16)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                left = basePad + bars.left,
                top = basePad + bars.top,
                right = basePad + bars.right,
                bottom = basePad + bars.bottom,
            )
            insets
        }

        status = TextView(this)
        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        root.addView(status)
        root.addView(divider())
        root.addView(controls)
        setContentView(ScrollView(this).apply { addView(root) })

        requestNotificationsIfNeeded()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Opening the owner app is the manual provisioning trigger.
        if (RoleDetector.detect(this) == Role.OWNER) syncOwner()
    }

    private fun refresh() {
        val role = RoleDetector.detect(this)
        controls.removeAllViews()
        status.text = buildStatus(role)

        controls.addView(roleButton())

        when (role) {
            Role.PRIVATE -> {
                controls.addView(button("Start distributor service") {
                    DistributorService.ensureStarted(this); refresh()
                })
                controls.addView(portEditor(role))
                val cfg = Config(this)
                controls.addView(TextView(this).apply { text = "\nOwner port (0 = manual provisioning):" })
                controls.addView(
                    singlePortField(cfg.ownerNudgePort.toString(), "Apply owner port") { applyOwnerNudgePort(it) }
                )
            }
            Role.OWNER -> {
                controls.addView(button("Sync now (provision pending accounts)") { syncOwner() })
                controls.addView(portEditor(role))
                val cfg = Config(this)
                controls.addView(button("Auto-provision listener: ${if (cfg.ownerListenerEnabled) "ON" else "OFF"}") {
                    toggleOwnerListener()
                })
                controls.addView(TextView(this).apply { text = "Auto-provision listener port:" })
                controls.addView(
                    singlePortField(cfg.ownerListenerPort.toString(), "Apply listener port") { applyOwnerListenerPort(it) }
                )
                controls.addView(TextView(this).apply { text = "\nSelect ntfy app as distributor:" })
                val self = packageName
                UnifiedPush.getDistributors(this).filter { it != self }.forEach { dist ->
                    controls.addView(button("Use: $dist") {
                        UnifiedPush.saveDistributor(this, dist)
                        syncOwner()
                    })
                }
            }
        }
    }

    /** Cycle the forced-role override auto → OWNER → PRIVATE → auto and recreate. */
    private fun roleButton(): Button {
        val cfg = Config(this)
        val current = cfg.forcedRole?.name ?: "auto"
        return button("Role: $current (tap to change)") {
            cfg.forcedRole = when (cfg.forcedRole) {
                null -> Role.OWNER
                Role.OWNER -> Role.PRIVATE
                Role.PRIVATE -> null
            }
            recreate()
        }
    }

    /**
     * PRIVATE: single bind port + Apply. OWNER: comma-separated list of the
     * distributor ports to reach + Apply. Two distributor profiles share the
     * loopback netns, so each must bind a distinct port and the owner lists both.
     */
    private fun portEditor(role: Role): LinearLayout {
        val cfg = Config(this)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val edit = EditText(this).apply {
            // TYPE_CLASS_NUMBER rejects the comma the owner's list needs, so the
            // owner side uses a digits-plus-comma key listener instead.
            if (role == Role.PRIVATE) inputType = InputType.TYPE_CLASS_NUMBER
            else keyListener = DigitsKeyListener.getInstance("0123456789,")
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(
                if (role == Role.PRIVATE) cfg.bridgePort.toString()
                else cfg.ownerBridgePorts.joinToString(",")
            )
        }
        val apply = button(if (role == Role.PRIVATE) "Apply port" else "Apply ports") {
            if (role == Role.PRIVATE) applyPrivatePort(cfg, edit.text.toString())
            else applyOwnerPorts(cfg, edit.text.toString())
        }
        row.addView(edit)
        row.addView(apply)
        return row
    }

    private fun applyPrivatePort(cfg: Config, raw: String) {
        val port = raw.trim().toIntOrNull()
        if (port == null || port !in Config.PORT_RANGE) {
            toast("Port must be ${Config.PORT_RANGE.first}..${Config.PORT_RANGE.last}")
            return
        }
        val changed = port != cfg.bridgePort
        cfg.bridgePort = port
        if (changed && DistributorService.running) DistributorService.restart(this)
        toast("Bridge port set to $port")
        refresh()
    }

    private fun applyOwnerPorts(cfg: Config, raw: String) {
        val ports = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { it.toIntOrNull() }
        if (ports.isEmpty() || ports.any { it == null || it !in Config.PORT_RANGE }) {
            toast("Ports must be a comma list in ${Config.PORT_RANGE.first}..${Config.PORT_RANGE.last}")
            return
        }
        cfg.ownerBridgePorts = ports.filterNotNull()
        toast("Owner ports set to ${cfg.ownerBridgePorts.joinToString(",")}")
        refresh()
    }

    /** A single numeric port field + Apply, for the auto-provision ports (owner
     *  listener port; distributor's owner-nudge port). Plain TYPE_CLASS_NUMBER — no
     *  comma list, unlike the owner's distributor-ports editor. */
    private fun singlePortField(initial: String, applyLabel: String, onApply: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val edit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText(initial)
        }
        row.addView(edit)
        row.addView(button(applyLabel) { onApply(edit.text.toString()) })
        return row
    }

    private fun toggleOwnerListener() {
        val cfg = Config(this)
        val enable = !cfg.ownerListenerEnabled
        cfg.ownerListenerEnabled = enable
        if (enable) OwnerListenerService.ensureStarted(this) else OwnerListenerService.stop(this)
        toast(if (enable) "Auto-provision listener enabled" else "Auto-provision listener disabled")
        refresh()
    }

    private fun applyOwnerListenerPort(raw: String) {
        val cfg = Config(this)
        val port = raw.trim().toIntOrNull()
        if (port == null || port !in Config.PORT_RANGE) {
            toast("Port must be ${Config.PORT_RANGE.first}..${Config.PORT_RANGE.last}")
            return
        }
        val changed = port != cfg.ownerListenerPort
        cfg.ownerListenerPort = port
        if (changed && cfg.ownerListenerEnabled && OwnerListenerService.running) OwnerListenerService.restart(this)
        toast("Listener port set to $port")
        refresh()
    }

    /** Distributor-side owner-nudge port. 0 is a valid input meaning disabled. */
    private fun applyOwnerNudgePort(raw: String) {
        val cfg = Config(this)
        val port = raw.trim().toIntOrNull()
        if (port == null || (port != 0 && port !in Config.PORT_RANGE)) {
            toast("Port must be 0 (manual) or ${Config.PORT_RANGE.first}..${Config.PORT_RANGE.last}")
            return
        }
        cfg.ownerNudgePort = port
        toast(if (port == 0) "Owner port cleared (manual provisioning)" else "Owner port set to $port")
        refresh()
    }

    private fun buildStatus(role: Role): String = buildString {
        appendLine("Role: $role")
        appendLine("UID: ${Process.myUid()} (appId=${Process.myUid() % 100_000}, user=${Process.myUid() / 100_000})")
        // Android 17 (API 37) gates cross-profile loopback behind INTERACT_ACROSS_USERS.
        if (android.os.Build.VERSION.SDK_INT >= 37) {
            appendLine()
            appendLine("Android 17+: if the bridge can't connect, grant via ADB:")
            appendLine("  pm grant --user ${Process.myUid() / 100_000} $packageName \\")
            appendLine("    android.permission.INTERACT_ACROSS_USERS")
        }
        appendLine()
        when (role) {
            Role.PRIVATE -> {
                val cfg = Config(this@MainActivity)
                appendLine("Bridge listener: 127.0.0.1:${cfg.bridgePort}")
                appendLine("Service running: ${DistributorService.running}")
                val np = cfg.ownerNudgePort
                appendLine("Owner nudge port: ${if (np in Config.PORT_RANGE) np.toString() else "manual (disabled)"}")
                val store = EndpointStore.get(this@MainActivity)
                appendLine("Registrations (${store.all().size}):")
                store.all().forEach { r ->
                    appendLine("  • ${r.app}")
                    appendLine("    token=${r.token}")
                    appendLine("    ${if (r.pending) "PENDING (awaiting owner)" else r.endpoint}")
                }
                val dp = store.deprovisionTokens()
                if (dp.isNotEmpty()) appendLine("Awaiting deprovision: ${dp.size}")
            }
            Role.OWNER -> {
                val cfg = Config(this@MainActivity)
                appendLine("Distributor ports: ${cfg.ownerBridgePorts.joinToString(",")}")
                appendLine(
                    if (cfg.ownerListenerEnabled)
                        "Auto-provision listener: ON 127.0.0.1:${cfg.ownerListenerPort} (running=${OwnerListenerService.running})"
                    else "Auto-provision listener: OFF (manual provisioning)"
                )
                appendLine("ntfy distributor: ${UnifiedPush.getSavedDistributor(this@MainActivity) ?: "(none selected)"}")
                val store = OwnerStore.get(this@MainActivity)
                val eps = store.knownEndpoints()
                appendLine("Provisioned endpoints (${eps.size}):")
                eps.forEach { (token, ep) ->
                    appendLine("  • $token  (:${store.portFor(token) ?: "?"})")
                    appendLine("    $ep")
                }
            }
        }
    }

    private fun syncOwner() {
        Thread { OwnerBridge.reconcile(this) }.apply { isDaemon = true }.start()
        status.postDelayed({ refresh() }, 1_500)
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        setBackgroundColor(0x33888888)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun requestNotificationsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
