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
        const val CHUNK_DURATION_MS = 30_000L

        /** Read by MainActivity to enable/disable Start vs Stop (mutually exclusive). */
        @Volatile var isRunning = false
        /** True after Stop is tapped while remaining chunks still transcribe. */
        @Volatile var isStopping = false
    }

    private var isRecording = false
    private var recordThread: Thread? = null
    private var startTimeMs = 0L
    private var model = "small"
    private var lang = "auto"
    private var engine = SttEngines.WHISPER
    private var mode = "txt"  // "txt" = save clean text file, "type" = commit to focused input
    private var transcriptFile: File? = null
    private var segmentCounter = 0
    private val allText = StringBuilder()
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioSaver: FullAudioSaver? = null
    private var audioBaseName = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperSpeechToText:Meeting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                if (isRunning) return START_STICKY // already recording - ignore double tap
                model = intent.getStringExtra("model") ?: "small"
                lang = intent.getStringExtra("lang") ?: "auto"
                mode = intent.getStringExtra("mode") ?: "txt"
                engine = intent.getStringExtra("engine") ?: SttEngines.WHISPER
                startMeeting()
            }
            "STOP" -> {
                requestStop() // fast, non-blocking - final flush happens in background
            }
        }
        return START_STICKY
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
        isRecording = true
        isRunning = true
        isStopping = false
        startTimeMs = System.currentTimeMillis()
        segmentCounter = 0
        allText.clear()

        val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val baseName = "meeting_${fmt.format(Date())}"
        audioBaseName = baseName

        if (mode == "txt") {
            val docsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "WhisperNotes"
            )
            if (!docsDir.exists()) docsDir.mkdirs()

            transcriptFile = File(docsDir, "$baseName.txt")
            transcriptFile?.writeText("")
        }

        // Full-session audio archive (independent of txt/type mode)
        FullAudioSaver.pruneTemp(this)
        audioSaver = if (getSharedPreferences("whisper", MODE_PRIVATE).getBoolean("save_audio_meeting", true)) {
            FullAudioSaver(this, FullAudioSaver.notesDir(), baseName)
        } else null

        try { if (wakeLock?.isHeld == false) wakeLock?.acquire(4*60*60*1000L) } catch (_: Exception) {}
        startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"))
        Log.i(TAG, "Meeting started: mode=$mode model=$model lang=$lang - WakeLock held, will survive lock screen")

        recordThread = Thread {
            try {
                val recorder = AudioUtils.createRecorder(this@MeetingRecordService)
                recorder.startRecording()

                var pcmChunk = ByteArrayOutputStream()
                var chunkStartTime = System.currentTimeMillis()
                var lastNotifUpdate = 0L

                while (isRecording) {
                    val buffer = ByteArray(4096)
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pcmChunk.write(buffer, 0, read)
                        audioSaver?.append(buffer, read)
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

                    if (now - chunkStartTime >= CHUNK_DURATION_MS && pcmChunk.size() > 8000) {
                        flushChunk(pcmChunk, chunkStartTime)
                        pcmChunk = ByteArrayOutputStream()
                        chunkStartTime = now
                    }
                }

                // NB: tail bytes were already streamed to audioSaver live in the loop.
                if (pcmChunk.size() > 4000) {
                    flushChunk(pcmChunk, chunkStartTime)
                }

                try {
                    recorder.stop()
                    // shared recorder stays alive for bubble/keyboard (never release)
                } catch (e: Exception) {
                    Log.w(TAG, "Recorder stop error: ${e.message}")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Recording error: ${e.message}")
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun flushChunk(pcmData: ByteArrayOutputStream, chunkStartMs: Long) {
        try {
            val pcmFile = File(cacheDir, "meeting_chunk_${System.currentTimeMillis()}.pcm")
            FileOutputStream(pcmFile).use { it.write(pcmData.toByteArray()) }

            val wavFile = File(cacheDir, "meeting_chunk_${System.currentTimeMillis()}.wav")
            AudioUtils.pcmToWav(pcmFile, wavFile)
            pcmFile.delete()

            segmentCounter++
            Log.i(TAG, "Flushing chunk #$segmentCounter, wav=${wavFile.length()} bytes")

            TranscriptionQueue.enqueue(
                TranscriptionQueue.Job(
                    context = applicationContext,
                    wavFile = wavFile,
                    model = model,
                    lang = lang,
                    engine = engine,
                    onResult = { text ->
                        if (text.isNotBlank()) {
                            synchronized(this) {
                                allText.append(text).append(" ")

                                if (mode == "txt" && transcriptFile != null) {
                                    // Save clean text only (no timestamps)
                                    transcriptFile!!.appendText("$text\n")
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
                    }
                )
            )
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
        Log.i(TAG, "Stop requested - finishing in background...")
        runCatching {
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, buildNotification("Finishing... transcribing remaining chunks"))
        }
        Thread {
            try { recordThread?.join(10_000) } catch (_: InterruptedException) {}

            // Finalize the full-session audio first (background thread - AAC
            // encode of a long meeting takes seconds), then drain the queue.
            try {
                val saver = audioSaver
                audioSaver = null
                if (saver != null) {
                    val format = getSharedPreferences("whisper", MODE_PRIVATE).getString("audio_format", "m4a") ?: "m4a"
                    val audioFile = saver.finish(format)
                    if (audioFile != null) {
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
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
