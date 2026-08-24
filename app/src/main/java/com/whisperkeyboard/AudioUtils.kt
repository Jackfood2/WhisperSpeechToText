package com.whisperkeyboard

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

object AudioUtils {

    const val SAMPLE_RATE = 16000
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

    fun createRecorder(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufSize = minBuf * 4
        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufSize
        )
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
            // RIFF header
            fos.write("RIFF".toByteArray())
            fos.write(intToLittleEndian(totalSize))
            fos.write("WAVE".toByteArray())

            // fmt sub-chunk
            fos.write("fmt ".toByteArray())
            fos.write(intToLittleEndian(16)) // Sub-chunk size
            fos.write(shortToLittleEndian(1)) // PCM format
            fos.write(shortToLittleEndian(channels.toShort()))
            fos.write(intToLittleEndian(sampleRate))
            fos.write(intToLittleEndian(byteRate))
            fos.write(shortToLittleEndian(blockAlign.toShort()))
            fos.write(shortToLittleEndian(bitsPerSample.toShort()))

            // data sub-chunk
            fos.write("data".toByteArray())
            fos.write(intToLittleEndian(pcmSize))
            fos.write(pcmData)
        }
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            (value shr 8 and 0xFF).toByte(),
            (value shr 16 and 0xFF).toByte(),
            (value shr 24 and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            (value.toInt() shr 8 and 0xFF).toByte()
        )
    }
}
