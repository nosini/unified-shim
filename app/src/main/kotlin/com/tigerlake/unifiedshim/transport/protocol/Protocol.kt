package com.tigerlake.unifiedshim.transport.protocol

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Frame types and (de)serialization for the bridge protocol.
 *
 * The owner side is an event-driven UnifiedPush *connector* registered with the
 * ntfy app; it connects to the always-up private listener when it has something
 * to deliver. The exchange is reconciliation-based (not request/response),
 * because the private side cannot wake a sleeping owner:
 *
 *   handshake:               HELLO (owner→priv) / HELLO_ACK (priv→owner)
 *   on connect (priv→owner): REGISTRATIONS — the full set of jmap-mail tokens,
 *                            plus tokens awaiting deprovision.
 *   owner→priv:              ENDPOINT (an endpoint the ntfy app minted),
 *                            DEPROVISIONED (a token released from the ntfy app),
 *                            PUSH (an inbound message, binary).
 *   priv→owner:              PUSH_ACK.
 *
 * Control/handshake bodies are UTF-8 JSON. PUSH is binary to stay byte-exact for
 * RFC 8291 (aes128gcm) Web Push payloads.
 */
object Protocol {
    const val VERSION = 2

    const val HELLO: Byte = 0x01
    const val HELLO_ACK: Byte = 0x02
    const val REGISTRATIONS: Byte = 0x10
    const val ENDPOINT: Byte = 0x11
    const val DEPROVISIONED: Byte = 0x12
    const val PUSH: Byte = 0x20
    const val PUSH_ACK: Byte = 0x21

    private fun json(type: Byte, obj: JSONObject) =
        Frame(type, obj.toString().toByteArray(StandardCharsets.UTF_8))

    fun parseJson(frame: Frame): JSONObject =
        JSONObject(String(frame.body, StandardCharsets.UTF_8))

    // ---- handshake ----

    fun hello(role: String, appId: Int, userId: Int) = json(
        HELLO,
        JSONObject().put("version", VERSION).put("role", role).put("appId", appId).put("userId", userId),
    )

    fun helloAck(ok: Boolean, reason: String? = null) = json(
        HELLO_ACK,
        JSONObject().put("version", VERSION).put("ok", ok).putOpt("reason", reason),
    )

    // ---- reconciliation ----

    fun registrations(active: Collection<String>, deprovision: Collection<String>) = json(
        REGISTRATIONS,
        JSONObject()
            .put("active", JSONArray(active))
            .put("deprovision", JSONArray(deprovision)),
    )

    class Registrations(val active: List<String>, val deprovision: List<String>)

    fun parseRegistrations(frame: Frame): Registrations {
        val o = parseJson(frame)
        return Registrations(o.getJSONArray("active").toStringList(), o.getJSONArray("deprovision").toStringList())
    }

    fun endpoint(token: String, endpoint: String) =
        json(ENDPOINT, JSONObject().put("token", token).put("endpoint", endpoint))

    fun deprovisioned(token: String) =
        json(DEPROVISIONED, JSONObject().put("token", token))

    // ---- data (binary) ----

    /** PUSH body: [int64 msgId][int16 tokenLen][token utf8][payload...]. */
    fun push(msgId: Long, token: String, payload: ByteArray): Frame {
        val tokenBytes = token.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(8 + 2 + tokenBytes.size + payload.size)
        buf.putLong(msgId)
        buf.putShort(tokenBytes.size.toShort())
        buf.put(tokenBytes)
        buf.put(payload)
        return Frame(PUSH, buf.array())
    }

    class PushMsg(val msgId: Long, val token: String, val payload: ByteArray)

    fun parsePush(frame: Frame): PushMsg {
        val buf = ByteBuffer.wrap(frame.body)
        val msgId = buf.long
        val tokenLen = buf.short.toInt() and 0xFFFF
        val tokenBytes = ByteArray(tokenLen).also { buf.get(it) }
        val payload = ByteArray(buf.remaining()).also { buf.get(it) }
        return PushMsg(msgId, String(tokenBytes, StandardCharsets.UTF_8), payload)
    }

    fun pushAck(msgId: Long) = json(PUSH_ACK, JSONObject().put("msgId", msgId))

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).map { getString(it) }
}
