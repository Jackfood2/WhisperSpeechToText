package com.whisperkeyboard

object ModelNotifier {

    fun loaded(fileName: String, sizeMb: Long) {
        AppLog.i("Model", "loaded $fileName (${sizeMb}MB)")
        ProcessingService.notifyActivity()
    }

    fun unloaded(fileName: String?) {

        AppLog.i("Model", "unloaded ${fileName ?: "model"}")
    }

    fun toast(msg: String) {
        try {
            val c = WhisperApp.holder ?: return
            android.widget.Toast.makeText(c, msg, android.widget.Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }
}
