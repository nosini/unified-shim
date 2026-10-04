package eu.nosini.unifiedshim.owner

import android.content.Context
import android.util.Base64
import eu.nosini.unifiedshim.common.Logx
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owner-side persistence.
 *
 * - `endpoints`: token → endpoint as learned from the ntfy app (`onNewEndpoint`),
 *   so a reconcile can re-send them to the private side without re-registering.
 * - `ports`: token → distributor loopback port, learned from which port's
 *   REGISTRATIONS snapshot the token appeared in. Lets token-directed operations
 *   open a session to the one distributor that owns the token instead of blindly
 *   flushing to every distributor (which would falsely-ack and drop the push).
 * - `pendingPush`: pushes that couldn't be forwarded (private momentarily
 *   unreachable), flushed on the next successful connection. Bounded.
 *
 * The token here is the UnifiedPush *instance* we register with the ntfy app,
 * which is exactly the private side's connector token (see [OwnerBridge]).
 */
class OwnerStore private constructor(context: Context) {

    class PendingPush(val msgId: Long, val token: String, val payload: ByteArray)

    private val prefs = context.applicationContext
        .getSharedPreferences("unifiedshim_owner", Context.MODE_PRIVATE)

    private val endpoints = HashMap<String, String>()
    private val ports = HashMap<String, Int>()
    private val pending = ArrayDeque<PendingPush>()

    init {
        load()
    }

    @Synchronized fun endpointFor(token: String): String? = endpoints[token]
    @Synchronized fun knownEndpoints(): Map<String, String> = HashMap(endpoints)

    /** Which distributor port owns this token, if we've seen it in a snapshot. */
    @Synchronized fun portFor(token: String): Int? = ports[token]

    /** Bind a token to the port whose snapshot it appeared in. A token seen on a
     *  different port than before is astronomically unlikely (UUID tokens) — warn
     *  and rebind. */
    @Synchronized
    fun bindPort(token: String, port: Int) {
        val existing = ports[token]
        if (existing == port) return
        if (existing != null) Logx.w("store", "token=$token remapped port $existing -> $port")
        ports[token] = port
        persist()
    }

    @Synchronized
    fun putEndpoint(token: String, endpoint: String) {
        if (endpoints[token] != endpoint) {
            endpoints[token] = endpoint
            persist()
        }
    }

    @Synchronized
    fun forget(token: String) {
        var changed = endpoints.remove(token) != null
        if (ports.remove(token) != null) changed = true
        if (changed) persist()
    }

    @Synchronized
    fun enqueue(push: PendingPush) {
        if (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(push)
        persist()
    }

    @Synchronized
    fun peekPending(): List<PendingPush> = pending.toList()

    @Synchronized
    fun removePending(msgId: Long) {
        if (pending.removeAll { it.msgId == msgId }) persist()
    }

    private fun persist() {
        val eps = JSONObject()
        endpoints.forEach { (k, v) -> eps.put(k, v) }
        val prt = JSONObject()
        ports.forEach { (k, v) -> prt.put(k, v) }
        val pp = JSONArray()
        pending.forEach {
            pp.put(JSONObject().put("msgId", it.msgId).put("token", it.token).put("payload", Base64.encodeToString(it.payload, Base64.NO_WRAP)))
        }
        prefs.edit()
            .putString("endpoints", eps.toString())
            .putString("ports", prt.toString())
            .putString("pending", pp.toString())
            .apply()
    }

    private fun load() {
        try {
            prefs.getString("endpoints", null)?.let { raw ->
                val o = JSONObject(raw)
                for (k in o.keys()) endpoints[k] = o.getString(k)
            }
            prefs.getString("ports", null)?.let { raw ->
                val o = JSONObject(raw)
                for (k in o.keys()) ports[k] = o.getInt(k)
            }
            prefs.getString("pending", null)?.let { raw ->
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    pending.addLast(PendingPush(o.getLong("msgId"), o.getString("token"), Base64.decode(o.getString("payload"), Base64.NO_WRAP)))
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
