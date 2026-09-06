package com.whisperkeyboard

import android.content.Context
import java.io.File

/**
 * Dual-pipeline dispatcher: Whisper (multilingual, ggml .bin via JNI) and
 * Moonshine v2 (English, fast, .ort via moonshine-voice AAR).
 *
 * The engine + model are chosen ONLY in the app Settings page ("engine" and
 * "model" prefs). The keyboard never switches - it shows a read-only yellow
 * badge (see WhisperKeyboardService.refreshAllButtons). Jobs capture the
 * engine at enqueue time, so switching mid-queue never corrupts in-flight work.
 */
object SttEngines {

    const val WHISPER = "whisper"
    const val MOONSHINE = "moonshine"

    private fun prefs(ctx: Context?) =
        ctx?.getSharedPreferences("whisper", Context.MODE_PRIVATE)

    fun current(ctx: Context?): String =
        prefs(ctx)?.getString("engine", WHISPER) ?: WHISPER

    fun model(ctx: Context?): String =
        prefs(ctx)?.getString("model", "small") ?: "small"

    /** Moonshine models are English-only: force "en" so jobs never fail on lang. */
    fun jobLang(ctx: Context?): String {
        if (current(ctx) == MOONSHINE) return "en"
        return prefs(ctx)?.getString("lang", "auto") ?: "auto"
    }

    fun badgeText(ctx: Context?): String {
        val e = current(ctx)
        val m = model(ctx)
        return "${e.uppercase()} · $m"
    }

    fun isReady(ctx: Context, engine: String, model: String): Boolean {
        return if (engine == MOONSHINE) {
            ModelManager.isMoonshineReady(ctx, model) || MoonshineEngine.isLoaded(model)
        } else {
            val f = ModelManager.modelFile(ctx, model)
            f.exists() && f.length() > 1_000_000
        }
    }

    fun ensureModel(ctx: Context, engine: String, model: String, lang: String): Boolean {
        // RAM hygiene: evict the OTHER engine first so whisper-medium (769MB) and a
        // moonshine model never sit resident together. unloadIfIdle is busy-guarded,
        // so an engine mid-transcription is left alone (its queued jobs self-heal).
        if (engine == MOONSHINE) {
            try { WhisperEngine.unloadIfIdle() } catch (_: Exception) {}
        } else {
            try { MoonshineEngine.unloadIfIdle() } catch (_: Exception) {}
        }
        val ok = if (engine == MOONSHINE) {
            MoonshineEngine.applyThreadPref(ctx)
            MoonshineEngine.ensureModel(ctx, model, "en")
        } else {
            val mf = ModelManager.modelFile(ctx, model)
            if (!mf.exists() || mf.length() < 1_000_000) return false
            if (WhisperEngine.isLoaded(mf.absolutePath)) return true
            WhisperEngine.applyThreadPref(ctx)
            WhisperEngine.ensureModel(mf.absolutePath)
        }
        // Guarantee the idle-unloader is armed whenever a model is pinned in RAM.
        // (Whisper's transcribe self-heal path reloads without notifying, which used
        // to leave models pinned forever when the service wasn't already running.)
        if (ok) ProcessingService.notifyActivity()
        return ok
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

    /** True while either native engine is inside a transcription. */
    fun busy(): Boolean = WhisperEngine.isBusy() || MoonshineEngine.isBusy()

    fun cancelAll() {
        try { WhisperEngine.cancelCurrent() } catch (_: Exception) {}
        try { MoonshineEngine.cancelCurrent() } catch (_: Exception) {}
    }

    /** Unload idle engines (each guards its own busy state). */
    fun unloadIdle() {
        try { WhisperEngine.unloadIfIdle() } catch (_: Exception) {}
        try { MoonshineEngine.unloadIfIdle() } catch (_: Exception) {}
    }

    fun loadedModel(): String? =
        WhisperEngine.loadedModel() ?: MoonshineEngine.loadedModel()

    /** Stats keys are engine-qualified: whisper_small vs moonshine_small never mix. */
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
