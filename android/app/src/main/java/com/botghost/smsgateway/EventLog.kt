package com.botghost.smsgateway

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(val time: Long, val kind: String, val to: String?, val detail: String) {
    fun timeText(): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(time))
}

/** In-app event log, newest first, persisted to SharedPreferences (capped). */
object EventLog {
    private const val PREFS = "gateway_events"
    private const val CAP = 200

    val entries = mutableStateListOf<LogEntry>()
    private var loaded = false

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    LogEntry(
                        time = o.getLong("t"),
                        kind = o.getString("k"),
                        to = o.optString("to").ifEmpty { null },
                        detail = o.getString("d"),
                    )
                )
            }
        }
    }

    @Synchronized
    fun add(context: Context, kind: String, to: String?, detail: String) {
        ensureLoaded(context)
        entries.add(0, LogEntry(System.currentTimeMillis(), kind, to, detail))
        while (entries.size > CAP) entries.removeAt(entries.size - 1)
        persist(context)
    }

    @Synchronized
    fun clear(context: Context) {
        entries.clear()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("entries").apply()
    }

    private fun persist(context: Context) {
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("t", e.time).put("k", e.kind).put("to", e.to ?: "").put("d", e.detail))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("entries", arr.toString()).apply()
    }
}
