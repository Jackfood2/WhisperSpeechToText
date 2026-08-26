package com.whisperkeyboard

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

object AudioUtils {

    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Process-wide SHARED recorder. Samsung (and some other) HALs return an all-zero stream
     * when an AudioRecord is created seconds after another was released - and keep the mic
     * hostage when one is held open. One instance for the whole app, serialized by callers
     * (bubble and keyboard never record simultaneously), fixes both failure modes.
     */
    @Volatile private var sharedRec: AudioRecord? = null
    @Volatile private var sharedBt: Boolean? = null

    /** Returns the shared recorder, (re)building it only when missing/unhealthy/BT-mode changed. */
    fun createRecorder(context: Context? = null): AudioRecord = synchronized(this) {
        val useBt = context?.getSharedPreferences("whisper", Context.MODE_PRIVATE)?.getBoolean("bt_mic", false) == true
        sharedRec?.let { existing ->
            if (existing.state == AudioRecord.STATE_INITIALIZED && sharedBt == useBt) return existing
            try { existing.release() } catch (_: Exception) {}
            sharedRec = null
        }
        val rec = buildRecorder(useBt, context)
        if (rec.state == AudioRecord.STATE_INITIALIZED) { sharedRec = rec; sharedBt = useBt }
        rec
    }

    /** Hard reset - only for dead-stream recovery or final app teardown. */
    fun releaseSharedRecorder() = synchronized(this) {
        sharedRec?.let { try { it.release() } catch (_: Exception) {} }
        sharedRec = null
        sharedBt = null
    }

    private fun buildRecorder(useBt: Boolean, context: Context?): AudioRecord {
        val source = if (useBt) {
            // Try VOICE_COMMUNICATION for BT headset; fallback to MIC if fails
            try {
                // Start Bluetooth SCO if available
                context?.let {
                    val am = it.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    if (am.isBluetoothScoAvailableOffCall) {
                        try { am.startBluetoothSco() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else MediaRecorder.AudioSource.MIC

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = (minBuf * 4).coerceAtLeast(8192)
        return try {
            AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
        } catch (e: Exception) {
            // fallback to MIC if VOICE_COMMUNICATION fails
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufSize)
        }
    }
    // RMS in 0..1 (16-bit PCM)
    fun rms16(buffer: ByteArray, readBytes: Int): Double {
        if (readBytes < 2) return 0.0
        var sum = 0L
        var n = 0
        var i = 0
        while (i + 1 < readBytes) {
            val s = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
            sum += s * s
            n++
            i += 2
        }
        if (n == 0) return 0.0
        return sqrt(sum.toDouble() / n) / 32768.0
    }

    fun isNoSpeechText(text: String): Boolean {
        var t = text.trim().lowercase()
        if (t.isEmpty()) return true
        // strip bracket/paren decorations whisper adds for non-speech events
        t = t.replace(Regex("[\\[\\](){}]"), "").trim()
        val garbage = listOf(
            "blank", "blank audio", "silence", "[silence]", "music", "inaudible",
            "inaudible speech", "noise", "background noise", "static", "quiet",
            "you", "thank you.", ".", ".."
        )
        if (t in garbage) return true
        if (t.length <= 2 && t.all { it in ".,!?-_:;()[]{}" }) return true
        // e.g. "...", "-", "♪", repeated punctuation only
        if (t.isNotEmpty() && t.all { it in ".,!?-_:;()[]{}♪~ " }) return true
        return false
    }

    fun pcmToWav(pcmFile: File, wavFile: File) {
        val pcmData = pcmFile.readBytes()
        val pcmSize = pcmData.size
        val totalSize = pcmSize + 36
        val sampleRate = SAMPLE_RATE
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        FileOutputStream(wavFile).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(totalSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16))
            fos.write(shortToLittleEndian(1))
            fos.write(shortToLittleEndian(channels.toShort()))
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(blockAlign.toShort()))
            fos.write(shortToLittleEndian(bitsPerSample.toShort()))
            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(pcmSize))
            fos.write(pcmData)
        }
    }

    // In-memory PCM bytes -> WAV file
    fun pcmBytesToWavFile(pcmBytes: ByteArray, wavFile: File) {
        val pcmSize = pcmBytes.size
        val totalSize = pcmSize + 36
        val sampleRate = SAMPLE_RATE
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        FileOutputStream(wavFile).use { fos ->
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(totalSize))
            fos.write("WAVE".toByteArray())
            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16))
            fos.write(shortToLittleEndian(1))
            fos.write(shortToLittleEndian(channels.toShort()))
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(blockAlign.toShort()))
            fos.write(shortToLittleEndian(bitsPerSample.toShort()))
            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(pcmSize))
            fos.write(pcmBytes)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray = byteArrayOf((value and 0xFF).toByte(), (value shr 8 and 0xFF).toByte(), (value shr 16 and 0xFF).toByte(), (value shr 24 and 0xFF).toByte())
    private fun shortToLittleEndian(value: Short): ByteArray = byteArrayOf((value.toInt() and 0xFF).toByte(), (value.toInt() shr 8 and 0xFF).toByte())
}
