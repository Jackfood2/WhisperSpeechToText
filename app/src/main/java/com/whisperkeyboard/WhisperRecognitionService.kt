package com.whisperkeyboard

import android.annotation.SuppressLint
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import java.io.ByteArrayOutputStream
import java.io.File

class WhisperRecognitionService : RecognitionService() {

    private companion object { const val TAG = "WhisperRecog"; const val SAMPLE_RATE = 16000 }

    @Volatile private var captureRequested = false
    @Volatile private var cancelled = false
    private var recorder: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    override fun onStartListening(recognizerIntent: Intent?, callback: Callback?) {
        val cb = callback ?: return
        AppLog.i(TAG, "onStartListening")
        val engine = SttEngines.current(this)
        val model = getSharedPreferences("whisper", MODE_PRIVATE).getString("model", "small") ?: "small"
        if (!SttEngines.isReady(this, engine, model)) {
            AppLog.e(TAG, "$engine/$model not downloaded")
            cb.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }
        if (ContextCompatMissing()) { cb.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS); return }
        if (!MicSessionManager.tryAcquire(MicOwner.RECOGNITION_SERVICE)) {
            cb.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }
        captureRequested = true
        cancelled = false
        val lang = SttEngines.jobLang(this)
        thread = Thread {
            var pcm: ByteArrayOutputStream? = null
            try {
                cb.readyForSpeech(Bundle())
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) {
                    throw IllegalStateException("Unsupported audio configuration: getMinBufferSize=$minBuf")
                }
                val rec = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 32000))
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    rec.release()
                    throw IllegalStateException("Unable to initialize AudioRecord")
                }
                recorder = rec
                rec.startRecording()
                cb.beginningOfSpeech()
                val buf = ByteArray(4096)
                pcm = ByteArrayOutputStream()
                var lastVoice = android.os.SystemClock.elapsedRealtime()
                var hasVoice = false
                val start = android.os.SystemClock.elapsedRealtime()
                while (captureRequested && !cancelled) {
                    val n = rec.read(buf, 0, buf.size)
                    when {
                        n > 0 -> {
                            synchronized(pcm) { pcm.write(buf, 0, n) }
                            val rms = AudioUtils.rms16(buf, n)
                            if (rms > 0.018) { hasVoice = true; lastVoice = android.os.SystemClock.elapsedRealtime() }
                            val elapsed = android.os.SystemClock.elapsedRealtime() - start
                            if ((hasVoice && android.os.SystemClock.elapsedRealtime() - lastVoice > 1300) || elapsed > 30_000) break
                        }
                        n == AudioRecord.ERROR_DEAD_OBJECT ->
                            throw IllegalStateException("AudioRecord device disconnected")
                        n == AudioRecord.ERROR_INVALID_OPERATION ->
                            throw IllegalStateException("AudioRecord invalid operation")
                        else -> break
                    }
                }
                cb.endOfSpeech()
                try { rec.stop(); rec.release() } catch (_: Exception) {}
                recorder = null
                if (cancelled) return@Thread
                val bytes = synchronized(pcm) { pcm.toByteArray() }
                if (bytes.size < 1800) {
                    cb.error(SpeechRecognizer.ERROR_NO_MATCH)
                    return@Thread
                }
                val wav = File(cacheDir, "recog_${System.currentTimeMillis()}.wav")
                AudioUtils.pcmBytesToWavFile(bytes, wav)
                val txt = SttEngines.transcribe(this, engine, model, wav, lang)
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
            } finally {
                MicSessionManager.release(MicOwner.RECOGNITION_SERVICE)
            }
        }.apply { isDaemon = true; start() }
    }

    private fun ContextCompatMissing(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    override fun onStopListening(callback: Callback?) {
        AppLog.i(TAG, "onStopListening")
        captureRequested = false
        runCatching { recorder?.stop() }
    }

    override fun onCancel(callback: Callback?) {
        AppLog.i(TAG, "onCancel")
        cancelled = true
        captureRequested = false

        val current = recorder
        recorder = null

        runCatching { current?.stop() }
        runCatching { current?.release() }
    }

    override fun onDestroy() {
        cancelled = true
        captureRequested = false
        MicSessionManager.release(MicOwner.RECOGNITION_SERVICE)
        try { recorder?.stop(); recorder?.release() } catch (_: Exception) {}
        super.onDestroy()
    }
}
