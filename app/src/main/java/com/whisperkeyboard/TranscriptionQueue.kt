package com.whisperkeyboard

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp

object TranscriptionQueue {

    private const val TAG = "TranscriptionQueue"

    data class Job(
        val context: Context,
        val wavFile: File,
        val model: String,
        val lang: String,
        val onResult: (String) -> Unit,
        val onError: (String) -> Unit
    )

    interface ProgressListener { fun onProgress(pct: Int) }

    private val queue = LinkedBlockingQueue<Job>()
    private val pendingCount = AtomicInteger(0)
    private val failedJobs = mutableListOf<Job>()
    private val paused = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper-worker").apply { isDaemon = true } }

    @Volatile private var isProcessing = false
    @Volatile private var currentModel = ""
    @Volatile private var currentPct = 0
    @Volatile private var currentFileSec = 0.0
    private val listeners = mutableSetOf<ProgressListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressTimer: java.util.Timer? = null

    // ---- adaptive stats ----

    private fun defaultRatio(model: String): Double = when (model) {
        "tiny" -> 0.06; "base" -> 0.10; "small" -> 0.28; "medium" -> 0.65; else -> 0.30
    }

    private fun getAvgRatio(ctx: Context, model: String): Double {
        return try {
            val prefs = ctx.getSharedPreferences("whisper_stats", Context.MODE_PRIVATE)
            val perCount = prefs.getInt("count_$model", 0)
            if (perCount >= 2) {
                val r = prefs.getFloat("ratio_$model", defaultRatio(model).toFloat()).toDouble()
                Log.i(TAG, "avgRatio $model = $r from $perCount samples (per-model)")
                return r
            }
            val gCount = prefs.getInt("count_global", 0)
            if (gCount >= 2) {
                val gr = prefs.getFloat("ratio_global", 0.28f).toDouble()
                Log.i(TAG, "avgRatio $model fallback global=$gr from $gCount samples")
                return gr
            }
            defaultRatio(model)
        } catch (_: Exception) { defaultRatio(model) }
    }

    private fun recordStats(ctx: Context, model: String, audioSec: Double, transcribeSec: Double) {
        try {
            if (audioSec < 0.5 || transcribeSec < 0.3) return
            val ratio = (transcribeSec / audioSec).coerceIn(0.02, 5.0)
            val prefs = ctx.getSharedPreferences("whisper_stats", Context.MODE_PRIVATE)
            val ed = prefs.edit()
            // per-model exponential moving average via count-weighted
            val cnt = prefs.getInt("count_$model", 0)
            val old = prefs.getFloat("ratio_$model", ratio.toFloat()).toDouble()
            val newAvg = if (cnt == 0) ratio else (old * cnt + ratio) / (cnt + 1)
            ed.putFloat("ratio_$model", newAvg.toFloat())
            ed.putInt("count_$model", cnt + 1)
            // global
            val gCnt = prefs.getInt("count_global", 0)
            val gOld = prefs.getFloat("ratio_global", ratio.toFloat()).toDouble()
            val gNew = if (gCnt == 0) ratio else (gOld * gCnt + ratio) / (gCnt + 1)
            ed.putFloat("ratio_global", gNew.toFloat())
            ed.putInt("count_global", gCnt + 1)
            // also store last audio/time for debug
            ed.putFloat("last_audio_${model}", audioSec.toFloat())
            ed.putFloat("last_time_${model}", transcribeSec.toFloat())
            ed.apply()
            Log.i(TAG, "recordStats model=$model audio=${String.format("%.1f", audioSec)}s time=${String.format("%.1f", transcribeSec)}s ratio=${String.format("%.3f", ratio)} -> avg $model=${String.format("%.3f", newAvg)} global=${String.format("%.3f", gNew)} cnt $cnt/$gCnt")
        } catch (e: Exception) { Log.w(TAG, "recordStats failed: ${e.message}") }
    }

    fun addListener(l: ProgressListener) { synchronized(listeners) { listeners.add(l) } }
    fun removeListener(l: ProgressListener) { synchronized(listeners) { listeners.remove(l) } }

    private fun notifyProgress(pct: Int) {
        currentPct = pct
        val copy = synchronized(listeners) { listeners.toList() }
        mainHandler.post { copy.forEach { try { it.onProgress(pct) } catch (_: Exception) {} } }
    }

