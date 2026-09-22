package com.whisperkeyboard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.exp

object TranscriptionQueue {

    private const val TAG = "TranscriptionQueue"

    private val nextJobId = AtomicLong(0L)
    private val currentJobId = AtomicLong(0L)
    private val skipJobId = AtomicLong(-1L)

    data class Job(
        val id: Long =
            nextJobId.incrementAndGet(),
        val context: Context,
        val wavFile: File,
        val model: String,
        val lang: String,
        val onResult: (String) -> Unit,
        val onError: (String) -> Unit,
        val engine: String = SttEngines.WHISPER
    )

    interface ProgressListener { fun onProgress(pct: Int) }

    private val queue = LinkedBlockingQueue<Job>()
    private val pendingCount = AtomicInteger(0)
    private val submittedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)
    private val stopAllFlag = AtomicBoolean(false)
    private val failedJobs = mutableListOf<Job>()
    private val paused = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "whisper-worker").apply { isDaemon = true } }

    @Volatile private var currentModel = ""
    @Volatile private var currentPct = 0
    @Volatile private var currentFileSec = 0.0
    private val workerRunning = AtomicBoolean(false)
    private val listeners = mutableSetOf<ProgressListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var progressTimer: java.util.Timer? = null
    private val progressGeneration = AtomicInteger(0)

    private fun defaultRatio(engine: String, model: String): Double =
        SttEngines.defaultRatio(engine, model)

    private fun getAvgRatio(ctx: Context, engine: String, model: String): Double {
        return try {
            val prefs = ctx.getSharedPreferences("whisper_stats", Context.MODE_PRIVATE)
            val key = SttEngines.statsKey(engine, model)
            val perCount = prefs.getInt("count_$key", 0)
            if (perCount >= 1) {
                val r = prefs.getFloat("ratio_$key", defaultRatio(engine, model).toFloat()).toDouble()
                Log.i(TAG, "avgRatio $key = $r from $perCount samples (per-engine)")
                return r
            }
            defaultRatio(engine, model)
        } catch (_: Exception) { defaultRatio(engine, model) }
    }

    private fun recordStats(ctx: Context, engine: String, model: String, audioSec: Double, transcribeSec: Double) {
        try {
            if (audioSec < 0.5 || transcribeSec < 0.3) return
            val ratio = (transcribeSec / audioSec).coerceIn(0.02, 5.0)
            val prefs = ctx.getSharedPreferences("whisper_stats", Context.MODE_PRIVATE)
            val ed = prefs.edit()
            val key = SttEngines.statsKey(engine, model)
            val cnt = prefs.getInt("count_$key", 0)
            val old = prefs.getFloat("ratio_$key", ratio.toFloat()).toDouble()
            val newAvg = if (cnt == 0) ratio else old * 0.8 + ratio * 0.2
            ed.putFloat("ratio_$key", newAvg.toFloat())
            ed.putInt("count_$key", cnt + 1)
            val gCnt = prefs.getInt("count_global", 0)
            val gOld = prefs.getFloat("ratio_global", ratio.toFloat()).toDouble()
            val gNew = if (gCnt == 0) ratio else gOld * 0.9 + ratio * 0.1
            ed.putFloat("ratio_global", gNew.toFloat())
            ed.putInt("count_global", gCnt + 1)
            ed.putFloat("last_audio_${key}", audioSec.toFloat())
            ed.putFloat("last_time_${key}", transcribeSec.toFloat())
            ed.apply()
            Log.i(TAG, "recordStats model=$model audio=${String.format("%.1f", audioSec)}s time=${String.format("%.1f", transcribeSec)}s ratio=${String.format("%.3f", ratio)} -> ema $model=${String.format("%.3f", newAvg)} global=${String.format("%.3f", gNew)} cnt $cnt/$gCnt")
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

    private fun expectedTranscribeSec(ctx: Context, engine: String, model: String, audioSec: Double): Double {
        val ratio = getAvgRatio(ctx, engine, model)
        return maxOf(1.2, audioSec * ratio + 0.6)
    }

    private fun startSimulatedProgress(ctx: Context, engine: String, model: String, wav: File) {
        stopSimulatedProgress()

        val generation =
            progressGeneration.incrementAndGet()

        val audioSec = estimateSeconds(wav)
        currentFileSec = audioSec
        val expected = expectedTranscribeSec(ctx, engine, model, audioSec)
        Log.i(TAG, "startProgress audio=${String.format("%.1f", audioSec)}s $engine/$model expected=${String.format("%.1f", expected)}s ratio=${String.format("%.3f", getAvgRatio(ctx, engine, model))}")
        notifyProgress(3)
        var elapsed = 0.0
        progressTimer = java.util.Timer("transcription-progress", true)
        progressTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (
                    progressGeneration.get() != generation
                ) {
                    cancel()
                    return
                }

                elapsed += 0.2
                val pct = when {
                    elapsed < expected -> ((elapsed / expected) * 88).toInt().coerceIn(3, 88)
                    else -> {
                        val extra = elapsed - expected
                        val creep = (9 * (1 - exp(-extra / (expected * 0.6 + 2.0)))).toInt()
                        (88 + creep).coerceIn(88, 98)
                    }
                }
                notifyProgress(pct)
            }
        }, 200L, 200L)
    }

    private fun stopSimulatedProgress() {
        progressGeneration.incrementAndGet()

        runCatching {
            progressTimer?.cancel()
            progressTimer?.purge()
        }

        progressTimer = null
    }

    fun status(): String {
        val q = pendingCount.get()
        val f = synchronized(failedJobs) { failedJobs.size }
        val state = when {
            paused.get() -> "PAUSED"
            workerRunning.get() -> {
                val (cur, total) = batchPosition()
                "Processing $cur/$total $currentModel ${currentPct}%"
            }
            else -> "Idle"
        }
        val retry = if (f > 0) " | $f failed" else ""
        return "Queue: $q pending | $state$retry"
    }

    fun progress(): Int = currentPct
    fun isPaused(): Boolean = paused.get()
    fun isActive(): Boolean = workerRunning.get() || pendingCount.get() > 0
    fun pendingCount(): Int = pendingCount.get()

    fun isCompletelyIdle(): Boolean {
        return pendingCount.get() == 0 &&
            queue.isEmpty() &&
            !workerRunning.get()
    }

    fun hasRunningJob(): Boolean =
        workerRunning.get() &&
            currentJobId.get() > 0L

    fun completedTotal(): Int =
        completedCount.get()

    fun queueDepth(): Int =
        queue.size

    fun currentAudioSeconds(): Double =
        currentFileSec

    fun pendingAudioBytes(): Long {
        return try {
            queue.sumOf {
                if (it.wavFile.exists()) {
                    it.wavFile.length()
                } else {
                    0L
                }
            }
        } catch (_: Exception) {
            0L
        }
    }

    fun skipCurrentJob() {
        val id = currentJobId.get()

        if (id > 0L) {
            skipJobId.set(id)
            SttEngines.cancelAll()
            AppLog.i(TAG, "Skip requested for job $id")
        }
    }

    fun stopEverything(): Int {
        AppLog.i(TAG, "STOP ALL requested")
        skipCurrentJob()
        stopAllFlag.set(true)
        SttEngines.cancelAll()
        paused.set(false)
        return clearQueue()
    }

    fun batchPosition(): Pair<Int, Int> {
        val total = maxOf(submittedCount.get(), completedCount.get())
        if (total <= 0) return Pair(0, 0)
        val cur = (completedCount.get() + if (workerRunning.get() && pendingCount.get() > 0) 1 else 0).coerceIn(1, total)
        return Pair(cur, total)
    }

    private const val MAX_PENDING_JOBS = 120

    private fun reservePendingSlot(): Boolean {
        while (true) {
            val current = pendingCount.get()

            if (current >= MAX_PENDING_JOBS) {
                return false
            }

            if (
                pendingCount.compareAndSet(
                    current,
                    current + 1
                )
            ) {
                return true
            }
        }
    }

    fun enqueue(job: Job): Boolean {
        val safeJob = job.copy(
            context = job.context.applicationContext
        )

        if (!reservePendingSlot()) {
            AppLog.w(
                TAG,
                "Queue full - rejecting ${safeJob.wavFile.name}"
            )

            runCatching { safeJob.wavFile.delete() }

            runCatching {
                safeJob.onError("Transcription queue is full")
            }

            return false
        }

        return try {
            stopAllFlag.set(false)
            submittedCount.incrementAndGet()
            queue.put(safeJob)

            AppLog.i(
                TAG,
                "Enqueued job, pending=${pendingCount.get()}"
            )

            ProcessingService.notifyActivity()
            ensureWorker()
            true
        } catch (e: Throwable) {
            pendingCount.updateAndGet {
                (it - 1).coerceAtLeast(0)
            }

            submittedCount.updateAndGet {
                (it - 1).coerceAtLeast(0)
            }

            runCatching { safeJob.wavFile.delete() }

            AppLog.e(
                TAG,
                "Unable to enqueue job: ${e.message}"
            )

            false
        }
    }

    fun pause() {
        paused.set(true)
        AppLog.i(TAG, "Queue will pause after current job")
    }
    fun resume() { if (paused.compareAndSet(true, false)) { Log.i(TAG, "Queue resumed"); ensureWorker() } }
    fun togglePause(): Boolean = if (paused.get()) { resume(); false } else { pause(); true }

    fun clearQueue(): Int {
        val drained = mutableListOf<Job>()
        queue.drainTo(drained)
        var deleted = 0
        for (j in drained) { try { if (j.wavFile.exists()) { j.wavFile.delete(); deleted++ } } catch (_: Exception) {} ; pendingCount.updateAndGet { current -> (current - 1).coerceAtLeast(0) } }
        skipCurrentJob()
        SttEngines.cancelAll()
        Log.i(TAG, "Cleared $deleted queued files (+cancelled current)")
        AppLog.i(TAG, "clearQueue: $deleted dropped, current cancelled")
        return deleted
    }

    fun forceStop() { paused.set(false); clearQueue(); Log.i(TAG, "Force stop - queue cleared") }

    fun retryFailed() {
        val snapshot =
            synchronized(failedJobs) {
                failedJobs.toList()
            }

        for (job in snapshot) {
            if (!job.wavFile.isFile) {
                AppLog.w(
                    TAG,
                    "Retry file missing: ${job.wavFile}"
                )

                synchronized(failedJobs) {
                    failedJobs.remove(job)
                }

                continue
            }

            val accepted = enqueue(
                job.copy(
                    context = job.context.applicationContext
                )
            )

            if (accepted) {
                synchronized(failedJobs) {
                    failedJobs.remove(job)
                }
            } else {
                AppLog.w(
                    TAG,
                    "Retry could not be queued: ${job.wavFile.name}"
                )
            }
        }
    }

    fun failedCount(): Int = synchronized(failedJobs) { failedJobs.size }

    private fun failedDir(context: Context): File {
        val dir = File(
            FullAudioSaver.privateNotesDir(
                context.applicationContext
            ),
            "failed"
        )

        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException(
                "Unable to create failed-job directory"
            )
        }

        return dir
    }

    private fun uniqueFailedFile(
        directory: File,
        original: File
    ): File {
        val base =
            original.nameWithoutExtension
                .take(60)
                .replace(
                    Regex("[^A-Za-z0-9.-]"),
                    ""
                )

        return File(
            directory,
            "failed_${System.currentTimeMillis()}" +
                "${java.util.UUID.randomUUID()}$base.wav"
        )
    }

    private fun ensureWorker() {
        if (!workerRunning.compareAndSet(false, true)) return
        if (paused.get()) { workerRunning.set(false); Log.i(TAG, "Paused - worker not started"); return }
        executor.submit {
            Log.i(TAG, "Worker started")
            try {
                while (true) {
                    try {
                        if (paused.get()) {
                            Log.i(TAG, "Worker pausing")
                            stopSimulatedProgress(); notifyProgress(0)
                            break
                        }
                        val job = try { queue.poll(500, TimeUnit.MILLISECONDS) } catch (e: InterruptedException) { null }
                        if (job == null) {
                            Log.i(TAG, "Worker idle")
                            stopSimulatedProgress(); notifyProgress(0)
                            submittedCount.set(0); completedCount.set(0); stopAllFlag.set(false); currentPct = 0
                            ProcessingService.notifyActivity()
                            break
                        }
                        processJobSafely(job)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Worker loop error: ${t.message}", t)
                        AppLog.e(TAG, "worker error: ${t.message}")
                        stopSimulatedProgress(); notifyProgress(0)
                    }
                }
            } finally {
                workerRunning.set(false)
                if (!queue.isEmpty() && !paused.get()) ensureWorker()
            }
        }
    }

    private fun processJobSafely(job: Job) {
        var callbackDelivered = false

        try {
            processJob(job)
            callbackDelivered = true
        } catch (e: Throwable) {
            AppLog.e(
                TAG,
                "Unexpected job failure: ${e.message}"
            )

            runCatching {
                job.onError(
                    e.message ?: "Unexpected transcription error"
                )
            }

            callbackDelivered = true
        } finally {
            pendingCount.updateAndGet {
                (it - 1).coerceAtLeast(0)
            }

            completedCount.incrementAndGet()
            currentJobId.compareAndSet(job.id, 0L)

            if (!callbackDelivered) {
                runCatching {
                    job.onError("Job ended unexpectedly")
                }
            }

            stopSimulatedProgress()
            notifyProgress(0)
        }
    }

    private fun safelyDeliverResult(
        job: Job,
        result: String
    ) {
        runCatching {
            job.onResult(result)
        }.onFailure {
            AppLog.e(
                TAG,
                "onResult callback failed: ${it.message}"
            )
        }
    }

    private fun safelyDeliverError(
        job: Job,
        error: String
    ) {
        runCatching {
            job.onError(error)
        }.onFailure {
            AppLog.e(
                TAG,
                "onError callback failed: ${it.message}"
            )
        }
    }

    private fun processJob(job: Job) {
        currentJobId.set(job.id)
        currentModel = "${job.engine}/${job.model}"
        AppLog.i(TAG, "process ${job.engine}/${job.model}/${job.lang} ${job.wavFile.name} (${job.wavFile.length() / 1024} KB)")
        val audioSec = estimateSeconds(job.wavFile)
        startSimulatedProgress(job.context, job.engine, job.model, job.wavFile)
        var success = false
        var resultText = ""
        var errorMsg = ""
        var attempt = 0
        while (attempt < 2 && !success) {
            attempt++
            try {
                if (job.engine == SttEngines.MOONSHINE) {
                    if (!ModelManager.isMoonshineReady(job.context, job.model) && !MoonshineEngine.isLoaded(job.model)) {
                        val ok = MoonshineEngine.ensureModel(job.context, job.model, "en")
                        if (!ok) throw IllegalStateException("Moonshine model ${job.model} not ready. Download it in app first. ${MoonshineEngine.lastError}")
                    }
                } else {
                    if (!ModelManager.isComplete(job.context, job.model)) {
                        val short = ModelManager.shortfall(job.context, job.model)
                        throw IllegalStateException(
                            if (short != null) "Model ggml-${job.model}.bin incomplete ($short) - re-download it in app first."
                            else "Model ggml-${job.model}.bin not found. Download it in app first."
                        )
                    }
                }
                val transcribeStart =
                    android.os.SystemClock.elapsedRealtime()

                resultText = SttEngines.transcribe(job.context, job.engine, job.model, job.wavFile, job.lang)

                val transcribeSeconds =
                    (
                        android.os.SystemClock.elapsedRealtime() -
                        transcribeStart
                    ) / 1000.0

                if (resultText.startsWith("ERROR: cancelled")) {
                    Log.i(TAG, "job cancelled by user")
                    AppLog.i(TAG, "job cancelled by user")
                    errorMsg = "cancelled"
                    stopSimulatedProgress(); notifyProgress(0)
                    break
                }
                if (resultText.startsWith("ERROR:")) {
                    if (attempt < 2) { Log.w(TAG, "Attempt $attempt returned ${resultText.take(60)} - retrying"); AppLog.w(TAG, "attempt $attempt ERROR result - retrying"); continue }
                    throw IllegalStateException(resultText)
                }
                val cleaned = resultText.trim()

                if (
                    cleaned.isEmpty() ||
                    AudioUtils.isNoSpeechText(cleaned)
                ) {
                    success = true
                    resultText = ""
                    AppLog.i(TAG, "No speech detected")
                } else {
                    success = true
                    resultText = cleaned
                }
                Log.i(TAG, "Result: ${resultText.take(120)}")
                AppLog.i(TAG, "done ${job.engine}/${job.model} in ${transcribeSeconds}s: ${resultText.take(50)}")
                recordStats(job.context, job.engine, job.model, audioSec, transcribeSeconds)
                stopSimulatedProgress(); notifyProgress(100)
                Thread.sleep(150)
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
                AppLog.e(TAG, "OOM")
                stopSimulatedProgress(); notifyProgress(0)
                break
            }
        }
        if (!success && errorMsg.isEmpty()) errorMsg = "Unknown error"
        val wasCancelled = errorMsg.contains("cancelled")
        if (!success && !wasCancelled) {
            try {
                val failDir = failedDir(job.context)
                val saved = if (job.wavFile.name.startsWith("failed_")) job.wavFile
                    else uniqueFailedFile(failDir, job.wavFile).also { job.wavFile.copyTo(it, overwrite = false) }
                AppLog.w(TAG, "saved for retry: ${saved.name}")
                val retryJob = job.copy(wavFile = saved)
                synchronized(failedJobs) {
                    if (failedJobs.none { it.wavFile.absolutePath == saved.absolutePath }) failedJobs.add(retryJob)
                }
            } catch (ex: Exception) {
                Log.w(TAG, "Failed to save failed WAV: ${ex.message}")
                synchronized(failedJobs) { failedJobs.add(job) }
            }
        }
        try {
            if (success || job.wavFile.absolutePath.contains("cache")) {
                try { if (job.wavFile.exists()) job.wavFile.delete() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        val skipped = skipJobId.compareAndSet(job.id, -1L)
        val stoppedAll = stopAllFlag.get()
        if (skipped || stoppedAll || wasCancelled) {
            AppLog.i(TAG, "job dropped (${if (wasCancelled) "cancelled" else if (skipped) "skipped" else "stop-all"}) - result discarded")
        } else if (success) safelyDeliverResult(job, resultText) else safelyDeliverError(job, errorMsg)
    }
}
