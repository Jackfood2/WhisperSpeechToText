package com.whisperkeyboard

import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Registers the app under system Settings -> "Voice input" (like Google voice typing).
 * Any app using SpeechRecognizer with Whisper set as default voice input records on-device,
 * transcribes offline, and returns text - audio never leaves the phone.
 */
class WhisperRecognitionService : RecognitionService() {

    private companion object { const val TAG = "WhisperRecog"; const val SAMPLE_RATE = 16000 }

    @Volatile private var listening = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        val cb = callback ?: return
        AppLog.i(TAG, "onStartListening")
        val model = getSharedPreferences("whisper", MODE_PRIVATE).getString("model", "small") ?: "small"
        val mf = ModelManager.modelFile(this, model)
        if (!mf.exists() || mf.length() < 1_000_000) {
            AppLog.e(TAG, "$model not downloaded")
            cb.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (ContextCompatMissing()) { cb.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return }
        listening = true
        val lang = getSharedPreferences("whisper", MODE_PRIVATE).getString("lang", "auto") ?: "auto"
        thread = Thread {
            var pcm: ByteArrayOutputStream? = null
            try {
                cb.readyForSpeech(Bundle())
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32000))
                recorder = rec
                rec.startRecording()
                cb.beginningOfSpeech()
                val buf = ByteArray(4096)
                pcm = ByteArrayOutputStream()
                var lastVoice = System.currentTimeMillis()
                var hasVoice = false
                val start = System.currentTimeMillis()
                while (listening) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    synchronized(pcm) { pcm.write(buf, 0, n) }
                    val rms = AudioUtils.rms16(buf, n)
                    if (rms > 0.018) { hasVoice = true; lastVoice = System.currentTimeMillis() }
                    val elapsed = System.currentTimeMillis() - start
                    // VAD auto-end after 1.3s silence, hard cap 30s
                    if ((hasVoice && System.currentTimeMillis() - lastVoice > 1300) || elapsed > 30_000) break
                }
                cb.endOfSpeech()
                try { rec.stop(); rec.release() } catch (_: Exception) {}
                recorder = null
                if (!listening) return@Thread
                val bytes = synchronized(pcm) { pcm.toByteArray() }
                if (bytes.size < 1800) {
                    cb.error(SpeechRecognizer.ERROR_NO_MATCH)
                    return@Thread
                }
                val wav = File(cacheDir, "recog_${System.currentTimeMillis()}.wav")
                AudioUtils.pcmBytesToWavFile(bytes, wav)
                val txt = WhisperEngine.transcribe(mf.absolutePath, wav.absolutePath, lang).trim()
                wav.delete()
                AppLog.i(TAG, "result: ${txt.take(60)}")
                if (txt.isEmpty() || txt.startsWith("ERROR") || AudioUtils.isNoSpeechText(txt)) {
                    cb.error(if (txt.startsWith("ERROR")) SpeechRecognizer.ERROR_CLIENT else SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    val res = Bundle()
                    res.putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(txt))
                    cb.results(res)
                }
            } catch (e: Throwable) {
                AppLog.e(TAG, "listen error: ${e.message}")
                try { cb.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
            }
        }.apply { isDaemon = true; start() }
    }

    private fun ContextCompatMissing(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onStopListening(callback: Callback?) {
        AppLog.i(TAG, "onStopListening")
        listening = false
    }

    override fun onCancel(callback: Callback?) {
        AppLog.i(TAG, "onCancel")
        listening = false
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        recorder = null
    }

    override fun onDestroy() {
        listening = false
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
