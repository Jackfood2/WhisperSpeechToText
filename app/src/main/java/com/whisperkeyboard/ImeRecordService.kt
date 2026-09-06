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

/**
 * Foreground holder so IME voice typing survives the lock screen.
 * The audio capture itself runs in WhisperKeyboardService - this service only
 * holds the WakeLock + foreground promotion.
 *
 * CRITICAL (v2.7.3): on some devices (OneUI/Android 14+) promoting to a
 * microphone-type FGS from the IME context throws SecurityException
 * ("eligible state/exemptions"). That used to be uncaught -> whole process
 * (including the keyboard) crashed on every input tap. Now the promotion is
 * best-effort: if denied, recording CONTINUES without the foreground holder
 * (lock-screen continuation is lost on those devices, but the keyboard,
 * mic button and transcription all keep working).
 */
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
            .setContentTitle("Speech to Text - recording voice typing")
            .setContentText("Recording continues on lock screen")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "Stop Recording", stopIntent)
            .build()
    }

    /** Best-effort foreground promotion. Returns true if foreground. Never throws. */
    private fun promote(): Boolean {
        if (promoted) return true
        val n = notif()
        // 1) explicit mic type (documented API 29+ form - works from visible components)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(102, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(102, n)
            }
        }.isSuccess
        if (ok) {
            promoted = true
            return true
        }
        // 2) legacy form (manifest-declared type) - older platform behavior
        val ok2 = runCatching { startForeground(102, n) }.isSuccess
        if (ok2) {
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
            "START" -> promote()
            "STOP_RECORDING" -> {
                // from the notification Stop button (works on lock screen):
                // end the recording; queued chunks keep processing in background
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
