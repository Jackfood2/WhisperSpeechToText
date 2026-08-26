package com.whisperkeyboard

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

/**
 * ONE notification for the model's whole lifecycle. Loaded and unloaded states reuse the
 * SAME id, so the card is always a live status - never two independent notifications that
 * can disagree (e.g. swipe the "unloaded" one and still see "loaded").
 *
 * Clarity rules:
 *  - Loaded:  persists until replaced or swiped (model really is in memory).
 *  - Unloaded: replaces the loaded card, then self-dismisses after ~8s so a stale state
 *              can't linger once memory is actually free.
 */
object ModelNotifier {

    private const val CHANNEL = "model_status"
    private const val ID = 2001 // single id for BOTH states

    private fun ensureChannel(ctx: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Model status", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun build(ctx: android.content.Context, title: String, text: String, icon: Int, autoDismissMs: Long = 0L): android.app.Notification {
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (autoDismissMs > 0) b.setTimeoutAfter(autoDismissMs)
        return b.build()
    }

    fun loaded(fileName: String, sizeMb: Long) {
        val ctx = WhisperApp.holder ?: return
        try {
            ensureChannel(ctx)
            ctx.getSystemService(NotificationManager::class.java)
                .notify(ID, build(ctx, "Whisper model loaded", "$fileName (${sizeMb}MB) in memory - ready to transcribe",
                    android.R.drawable.stat_sys_download_done))
        } catch (_: Exception) {}
    }

    fun unloaded(fileName: String?) {
        val ctx = WhisperApp.holder ?: return
        try {
            ensureChannel(ctx)
            ctx.getSystemService(NotificationManager::class.java)
                .notify(ID, build(ctx, "Whisper model unloaded", "${fileName ?: "model"} released - memory freed, battery protected",
                    android.R.drawable.stat_notify_sync_noanim, autoDismissMs = 8_000))
        } catch (_: Exception) {}
        toast("Model unloaded - memory freed")
    }

    fun toast(msg: String) {
        try {
            val c = WhisperApp.holder ?: return
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
