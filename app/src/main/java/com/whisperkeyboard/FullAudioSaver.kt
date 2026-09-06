package com.whisperkeyboard

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Saves the FULL session audio next to the transcript while transcription runs.
 *
 * Why WAV + M4A and not MP3: Android ships NO MP3 encoder (MediaCodec can
 * decode MP3 but cannot encode it on virtually any device). Real MP3 would
 * require bundling LAME/FFmpeg as native code (extra ~1-2 MB, LGPL licensing,
 * NDK build complexity). Instead we offer:
 *  - WAV: lossless 16 kHz mono PCM, ~1.9 MB/min - plays everywhere, zero risk.
 *  - M4A (AAC-LC via hardware MediaCodec): ~0.24 MB/min, plays on
 *    Android/iOS/Windows. Falls back to WAV automatically if the encoder fails.
 *
 * Design: mic bytes are streamed to a temp .pcm in cache (never held in RAM -
 * a 1-hour meeting is ~115 MB), then finalized to Documents/WhisperNotes with
 * the same base name as the transcript (+ "_audio").
 */
class FullAudioSaver(
    private val context: Context,
    private val destDir: File,
    private val baseName: String
) {
    private val pcmTmp: File = File.createTempFile("${baseName}_full_", ".pcm", context.cacheDir)
    private var out: FileOutputStream? = FileOutputStream(pcmTmp, true)
    private var bytesWritten = 0L
    private var closed = false

    /** Append raw 16-bit mono 16 kHz bytes from the recorder loop. Cheap, thread-safe. */
    @Synchronized
    fun append(data: ByteArray, len: Int) {
        if (closed || len <= 0) return
        try {
            out?.write(data, 0, len)
            bytesWritten += len
        } catch (e: Exception) {
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    /**
     * Close the stream and write the final file. Runs on the caller's thread -
     * call from a background thread (M4A encode of a long meeting takes seconds).
     * Returns the saved file, or null if there was <0.5 s of audio / on failure.
     */
    fun finish(format: String): File? {
        synchronized(this) {
            if (closed) return null
            closed = true
            try { out?.flush(); out?.close() } catch (_: Exception) {}
            out = null
        }
        try {
            if (bytesWritten < MIN_BYTES) {
                Log.i(TAG, "too short (${bytesWritten}B) - discarded")
                pcmTmp.delete()
                return null
            }
            if (!destDir.exists()) destDir.mkdirs()
            val suffix = "_audio"
            val saved: File? = if (format == "wav") {
                val wav = File(destDir, "$baseName$suffix.wav")
                pcmFileToWav(pcmTmp, wav)
                wav
            } else {
                val m4a = File(destDir, "$baseName$suffix.m4a")
                try {
                    pcmFileToM4a(pcmTmp, m4a)
                    m4a
                } catch (e: Exception) {
                    Log.w(TAG, "AAC encode failed (${e.message}) - falling back to WAV")
                    AppLog.w(TAG, "AAC encode failed - WAV fallback: ${e.message}")
                    val wav = File(destDir, "$baseName$suffix.wav")
                    pcmFileToWav(pcmTmp, wav)
                    runCatching { if (m4a.exists()) m4a.delete() }
                    wav
                }
            }
            pruneAudio(destDir)
            Log.i(TAG, "saved ${saved?.name} (${(saved?.length() ?: 0) / 1024} KB from ${bytesWritten / 1024} KB pcm)")
            return saved
        } catch (e: Exception) {
            Log.e(TAG, "finish failed: ${e.message}")
            AppLog.e(TAG, "audio save failed: ${e.message}")
            return null
        } finally {
            runCatching { pcmTmp.delete() }
        }
    }

    /** Abandon the recording without saving (e.g. empty session). */
    fun discard() {
        synchronized(this) {
            closed = true
            try { out?.close() } catch (_: Exception) {}
            out = null
        }
        runCatching { pcmTmp.delete() }
    }

    companion object {
        private const val TAG = "FullAudio"
        private const val MIN_BYTES = 16000L // 0.5 s of 16 kHz 16-bit mono

        fun notesDir(): File {
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "WhisperNotes"
            )
            if (!d.exists()) d.mkdirs()
            return d
        }

        /** Keep storage bounded: max 50 audio files, drop anything older than 30 days. */
        fun pruneAudio(dir: File, keepNewest: Int = 50, maxAgeDays: Int = 30) {
            try {
                val files = dir.listFiles { f ->
                    f.isFile && (f.name.endsWith("_audio.wav") || f.name.endsWith("_audio.m4a"))
                }?.sortedByDescending { it.lastModified() } ?: return
                val cutoff = System.currentTimeMillis() - maxAgeDays * 24L * 3600L * 1000L
                files.forEachIndexed { i, f ->
                    if (i >= keepNewest || f.lastModified() < cutoff) {
                        if (f.delete()) Log.i(TAG, "pruned old audio: ${f.name}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "prune failed: ${e.message}")
            }
        }

        /** Delete orphaned full-session temp files from crashes/kills. */
        fun pruneTemp(ctx: Context) {
            try {
                ctx.cacheDir.listFiles { f -> f.name.endsWith("_full_.pcm") || f.name.contains("_full_") && f.name.endsWith(".pcm") }
                    ?.forEach { if (System.currentTimeMillis() - it.lastModified() > 24L * 3600L * 1000L) it.delete() }
            } catch (_: Exception) {}
        }

        private fun pcmFileToWav(pcm: File, wav: File) {
            val pcmSize = pcm.length()
            val totalSize = (pcmSize + 36).toInt()
            FileOutputStream(wav).use { fos ->
                fos.write("RIFF".toByteArray())
                fos.write(intLE(totalSize))
                fos.write("WAVE".toByteArray())
                fos.write("fmt ".toByteArray())
                fos.write(intLE(16))
                fos.write(shortLE(1))
                fos.write(shortLE(1))
                fos.write(intLE(AudioUtils.SAMPLE_RATE))
                fos.write(intLE(AudioUtils.SAMPLE_RATE * 2))
                fos.write(shortLE(2))
                fos.write(shortLE(16))
                fos.write("data".toByteArray())
                fos.write(intLE(pcmSize.toInt()))
                pcm.inputStream().use { it.copyTo(fos) }
            }
        }

        /**
         * Stream the PCM file through the hardware AAC encoder into .m4a.
         * 16 kHz mono AAC-LC @ 32 kbps - voice-transparent at ~0.24 MB/min.
         */
        private fun pcmFileToM4a(pcm: File, out: File) {
            val sampleRate = AudioUtils.SAMPLE_RATE
            val codec = MediaCodec.createEncoderByType("audio/mp4a-latm")
            try {
                val fmt = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 32000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                var muxer: MediaMuxer? = null
                try {
                    val info = MediaCodec.BufferInfo()
                    var trackIdx = -1
                    var muxerStarted = false
                    var inputEos = false
                    var outputEos = false
                    var ptsUs = 0L
                    pcm.inputStream().buffered().use { inp ->
                        val readBuf = ByteArray(2048)
                        while (!outputEos) {
                            if (!inputEos) {
                                val inIdx = codec.dequeueInputBuffer(5000)
                                if (inIdx >= 0) {
                                    val buf = codec.getInputBuffer(inIdx)
                                    if (buf == null) {
                                        codec.queueInputBuffer(inIdx, 0, 0, ptsUs, 0)
                                    } else {
                                        buf.clear()
                                        var n = 0
                                        val want = minOf(2048, buf.remaining())
                                        while (n < want) {
                                            val r = inp.read(readBuf, 0, minOf(readBuf.size, want - n))
                                            if (r <= 0) break
                                            buf.put(readBuf, 0, r)
                                            n += r
                                        }
                                        if (n > 0) {
                                            ptsUs += (n / 2 * 1_000_000L / sampleRate)
                                            // FileInputStream.available()==0 reliably means EOF for files
                                            val eof = inp.available() == 0
                                            codec.queueInputBuffer(inIdx, 0, n, ptsUs,
                                                if (eof) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                            if (eof) inputEos = true
                                        } else {
                                            codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                            inputEos = true
                                        }
                                    }
                                }
                            }
                            val outIdx = codec.dequeueOutputBuffer(info, 5000)
                            when {
                                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    if (!muxerStarted) {
                                        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                                        trackIdx = muxer!!.addTrack(codec.outputFormat)
                                        muxer!!.start()
                                        muxerStarted = true
                                    }
                                }
                                outIdx >= 0 -> {
                                    val outBuf = codec.getOutputBuffer(outIdx)
                                    if (outBuf != null && info.size > 0 && muxerStarted) {
                                        outBuf.position(info.offset)
                                        outBuf.limit(info.offset + info.size)
                                        muxer!!.writeSampleData(trackIdx, outBuf, info)
                                    }
                                    codec.releaseOutputBuffer(outIdx, false)
                                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputEos = true
                                }
                            }
                        }
                    }
                    if (muxerStarted) muxer!!.stop()
                    muxer?.release()
                    if (!muxerStarted) throw IllegalStateException("encoder produced no output")
                } catch (e: Exception) {
                    try { muxer?.release() } catch (_: Exception) {}
                    throw e
                }
            } finally {
                try { codec.stop() } catch (_: Exception) {}
                try { codec.release() } catch (_: Exception) {}
            }
        }

        private fun intLE(v: Int): ByteArray =
            byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte(), (v shr 16 and 0xFF).toByte(), (v shr 24 and 0xFF).toByte())

        private fun shortLE(v: Int): ByteArray =
            byteArrayOf((v and 0xFF).toByte(), (v shr 8 and 0xFF).toByte())
    }
}
