package com.whisperkeyboard

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

object AppLog {

    private const val MAX = 300
    private val lock = Any()
    private val buf = ArrayDeque<String>(MAX)

    private val fmt =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    var lastError: String = ""
        private set

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String) = add("E", tag, msg)

    private fun add(level: String, tag: String, msg: String) {
        val safeTag = tag.take(100)
        val safeMessage = msg.take(10_000)

        synchronized(lock) {
            val timestamp = fmt.format(Date())

            if (level == "E") {
                lastError = "$safeTag: $safeMessage"
            }

            buf.addLast("$timestamp $level/$safeTag: $safeMessage")

            while (buf.size > MAX) {
                buf.removeFirst()
            }
        }

        runCatching {
            when (level) {
                "E" -> android.util.Log.e(safeTag, safeMessage)
                "W" -> android.util.Log.w(safeTag, safeMessage)
                else -> android.util.Log.i(safeTag, safeMessage)
            }
        }
    }

    fun dump(): String =
        synchronized(lock) {
            buf.joinToString("\n")
        }

    fun clear() {
        synchronized(lock) {
            buf.clear()
            lastError = ""
        }
    }
}
