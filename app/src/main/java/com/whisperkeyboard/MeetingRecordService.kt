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

    private companion object {
        const val TAG = "MeetingService"
        const val CHANNEL_ID = "whisper_meeting"
        const val NOTIFICATION_ID = 101
        const val CHUNK_DURATION_MS = 30_000L
    }

    private var isRecording = false
    private var recordThread: Thread? = null
    private var startTimeMs = 0L
    private var model = "small"
    private var lang = "auto"
    private var mode = "txt"  // "txt" = save clean text file, "type" = commit to focused input
    private var transcriptFile: File? = null
    private var segmentCounter = 0
    private val allText = StringBuilder()
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperSpeechToText:Meeting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                model = intent.getStringExtra("model") ?: "small"
                lang = intent.getStringExtra("lang") ?: "auto"
                mode = intent.getStringExtra("mode") ?: "txt"
                startMeeting()
            }
            "STOP" -> {
                stopMeeting()
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
                description = "Whisper meeting recording in progress"
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
            .setContentTitle("Whisper Meeting - Recording")
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
        startTimeMs = System.currentTimeMillis()
        segmentCounter = 0
        allText.clear()

        val fmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)
        val baseName = "meeting_${fmt.format(Date())}"

        if (mode == "txt") {
            val docsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "WhisperNotes"
            )
            if (!docsDir.exists()) docsDir.mkdirs()

            transcriptFile = File(docsDir, "$baseName.txt")
            transcriptFile?.writeText("")
        }

        try { if (wakeLock?.isHeld == false) wakeLock?.acquire(4*60*60*1000L) } catch (_: Exception) {}
        startForeground(NOTIFICATION_ID, buildNotification("Starting... (screen may lock, still recording)"))
        Log.i(TAG, "Meeting started: mode=$mode model=$model lang=$lang - WakeLock held, will survive lock screen")

        recordThread = Thread {
            try {
                val recorder = AudioUtils.createRecorder()
                recorder.startRecording()

                var pcmChunk = ByteArrayOutputStream()
                var chunkStartTime = System.currentTimeMillis()
                var lastNotifUpdate = 0L

                while (isRecording) {
                    val buffer = ByteArray(4096)
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        pcmChunk.write(buffer, 0, read)
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

                if (pcmChunk.size() > 4000) {
                    flushChunk(pcmChunk, chunkStartTime)
                }

                try {
                    recorder.stop()
                    recorder.release()
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
                    context = this,
                    wavFile = wavFile,
                    model = model,
                    lang = lang,
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

    private fun stopMeeting() {
        if (!isRecording) {
            stopSelf()
            return
        }

        isRecording = false
        Log.i(TAG, "Stopping meeting...")

        try {
            recordThread?.join(10000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "Thread join interrupted")
        }

        // Wait for remaining queue items
        var waitCount = 0
        while (TranscriptionQueue.status().contains("pending") && waitCount < 30) {
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            waitCount++
            Log.i(TAG, "Waiting for queue... ($waitCount)")
        }

        val path = transcriptFile?.absolutePath ?: "unknown"
        Log.i(TAG, "Meeting saved: $path")

        val nm = getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)

        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
