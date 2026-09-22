package com.whisperkeyboard

import android.content.Context
import ai.moonshine.voice.JNI
import ai.moonshine.voice.MicTranscriber
import ai.moonshine.voice.Transcriber
import ai.moonshine.voice.Transcript
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object MoonshineEngine {

    private const val TAG = "MoonshineEngine"
    private val lock = ReentrantLock()
    private val busyCount = AtomicInteger(0)

    @Volatile private var loadedModel: String? = null
    @Volatile private var loadedArch: Int = -1
    @Volatile var lastError: String = ""
        private set

    private val cancelRequested = AtomicInteger(0)

    @Volatile private var transcriber: Transcriber? = null

    @Volatile private var micTranscriber: MicTranscriber? = null

    private fun archFor(model: String): Int = when (model) {
        "tiny" -> JNI.MOONSHINE_MODEL_ARCH_TINY_STREAMING
        "base" -> JNI.MOONSHINE_MODEL_ARCH_BASE_STREAMING
        "small" -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
        "medium" -> JNI.MOONSHINE_MODEL_ARCH_MEDIUM_STREAMING
        "tiny-legacy" -> JNI.MOONSHINE_MODEL_ARCH_TINY
        "base-legacy" -> JNI.MOONSHINE_MODEL_ARCH_BASE
        else -> JNI.MOONSHINE_MODEL_ARCH_SMALL_STREAMING
    }

    private fun langFor(lang: String): String = when (lang) {
        "auto", "" -> "en"
        "en", "zh", "ja", "ko", "fr", "de", "es" -> lang
        else -> "en"
    }

    fun setThreads(threads: Int) {
        AppLog.i(TAG, "setThreads($threads)")
        try {

            try {
                val cls = Class.forName("ai.onnxruntime.OrtEnvironment")

            } catch (_: Exception) {}

            try { System.setProperty("onnx.intra_op_num_threads", threads.toString()) } catch (_: Exception) {}
            try { android.system.Os.setenv("OMP_NUM_THREADS", threads.toString(), true) } catch (_: Exception) {}
            try { android.system.Os.setenv("MKL_NUM_THREADS", threads.toString(), true) } catch (_: Exception) {}
        } catch (e: Exception) {
            AppLog.w(TAG, "setThreads failed: ${e.message}")
        }
    }

    fun applyThreadPref(ctx: Context?): Int {
        if (ctx == null) return 4
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val pref = ctx.getSharedPreferences("whisper", android.content.Context.MODE_PRIVATE).getString("threads_mode", "auto") ?: "auto"
        val n = if (pref == "auto") {
            SttEngines.automaticThreadCount()
        } else pref.toIntOrNull()?.coerceIn(1, cores) ?: 4
        setThreads(n)
        return n
    }

    fun cancelCurrent() {
        AppLog.i(TAG, "Cancellation requested")
        cancelRequested.set(1)
    }

    private fun isTrLoaded(): Boolean =
        runCatching { transcriber?.isLoaded == true }.getOrDefault(false)

    fun ensureModel(context: Context, model: String, lang: String = "en"): Boolean {
        if (model.isBlank()) return false
        val arch = archFor(model)
        val langCode = langFor(lang)

        val fastHit = lock.withLock { loadedModel == model && loadedArch == arch && isTrLoaded() }
        if (fastHit) return true
        cancelRequested.set(0)
        AppLog.i(TAG, "ensureModel $model arch=$arch lang=$langCode")

        val appCtx = context.applicationContext
        var mic: MicTranscriber? = null
        try {
            mic = MicTranscriber(appCtx).language(langCode).modelArch(arch)
            mic.onProgress { fraction, file ->
                AppLog.i(TAG, "downloading $file ${(fraction*100).toInt()}%")
            }
            val t0 = android.os.SystemClock.elapsedRealtime()
            try {
                mic.load()
            } catch (e: Throwable) {
                lastError = e.message ?: "load failed"
                AppLog.e(TAG, "MicTranscriber load failed: ${e.message}")
                try { mic.close() } catch (_: Throwable) {}
                return false
            }
            val ms = android.os.SystemClock.elapsedRealtime() - t0
            AppLog.i(TAG, "MicTranscriber loaded $model in $ms ms")
            lock.withLock {

                if (cancelRequested.get() != 0) {
                    try { mic?.close() } catch (_: Throwable) {}
                    cancelRequested.set(0)
                    lastError = "cancelled"
                    return false
                }

                if (loadedModel == model && loadedArch == arch && isTrLoaded()) {
                    try { mic?.close() } catch (_: Throwable) {}
                    return true
                }
                try { transcriber?.close() } catch (_: Throwable) {}
                try { micTranscriber?.close(); } catch (_: Throwable) {}

                transcriber = mic
                micTranscriber = mic
                mic = null
                loadedModel = model
                loadedArch = arch
                lastError = ""
                ModelNotifier.loaded("moonshine-$model", 0)
                return true
            }
        } catch (e: Throwable) {
            lastError = e.message ?: "load error"
            AppLog.e(TAG, "ensureModel error: ${e.message}")
            try { mic?.close() } catch (_: Throwable) {}
            return false
        }
    }

    fun ensureModel(modelPath: String): Boolean {

        val name = when {
            modelPath.contains("tiny") -> "tiny"
            modelPath.contains("base") -> "base"
            modelPath.contains("small") -> "small"
            modelPath.contains("medium") -> "medium"
            else -> "small"
        }

        val ctx = WhisperApp.holder
        return if (ctx != null) ensureModel(ctx, name) else false
    }

    fun isLoaded(model: String): Boolean = loadedModel == model && isTrLoaded()
    fun isLoaded(modelPath: String, dummy: Boolean): Boolean = isLoaded(modelPath)
    fun loadedModel(): String? = loadedModel
    fun isBusy(): Boolean = busyCount.get() > 0

    fun unloadIfIdle(): Boolean {
        lock.withLock {
            if (busyCount.get() > 0) {
                AppLog.i(TAG, "skip unload - busy")
                return false
            }
            return try {
                val was = loadedModel
                try { transcriber?.close() } catch (_: Throwable) {}

                transcriber = null
                micTranscriber = null
                loadedModel = null
                loadedArch = -1
                cancelRequested.set(0)
                AppLog.i(TAG, "unloaded $was")
                ModelNotifier.unloaded(was)
                true
            } catch (e: Throwable) {
                AppLog.w(TAG, "unload failed: ${e.message}")
                false
            }
        }
    }

    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        busyCount.incrementAndGet()
        cancelRequested.set(0)
        val t0 = System.currentTimeMillis()
        try {
            val modelName = when {
                modelPath.contains("ggml-tiny") || modelPath.contains("tiny") -> "tiny"
                modelPath.contains("ggml-base") || modelPath.contains("base") -> "base"
                modelPath.contains("ggml-medium") || modelPath.contains("medium") -> "medium"
                else -> loadedModel ?: "small"
            }
            if (transcriber == null || loadedModel != modelName) {
                val ctx = WhisperApp.holder
                    ?: return "ERROR: No context to load model"
                if (!ensureModel(ctx, modelName, lang)) {
                    return if (
                        cancelRequested.get() != 0 ||
                        lastError == "cancelled"
                    ) {
                        "ERROR: cancelled"
                    } else {
                        "ERROR: Failed to load moonshine model " +
                            "$modelName: $lastError"
                    }
                }
            }
            lock.withLock {
                try {
                    val tr = transcriber ?: return "ERROR: Transcriber not loaded"
                    if (cancelRequested.get() != 0) return "ERROR: cancelled"

                    val wavFile = File(wavPath)
                    if (!wavFile.exists()) return "ERROR: WAV not found $wavPath"
                    val pcmFloats = readWavAsFloats(wavFile) ?: return "ERROR: Invalid WAV"
                    if (pcmFloats.isEmpty()) return ""
                    if (cancelRequested.get() != 0) return "ERROR: cancelled"

                    val transcript: Transcript = try {
                        tr.transcribeWithoutStreaming(pcmFloats, 16000)
                    } catch (e: Throwable) {
                        AppLog.e(TAG, "transcribeWithoutStreaming threw: ${e.message}")
                        if (cancelRequested.get() != 0) return "ERROR: cancelled"
                        return "ERROR: ${e.message}"
                    }
                    if (cancelRequested.getAndSet(0) != 0) return "ERROR: cancelled"
                    val text = transcript.text()?.trim() ?: ""
                    val ms = android.os.SystemClock.elapsedRealtime() - t0
                    AppLog.i(TAG, "moonshine transcribed in $ms ms -> ${text.take(60)}")
                    lastError = ""
                    if (text.isEmpty() || AudioUtils.isNoSpeechText(text)) return ""
                    return text
                } catch (e: OutOfMemoryError) {
                    lastError = "Out of memory"
                    AppLog.e(TAG, "OOM during transcribe")
                    try { transcriber?.close(); transcriber=null; micTranscriber=null; loadedModel=null } catch (_: Throwable) {}
                    return "ERROR: Out of memory - try smaller model"
                } catch (e: Throwable) {
                    lastError = e.message ?: "transcribe error"
                    AppLog.e(TAG, "Transcribe error: ${e.message}")
                    return "ERROR: ${e.message}"
                }
            }
        } finally {
            busyCount.decrementAndGet()
        }
    }

    fun transcribeWithModel(model: String, wavPath: String, lang: String, ctx: Context): String {
        return transcribe("ggml-$model.bin", wavPath, lang)
    }

    private fun resampleLinear(
        input: FloatArray,
        sourceRate: Int,
        targetRate: Int
    ): FloatArray {
        if (
            input.isEmpty() ||
            sourceRate <= 0 ||
            targetRate <= 0 ||
            sourceRate == targetRate
        ) {
            return input
        }

        val outputSize =
            ((input.size.toLong() * targetRate) / sourceRate)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()

        if (outputSize <= 0) return FloatArray(0)

        val output = FloatArray(outputSize)
        val ratio = sourceRate.toDouble() / targetRate.toDouble()

        for (i in output.indices) {
            val sourcePosition = i * ratio
            val left = sourcePosition.toInt()
                .coerceIn(0, input.lastIndex)
            val right = (left + 1)
                .coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - left).toFloat()

            output[i] =
                input[left] +
                (input[right] - input[left]) * fraction
        }

        return output
    }

    private fun readWavAsFloats(wav: File): FloatArray? {

        if (wav.length() > 50L * 1024 * 1024) {
            AppLog.e(TAG, "WAV too large ${wav.length()} bytes")
            return null
        }
        try {
            java.io.FileInputStream(wav).use { fis ->
                val hdr = ByteArray(44)
                if (fis.read(hdr) != 44) return null
                fun le16(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off+1].toInt() and 0xFF) shl 8)
                fun le32(b: ByteArray, off: Int) = (b[off].toInt() and 0xFF) or ((b[off+1].toInt() and 0xFF) shl 8) or ((b[off+2].toInt() and 0xFF) shl 16) or ((b[off+3].toInt() and 0xFF) shl 24)
                var channels = le16(hdr, 22)
                var sampleRate = le32(hdr, 24)
                var bits = le16(hdr, 34)
                var dataSize = le32(hdr, 40)
                var dataOffset = 44

                if (String(hdr.sliceArray(36..39)) != "data") {

                    var offset = 12
                    fis.channel.position(12)
                    val chunkHdr = ByteArray(8)
                    while (fis.read(chunkHdr) == 8) {
                        val tag = String(chunkHdr.sliceArray(0..3))
                        val sz = le32(chunkHdr, 4)
                        if (tag == "data") { dataOffset = offset + 8; dataSize = sz; break }

                        fis.channel.position(fis.channel.position() + sz)
                        offset += 8 + sz
                        if (offset > 1024) break
                    }
                    fis.channel.position(dataOffset.toLong())
                }
                if (channels !in 1..2) {
                    AppLog.e(TAG, "Unsupported WAV channels: $channels")
                    return null
                }

                if (bits != 16) {
                    AppLog.e(TAG, "Unsupported WAV bit depth: $bits")
                    return null
                }

                if (sampleRate <= 0) {
                    AppLog.e(TAG, "Invalid WAV sample rate: $sampleRate")
                    return null
                }

                val safeDataSize = minOf(
                    dataSize.toLong().coerceAtLeast(0L),
                    wav.length() - dataOffset
                )

                if (safeDataSize <= 0L || safeDataSize > Int.MAX_VALUE) {
                    return null
                }

                val pcmBytes = safeDataSize.toInt()
                val numSamples = pcmBytes / 2 / maxOf(1, channels)

                val floats = FloatArray(numSamples)
                val buf = ByteArray(32 * 1024)
                var outIdx = 0
                var leftover = ByteArray(0)

                var carry = 0

                val frameBytes = if (channels == 2 && bits == 16) 4 else 2
                var tmp = ByteArray(0)

                fis.channel.position(dataOffset.toLong())
                var totalRead = 0
                var dataRemaining = pcmBytes
                while (dataRemaining > 0) {
                    val bytesRead = fis.read(buf, 0, minOf(buf.size, dataRemaining))
                    if (bytesRead <= 0) break
                    dataRemaining -= bytesRead
                    totalRead += bytesRead

                    val combined: ByteArray
                    val combinedSize: Int
                    if (tmp.isNotEmpty()) {
                        combined = ByteArray(tmp.size + bytesRead)
                        System.arraycopy(tmp, 0, combined, 0, tmp.size)
                        System.arraycopy(buf, 0, combined, tmp.size, bytesRead)
                        combinedSize = tmp.size + bytesRead
                        tmp = ByteArray(0)
                    } else {
                        combined = buf
                        combinedSize = bytesRead
                    }
                    val completeFrames = combinedSize / frameBytes
                    val bytesToProcess = completeFrames * frameBytes
                    for (i in 0 until completeFrames) {
                        val off = i * frameBytes
                        val s = if (channels == 2 && bits == 16) {
                            val l = (((combined[off+1].toInt() and 0xFF) shl 8) or (combined[off].toInt() and 0xFF)).toShort().toInt()
                            val r = (((combined[off+3].toInt() and 0xFF) shl 8) or (combined[off+2].toInt() and 0xFF)).toShort().toInt()
                            (l + r) / 2
                        } else {
                            (((combined[off+1].toInt() and 0xFF) shl 8) or (combined[off].toInt() and 0xFF)).toShort().toInt()
                        }
                        if (outIdx < floats.size) floats[outIdx++] = s / 32768f
                    }
                    val leftoverBytes = combinedSize - bytesToProcess
                    if (leftoverBytes > 0) {
                        tmp = combined.copyOfRange(bytesToProcess, combinedSize)
                    }
                    if (outIdx >= numSamples) break
                }
                val actual = if (outIdx < floats.size) floats.copyOf(outIdx) else floats
                if (sampleRate != 16000 && actual.isNotEmpty()) {
                    return resampleLinear(actual, sampleRate, 16000)
                }
                return actual
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "readWav failed: ${e.message}")
            return null
        }
    }
}
