package com.whisperkeyboard

import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

/** One-shot notifications so the user can SEE when the model occupies/frees memory. */
object ModelNotifier {

    private const val CHANNEL = "model_status"
    private const val ID_LOADED = 2001
    private const val ID_UNLOADED = 2002

    private fun ensureChannel(ctx: android.content.Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Model status", NotificationManager.IMPORTANCE_DEFAULT)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    fun loaded(fileName: String, sizeMb: Long) {
        val ctx = WhisperApp.holder ?: return
        try {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Whisper model loaded")
                .setContentText("$fileName (${sizeMb}MB) is now in memory")
                .setOngoing(false)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID_LOADED, n)
        } catch (_: Exception) {}
    }

    fun unloaded(fileName: String?) {
        val ctx = WhisperApp.holder ?: return
        try {
            ensureChannel(ctx)
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Whisper model unloaded")
                .setContentText("${fileName ?: "model"} released - memory freed, battery protected")
                .setOngoing(false)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(ID_UNLOADED, n)
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
