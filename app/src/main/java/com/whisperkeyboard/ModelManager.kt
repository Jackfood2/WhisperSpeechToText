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

    fun localStatus(ctx: Context, model: String): String {
        val f = modelFile(ctx, model)
        return if (f.exists() && f.length() > 1_000_000) {
            "Ready: ggml-$model.bin (${f.length() / 1024 / 1024} MB)"
        } else {
            "Not downloaded - tap Download (WiFi recommended)"
        }
    }

    fun download(ctx: Context, model: String, onProgress: (Int, String) -> Unit) {
        val outFile = modelFile(ctx, model)
        if (outFile.exists() && outFile.length() > 1_000_000) {
            onProgress(100, "Already downloaded: ggml-${model}.bin")
            return
        }

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

            // Verify file was written
        if (!outFile.exists() || outFile.length() < 1_000_000) {
            outFile.delete()
            throw RuntimeException("Download failed or file incomplete")
        }
    }

    // ---------- Moonshine v2 (.ort bundles, auto-downloaded by moonshine-voice) ----------

    /** Human-readable sizes for Settings UI. */
    fun whisperSize(model: String): String = when (model) {
        "tiny" -> "~75 MB"; "base" -> "~142 MB"; "small" -> "~244 MB"; "medium" -> "~769 MB"; else -> ""
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
