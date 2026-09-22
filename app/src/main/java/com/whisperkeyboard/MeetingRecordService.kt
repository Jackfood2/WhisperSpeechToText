package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
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

        const val MIN_CHUNK_MS = 30_000L
        const val MAX_CHUNK_MS = 60_000L
        const val SILENCE_MIN_MS = 2_000L
        const val MIN_CHUNK_BYTES = 8_000
        const val SILENCE_RMS_THRESHOLD = 0.015
        private const val MAX_MEETING_HOURS = 8L

        private fun estimatedAudioBytes(): Long {
            val bytesPerSecond =
                AudioUtils.SAMPLE_RATE.toLong() * 2L

            return bytesPerSecond *
                3600L *
                MAX_MEETING_HOURS +
                50L * 1024L * 1024L
        }

        @Volatile var isRunning = false

        @Volatile var isStopping = false

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
                if (isRunning) return START_NOT_STICKY
                model = intent.getStringExtra("model") ?: "small"
                lang = intent.getStringExtra("lang") ?: "auto"
                engine = intent.getStringExtra("engine") ?: SttEngines.WHISPER
                startMeeting()
            }
            "STOP" -> {
                requestStop()
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

        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting meeting recording..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Starting meeting recording...")
                )
            }
        }.isSuccess

        if (!promoted) {
            uiStatus = "Microphone foreground service unavailable"
            AppLog.e(
                TAG,
                "Unable to promote MeetingRecordService"
            )
            stopSelf()
            return
        }

        startMeetingAfterForeground()
    }

    private fun startMeetingAfterForeground() {
        val missing = SttEngines.describeMissing(this, engine, model)
        if (missing != null) {
            Log.w(TAG, "refusing to record: $engine: $missing")
            uiStatus = "$engine: $missing"
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return
        }
        if (!MicSessionManager.tryAcquire(MicOwner.MEETING)) {
            Log.w(TAG, "Microphone currently owned by another recorder")
            uiStatus = "Microphone is busy"
            runCatching {
                android.widget.Toast.makeText(this, "Microphone is busy", android.widget.Toast.LENGTH_SHORT).show()
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return
        }

        val saveAudioMeeting = getSharedPreferences("whisper", MODE_PRIVATE).getBoolean("save_audio_meeting", true)
        val docsDir = FullAudioSaver.privateNotesDir(this)
        val estimatedBytes = if (saveAudioMeeting) estimatedAudioBytes() else 200_000_000L
        if (!hasEnoughStorage(docsDir, estimatedBytes)) {
            MicSessionManager.release(MicOwner.MEETING)
            Log.w(TAG, "Not enough free storage for recording")
            uiStatus = "Not enough free storage for recording"
            runCatching {
                android.widget.Toast.makeText(this, "Not enough free storage for recording", android.widget.Toast.LENGTH_LONG).show()
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return
        }
        isRecording = true
        isRunning = true
        isStopping = false
        stopStarted.set(false)
        transcriptFile = null
        savedAudioFile = null
        uiStatus = "Recording... tap Stop (continues with screen off)"
        startTimeMs = android.os.SystemClock.elapsedRealtime()
        segmentCounter = 0

        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss_SSS", Locale.US)
            val baseName = "meeting_${fmt.format(Date())}"
            audioBaseName = baseName

        transcriptFile = File(docsDir, "$baseName.txt")
        transcriptFile?.writeText("")

        FullAudioSaver.pruneTemp(this)
        audioSaver = if (getSharedPreferences("whisper", MODE_PRIVATE).getBoolean("save_audio_meeting", true)) {
            FullAudioSaver(this, docsDir, baseName)
        } else null
        } catch (e: Exception) {

            Log.e(TAG, "Meeting setup failed: ${e.message}", e)
            uiStatus = "Meeting failed to start: ${e.message}"
            audioSaver = null
            transcriptFile = null
            MicSessionManager.release(MicOwner.MEETING)
            isRecording = false
            isRunning = false
            isStopping = false
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
            return
        }

        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
        } catch (_: Exception) {}

        Log.i(TAG, "Meeting started: model=$model lang=$lang engine=$engine - WakeLock held, will survive lock screen")

        recordThread = Thread {
            var pcmChunk = ByteArrayOutputStream()
            var chunkStartTime = android.os.SystemClock.elapsedRealtime()
            var recorder: android.media.AudioRecord? = null

            try {
                recorder = AudioUtils.createRecorder(this@MeetingRecordService)
                activeRecorder = recorder
                recorder.startRecording()

                var lastNotifUpdate = 0L
                var silenceStartMs: Long? = null
                var lastRms: Double

                var badReads = 0
                while (isRecording) {
                    val buffer = ByteArray(4096)
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> badReads = 0
                        read == android.media.AudioRecord.ERROR_DEAD_OBJECT ->
                            throw IllegalStateException("AudioRecord device disconnected")
                        read == android.media.AudioRecord.ERROR_INVALID_OPERATION ->
                            throw IllegalStateException("AudioRecord invalid operation")
                        read == android.media.AudioRecord.ERROR_BAD_VALUE ->
                            throw IllegalStateException("AudioRecord bad read parameters")
                        else -> {
                            badReads++
                            if (badReads >= 50) {
                                throw IllegalStateException("AudioRecord read failed: $read")
                            }
                            continue
                        }
                    }
                    if (read > 0) {
                        pcmChunk.write(buffer, 0, read)
                        audioSaver?.append(buffer, read)

                        lastRms = AudioUtils.rms16(buffer, read)
                        if (lastRms < SILENCE_RMS_THRESHOLD) {
                            if (silenceStartMs == null) silenceStartMs = android.os.SystemClock.elapsedRealtime()
                        } else {
                            silenceStartMs = null
                        }
                    }

                    val now = android.os.SystemClock.elapsedRealtime()

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

                            (chunkElapsed >= MIN_CHUNK_MS && isSafeSilence) ||

                            chunkElapsed >= MAX_CHUNK_MS
                        )

                    if (shouldFlush) {
                        val toFlush = pcmChunk

                        pcmChunk = ByteArrayOutputStream()
                        chunkStartTime = now
                        silenceStartMs = null

                        flushChunk(toFlush)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}", e)
            } finally {
                try { recorder?.stop() } catch (_: Exception) {}

                activeRecorder = null

                if (pcmChunk.size() > 4000) {
                    flushChunk(pcmChunk)
                }

                Log.i(TAG, "Meeting recording thread finished")
            }
        }.apply {
            isDaemon = true
            name = "meeting-recorder"
            start()
        }
    }

    private val transcriptLock = Any()

    private fun storeTranscriptResult(text: String) {
        val cleaned = text.trim()

        if (
            cleaned.isEmpty() ||
            AudioUtils.isNoSpeechText(cleaned)
        ) {
            return
        }

        synchronized(transcriptLock) {
            val file = transcriptFile ?: return

            runCatching {
                file.appendText(cleaned + System.lineSeparator())

                getSharedPreferences("whisper", MODE_PRIVATE)
                    .edit()
                    .putString(
                        "last_transcript_path",
                        file.absolutePath
                    )
                    .apply()
            }.onFailure {
                AppLog.e(
                    TAG,
                    "Transcript write failed: ${it.message}"
                )
            }
        }
    }

    private fun flushChunk(pcmData: ByteArrayOutputStream) {
        if (pcmData.size() < MIN_CHUNK_BYTES) return

        val id = "${System.currentTimeMillis()}_${segmentCounter + 1}"
        val pcmFile = File(cacheDir, "meeting_chunk_$id.pcm")
        val wavFile = File(cacheDir, "meeting_chunk_$id.wav")

        try {
            FileOutputStream(pcmFile).use {
                pcmData.writeTo(it)
            }

            AudioUtils.pcmToWav(pcmFile, wavFile)

            if (!wavFile.isFile || wavFile.length() <= 44L) {
                throw IllegalStateException("Generated WAV is empty")
            }

            segmentCounter++

            val job = TranscriptionQueue.Job(
                context = applicationContext,
                wavFile = wavFile,
                model = model,
                lang = lang,
                engine = engine,
                onResult = { text ->
                    storeTranscriptResult(text)
                },
                onError = { error ->
                    Log.e(TAG, "Chunk failed: $error")
                    uiStatus =
                        "Chunk failed (${TranscriptionQueue.failedCount()} failed): $error"
                }
            )

            if (!TranscriptionQueue.enqueue(job)) {
                throw IllegalStateException(
                    "Unable to enqueue transcription chunk"
                )
            }
        } catch (e: Exception) {
            AppLog.e(
                TAG,
                "flushChunk failed: ${e.javaClass.simpleName}: ${e.message}"
            )
            runCatching { wavFile.delete() }
        } finally {
            runCatching { pcmFile.delete() }
        }
    }

    private val stopStarted =
        java.util.concurrent.atomic.AtomicBoolean(false)

    private fun requestStop() {
        if (!stopStarted.compareAndSet(false, true)) {
            AppLog.i(TAG, "Stop already in progress")
            return
        }

        if (!isRecording && !isRunning) {
            stopSelf()
            return
        }

        isRecording = false
        isStopping = true

        runCatching {
            activeRecorder?.stop()
        }.onFailure {
            Log.w(TAG, "Unable to unblock recorder: ${it.message}")
        }

        uiStatus = "Stopping... transcript saving"
        Log.i(TAG, "Stop requested")

        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(
                    NOTIFICATION_ID,
                    buildNotification("Finishing remaining audio...")
                )
        }

        Thread {
            finishMeetingInBackground()
        }.apply {
            name = "meeting-stop"
            start()
        }
    }

    private fun finishMeetingInBackground() {
        try { recordThread?.join(10_000) } catch (_: InterruptedException) {}
        MicSessionManager.release(MicOwner.MEETING)

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

        val completed = waitForTranscriptionQueue()

        if (completed) {
            finishStop()
        } else {
            uiStatus = "Recording saved, transcription still pending"
            runCatching { getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
            try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            isRunning = false
            isStopping = false
            stopSelf()
        }
    }

    private fun waitForTranscriptionQueue(
        timeoutMs: Long = 15L * 60L * 1000L
    ): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMs

        while (
            isStopping &&
            (
                TranscriptionQueue.pendingCount() > 0 ||
                TranscriptionQueue.isActive()
            )
        ) {
            if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                AppLog.w(TAG, "Timed out waiting for transcription queue")
                return false
            }

            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        return true
    }

    private fun finishStop() {
        if (transcriptFile != null && transcriptFile!!.length() == 0L) {
            runCatching { transcriptFile!!.delete() }
            transcriptFile = null
        }
        val path = transcriptFile?.absolutePath ?: "unknown"
        Log.i(TAG, "Meeting finished: $path")

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

    private fun hasEnoughStorage(
        destinationDir: File,
        requiredBytes: Long
    ): Boolean {
        return try {
            if (!destinationDir.exists()) {
                destinationDir.mkdirs()
            }

            val stat = android.os.StatFs(destinationDir.absolutePath)
            stat.availableBytes >= requiredBytes
        } catch (e: Exception) {
            AppLog.w(
                "MeetingService",
                "Storage check failed: ${e.message}"
            )
            false
        }
    }



    override fun onBind(intent: Intent?): IBinder? = null
}
