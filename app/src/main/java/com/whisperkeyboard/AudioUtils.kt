package com.whisperkeyboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt

object AudioUtils {

    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var sharedRec: AudioRecord? = null
    @Volatile private var sharedBt: Boolean? = null
    @Volatile private var sharedHandedOut: Boolean = false

    fun createRecorder(context: Context? = null): AudioRecord = synchronized(this) {
        val useBt = context?.getSharedPreferences("whisper", Context.MODE_PRIVATE)?.getBoolean("bt_mic", false) == true
        sharedRec?.let { existing ->
            if (existing.state == AudioRecord.STATE_INITIALIZED && sharedBt == useBt && !sharedHandedOut) {
                return existing
            }
            // AudioRecord exposes no public flush(): a stopped-but-not-released
            // instance replays whatever it buffered from the previous session.
            // Build a fresh one per recording session instead of reusing it.
            try { existing.release() } catch (_: Exception) {}
            sharedRec = null
            sharedBt = null
            sharedHandedOut = false
        }
        if (!useBt) stopBluetoothSco(context)
        val rec = buildRecorder(useBt, context)
        if (rec.state == AudioRecord.STATE_INITIALIZED) {
            sharedRec = rec
            sharedBt = useBt
            sharedHandedOut = true
        }
        rec
    }

    fun releaseSharedRecorder() = synchronized(this) {
        sharedRec?.let { try { it.release() } catch (_: Exception) {} }
        sharedRec = null
        sharedBt = null
        sharedHandedOut = false
        // Leaving the SCO link up after recording is a measurable idle drain.
        stopBluetoothSco(WhisperApp.holder)
    }

    private fun stopBluetoothSco(ctx: Context?) {
        try {
            val am = ctx?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (am.isBluetoothScoOn) {
                am.stopBluetoothSco()
                am.isBluetoothScoOn = false
            }
        } catch (_: Exception) {}
    }

    private fun buildRecorder(useBt: Boolean, context: Context?): AudioRecord {
        val source = if (useBt) {

            try {

                context?.let {
                    val am = it.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val canSco = if (Build.VERSION.SDK_INT >= 31) {
                        ContextCompat.checkSelfPermission(it, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                    } else {
                        true
                    }
                    if (am.isBluetoothScoAvailableOffCall && canSco) {
                        try { am.startBluetoothSco() } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else MediaRecorder.AudioSource.MIC

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (minBuffer <= 0) {
            throw IllegalStateException(
                "Unsupported audio configuration: getMinBufferSize=$minBuffer"
            )
        }

        val bufferSize = maxOf(minBuffer * 4, 8192)

        @SuppressLint("MissingPermission")
        fun create(audioSource: Int): AudioRecord {
            return AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }

        val preferred = runCatching { create(source) }.getOrNull()

        if (preferred?.state == AudioRecord.STATE_INITIALIZED) {
            return preferred
        }

        runCatching { preferred?.release() }

        val fallback = create(MediaRecorder.AudioSource.MIC)

        if (fallback.state != AudioRecord.STATE_INITIALIZED) {
            fallback.release()
            throw IllegalStateException("Unable to initialize AudioRecord")
        }

        return fallback
    }

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

        t = t.replace(Regex("[\\[\\](){}]"), "").trim()
        val garbage = listOf(
            "blank", "blank audio", "silence", "[silence]", "music", "inaudible",
            "inaudible speech", "noise", "background noise", "static", "quiet",
            "you", "thank you.", ".", ".."
        )
        if (t in garbage) return true
        if (t.length <= 2 && t.all { it in ".,!?-_:;()[]{}" }) return true

        if (t.isNotEmpty() && t.all { it in ".,!?-_:;()[]{}♪~ " }) return true
        return false
    }

    fun pcmToWav(pcmFile: File, wavFile: File) {
        // Stream, do not readBytes(): a 60 s chunk used to be copied into a fresh
        // ~1.9 MB ByteArray on top of the buffer the caller already held.
        val pcmSize = pcmFile.length().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val totalSize = pcmSize + 36
        val sampleRate = SAMPLE_RATE
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        java.io.BufferedOutputStream(FileOutputStream(wavFile), 64 * 1024).use { fos ->
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
            pcmFile.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    fos.write(buf, 0, n)
                }
            }
        }
    }

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
