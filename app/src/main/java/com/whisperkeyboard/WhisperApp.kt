package com.whisperkeyboard

import android.app.Application
import android.content.Context

class WhisperApp : Application() {
    companion object {
        @Volatile var holder: Context? = null
    }
    override fun onCreate() {
        super.onCreate()
        holder = this
        installGlobalCrashCapture()
    }

    /**
     * Capture ANY crash in ANY process (app launch, IME, services) to
     * dashboard_crash.txt, which Privacy Dashboard already displays.
     * Previously only crashes inside Privacy Dashboard itself were saved,
     * so a launch crash left no trace.
     */
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
    /**
     * OS memory pressure: drop idle voice models (each unload is busy-guarded,
     * so a mid-transcription engine is never yanked). The models reload on
     * next use; transcripts/queue are unaffected.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_MODERATE) {
            Thread {
                try { SttEngines.unloadIdle() } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "trim-unload"; start() }
        }
    }
}
