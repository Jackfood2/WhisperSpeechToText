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
import androidx.core.app.NotificationCompat

class ImeRecordService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperSpeechToText:ImeRec")
        wakeLock?.acquire(30*60*1000L)
    }
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel("whisper_ime", "Voice Typing", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(ch)
        }
    }
    private fun notif(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ImeRecordService::class.java).setAction("STOP_RECORDING"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, "whisper_ime")
            .setContentTitle("Whisper - recording voice typing")
            .setContentText("Recording continues on lock screen")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .addAction(0, "■ Stop Recording", stopIntent)
            .build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startForeground(102, notif())
            "STOP_RECORDING" -> {
                // from the notification Stop button (works on lock screen):
                // end the recording; queued chunks keep processing in background
                WhisperKeyboardService.stopHook?.invoke()
                WhisperKeyboardService.stopHook = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            "STOP" -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
