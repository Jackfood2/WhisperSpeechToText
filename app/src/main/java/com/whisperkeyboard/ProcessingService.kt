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
 * Ongoing notification while transcription chunks are processing or text is waiting
 * to be typed in. Visible on the lock screen. Stays until everything is delivered,
 * then releases the cached model (battery protection) and stops.
 */
class ProcessingService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var idleTicks = 0

    private val poller = object : Runnable {
        var lastShown = ""
        override fun run() {
            val held = OutstandingStore.count(WhisperApp.holder)
            // recording on ANY interface counts as activity (strict timeout semantics)
            val recNow = runCatching { QuickSwitchService.recActive }.getOrDefault(false) ||
                    runCatching { WhisperKeyboardService.imeRecording }.getOrDefault(false)
            val busy = TranscriptionQueue.isActive() || TextRouter.pendingTypingCount() > 0 || held > 0 || recNow
            val nm = getSystemService(NotificationManager::class.java)
            if (!busy) {
                idleTicks++
                // STRICT user setting: unload exactly after unload_idle_ticks*30s of inactivity.
                val ticksAllowed = getSharedPreferences("whisper", MODE_PRIVATE).getInt("unload_idle_ticks", 2) * 30

                val txt = when {
                    ticksAllowed == 0 -> "Idle - model kept in memory (Never = no unload)"
                    idleTicks < ticksAllowed -> "Loaded - unloads in ${ticksAllowed - idleTicks}s"
                    else -> "Model unloaded - memory freed"
                }
                if (txt != lastShown) { lastShown = txt; nm?.notify(NOTIF_ID, buildNotif(txt)) }

                if (ticksAllowed > 0 && idleTicks == ticksAllowed) {
                    Thread { WhisperEngine.unloadIfIdle() }.start() // real native free + its own notification/toast
                }
                val stopAt = if (ticksAllowed == 0) 20 else ticksAllowed + 8
                if (idleTicks >= stopAt) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
            } else {
                idleTicks = 0
                lastShown = ""
                val q = TranscriptionQueue.pendingCount()
                val typing = TextRouter.pendingTypingCount()
                nm?.notify(NOTIF_ID, buildNotif(q, typing, held))
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Processing", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        startForeground(NOTIF_ID, buildNotif(0, 0, 0))
        handler.post(poller)
    }

    private fun buildNotif(txt: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Whisper - processing")
            .setContentText(txt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    private fun buildNotif(queued: Int, typing: Int, held: Int): Notification {
        val parts = mutableListOf<String>()
        if (queued > 0) parts.add("transcribing $queued chunk(s)")
        if (typing > 0) parts.add("$typing waiting to type in")
        if (held > 0) parts.add("$held HELD - refocus a field to insert")
        val txt = if (parts.isEmpty()) "Done - freeing memory..." else parts.joinToString(" • ").replaceFirstChar { it.uppercase() }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Whisper - processing")
            .setContentText(txt)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
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

        /** Start/nudge the processing notification (called from any thread). */
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
