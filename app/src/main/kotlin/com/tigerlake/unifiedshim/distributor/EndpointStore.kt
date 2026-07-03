package com.tigerlake.unifiedshim.distributor

import android.content.Context
import com.tigerlake.unifiedshim.common.Logx
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent registration table for the distributor side.
 *
 * Keyed by the connector's `token` (one per jmap-mail registration). The endpoint
 * is minted on the owner side by the ntfy app and arrives asynchronously, so a
 * registration sits with `endpoint == null` until then.
 *
 * `deprovision` holds tokens jmap-mail has unregistered but whose ntfy-app
 * instance the owner hasn't released yet — kept until the owner confirms
 * (`DEPROVISIONED`), because the owner may be asleep when the unregister happens.
 */
class EndpointStore private constructor(context: Context) {

    data class Reg(val token: String, val app: String, val endpoint: String?) {
        val pending get() = endpoint == null
    }

    private val prefs = context.applicationContext
        .getSharedPreferences("unifiedshim_endpoints", Context.MODE_PRIVATE)

    private val byToken = HashMap<String, Reg>()
    private val deprovision = LinkedHashSet<String>()

    init {
        load()
    }

    @Synchronized fun byToken(token: String): Reg? = byToken[token]
    @Synchronized fun all(): List<Reg> = byToken.values.toList()
    @Synchronized fun activeTokens(): List<String> = byToken.keys.toList()
    @Synchronized fun deprovisionTokens(): List<String> = deprovision.toList()

    /** Record a new registration awaiting an endpoint. No-op if already present. */
    @Synchronized
    fun putPending(token: String, app: String) {
        if (byToken[token] == null) {
            byToken[token] = Reg(token, app, endpoint = null)
            deprovision.remove(token)
            persist()
        }
    }

    /** Bind an endpoint; returns true if it changed (caller should notify the app). */
    @Synchronized
    fun setEndpoint(token: String, endpoint: String): Boolean {
        val reg = byToken[token] ?: return false
        if (reg.endpoint == endpoint) return false
        byToken[token] = reg.copy(endpoint = endpoint)
        persist()
        return true
    }

    /** jmap-mail unregistered: drop the reg, remember the token to release upstream. */
    @Synchronized
    fun markDeprovision(token: String): Reg? {
        val reg = byToken.remove(token) ?: return null
        deprovision.add(token)
        persist()
        return reg
    }

    /** Owner confirmed the ntfy-app instance was released. */
    @Synchronized
    fun confirmDeprovisioned(token: String) {
        if (deprovision.remove(token)) persist()
    }

    private fun persist() {
        val regs = JSONObject()
        for (reg in byToken.values) {
            regs.put(reg.token, JSONObject().put("app", reg.app).putOpt("endpoint", reg.endpoint))
        }
        prefs.edit()
            .putString("regs", regs.toString())
            .putString("deprovision", JSONArray(deprovision.toList()).toString())
            .apply()
    }

    private fun load() {
        try {
            prefs.getString("regs", null)?.let { raw ->
                val root = JSONObject(raw)
                for (token in root.keys()) {
                    val o = root.getJSONObject(token)
                    byToken[token] = Reg(token, o.getString("app"), o.optString("endpoint", "").ifEmpty { null })
                }
            }
            prefs.getString("deprovision", null)?.let { raw ->
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) deprovision.add(arr.getString(i))
            }
        } catch (e: Exception) {
            Logx.w("store", "corrupt endpoint prefs, starting empty: ${e.message}")
        }
    }

    companion object {
        @Volatile private var instance: EndpointStore? = null

        fun get(context: Context): EndpointStore =
            instance ?: synchronized(this) {
                instance ?: EndpointStore(context.applicationContext).also { instance = it }
            }
    }
}
