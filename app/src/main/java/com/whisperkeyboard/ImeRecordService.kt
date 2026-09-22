package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class ImeRecordService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var promoted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WhisperSpeechToText:ImeRec")
        runCatching { wakeLock?.acquire(30*60*1000L) }
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
            .setContentTitle("Speech to Text keyboard recording")
            .setContentText("Keyboard recording stops when screen locks")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop Recording", stopIntent)
            .build()
    }

    private fun promote(): Boolean {
        if (promoted) return true
        val n = notif()

        val first = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(102, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(102, n)
            }
        }
        first.onFailure {
            AppLog.w("ImeRec", "mic FGS promote failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        if (first.isSuccess) {
            promoted = true
            return true
        }

        val second = runCatching { startForeground(102, n) }
        second.onFailure {
            AppLog.w("ImeRec", "legacy FGS promote failed: ${it.javaClass.simpleName}: ${it.message}")
        }
        if (second.isSuccess) {
            promoted = true
            return true
        }
        AppLog.w("ImeRec", "FGS promote denied - recording continues without lock-screen holder")
        return false
    }

    private fun demoteAndStop() {
        if (promoted) runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        promoted = false
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "START" -> {
                if (!promote()) {

                    try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
                    stopSelf()
                }
            }
            "STOP_RECORDING" -> {

                runCatching { WhisperKeyboardService.stopHook?.invoke() }
                WhisperKeyboardService.stopHook = null
                demoteAndStop()
            }
            "STOP" -> {
                demoteAndStop()
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
