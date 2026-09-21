package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingRecordService : Service() {

    companion object {
        const val TAG = "MeetingService"
        const val CHANNEL_ID = "whisper_meeting"
        const val NOTIFICATION_ID = 101
        // Chunk rule: 30-59.9s + 2s silence = close; 60s = force-close.
        const val MIN_CHUNK_MS = 30_000L
        const val MAX_CHUNK_MS = 60_000L
        const val SILENCE_MIN_MS = 2_000L
        const val MIN_CHUNK_BYTES = 8_000
        const val SILENCE_RMS_THRESHOLD = 0.015

        /** Read by MainActivity to enable/disable Start vs Stop (mutually exclusive). */
        @Volatile var isRunning = false
        /** True after Stop is tapped while remaining chunks still transcribe. */
        @Volatile var isStopping = false
        /**
         * Single source of truth for the meeting status line in the app UI.
         * MainActivity's poll loop displays this, so the text always reflects
         * reality - including the moment the background save actually finishes.
         */
        @Volatile var uiStatus = "Idle"
    }

    private var isRecording = false
    private var recordThread: Thread? = null
    private var startTimeMs = 0L
    private var model = "small"
    private var lang = "auto"
    private var engine = SttEngines.WHISPER
    private var transcriptFile: File? = null
    private var segmentCounter = 0
    @Volatile
    private var activeRecorder: android.media.AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioSaver: FullAudioSaver? = null
    private var audioBaseName = ""
    private var savedAudioFile: File? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperSpeechToText:Meeting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                if (isRunning) return START_NOT_STICKY // already recording - ignore double tap
                model = intent.getStringExtra("model") ?: "small"
                lang = intent.getStringExtra("lang") ?: "auto"
                engine = intent.getStringExtra("engine") ?: SttEngines.WHISPER
                startMeeting()
            }
            "STOP" -> {
                requestStop() // fast, non-blocking - final flush happens in background
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Meeting Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Speech to Text meeting recording in progress"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MeetingRecordService::class.java).apply {
            action = "STOP"
        }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPending = PendingIntent.getActivity(
            this, 3, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Speech to Text - Meeting")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openAppPending)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPending)
            .build()
    }

    private fun startMeeting() {
        if (isRecording) return
        // Defense in depth (MainActivity already preflights): refuse to record
        // what the engine cannot transcribe instead of failing every chunk.
        val missing = SttEngines.describeMissing(this, engine, model)
        if (missing != null) {
            Log.w(TAG, "refusing to record: $engine: $missing")
            uiStatus = "$engine: $missing"
            runCatching { stopSelf() }
            return
        }
        if (!MicSessionManager.tryAcquire(MicOwner.MEETING)) {
            Log.w(TAG, "Microphone currently owned by another recorder")
            uiStatus = "Microphone is busy"
            runCatching {
                android.widget.Toast.makeText(this, "Microphone is busy", android.widget.Toast.LENGTH_SHORT).show()
            }
            runCatching { stopSelf() }
            return
        }
        // Storage guard: full-session archive can approach ~1GB for long meetings.
        val saveAudioMeeting = getSharedPreferences("whisper", MODE_PRIVATE).getBoolean("save_audio_meeting", true)
        val estimatedBytes = if (saveAudioMeeting) 1_000_000_000L else 200_000_000L
        if (!hasEnoughStorage(applicationContext, estimatedBytes)) {
            MicSessionManager.release(MicOwner.MEETING)
            Log.w(TAG, "Not enough free storage for recording")
            uiStatus = "Not enough free storage for recording"
            runCatching {
                android.widget.Toast.makeText(this, "Not enough free storage for recording", android.widget.Toast.LENGTH_LONG).show()
            }
            runCatching { stopSelf() }
            return
        }
        isRecording = true
        isRunning = true
        isStopping = false
        transcriptFile = null
        savedAudioFile = null
        uiStatus = "Recording... tap Stop (continues with screen off)"
        startTimeMs = System.currentTimeMillis()
        segmentCounter = 0

        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
            val baseName = "meeting_${fmt.format(Date())}"
            audioBaseName = baseName

            // Meeting always saves a clean words-only transcript + full audio.
            val docsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "WhisperNotes"
            )
            if (!docsDir.exists()) docsDir.mkdirs()

            transcriptFile = File(docsDir, "$baseName.txt")
            transcriptFile?.writeText("")

            // Full-session audio archive alongside the transcript
            FullAudioSaver.pruneTemp(this)
            audioSaver = if (saveAudioMeeting) {
                FullAudioSaver(this, FullAudioSaver.notesDir(), baseName)
            } else null
        } catch (e: Exception) {
            // Setup failed after mic acquire (e.g. storage I/O):
            // release everything so state and mic never get stuck.
            Log.e(TAG, "Meeting setup failed: ${e.message}", e)
            uiStatus = "Meeting failed to start: ${e.message}"
            audioSaver = null
            transcriptFile = null
            MicSessionManager.release(MicOwner.MEETING)
            isRecording = false
            isRunning = false
            isStopping = false
            runCatching { stopSelf() }
            return
        }

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}
        // Explicit mic type (API 29+ documented form - same as the proven bubble path).
        // Never crash here: without foreground the meeting cannot survive lock screen,
        // so bail out cleanly instead of taking the app down in a crash loop.
        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"))
            }
        }.isSuccess || runCatching {
            startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"))
        }.isSuccess
        if (!promoted) {
            Log.e(TAG, "FGS promote denied - meeting recording unavailable on this device state")
            AppLog.e(TAG, "meeting FGS denied - aborting start")
            MicSessionManager.release(MicOwner.MEETING)
            isRecording = false; isRunning = false; isStopping = false
            runCatching { stopSelf() }
            return
        }
        Log.i(TAG, "Meeting started: model=$model lang=$lang engine=$engine - WakeLock held, will survive lock screen")

        recordThread = Thread {
            var pcmChunk = ByteArrayOutputStream()
            var chunkStartTime = System.currentTimeMillis()
            var recorder: android.media.AudioRecord? = null

            try {
                recorder = AudioUtils.createRecorder(this@MeetingRecordService)
                activeRecorder = recorder
                recorder.startRecording()

                var lastNotifUpdate = 0L
                var silenceStartMs: Long? = null
                var lastRms: Double

                while (isRecording) {
                    val buffer = ByteArray(4096)
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pcmChunk.write(buffer, 0, read)
                        audioSaver?.append(buffer, read)
                        // track silence for VAD boundary
                        lastRms = AudioUtils.rms16(buffer, read)
                        if (lastRms < SILENCE_RMS_THRESHOLD) {
                            if (silenceStartMs == null) silenceStartMs = System.currentTimeMillis()
                        } else {
                            silenceStartMs = null
                        }
                    }

                    val now = System.currentTimeMillis()

                    if (now - lastNotifUpdate > 1000) {
                        lastNotifUpdate = now
                        val elapsed = (now - startTimeMs) / 1000
                        val min = elapsed / 60
                        val sec = elapsed % 60
                        val status = String.format("%02d:%02d | %s", min, sec, TranscriptionQueue.status())
                        val nm = getSystemService(NotificationManager::class.java)
                        nm.notify(NOTIFICATION_ID, buildNotification(status))
                    }

                    val chunkElapsed = now - chunkStartTime

                    val silenceDurationMs =
                        if (silenceStartMs != null) {
                            now - silenceStartMs
                        } else {
                            0L
                        }

                    val isSafeSilence =
                        silenceDurationMs >= SILENCE_MIN_MS

                    val hasEnoughAudio =
                        pcmChunk.size() >= MIN_CHUNK_BYTES

                    val shouldFlush =
                        hasEnoughAudio && (
                            // Between 30 and 60 seconds, close only at a safe silence boundary.
                            (chunkElapsed >= MIN_CHUNK_MS && isSafeSilence) ||

                            // Never allow one chunk to exceed 60 seconds.
                            chunkElapsed >= MAX_CHUNK_MS
                        )

                    if (shouldFlush) {
                        val toFlush = pcmChunk
                        val flushedChunkStart = chunkStartTime

                        pcmChunk = ByteArrayOutputStream()
                        chunkStartTime = now
                        silenceStartMs = null

                        flushChunk(toFlush, flushedChunkStart)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}
                // shared recorder stays alive for bubble/keyboard (never release)
                activeRecorder = null

                // NB: tail bytes were already streamed to audioSaver live in the loop.
                if (pcmChunk.size() > 4000) {
                    val finalChunk = pcmChunk
                    val finalChunkStart = chunkStartTime

                    flushChunk(finalChunk, finalChunkStart)
                }

                Log.i(TAG, "Meeting recording thread finished")
            }
        }.apply {
            isDaemon = true
            name = "meeting-recorder"
            start()
        }
    }

    private fun flushChunk(pcmData: ByteArrayOutputStream, chunkStartMs: Long) {
        try {
            val timestamp = System.currentTimeMillis()

            val pcmFile =
                File(cacheDir, "meeting_chunk_$timestamp.pcm")
            FileOutputStream(pcmFile).use { it.write(pcmData.toByteArray()) }

            val wavFile =
                File(cacheDir, "meeting_chunk_$timestamp.wav")
            AudioUtils.pcmToWav(pcmFile, wavFile)
            pcmFile.delete()

            segmentCounter++
            // 16 kHz mono 16-bit PCM = 32,000 bytes/sec (matches AudioUtils.createRecorder).
            val audioSeconds = pcmData.size().toDouble() / 32_000.0
            Log.i(
                TAG,
                "Flushing chunk #$segmentCounter, " +
                    "duration=${"%.1f".format(audioSeconds)}s, " +
                    "wav=${wavFile.length()} bytes"
            )

            val job = TranscriptionQueue.Job(
                context = applicationContext,
                wavFile = wavFile,
                model = model,
                lang = lang,
                engine = engine,
                onResult = { text ->
                    val cleaned = text.trim()
                    if (cleaned.isNotEmpty() && !AudioUtils.isNoSpeechText(cleaned)) {
                        synchronized(this) {
                            if (transcriptFile != null) {
                                // Save clean text only (no timestamps)
                                transcriptFile!!.appendText("$cleaned\n")
                                getSharedPreferences("whisper", MODE_PRIVATE)
                                    .edit()
                                    .putString("last_transcript_path", transcriptFile!!.absolutePath)
                                    .apply()
                            }
                        }
                    }
                },
                onError = { error ->
                    Log.e(TAG, "Chunk failed: $error")
                    // User-visible: the old code only logged, leaving "1 failed" a mystery.
                    val n = TranscriptionQueue.failedCount()
                    uiStatus = "Chunk failed ($n failed): $error"
                }
            )
            if (!TranscriptionQueue.enqueue(job)) {
                AppLog.e(TAG, "Unable to enqueue transcription chunk")
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushChunk error: ${e.message}")
        }
    }

    /**
     * Stop requested (main thread - must return FAST, never block).
     * Signals the capture loop to exit, flips the notification to
     * "Finishing...", and hands the join + queue drain to a background
     * thread. Previously this joined the recorder and slept on the MAIN
     * thread with a wait condition that could never go false -> ANR/crash
     * just as the transcript finished saving.
     */
    private fun requestStop() {
        if (!isRecording && !isRunning) {
            stopSelf()
            return
        }
        isRecording = false
        isStopping = true
        // Unblock a recorder.read() immediately so the loop exits without delay.
        try {
            activeRecorder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to unblock recorder: ${e.message}")
        }
        uiStatus = "Stopping... transcript saving (wait for queue)"
        Log.i(TAG, "Stop requested - finishing in background...")
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification("Finishing... transcribing remaining chunks"))
        }
        Thread {
            try { recordThread?.join(10_000) } catch (_: InterruptedException) {}
            MicSessionManager.release(MicOwner.MEETING)

            // Finalize the full-session audio first (background thread - AAC
            // encode of a long meeting takes seconds), then drain the queue.
            try {
                val saver = audioSaver
                audioSaver = null
                if (saver != null) {
                    val format = getSharedPreferences("whisper", MODE_PRIVATE).getString("audio_format", "m4a") ?: "m4a"
                    val audioFile = saver.finish(format)
                    if (audioFile != null) {
                        savedAudioFile = audioFile
                        getSharedPreferences("whisper", MODE_PRIVATE).edit()
                            .putString("last_audio_path", audioFile.absolutePath).apply()
                        Log.i(TAG, "Audio saved: ${audioFile.absolutePath}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "audio finalize failed: ${e.message}")
            }

            // Wait for remaining queue items - OFF the main thread, with a
            // REAL condition (pendingCount/isActive, not a status-substring
            // that is always true) and a bounded timeout.
            var waited = 0
            while ((TranscriptionQueue.pendingCount() > 0 || TranscriptionQueue.isActive()) && waited < 120) {
                try { Thread.sleep(500) } catch (_: InterruptedException) { break }
                waited++
            }
            Log.i(TAG, "Meeting saved: ${transcriptFile?.absolutePath ?: "unknown"} (queue drained: waited ${waited * 0.5}s)")
            finishStop()
        }.apply { isDaemon = true; name = "meeting-stop"; start() }
    }

    /** Called by the queue-drain watcher below once everything is delivered. */
    private fun finishStop() {
        val path = transcriptFile?.absolutePath ?: "unknown"
        Log.i(TAG, "Meeting finished: $path")
        // Publish the final status BEFORE tearing down, so the app UI (which polls
        // uiStatus) flips from "Stopping..." to the saved result. This was the bug:
        // nothing ever updated the line after the background save completed.
        val parts = mutableListOf<String>()
        transcriptFile?.name?.let { parts.add(it) }
        savedAudioFile?.name?.let { parts.add(it) }
        val failed = TranscriptionQueue.failedCount()
        uiStatus = if (parts.isEmpty() && failed == 0) "Saved ✓ (nothing recorded)"
        else "Saved ✓ ${parts.joinToString(" + ")}" + (if (failed > 0) " ($failed chunk(s) failed - see Retry Failed)" else "")
        runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        isRunning = false
        isStopping = false
        stopSelf()
    }

    override fun onDestroy() {
        isRecording = false
        isRunning = false
        isStopping = false
        MicSessionManager.release(MicOwner.MEETING)
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    fun hasEnoughStorage(
        context: android.content.Context,
        requiredBytes: Long
    ): Boolean {

        val stat =
            android.os.StatFs(
                context.filesDir.absolutePath
            )

        val available =
            stat.availableBytes

        return available >= requiredBytes
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
