package com.whisperkeyboard

import android.content.Context
import org.json.JSONArray

/**
 * Transcripts that could not be typed yet (e.g. recorded on lock screen).
 * Persisted so nothing is lost; the keyboard shows a one-tap-per-entry button
 * once the user focuses a field again.
 */
object OutstandingStore {

    private const val PREF = "whisper_outstanding"
    private const val KEY = "items"
    private const val MAX = 20

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
            val arr = prefs.getString(KEY, null)?.let { JSONArray(it) } ?: JSONArray()
            // cap: drop oldest beyond MAX
            while (arr.length() >= MAX) { arr.remove(0) }
            arr.put(text.trim())
            prefs.edit().putString(KEY, arr.toString()).apply()
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

    /** Oldest first - returns and removes one entry. */
    @Synchronized fun popOldest(ctx: Context?): String? {
        if (ctx == null) return null
        return try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = prefs.getString(KEY, null)?.let { JSONArray(it) } ?: return null
            if (arr.length() == 0) return null
            val text = arr.getString(0)
            arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
            notifyChanged()
            text
        } catch (_: Exception) { null }
    }

    /** Remove the oldest entry without returning it (discard). */
    @Synchronized fun discardOldest(ctx: Context?): Boolean {
        if (ctx == null) return false
        return try {
            val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            val arr = prefs.getString(KEY, null)?.let { JSONArray(it) } ?: return false
            if (arr.length() == 0) return false
            arr.remove(0)
            prefs.edit().putString(KEY, arr.toString()).apply()
            notifyChanged()
            true
        } catch (_: Exception) { false }
    }

    @Synchronized fun clearAll(ctx: Context?) {
        if (ctx == null) return
        try {
            ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
            notifyChanged()
        } catch (_: Exception) {}
    }
}
