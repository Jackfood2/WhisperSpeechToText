package com.whisperkeyboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {

    private fun urlFor(model: String): String {
        return when (model) {
            "tiny" -> "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin"
            "base" -> "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin"
            "small" -> "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
            "medium" -> "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium.bin"
            else -> "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
        }
    }

    fun modelsDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(ctx: Context, model: String): File {
        return File(modelsDir(ctx), "ggml-$model.bin")
    }

    /** Official ggml sizes (bytes, HuggingFace ggerganov/whisper.cpp). */
    fun expectedBytes(model: String): Long = when (model) {
        "tiny" -> 77_691_713L      // ~74 MB
        "base" -> 147_951_465L     // ~141 MB
        "small" -> 487_601_967L    // ~465 MB
        "medium" -> 1_533_763_059L // ~1.43 GB
        else -> 0L
    }

    /**
     * A download counts as complete only if it reached ~its official size.
     * The old >1MB check blessed truncated files (e.g. a 426/465MB small),
     * which then failed at native load with a cryptic "1 failed".
     */
    fun isComplete(ctx: Context, model: String): Boolean {
        return try {
            val f = modelFile(ctx, model)
            if (!f.exists()) return false
            val exp = expectedBytes(model)
            if (exp <= 0) return f.length() > 1_000_000
            f.length() >= (exp * 0.98).toLong()
        } catch (_: Exception) { false }
    }

    /** Human shortfall description, or null when complete/missing. */
    fun shortfall(ctx: Context, model: String): String? {
        return try {
            val f = modelFile(ctx, model)
            if (!f.exists()) return null
            if (isComplete(ctx, model)) return null
            "${f.length() / 1024 / 1024}/${expectedBytes(model) / 1024 / 1024} MB"
        } catch (_: Exception) { null }
    }

    fun localStatus(ctx: Context, model: String): String {
        val f = modelFile(ctx, model)
        return when {
            isComplete(ctx, model) -> "Ready: ggml-$model.bin (${f.length() / 1024 / 1024} MB)"
            f.exists() -> "INCOMPLETE ${shortfall(ctx, model)} - tap Download to re-download (WiFi)"
            else -> "Not downloaded - tap Download (WiFi recommended)"
        }
    }

    fun download(ctx: Context, model: String, onProgress: (Int, String) -> Unit) {
        val outFile = modelFile(ctx, model)
        if (isComplete(ctx, model)) {
            onProgress(100, "Already downloaded: ggml-${model}.bin")
            return
        }
        // Drop any truncated previous attempt so it can never pass as complete.
        try { if (outFile.exists()) outFile.delete() } catch (_: Exception) {}

        val url = URL(urlFor(model))
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.connect()

        val totalSize = conn.contentLengthLong
        val inputStream = conn.inputStream
        val outputStream = FileOutputStream(outFile)

        val buffer = ByteArray(128 * 1024)
        var totalRead = 0L
        var lastProgress = 0

        try {
            while (true) {
                val bytesRead = inputStream.read(buffer)
                if (bytesRead == -1) break
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                if (totalSize > 0) {
                    val progress = (totalRead * 100 / totalSize).toInt()
                    if (progress != lastProgress) {
                        lastProgress = progress
                        val sizeMB = totalRead / 1024 / 1024
                        val totalMB = totalSize / 1024 / 1024
                        onProgress(progress, "Downloading $model: ${progress}% (${sizeMB}/${totalMB} MB)")
                    }
                } else {
                    val sizeMB = totalRead / 1024 / 1024
                    onProgress(0, "Downloading $model: ${sizeMB} MB received...")
                }
            }
        } finally {
            outputStream.close()
            inputStream.close()
            conn.disconnect()
        }

            // Verify file reached its official size (a stall/truncate must NOT pass)
        val exp = expectedBytes(model)
        if (!outFile.exists() || (exp > 0 && outFile.length() < (exp * 0.98).toLong())) {
            val got = if (outFile.exists()) "${outFile.length() / 1024 / 1024} MB" else "nothing"
            outFile.delete()
            throw RuntimeException("Download incomplete (got $got, expected ${exp / 1024 / 1024} MB) - retry on stable WiFi")
        }
        if (exp <= 0 && (!outFile.exists() || outFile.length() < 1_000_000)) {
            outFile.delete()
            throw RuntimeException("Download failed or file incomplete")
        }
    }

    // ---------- Moonshine v2 (.ort bundles, auto-downloaded by moonshine-voice) ----------

    /** Human-readable sizes for Settings UI (official ggml sizes). */
    fun whisperSize(model: String): String = when (model) {
        "tiny" -> "~74 MB"; "base" -> "~141 MB"; "small" -> "~465 MB"; "medium" -> "~1.43 GB"; else -> ""
    }

    fun moonshineSize(model: String): String = when (model) {
        "tiny" -> "~34 MB"; "base" -> "~60 MB"; "small" -> "~123 MB"; "medium" -> "~245 MB"; else -> ""
    }

    /** Marker file: real .ort bundles live in moonshine-voice ModelCache (app-private). */
    fun moonshineMarker(ctx: Context, model: String): File {
        return File(modelsDir(ctx), "moonshine-$model.marker")
    }

    fun isMoonshineReady(ctx: Context, model: String): Boolean {
        if (moonshineMarker(ctx, model).exists()) return true
        return try {
            val cacheRoot = ai.moonshine.voice.ModelCache.defaultRoot(ctx)
            cacheRoot.listFiles()?.any { it.name.contains(model, ignoreCase = true) } == true
        } catch (_: Throwable) { false }
    }

    fun moonshineStatus(ctx: Context, model: String): String {
        val arch = when (model) {
            "tiny" -> "Tiny Streaming v2"; "base" -> "Base Streaming"
            "small" -> "Small Streaming v2"; "medium" -> "Medium Streaming v2"; else -> model
        }
        return if (isMoonshineReady(ctx, model) || MoonshineEngine.isLoaded(model)) {
            "Ready: $arch ${moonshineSize(model)} - cached on-device (English only)"
        } else {
            "Not downloaded - tap Download (WiFi recommended, ${moonshineSize(model)})"
        }
    }

    /** Download = load via MoonshineEngine (fetches .ort bundles on first run). */
    fun downloadMoonshine(ctx: Context, model: String, onProgress: (Int, String) -> Unit) {
        if (isMoonshineReady(ctx, model) && MoonshineEngine.isLoaded(model)) {
            onProgress(100, "Already downloaded: moonshine-$model")
            return
        }
        onProgress(5, "Preparing $model (${moonshineSize(model)})...")
        onProgress(15, "Downloading $model - first run may take a minute...")
        val ok = MoonshineEngine.ensureModel(ctx, model, "en")
        if (!ok) throw RuntimeException(MoonshineEngine.lastError.ifEmpty { "Model load failed" })
        try {
            moonshineMarker(ctx, model).writeText("moonshine-$model cached at ${System.currentTimeMillis()}")
        } catch (_: Throwable) {}
        onProgress(100, "Ready: moonshine-$model (${moonshineSize(model)})")
    }

    /** Delete whisper .bin files AND moonshine markers (the .ort cache itself is app-private). */
    fun clearAllModels(ctx: Context): Pair<Int, Long> {
        val dir = modelsDir(ctx)
        val files = dir.listFiles() ?: emptyArray()
        var freed = 0L
        for (f in files) {
            if (f.name.startsWith("ggml-") || f.name.startsWith("moonshine-")) {
                freed += f.length()
                f.delete()
            }
        }
        return Pair(files.size, freed)
    }
}
