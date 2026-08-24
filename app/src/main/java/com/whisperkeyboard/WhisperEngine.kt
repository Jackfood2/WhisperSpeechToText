package com.whisperkeyboard

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object WhisperEngine {

    init {
        try {
            System.loadLibrary("whisper_jni")
            android.util.Log.i("WhisperEngine", "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("WhisperEngine", "Failed to load native library: ${e.message}")
        }
    }

    private val lock = ReentrantLock()
    private val busyCount = AtomicInteger(0)

    @Volatile private var loadedPath: String? = null
    @Volatile var lastError: String = ""
        private set

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree()
    private external fun nativeTranscribe(modelPath: String, wavPath: String, lang: String): String

    /** Preload/cache the model. Safe to call repeatedly; no-op if already loaded for this path.
     *  If a different model is cached, it is unloaded and the new one loaded (when idle). */
    fun ensureModel(modelPath: String): Boolean {
        if (modelPath.isBlank()) return false
        lock.withLock {
            try {
                val handle = nativeInit(modelPath)
                if (handle > 0) {
                    loadedPath = modelPath
                    lastError = ""
                    android.util.Log.i("WhisperEngine", "Model ready: $modelPath")
                    return true
                }
                lastError = "Model load failed"
                return false
            } catch (e: Exception) {
                lastError = e.message ?: "load error"
                android.util.Log.e("WhisperEngine", "ensureModel failed: ${e.message}")
                return false
            }
        }
    }

    fun isLoaded(modelPath: String): Boolean = loadedPath == modelPath
    fun loadedModel(): String? = loadedPath
    fun isBusy(): Boolean = busyCount.get() > 0

    /** Unload cached model only when no transcription is in flight. Returns false if busy. */
    fun unloadIfIdle(): Boolean {
        lock.withLock {
            if (busyCount.get() > 0) {
                android.util.Log.i("WhisperEngine", "Skip unload - transcription busy")
                return false
            }
            return try {
                nativeFree()
                loadedPath = null
                android.util.Log.i("WhisperEngine", "Model unloaded (idle)")
                true
            } catch (e: Exception) {
                android.util.Log.w("WhisperEngine", "unload failed: ${e.message}")
                false
            }
        }
    }

    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        busyCount.incrementAndGet()
        try {
            lock.withLock {
                try {
                    val effectiveLang = if (lang == "auto" || lang.isEmpty()) "auto" else lang
                    // self-heal: reload if something freed the cache behind our back
                    if (loadedPath != modelPath) {
                        val handle = nativeInit(modelPath)
                        if (handle <= 0) {
                            lastError = "Model load failed"
                            return "ERROR: Failed to load whisper model"
                        }
                        loadedPath = modelPath
                    }
                    val out = nativeTranscribe(modelPath, wavPath, effectiveLang)
                    lastError = if (out.startsWith("ERROR:")) out else ""
                    return out
                } catch (e: OutOfMemoryError) {
                    lastError = "Out of memory"
                    android.util.Log.e("WhisperEngine", "OOM during transcribe - freeing model", e)
                    try { nativeFree(); loadedPath = null } catch (_: Throwable) {}
                    return "ERROR: Out of memory - try a smaller model"
                } catch (e: Exception) {
                    lastError = e.message ?: "transcribe error"
                    android.util.Log.e("WhisperEngine", "Transcribe error: ${e.message}")
                    return "ERROR: ${e.message}"
                } finally {
                    // nothing extra
                }
            }
        } finally {
            busyCount.decrementAndGet()
        }
    }
}
