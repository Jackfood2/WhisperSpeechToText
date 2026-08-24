package com.whisperkeyboard

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object WhisperEngine {

    init {
        try {
            System.loadLibrary("whisper_jni")
            AppLog.i("WhisperEngine", "Native library loaded")
        } catch (e: Throwable) {
            AppLog.e("WhisperEngine", "Failed to load native library: ${e.message}")
        }
    }

    private const val TAG = "WhisperEngine"
    private val lock = ReentrantLock()
    private val busyCount = AtomicInteger(0)

    @Volatile private var loadedPath: String? = null
    @Volatile var lastError: String = ""
        private set

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeFree()
    private external fun nativeTranscribe(modelPath: String, wavPath: String, lang: String): String

    /** Preload/cache the model with retries. No-op if already loaded for this path. */
    fun ensureModel(modelPath: String): Boolean {
        if (modelPath.isBlank()) return false
        lock.withLock {
            try {
                val f = java.io.File(modelPath)
                if (!f.exists() || f.length() < 1_000_000) {
                    lastError = "Model file missing"
                    AppLog.e(TAG, "file missing/incomplete: $modelPath (${if (f.exists()) f.length() else 0} bytes)")
                    return false
                }
                if (loadedPath == modelPath) return true
                var lastMsg = ""
                for (attempt in 1..3) {
                    val t0 = System.currentTimeMillis()
                    val handle = try { nativeInit(modelPath) } catch (e: Throwable) { AppLog.e(TAG, "nativeInit threw: ${e.message}"); -1L }
                    val ms = System.currentTimeMillis() - t0
                    if (handle > 0) {
                        loadedPath = modelPath
                        lastError = ""
                        AppLog.i(TAG, "load OK ($ms ms, attempt $attempt): ${f.name}")
                        return true
                    }
                    lastMsg = "init returned $handle after $ms ms"
                    AppLog.w(TAG, "load attempt $attempt failed: $lastMsg")
                    Thread.sleep(400)
                }
                lastError = "Model load failed"
                AppLog.e(TAG, "load FAILED after 3 attempts: $lastMsg (${f.name}, ${f.length() / 1024 / 1024} MB)")
                return false
            } catch (e: Throwable) {
                lastError = e.message ?: "load error"
                AppLog.e(TAG, "ensureModel error: ${e.message}")
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
                AppLog.i(TAG, "skip unload - transcription busy")
                return false
            }
            return try {
                val was = loadedPath
                nativeFree()
                loadedPath = null
                AppLog.i(TAG, "unloaded ${was?.substringAfterLast('/') ?: "-"}")
                true
            } catch (e: Throwable) {
                AppLog.w(TAG, "unload failed: ${e.message}")
                false
            }
        }
    }

    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        busyCount.incrementAndGet()
        val t0 = System.currentTimeMillis()
        try {
            lock.withLock {
                try {
                    val effectiveLang = if (lang == "auto" || lang.isEmpty()) "auto" else lang
                    // self-heal: reload if cache is empty or different (with one retry)
                    if (loadedPath != modelPath) {
                        var ok = false
                        for (attempt in 1..2) {
                            val handle = try { nativeInit(modelPath) } catch (e: Throwable) { AppLog.e(TAG, "reload threw: ${e.message}"); -1L }
                            if (handle > 0) { ok = true; break }
                            AppLog.w(TAG, "reload attempt $attempt failed"); Thread.sleep(300)
                        }
                        if (!ok) {
                            lastError = "Model load failed"
                            AppLog.e(TAG, "transcribe aborted - model reload failed: $modelPath")
                            return "ERROR: Failed to load whisper model"
                        }
                        loadedPath = modelPath
                        AppLog.i(TAG, "reloaded model in transcribe: ${modelPath.substringAfterLast('/')}")
                    }
                    val out = nativeTranscribe(modelPath, wavPath, effectiveLang)
                    val ms = System.currentTimeMillis() - t0
                    AppLog.i(TAG, "transcribed in $ms ms -> ${out.take(60)}")
                    lastError = if (out.startsWith("ERROR:")) out else ""
                    return out
                } catch (e: OutOfMemoryError) {
                    lastError = "Out of memory"
                    AppLog.e(TAG, "OOM during transcribe - freeing model")
                    try { nativeFree(); loadedPath = null } catch (_: Throwable) {}
                    return "ERROR: Out of memory - try a smaller model"
                } catch (e: Throwable) {
                    lastError = e.message ?: "transcribe error"
                    AppLog.e(TAG, "Transcribe error: ${e.message}")
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
