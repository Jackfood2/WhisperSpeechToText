package com.whisperkeyboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Transcripts that could not be typed yet (e.g. recorded on lock screen or
 * while another keyboard was active).
 * Persisted so nothing is lost; the keyboard auto-types them on return
 * (see WhisperKeyboardService.onStartInputView) or via the one-tap button.
 *
 * EXPIRY: entries older than [EXPIRY_MS] are dropped without typing - if the
 * user did not return to the Whisper keyboard within 2 minutes of processing,
 * the cached text is cleared instead of being inserted stale.
 */
object OutstandingStore {

    private const val PREF = "whisper_outstanding"
    private const val KEY = "items"
    private const val KEY_LAST_ADD = "last_add_ts"
    private const val MAX = 20
    /** 2 minutes: pending text not claimed by then is cleared, never typed stale. */
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
            // cap: drop oldest beyond MAX
            while (arr.length() >= MAX) { arr.remove(0) }
            arr.put(JSONObject().put("t", text.trim()).put("ts", System.currentTimeMillis()))
            prefs.edit().putString(KEY, arr.toString()).putLong(KEY_LAST_ADD, System.currentTimeMillis()).apply()
            // optional convenience copy (Settings toggle) - off = retrieve only via keyboard button
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

    /** Read stored array, tolerating the legacy plain-string format. */
    private fun readArray(raw: String?): JSONArray {
        if (raw.isNullOrEmpty()) return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private fun entryText(o: Any?): String = when (o) {
        is JSONObject -> o.optString("t", "")
        is String -> o
        else -> o?.toString() ?: ""
    }

    /** Oldest first - returns and removes one entry. */
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

    /** Remove the oldest entry without returning it (discard). */
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

    /**
     * Drop everything if the last transcript completed more than [EXPIRY_MS]
     * ago. Returns the number of entries cleared (0 = nothing expired).
     * Called when the Whisper keyboard is shown - lazy expiry, no background work.
     */
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
