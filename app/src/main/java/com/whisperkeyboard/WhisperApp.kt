package com.whisperkeyboard

import android.app.Application
import android.content.Context

class WhisperApp : Application() {
    companion object {
        @Volatile var holder: Context? = null

        fun applyTheme(ctx: Context) {
            val mode = ctx.getSharedPreferences("whisper", Context.MODE_PRIVATE)
                .getString("theme_mode", "system") ?: "system"
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                when (mode) {
                    "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    "dark" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                    else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            )
        }
    }

    private fun installGlobalCrashCapture() {
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = java.io.StringWriter()
                e.printStackTrace(java.io.PrintWriter(sw))
                val trace = sw.toString().take(6000)
                try { AppLog.e("Crash", "${e.message}\n${trace.take(500)}") } catch (_: Exception) {}
                try {
                    java.io.File(getExternalFilesDir(null), "dashboard_crash.txt").writeText(trace)
                } catch (_: Exception) {}
            }
            prevHandler?.uncaughtException(t, e)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            Thread {
                try { SttEngines.unloadIdle() } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "trim-unload"; start() }
        }
    }
}
