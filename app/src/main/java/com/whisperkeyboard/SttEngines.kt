package com.whisperkeyboard

import android.content.Context
import java.io.File

object SttEngines {

    const val WHISPER = "whisper"
    const val MOONSHINE = "moonshine"

    private fun prefs(ctx: Context?) =
        ctx?.getSharedPreferences("whisper", Context.MODE_PRIVATE)

    fun normalizeEngine(engine: String?): String {
        return when (engine) {
            MOONSHINE -> MOONSHINE
            WHISPER -> WHISPER
            else -> WHISPER
        }
    }

    private val supportedModels =
        setOf("tiny", "base", "small", "medium")

    fun normalizeModel(model: String?): String {
        return model
            ?.takeIf { it in supportedModels }
            ?: ""
    }

    fun current(ctx: Context?): String =
        normalizeEngine(
            prefs(ctx)?.getString("engine", WHISPER)
        )

    fun automaticThreadCount(): Int {
        val cores =
            Runtime.getRuntime()
                .availableProcessors()
                .coerceAtLeast(1)

        return when {
            cores >= 8 -> 6
            cores >= 4 -> 4
            else -> cores
        }
    }

    fun model(ctx: Context?): String =
        prefs(ctx)?.getString("model", "small") ?: "small"

    fun jobLang(ctx: Context?): String {
        if (current(ctx) == MOONSHINE) return "en"
        return prefs(ctx)?.getString("lang", "auto") ?: "auto"
    }

    fun badgeText(ctx: Context?): String {
        val e = current(ctx)
        val m = model(ctx)
        return "${e.uppercase()} · ${m.ifEmpty { "—" }}"
    }

    fun describeMissing(ctx: Context, engine: String, model: String): String? {
        if (model.isEmpty()) return "no model selected - pick one in Settings"
        if (isReady(ctx, engine, model)) return null
        if (engine == MOONSHINE) return "$model not downloaded - tap Download in Settings"
        val short = ModelManager.shortfall(ctx, model)
        return if (short != null) "$model incomplete ($short) - re-download in Settings"
        else "$model not downloaded - tap Download in Settings"
    }

    fun isReady(ctx: Context, engine: String, model: String): Boolean {
        return if (engine == MOONSHINE) {
            ModelManager.isMoonshineReady(ctx, model) || MoonshineEngine.isLoaded(model)
        } else {
            ModelManager.isComplete(ctx, model)
        }
    }

    fun ensureModel(ctx: Context, engine: String, model: String, lang: String): Boolean {
        val normalizedEngine = normalizeEngine(engine)
        val normalizedModel = normalizeModel(model)

        val loaded = when (normalizedEngine) {
            MOONSHINE -> {
                MoonshineEngine.applyThreadPref(ctx)
                MoonshineEngine.ensureModel(
                    ctx.applicationContext,
                    normalizedModel,
                    "en"
                )
            }

            else -> {
                if (!ModelManager.isComplete(ctx, normalizedModel)) {
                    false
                } else {
                    val file =
                        ModelManager.modelFile(ctx, normalizedModel)

                    if (
                        WhisperEngine.isLoaded(
                            file.absolutePath
                        )
                    ) {
                        true
                    } else {
                        WhisperEngine.applyThreadPref(ctx)
                        WhisperEngine.ensureModel(
                            file.absolutePath
                        )
                    }
                }
            }
        }

        if (loaded) {
            if (normalizedEngine == MOONSHINE) {
                WhisperEngine.unloadIfIdle()
            } else {
                MoonshineEngine.unloadIfIdle()
            }

            ProcessingService.notifyActivity()
        }

        return loaded
    }

    fun isLoaded(ctx: Context, engine: String, model: String): Boolean {
        return if (engine == MOONSHINE) MoonshineEngine.isLoaded(model)
        else WhisperEngine.isLoaded(ModelManager.modelFile(ctx, model).absolutePath)
    }

    fun transcribe(ctx: Context, engine: String, model: String, wav: File, lang: String): String {
        return if (engine == MOONSHINE) {
            MoonshineEngine.transcribe("moonshine-$model", wav.absolutePath, "en").trim()
        } else {
            val mf = ModelManager.modelFile(ctx, model)
            WhisperEngine.transcribe(mf.absolutePath, wav.absolutePath, lang).trim()
        }
    }

    fun busy(): Boolean = WhisperEngine.isBusy() || MoonshineEngine.isBusy()

    fun cancelAll() {
        try { WhisperEngine.cancelCurrent() } catch (_: Exception) {}
        try { MoonshineEngine.cancelCurrent() } catch (_: Exception) {}
    }

    fun unloadIdle() {
        try { WhisperEngine.unloadIfIdle() } catch (_: Exception) {}
        try { MoonshineEngine.unloadIfIdle() } catch (_: Exception) {}

        try { KeyboardAutoStart.reset() } catch (_: Exception) {}
    }

    fun loadedModel(): String? =
        WhisperEngine.loadedModel() ?: MoonshineEngine.loadedModel()

    fun statsKey(engine: String, model: String) = "${engine}_$model"

    fun defaultRatio(engine: String, model: String): Double {
        if (engine == MOONSHINE) return when (model) {
            "tiny" -> 0.03; "base" -> 0.05; "small" -> 0.08; "medium" -> 0.15; else -> 0.08
        }
        return when (model) {
            "tiny" -> 0.06; "base" -> 0.10; "small" -> 0.28; "medium" -> 0.65; else -> 0.30
        }
    }
}
