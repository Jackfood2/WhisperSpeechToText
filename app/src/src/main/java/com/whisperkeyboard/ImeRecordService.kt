package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        return NotificationCompat.Builder(this, "whisper_ime")
            .setContentTitle("Whisper Speech to Text")
            .setContentText("Listening... tap Stop on keyboard")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .build()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> startForeground(102, notif())
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
