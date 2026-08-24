package com.whisperkeyboard

object WhisperEngine {

    init {
        try {
            System.loadLibrary("whisper_jni")
            android.util.Log.i("WhisperEngine", "Native library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("WhisperEngine", "Failed to load native library: ${e.message}")
        }
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(modelPath: String, wavPath: String, lang: String): String

    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        return try {
            val effectiveLang = if (lang == "auto" || lang.isEmpty()) "auto" else lang
            nativeTranscribe(modelPath, wavPath, effectiveLang)
        } catch (e: Exception) {
            android.util.Log.e("WhisperEngine", "Transcribe error: ${e.message}")
            "ERROR: ${e.message}"
        }
    }
}
