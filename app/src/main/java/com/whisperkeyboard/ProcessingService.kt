package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class ProcessingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0

    private fun busyNow(): Boolean {
        val recordingNow =
            runCatching { QuickSwitchService.recActive }.getOrDefault(false) ||
            runCatching { WhisperKeyboardService.imeRecording }.getOrDefault(false) ||
            MeetingRecordService.isRunning ||
            MeetingRecordService.isStopping

        val transcriptionNow =
            TranscriptionQueue.isActive() ||
            TranscriptionQueue.pendingCount() > 0 ||
            SttEngines.busy()

        val deliveryNow =
            TextRouter.pendingTypingCount() > 0

        return recordingNow || transcriptionNow || deliveryNow
    }

    private val poller = object : Runnable {
        override fun run() {
            val busy = busyNow()
            if (!busy) {
                idleTicks++

                val configuredMinutes =
                    getSharedPreferences("whisper", MODE_PRIVATE)
                        .getInt("unload_idle_ticks", 2)
                        .coerceIn(0, 120)

                val ticksAllowed = configuredMinutes * 30

                if (ticksAllowed > 0 && idleTicks == ticksAllowed) {

                    Thread {
                        if (!busyNow()) {
                            SttEngines.unloadIdle()
                        } else {
                            AppLog.i("Processing", "unload skipped - activity resumed")
                        }

                        handler.post {
                            if (SttEngines.loadedModel() == null) {
                                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                                stopSelf()
                            }
                        }
                    }.start()
                } else if (ticksAllowed > 0 && idleTicks >= ticksAllowed + 8) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                    return
                }

            } else {
                idleTicks = 0
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val ch = NotificationChannel(CHANNEL, "Model status", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        val promoted = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIF_ID,
                    buildStaticNotif(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, buildStaticNotif())
            }
        }.isSuccess
        if (!promoted) {
            runCatching { stopSelf() }
            return
        }
        handler.post(poller)
    }

    private fun buildStaticNotif(): Notification {
        val contentIntent = android.app.PendingIntent.getActivity(
            this, 21,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setContentTitle("Speech to Text ready")
            .setContentText("On-device transcription ready")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        lastStart = 0
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val CHANNEL = "processing"
        const val NOTIF_ID = 103

        @Volatile private var lastStart = 0L
        fun notifyActivity() {
            val now = System.currentTimeMillis()
            if (now - lastStart < 900) return
            lastStart = now
            try {
                val ctx = WhisperApp.holder
                if (ctx != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(Intent(ctx, ProcessingService::class.java))
                    else ctx.startService(Intent(ctx, ProcessingService::class.java))
                }
            } catch (_: Exception) {}
        }
        fun notifyIdle() {}
    }
}
