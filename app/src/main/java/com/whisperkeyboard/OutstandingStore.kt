package com.whisperkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object OutstandingStore {

    private const val PREF = "whisper_outstanding"
    private const val KEY = "items"
    private const val KEY_LAST_ADD = "last_add_ts"
    private const val MAX = 20

    const val EXPIRY_MS = 120_000L

    private val listeners = mutableSetOf<() -> Unit>()

    fun register(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unregister(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyChanged() {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { try { it() } catch (_: Exception) {} }
    }

    @Synchronized fun count(ctx: Context?): Int {
        if (ctx == null) return 0
        return try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            prefs.getString(KEY, null)?.let { JSONArray(it).length() } ?: 0
        } catch (_: Exception) { 0 }
    }

    @Synchronized fun add(ctx: Context?, text: String) {
        if (ctx == null || text.isBlank()) return
        try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = readArray(prefs.getString(KEY, null))

            while (arr.length() >= MAX) { arr.remove(0) }
            arr.put(JSONObject().put("t", text.trim()).put("ts", System.currentTimeMillis()))
            prefs.edit().putString(KEY, arr.toString()).putLong(KEY_LAST_ADD, System.currentTimeMillis()).apply()

            val clipOn = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE).getBoolean("out_clipboard", true)
            if (clipOn) {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                cm?.setPrimaryClip(android.content.ClipData.newPlainText("whisper", text.trim()))
            }
            AppLog.i("Outstanding", "held for later (${arr.length()} waiting)${if (clipOn) " + copied to clipboard" else ""}: ${text.take(50)}")
            ProcessingService.notifyActivity()
            notifyChanged()
        } catch (_: Exception) {}
    }

    private fun readArray(raw: String?): JSONArray {
        if (raw.isNullOrEmpty()) return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun entryText(o: Any?): String = when (o) {
        is JSONObject -> o.optString("t", "")
        is String -> o
        else -> o?.toString() ?: ""
    }

    @Synchronized fun popOldest(ctx: Context?): String? {
        if (ctx == null) return null
        return try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = readArray(prefs.getString(KEY, null))
            if (arr.length() == 0) return null
            val text = entryText(arr.get(0))
            arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
            notifyChanged()
            text.ifEmpty { null }
        } catch (_: Exception) { null }
    }

    @Synchronized fun requeueFront(ctx: Context?, text: String) {
        if (ctx == null || text.isBlank()) return
        try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = readArray(prefs.getString(KEY, null))
            val kept = JSONArray()
            kept.put(JSONObject().put("t", text.trim()).put("ts", System.currentTimeMillis()))
            for (i in 0 until arr.length()) kept.put(arr.get(i))
            while (kept.length() > MAX) kept.remove(kept.length() - 1)
            prefs.edit().putString(KEY, kept.toString()).apply()
            notifyChanged()
        } catch (_: Exception) {}
    }

    @Synchronized fun discardOldest(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = readArray(prefs.getString(KEY, null))
            if (arr.length() == 0) return false
            arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
            notifyChanged()
            true
        } catch (_: Exception) { false }
    }

    @Synchronized fun clearIfExpired(ctx: Context?): Int {
        if (ctx == null) return 0
        return try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val n = readArray(prefs.getString(KEY, null)).length()
            if (n == 0) return 0
            val lastAdd = prefs.getLong(KEY_LAST_ADD, 0L)
            if (lastAdd > 0 && System.currentTimeMillis() - lastAdd > EXPIRY_MS) {
                prefs.edit().remove(KEY).remove(KEY_LAST_ADD).apply()
                AppLog.i("Outstanding", "cleared $n expired pending transcript(s) (>2min unclaimed)")
                notifyChanged()
                n
            } else 0
        } catch (_: Exception) { 0 }
    }

    @Synchronized fun clearAll(ctx: Context?) {
        if (ctx == null) return
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).remove(KEY_LAST_ADD).apply()
            notifyChanged()
        } catch (_: Exception) {}
    }
}
