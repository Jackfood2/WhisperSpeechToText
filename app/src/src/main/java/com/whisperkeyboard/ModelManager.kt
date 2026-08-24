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
}
