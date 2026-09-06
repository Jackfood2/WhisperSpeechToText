package com.whisperkeyboard

/**
 * Model lifecycle signals.
 *
 * There is exactly ONE user-visible indicator for the model: the silent
 * status-bar icon owned by [ProcessingService] (shown while the model is in
 * memory, removed on unload - wifi-style). These hooks only ensure that icon
 * exists / keep the idle-unload timer honest. No separate notifications, no
 * countdown text, no toasts.
 */
object ModelNotifier {

    fun loaded(fileName: String, sizeMb: Long) {
        AppLog.i("Model", "loaded $fileName (${sizeMb}MB)")
        ProcessingService.notifyActivity() // ensure the status icon is up
    }

    fun unloaded(fileName: String?) {
        // Icon removal is handled by ProcessingService itself after unload.
        AppLog.i("Model", "unloaded ${fileName ?: "model"}")
    }

    fun toast(msg: String) {
        try {
            val c = WhisperApp.holder ?: return
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
