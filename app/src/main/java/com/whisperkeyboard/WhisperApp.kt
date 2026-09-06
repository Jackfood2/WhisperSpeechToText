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
