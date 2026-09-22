package com.whisperkeyboard

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object ModelManager {

    private fun urlFor(model: String): String {
        val fileName = when (model) {
            "tiny" -> "ggml-tiny.bin"
            "base" -> "ggml-base.bin"
            "small" -> "ggml-small.bin"
            "medium" -> "ggml-medium.bin"
            else -> throw IllegalArgumentException("Unsupported model: $model")
        }

        return "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$fileName"
    }

    fun modelsDir(ctx: Context): File {
        val dir = File(ctx.getExternalFilesDir(null), "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun modelFile(ctx: Context, model: String): File {
        return File(modelsDir(ctx), "ggml-$model.bin")
    }

    fun expectedBytes(model: String): Long = when (model) {
        "tiny" -> 77_691_713L
        "base" -> 147_951_465L
        "small" -> 487_601_967L
        "medium" -> 1_533_763_059L
        else -> 0L
    }

    fun isComplete(ctx: Context, model: String): Boolean {
        return try {
            val f = modelFile(ctx, model)
            if (!f.exists()) return false
            val exp = expectedBytes(model)
            if (exp <= 0) return f.length() > 1_000_000
            f.length() >= (exp * 0.98).toLong()
        } catch (_: Exception) { false }
    }

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
        val finalFile = modelFile(ctx, model)
        if (isComplete(ctx, model)) {
            onProgress(100, "Already downloaded: ggml-${model}.bin")
            return
        }
        val partialFile = File(
            finalFile.parentFile,
            "${finalFile.name}.part"
        )

        runCatching { partialFile.delete() }

        val conn = URL(urlFor(model))
            .openConnection() as HttpURLConnection

        try {
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.requestMethod = "GET"
            conn.connect()

            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException(
                    "Download failed with HTTP $code"
                )
            }

            val totalSize = conn.contentLengthLong

            conn.inputStream.buffered().use { input ->
                FileOutputStream(partialFile).buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var totalRead = 0L
                    var lastProgress = -1

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        totalRead += count

                        if (totalSize > 0L) {
                            val progress =
                                ((totalRead * 100L) / totalSize)
                                    .coerceIn(0L, 100L)
                                    .toInt()

                            if (progress != lastProgress) {
                                lastProgress = progress

                                onProgress(
                                    progress,
                                    "Downloading $model: $progress% " +
                                        "(${totalRead / 1024 / 1024}/" +
                                        "${totalSize / 1024 / 1024} MB)"
                                )
                            }
                        }
                    }

                    output.flush()
                }
            }

            val expected = expectedBytes(model)
            val actual = partialFile.length()

            if (
                expected > 0L &&
                actual < (expected * 0.98).toLong()
            ) {
                throw java.io.IOException(
                    "Incomplete download: " +
                        "${actual / 1024 / 1024} MB received"
                )
            }

            if (!partialFile.renameTo(finalFile)) {
                partialFile.copyTo(finalFile, overwrite = true)
                partialFile.delete()
            }

            onProgress(
                100,
                "Downloaded: ${finalFile.name}"
            )
        } catch (e: Exception) {
            partialFile.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    fun whisperSize(model: String): String = when (model) {
        "tiny" -> "~74 MB"; "base" -> "~141 MB"; "small" -> "~465 MB"; "medium" -> "~1.43 GB"; else -> ""
    }

    fun moonshineSize(model: String): String = when (model) {
        "tiny" -> "~34 MB"; "base" -> "~60 MB"; "small" -> "~123 MB"; "medium" -> "~245 MB"; else -> ""
    }

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

    fun clearAllModels(ctx: Context): Pair<Int, Long> {
        val files = modelsDir(ctx).listFiles() ?: return 0 to 0L

        var deletedCount = 0
        var freedBytes = 0L

        for (file in files) {
            if (
                file.name.startsWith("ggml-") ||
                file.name.startsWith("moonshine-")
            ) {
                val size = file.length()

                if (file.delete()) {
                    deletedCount++
                    freedBytes += size
                }
            }
        }

        return deletedCount to freedBytes
    }
}
