package com.whisperkeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Silent status-icon holder + idle model unloader.
 *
 * Shows ONE static status-bar icon (round green dot, like a wifi indicator)
 * while the Whisper model is in memory, and removes it the moment the model
 * unloads. No progress text, no unload countdown, no upload animation - the
 * notification shade entry is minimal and silent.
 *
 * The service also enforces the user's "Unload model when idle" timeout
 * (strictly: only real work - active recording or queued/in-flight
 * transcription - blocks the countdown) and keeps the process alive so that
 * transcription started from the keyboard keeps running after the user
 * switches back to the default keyboard.
 */
class ProcessingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0

    /**
     * Single source of truth for "model must stay": only REAL work blocks the countdown -
     * an active recording or transcription actually in flight/queued.
     *
     * Waiting-to-type and outstanding transcripts do NOT block it: they are
     * already finished text cached in OutstandingStore/TextRouter - inserting them later
     * just commits the stored string, whisper is never needed again for them.
     */
    private fun busyNow(): Boolean {
        val recNow = runCatching { QuickSwitchService.recActive }.getOrDefault(false) ||
                runCatching { WhisperKeyboardService.imeRecording }.getOrDefault(false)
        return TranscriptionQueue.isActive() || recNow
    }

    private val poller = object : Runnable {
        override fun run() {
            val busy = busyNow()
            if (!busy) {
                idleTicks++
                // STRICT user setting: unload exactly after unload_idle_ticks*30s of inactivity.
                val ticksAllowed = getSharedPreferences("whisper", MODE_PRIVATE).getInt("unload_idle_ticks", 2) * 30

                if (ticksAllowed > 0 && idleTicks == ticksAllowed) {
                    // Final re-check inside the unload thread: if anything became active
                    // at the last second, skip - the busy branch resets the countdown.
                    Thread {
                        if (!busyNow()) {
                            SttEngines.unloadIdle()
                        } else {
                            AppLog.i("Processing", "unload skipped - activity resumed")
                        }
                        // Back on the service thread: no engine loaded -> remove the icon.
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
                // ticksAllowed == 0 ("Never"): keep the icon, never unload, never stop.
            } else {
                idleTicks = 0
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // MIN: silent, no heads-up, no badge - status-bar icon only (wifi-style).
            val ch = NotificationChannel(CHANNEL, "Model status", NotificationManager.IMPORTANCE_MIN)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        startForeground(NOTIF_ID, buildStaticNotif())
        handler.post(poller)
    }

    /** The single static icon: round dot = model in memory. No counters, no countdown. */
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

        /** Start/nudge the status icon (called from any thread). */
        @Volatile private var lastStart = 0L
        fun notifyActivity() {
            val now = System.currentTimeMillis()
            if (now - lastStart < 900) return // throttle
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
