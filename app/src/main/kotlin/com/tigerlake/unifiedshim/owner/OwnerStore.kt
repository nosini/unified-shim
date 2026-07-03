package com.tigerlake.unifiedshim.owner

import android.content.Context
import android.util.Base64
import com.tigerlake.unifiedshim.common.Logx
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner-side persistence.
 *
 * - `endpoints`: token → endpoint as learned from the ntfy app (`onNewEndpoint`),
 *   so a reconcile can re-send them to the private side without re-registering.
 * - `pendingPush`: pushes that couldn't be forwarded (private momentarily
 *   unreachable), flushed on the next successful connection. Bounded.
 *
 * The token here is the UnifiedPush *instance* we register with the ntfy app,
 * which is exactly the private side's connector token (see [OwnerBridge]).
 */
class OwnerStore private constructor(context: Context) {

    class PendingPush(val token: String, val payload: ByteArray)

    private val prefs = context.applicationContext
        .getSharedPreferences("unifiedshim_owner", Context.MODE_PRIVATE)

    private val endpoints = HashMap<String, String>()
    private val pending = ArrayDeque<PendingPush>()

    init {
        load()
    }

    @Synchronized fun endpointFor(token: String): String? = endpoints[token]
    @Synchronized fun knownEndpoints(): Map<String, String> = HashMap(endpoints)

    @Synchronized
    fun putEndpoint(token: String, endpoint: String) {
        if (endpoints[token] != endpoint) {
            endpoints[token] = endpoint
            persist()
        }
    }

    @Synchronized
    fun forget(token: String) {
        if (endpoints.remove(token) != null) persist()
    }

    @Synchronized
    fun enqueuePending(push: PendingPush) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(push)
        persist()
    }

    @Synchronized
    fun drainPending(): List<PendingPush> {
        val out = pending.toList()
        if (pending.isNotEmpty()) { pending.clear(); persist() }
        return out
    }

    private fun persist() {
        val eps = JSONObject()
        endpoints.forEach { (k, v) -> eps.put(k, v) }
        val pp = JSONArray()
        pending.forEach {
            pp.put(JSONObject().put("token", it.token).put("payload", Base64.encodeToString(it.payload, Base64.NO_WRAP)))
        }
        prefs.edit().putString("endpoints", eps.toString()).putString("pending", pp.toString()).apply()
    }

    private fun load() {
        try {
            prefs.getString("endpoints", null)?.let { raw ->
                val o = JSONObject(raw)
                for (k in o.keys()) endpoints[k] = o.getString(k)
            }
            prefs.getString("pending", null)?.let { raw ->
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    pending.addLast(PendingPush(o.getString("token"), Base64.decode(o.getString("payload"), Base64.NO_WRAP)))
                }
            }
        } catch (e: Exception) {
            Logx.w("store", "corrupt owner prefs, starting empty: ${e.message}")
        }
    }

    companion object {
        private const val MAX_PENDING = 256

        @Volatile private var instance: OwnerStore? = null

        fun get(context: Context): OwnerStore =
            instance ?: synchronized(this) {
                instance ?: OwnerStore(context.applicationContext).also { instance = it }
            }
    }
}
