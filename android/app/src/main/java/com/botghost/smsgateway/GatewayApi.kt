package com.botghost.smsgateway

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

data class PendingMessage(val id: String, val to: String, val message: String)

/** Minimal HTTP client for the relay worker; throws IOException on any non-2xx. */
class GatewayApi(serverUrl: String, private val deviceId: String, private val apiKey: String) {

    private val base: String = run {
        val t = serverUrl.trim()
        (if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t").trimEnd('/')
    }
    private val devicePath: String get() = "$base/g/$deviceId"

    fun heartbeat() {
        request("POST", "$devicePath/heartbeat", "{}")
    }

    fun poll(limit: Int = 10): List<PendingMessage> {
        val body = request("GET", "$devicePath/poll?limit=$limit", null) ?: return emptyList()
        val arr = JSONObject(body).optJSONArray("messages") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PendingMessage(o.getString("id"), o.getString("to"), o.getString("message"))
        }
    }

    fun ack(id: String, sent: Boolean, error: String? = null) {
        val o = JSONObject().put("id", id).put("status", if (sent) "sent" else "failed")
        if (error != null) o.put("error", error)
        request("POST", "$devicePath/ack", o.toString())
    }

    /** Enqueue a message on this device's queue (used for the in-app test send). */
    fun sendTo(to: String, message: String) {
        val body = JSONObject().put("to", to).put("message", message).toString()
        request("POST", "$devicePath/outbox", body)
    }

    fun rotateKey(newKey: String) {
        val body = JSONObject().put("newKeyHash", sha256Hex(newKey)).toString()
        request("POST", "$devicePath/rotate", body)
    }

    fun destroy() {
        request("DELETE", "$devicePath/delete", null)
    }

    @Throws(IOException::class)
    private fun request(method: String, url: String, body: String?): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("X-Api-Key", apiKey)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299) throw IOException("HTTP $code: ${text?.take(200)}")
            text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** 48-hex-char API key generated on the phone; the server only sees its hash. */
        fun generateApiKey(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun sha256Hex(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Register a new gateway; returns the device id. */
        @Throws(IOException::class)
        fun register(serverUrl: String, apiKey: String, name: String): String {
            val base = run {
                val t = serverUrl.trim()
                (if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t").trimEnd('/')
            }
            val body = JSONObject()
                .put("keyHash", sha256Hex(apiKey))
                .put("name", name)
                .toString()
            val conn = URL("$base/api/register").openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }
                if (code !in 200..299) throw IOException("HTTP $code: ${text?.take(200)}")
                return JSONObject(text ?: "{}").getString("deviceId")
            } finally {
                conn.disconnect()
            }
        }
    }
}
