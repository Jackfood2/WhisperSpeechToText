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

/**
 * Moonshine v2 engine - replaces WhisperEngine.
 * Uses ai.moonshine:moonshine-voice (Transcriber + MicTranscriber) for on-device streaming STT.
 * Same public API as WhisperEngine so TranscriptionQueue / IME need minimal changes.
 * File transcription via Transcriber.transcribeWithoutStreaming (offline), not mic.
 */
object MoonshineEngine {

    private const val TAG = "MoonshineEngine"
    private val lock = ReentrantLock()
    private val busyCount = AtomicInteger(0)

    @Volatile private var loadedModel: String? = null
    @Volatile private var loadedArch: Int = -1
    @Volatile var lastError: String = ""
        private set
    /**
     * Set by cancelCurrent(); consumed by transcribe()/ensureModel and mapped to
     * "ERROR: cancelled" so TranscriptionQueue drops the chunk instead of
     * retrying it and parking it in failed/ (parity with Whisper's nativeCancel).
     */
    private val cancelRequested = AtomicInteger(0)

    // The transcriber used for file transcription (offline)
    @Volatile private var transcriber: Transcriber? = null
    // Mic transcriber cache for download (also usable for transcription)
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
            // Try to set ONNX Runtime intra-op threads via reflection if available
            try {
                val cls = Class.forName("ai.onnxruntime.OrtEnvironment")
                // no direct API, fallback to env
            } catch (_: Exception) {}
            // Set env for native libs that read it
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
            if (cores >= 8) 6 else if (cores >= 4) 4 else cores
        } else pref.toIntOrNull()?.coerceIn(1, cores) ?: 4
        setThreads(n)
        return n
    }

    fun cancelCurrent() {
        AppLog.i(TAG, "cancelCurrent - interrupting")
        cancelRequested.set(1)
        // Best effort: close transcriber to unblock transcribeWithoutStreaming if stuck
        // It will be reloaded on next ensureModel
        try {
            // don't fully unload if busyCount>0 - just interrupt
            if (busyCount.get() > 0) {
                try { transcriber?.close() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private fun isTrLoaded(): Boolean =
        runCatching { transcriber?.isLoaded == true }.getOrDefault(false)

    /**
     * Load (downloading .ort bundles on first run) WITHOUT holding the engine
     * lock - the old code held it across mic.load(), stalling every transcribe/
     * unload on any thread for the whole download. Only the reference swap below
     * takes the lock.
     */
    fun ensureModel(context: Context, model: String, lang: String = "en"): Boolean {
        if (model.isBlank()) return false
        val arch = archFor(model)
        val langCode = langFor(lang)
        // fast check: lock held only for the integer/ref comparison
        val fastHit = lock.withLock { loadedModel == model && loadedArch == arch && isTrLoaded() }
        if (fastHit) return true
        cancelRequested.set(0) // new load generation
        AppLog.i(TAG, "ensureModel $model arch=$arch lang=$langCode")
        var mic: MicTranscriber? = null
        try {
            mic = MicTranscriber(context).language(langCode).modelArch(arch)
            mic.onProgress { fraction, file ->
                AppLog.i(TAG, "downloading $file ${(fraction*100).toInt()}%")
            }
            val t0 = System.currentTimeMillis()
            try {
                mic.load()
            } catch (e: Throwable) {
                lastError = e.message ?: "load failed"
                AppLog.e(TAG, "MicTranscriber load failed: ${e.message}")
                try { mic.close() } catch (_: Throwable) {}
                return false
            }
            val ms = System.currentTimeMillis() - t0
            AppLog.i(TAG, "MicTranscriber loaded $model in $ms ms")
            lock.withLock {
                // cancelled while downloading -> discard, do NOT install
                if (cancelRequested.get() != 0) {
                    try { mic?.close() } catch (_: Throwable) {}
                    cancelRequested.set(0)
                    lastError = "cancelled"
                    return false
                }
                // double-check after load (another thread may have installed it)
                if (loadedModel == model && loadedArch == arch && isTrLoaded()) {
                    try { mic?.close() } catch (_: Throwable) {}
                    return true
                }
                try { transcriber?.close() } catch (_: Throwable) {}
                try { micTranscriber?.close(); } catch (_: Throwable) {}
                // mic and transcriber share same object - keep single ref to avoid double-close alias
                transcriber = mic
                micTranscriber = mic
                mic = null // installed - must not be closed by the catch/finally below
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

    // Compatibility overload used by old callers: modelPath is ggml path, we map to model name
    fun ensureModel(modelPath: String): Boolean {
        // modelPath like /.../ggml-small.bin -> extract "small"
        val name = when {
            modelPath.contains("tiny") -> "tiny"
            modelPath.contains("base") -> "base"
            modelPath.contains("small") -> "small"
            modelPath.contains("medium") -> "medium"
            else -> "small"
        }
        // Need a context - try to get app context via WhisperApp if available
        val ctx = WhisperApp.holder
        return if (ctx != null) ensureModel(ctx, name) else false
    }

    fun isLoaded(model: String): Boolean = loadedModel == model && isTrLoaded()
    fun isLoaded(modelPath: String, dummy: Boolean): Boolean = isLoaded(modelPath) // keep compat
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
                // mic and transcriber are same object
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

    /**
     * Transcribe WAV file using Moonshine.
     * Reads WAV, converts to float PCM [-1,1], calls transcribeWithoutStreaming.
     */
    fun transcribe(modelPath: String, wavPath: String, lang: String): String {
        busyCount.incrementAndGet()
        cancelRequested.set(0) // new utterance generation; a cancel landing mid-call is detected below
        val t0 = System.currentTimeMillis()
        try {
            lock.withLock {
                try {
                    // Resolve model name from path or from loadedModel
                    val modelName = when {
                        modelPath.contains("ggml-tiny") || modelPath.contains("tiny") -> "tiny"
                        modelPath.contains("ggml-base") || modelPath.contains("base") -> "base"
                        modelPath.contains("ggml-medium") || modelPath.contains("medium") -> "medium"
                        else -> loadedModel ?: "small"
                    }
                    val ctx = WhisperApp.holder
                    if (transcriber == null || loadedModel != modelName) {
                        if (ctx != null) {
                            val ok = ensureModel(ctx, modelName, lang)
                            if (!ok) {
                                if (cancelRequested.get() != 0 || lastError == "cancelled") return "ERROR: cancelled"
                                return "ERROR: Failed to load moonshine model $modelName: $lastError"
                            }
                        } else {
                            return "ERROR: No context to load model"
                        }
                    }
                    val tr = transcriber ?: return "ERROR: Transcriber not loaded"
                    if (cancelRequested.get() != 0) return "ERROR: cancelled"

                    // Read WAV -> float[]
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
                    val ms = System.currentTimeMillis() - t0
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

    // overload used by callers that pass model name directly
    fun transcribeWithModel(model: String, wavPath: String, lang: String, ctx: Context): String {
        return transcribe("ggml-$model.bin", wavPath, lang)
    }

    private fun readWavAsFloats(wav: File): FloatArray? {
        // Guard large files to avoid OOM (50MB ~ 15min 16kHz mono)
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
                // chunked header: find "data" tag properly without scanning whole file byte-by-byte
                if (String(hdr.sliceArray(36..39)) != "data") {
                    // parse chunks: while offset+8 < file size
                    var offset = 12
                    fis.channel.position(12)
                    val chunkHdr = ByteArray(8)
                    while (fis.read(chunkHdr) == 8) {
                        val tag = String(chunkHdr.sliceArray(0..3))
                        val sz = le32(chunkHdr, 4)
                        if (tag == "data") { dataOffset = offset + 8; dataSize = sz; break }
                        // skip chunk
                        fis.channel.position(fis.channel.position() + sz)
                        offset += 8 + sz
                        if (offset > 1024) break // header shouldn't be that large
                    }
                    fis.channel.position(dataOffset.toLong())
                }
                val pcmBytes = (wav.length() - dataOffset).toInt().coerceAtLeast(0)
                val numSamples = when (bits) {
                    16 -> pcmBytes / 2 / maxOf(1, channels)
                    32 -> pcmBytes / 4 / maxOf(1, channels)
                    else -> pcmBytes / 2 / maxOf(1, channels)
                }
                // stream 32KB chunks to avoid double copy
                val floats = FloatArray(numSamples)
                val buf = ByteArray(32 * 1024)
                var outIdx = 0
                var leftover = ByteArray(0)
                // handle stereo averaging via sample loop
                // For streaming we need to handle partial samples across buffer boundaries
                // Simpler: read all remaining via buffered stream and convert per sample
                // Use 16-bit path
                var bytesRead: Int
                var carry = 0
                // For 16-bit stereo, need 4 bytes per frame; for mono 2 bytes
                val frameBytes = if (channels == 2 && bits == 16) 4 else 2
                var tmp = ByteArray(0)
                // Reset to data start
                fis.channel.position(dataOffset.toLong())
                var totalRead = 0
                while (fis.read(buf).also { bytesRead = it } > 0) {
                    totalRead += bytesRead
                    // combine with leftover
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
                    val ratio = sampleRate / 16000f
                    val newSize = (actual.size / ratio).toInt()
                    val res = FloatArray(newSize)
                    for (i in 0 until newSize) {
                        val src = (i * ratio).toInt().coerceIn(0, actual.size - 1)
                        res[i] = actual[src]
                    }
                    return res
                }
                return actual
            }
        } catch (e: Throwable) {
            AppLog.e(TAG, "readWav failed: ${e.message}")
            return null
        }
    }
}
