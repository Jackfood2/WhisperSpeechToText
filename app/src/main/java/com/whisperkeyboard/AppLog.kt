package com.whisperkeyboard

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Thread-safe in-memory diagnostic log (last ~300 lines). Shown in Privacy Dashboard, copyable. */
object AppLog {

    private const val MAX = 300
    private val buf = ArrayDeque<String>(MAX)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile var lastError: String = ""
        private set

    fun i(tag: String, msg: String) { add("I", tag, msg) }
    fun w(tag: String, msg: String) { add("W", tag, msg) }
    fun e(tag: String, msg: String) { lastError = "$tag: $msg"; add("E", tag, msg) }

    private fun add(level: String, tag: String, msg: String) {
        val line = "${fmt.format(Date())} $level/$tag: $msg"
        synchronized(buf) {
            buf.addLast(line)
            while (buf.size > MAX) buf.removeFirst()
        }
        when (level) {
            "E" -> android.util.Log.e(tag, msg)
            "W" -> android.util.Log.w(tag, msg)
            else -> android.util.Log.i(tag, msg)
        }
    }

    fun dump(): String = synchronized(buf) { buf.joinToString("\n") }
    fun clear() = synchronized(buf) { buf.clear(); lastError = "" }
}
