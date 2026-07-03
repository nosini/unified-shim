package com.tigerlake.unifiedshim.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tigerlake.unifiedshim.common.Config
import com.tigerlake.unifiedshim.distributor.DistributorService
import com.tigerlake.unifiedshim.distributor.EndpointStore
import com.tigerlake.unifiedshim.owner.OwnerBridge
import com.tigerlake.unifiedshim.owner.OwnerStore
import com.tigerlake.unifiedshim.role.Role
import com.tigerlake.unifiedshim.role.RoleDetector
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Status + control screen. Built in code to avoid layout resources. The owner
 * screen is where the user selects the ntfy app as distributor and triggers the
 * manual sync that provisions newly added accounts.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var controls: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
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
        when (role) {
            Role.PRIVATE -> {
                controls.addView(button("Start distributor service") {
                    DistributorService.ensureStarted(this); refresh()
                })
            }
            Role.OWNER -> {
                controls.addView(button("Sync now (provision pending accounts)") { syncOwner() })
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

    private fun buildStatus(role: Role): String = buildString {
        appendLine("Role: $role")
        appendLine("UID: ${Process.myUid()} (appId=${Process.myUid() % 100_000}, user=${Process.myUid() / 100_000})")
        appendLine("Bridge: 127.0.0.1:${Config.BRIDGE_PORT}")
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
                appendLine("ntfy distributor: ${UnifiedPush.getSavedDistributor(this@MainActivity) ?: "(none selected)"}")
                val eps = OwnerStore.get(this@MainActivity).knownEndpoints()
                appendLine("Provisioned endpoints (${eps.size}):")
                eps.forEach { (token, ep) -> appendLine("  • $token\n    $ep") }
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

    private fun requestNotificationsIfNeeded() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