    private fun estimateSeconds(wav: File): Double {
        return try {
            val dataBytes = wav.length() - 44
            if (dataBytes <= 0) 1.0 else dataBytes / 32000.0
        } catch (_: Exception) { 5.0 }
    }

    private fun expectedTranscribeSec(ctx: Context, model: String, audioSec: Double): Double {
        val ratio = getAvgRatio(ctx, model)
        // overhead 0.6s for init + commit
        return maxOf(1.2, audioSec * ratio + 0.6)
    }

    private fun startSimulatedProgress(ctx: Context, model: String, wav: File) {
        stopSimulatedProgress()
        val audioSec = estimateSeconds(wav)
        currentFileSec = audioSec
        val expected = expectedTranscribeSec(ctx, model, audioSec)
        Log.i(TAG, "startProgress audio=${String.format("%.1f", audioSec)}s model=$model expected=${String.format("%.1f", expected)}s ratio=${String.format("%.3f", getAvgRatio(ctx, model))}")
        notifyProgress(3)
        var elapsed = 0.0
        progressTimer = java.util.Timer(true)
        progressTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                elapsed += 0.2
                val pct = when {
                    elapsed < expected -> ((elapsed / expected) * 88).toInt().coerceIn(3, 88)
                    else -> {
                        // creep 88 -> 98 asymptotically after expected exceeded
                        val extra = elapsed - expected
                        val creep = (9 * (1 - exp(-extra / (expected * 0.6 + 2.0)))).toInt()
                        (88 + creep).coerceIn(88, 98)
                    }
                }
                notifyProgress(pct)
            }
        }, 200, 200)
    }

    private fun stopSimulatedProgress() {
        try { progressTimer?.cancel() } catch (_: Exception) {}
        progressTimer = null
    }

    fun status(): String {
        val q = pendingCount.get()
        val f = synchronized(failedJobs) { failedJobs.size }
        val state = when {
            paused.get() -> "PAUSED"
            isProcessing -> "Processing $currentModel ${currentPct}% (${String.format("%.0f", currentFileSec)}s)"
            else -> "Idle"
        }
        val retry = if (f > 0) " | $f failed" else ""
        return "Queue: $q pending | $state$retry"
    }

    fun progress(): Int = currentPct
    fun isPaused(): Boolean = paused.get()
    /** True when nothing is being transcribed and nothing is pending. */
    fun isActive(): Boolean = isProcessing || pendingCount.get() > 0

    fun enqueue(job: Job) {
        pendingCount.incrementAndGet()
        queue.put(job)
        Log.i(TAG, "Enqueued job, pending=${pendingCount.get()} paused=${paused.get()}")
        ensureWorker()
    }

    fun pause() { paused.set(true); Log.i(TAG, "Queue paused") }
    fun resume() { if (paused.compareAndSet(true, false)) { Log.i(TAG, "Queue resumed"); ensureWorker() } }
    fun togglePause(): Boolean = if (paused.get()) { resume(); false } else { pause(); true }

    fun clearQueue(): Int {
        val drained = mutableListOf<Job>()
        queue.drainTo(drained)
        var deleted = 0
        for (j in drained) { try { if (j.wavFile.exists()) { j.wavFile.delete(); deleted++ } } catch (_: Exception) {} ; pendingCount.decrementAndGet() }
        Log.i(TAG, "Cleared $deleted queued files")
        return deleted
    }

    fun forceStop() { paused.set(false); clearQueue(); Log.i(TAG, "Force stop - queue cleared") }

    fun retryFailed() {
        val toRetry: List<Job>
        synchronized(failedJobs) { toRetry = failedJobs.toList(); failedJobs.clear() }
        if (toRetry.isEmpty()) return
        Log.i(TAG, "Retrying ${toRetry.size} failed jobs")
        for (j in toRetry) { if (j.wavFile.exists()) enqueue(j) else Log.w(TAG, "Failed file missing: ${j.wavFile}") }
    }

    fun failedCount(): Int = synchronized(failedJobs) { failedJobs.size }

    private fun ensureWorker() {
        if (isProcessing) return
        if (paused.get()) { Log.i(TAG, "Paused - worker not started"); return }
        isProcessing = true
        executor.submit {
            Log.i(TAG, "Worker started")
            while (true) {
                try {
                    if (paused.get()) { Log.i(TAG, "Worker pausing"); isProcessing = false; stopSimulatedProgress(); notifyProgress(0); break }
                    val job = try { queue.poll(500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
                    if (job == null) { isProcessing = false; stopSimulatedProgress(); notifyProgress(0); Log.i(TAG, "Worker idle"); break }
                    currentModel = job.model
                    AppLog.i(TAG, "process ${job.model}/${job.lang} ${job.wavFile.name} (${job.wavFile.length() / 1024} KB)")
                    val audioSec = estimateSeconds(job.wavFile)
                    val startMs = System.currentTimeMillis()
                    startSimulatedProgress(job.context, job.model, job.wavFile)
                    var success = false
                    var resultText = ""
                    var errorMsg = ""
                    var attempt = 0
                    while (attempt < 2 && !success) {
                        attempt++
                        try {
                            val modelFile = ModelManager.modelFile(job.context, job.model)
                            if (!modelFile.exists() || modelFile.length() < 1_000_000) throw IllegalStateException("Model ggml-${job.model}.bin not found. Download it in app first.")
                            resultText = WhisperEngine.transcribe(modelFile.absolutePath, job.wavFile.absolutePath, job.lang).trim()
                            if (resultText.startsWith("ERROR:")) {
                                // native-level failure: retry once (self-heal reloads model), then fail
                            if (attempt < 2) { Log.w(TAG, "Attempt $attempt returned ${resultText.take(60)} - retrying"); AppLog.w(TAG, "attempt $attempt ERROR result - retrying"); continue }
                                throw IllegalStateException(resultText)
                            }
                            Log.i(TAG, "Result: ${resultText.take(120)}")
                            AppLog.i(TAG, "done ${job.model} in ${(System.currentTimeMillis() - startMs) / 1000.0}s: ${resultText.take(50)}")
                            success = true
                            val elapsedSec = (System.currentTimeMillis() - startMs) / 1000.0
                            recordStats(job.context, job.model, audioSec, elapsedSec)
                            stopSimulatedProgress(); notifyProgress(100)
                            Thread.sleep(500)
                        } catch (e: Exception) {
                            if (attempt < 2) { Log.w(TAG, "Attempt $attempt failed: ${e.message} - retrying"); Thread.sleep(300); continue }
                            Log.e(TAG, "Transcribe failed after $attempt attempts: ${e.message}", e)
                            AppLog.e(TAG, "transcribe failed x$attempt: ${e.message}")
                            errorMsg = e.message ?: "Unknown error"
                            stopSimulatedProgress(); notifyProgress(0)
                            break
                        } catch (e: OutOfMemoryError) {
                            errorMsg = "Out of memory - try a smaller model"
                            Log.e(TAG, "OOM: ${e.message}")
                            stopSimulatedProgress(); notifyProgress(0)
                            break
                        }
                    }
                    if (!success && errorMsg.isEmpty()) errorMsg = "Unknown error"
                    if (!success) {
                        try {
                            val failDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "WhisperNotes/failed")
                            failDir.mkdirs()
                            val saved = File(failDir, "failed_${System.currentTimeMillis()}.wav")
                            job.wavFile.copyTo(saved, overwrite = true)
                            Log.i(TAG, "Saved failed to ${saved.absolutePath}")
                            val retryJob = job.copy(wavFile = saved)
                            synchronized(failedJobs) { failedJobs.add(retryJob) }
                        } catch (ex: Exception) {
                            Log.w(TAG, "Failed to save failed WAV: ${ex.message}")
                            synchronized(failedJobs) { failedJobs.add(job) }
                        }
                    }
                    try {
                        pendingCount.decrementAndGet()
                        if (success || job.wavFile.absolutePath.contains("cache")) {
                            try { if (job.wavFile.exists()) job.wavFile.delete() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                    if (success) job.onResult(resultText) else job.onError(errorMsg)
                    notifyProgress(0)
                } catch (t: Throwable) {
                    // never let the worker thread die
                    Log.e(TAG, "Worker loop error: ${t.message}", t)
                    stopSimulatedProgress(); notifyProgress(0)
                }
            }
        }
    }
}

