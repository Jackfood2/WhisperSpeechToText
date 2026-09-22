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

class FullAudioSaver(
    private val context: Context,
    private val destDir: File,
    private val baseName: String
) {
    private val pcmTmp: File = File.createTempFile("${baseName}_full_", ".pcm", context.cacheDir)
    private var out: FileOutputStream? = FileOutputStream(pcmTmp, true)
    private var bytesWritten = 0L
    private var closed = false

    @Synchronized
    fun append(data: ByteArray, len: Int) {
        if (closed || len <= 0) return

        val safeLength = len.coerceAtMost(data.size)
        if (safeLength <= 0) return

        try {
            val stream = out ?: return
            stream.write(data, 0, safeLength)
            bytesWritten += safeLength
        } catch (e: Exception) {
            Log.w(TAG, "Audio append failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

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
        private const val MIN_BYTES = 16000L

        fun notesDir(): File {
            val d = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "WhisperNotes"
            )
            if (!d.exists()) d.mkdirs()
            return d
        }

        fun privateNotesDir(ctx: Context): File {
            val d = File(ctx.getExternalFilesDir(null), "WhisperNotes")
            if (!d.exists() && !d.mkdirs()) {
                throw java.io.IOException(
                    "Unable to create directory: ${d.absolutePath}"
                )
            }

            if (!d.isDirectory) {
                throw java.io.IOException(
                    "Destination is not a directory: ${d.absolutePath}"
                )
            }
            return d
        }

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
                    var inputBytesRead = 0L
                    val inputLength = pcm.length()
                    val encodeDeadline =
                        android.os.SystemClock.elapsedRealtime() + 5L * 60L * 1000L
                    pcm.inputStream().buffered().use { inp ->
                        val readBuf = ByteArray(2048)
                        while (!outputEos) {
                            if (android.os.SystemClock.elapsedRealtime() >= encodeDeadline) {
                                throw IllegalStateException("AAC encoder timed out")
                            }
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
                                            inputBytesRead += n

                                            val endOfInput = inputBytesRead >= inputLength
                                            val flags = if (endOfInput) {
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            } else {
                                                0
                                            }

                                            codec.queueInputBuffer(
                                                inIdx,
                                                0,
                                                n,
                                                ptsUs,
                                                flags
                                            )

                                            ptsUs +=
                                                (n.toLong() / 2L) *
                                                1_000_000L /
                                                sampleRate

                                            if (endOfInput) {
                                                inputEos = true
                                            }
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
                                    if (
                                        info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                    ) {
                                        info.size = 0
                                    }
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
                    try {
                        if (muxerStarted) {
                            muxer?.stop()
                        }
                    } finally {
                        muxer?.release()
                        muxer = null
                    }
                    if (!muxerStarted) throw IllegalStateException("encoder produced no output")
                } catch (e: Exception) {
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
