#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- Cached model context (loaded once, reused across transcriptions) ----
static struct whisper_context *g_ctx = NULL;
static char g_model_path[1024] = {0};
static volatile int g_cancel = 0;
static int g_threads = 4;

JNIEXPORT void JNICALL
Java_com_whisperkeyboard_WhisperEngine_nativeSetThreads(
    JNIEnv *env, jobject thiz, jint threads)
{
    if (threads > 0 && threads <= 32) {
        g_threads = (int)threads;
        LOGI("threads set to %d", g_threads);
    }
}

// Abort hooks - whisper_full checks these during compute; return true to cancel
static bool abort_cb(void * user_data)
{
    (void)user_data;
    return g_cancel != 0;
}

// If encoder_begin_callback returns false, the computation is aborted
static bool encoder_begin_cb(struct whisper_context *ctx, struct whisper_state *state, void *user_data)
{
    (void)ctx; (void)state; (void)user_data;
    return g_cancel == 0;
}

JNIEXPORT jlong JNICALL
Java_com_whisperkeyboard_WhisperEngine_nativeCancel(
    JNIEnv *env, jobject thiz)
{
    g_cancel = 1;
    return 0;
}

// Caller must serialize access (Kotlin side holds a lock around all native calls)
static struct whisper_context *ensure_context(const char *model_path)
{
    if (g_ctx && strcmp(g_model_path, model_path) == 0) {
        return g_ctx;
    }
    if (g_ctx) {
        LOGI("Unloading previous model: %s", g_model_path);
        whisper_free(g_ctx);
        g_ctx = NULL;
        g_model_path[0] = '\0';
    }
    LOGI("Loading model from: %s", model_path);
    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);
    if (!ctx) {
        LOGE("Failed to load model");
        return NULL;
    }
    snprintf(g_model_path, sizeof(g_model_path), "%s", model_path);
    g_ctx = ctx;
    LOGI("Model loaded and cached");
    return g_ctx;
}

// ---- Init / preload context from model file ----
JNIEXPORT jlong JNICALL
Java_com_whisperkeyboard_WhisperEngine_nativeInit(
    JNIEnv *env, jobject thiz, jstring jModelPath)
{
    const char *model_path = (*env)->GetStringUTFChars(env, jModelPath, NULL);
    struct whisper_context *ctx = ensure_context(model_path);
    (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
    // NOTE: return status code, NOT the raw pointer -
    // Android tagged pointers (0xB4...) have the sign bit set and would read as negative
    return ctx ? 1 : -1;
}

// ---- Free cached context ----
JNIEXPORT void JNICALL
Java_com_whisperkeyboard_WhisperEngine_nativeFree(
    JNIEnv *env, jobject thiz)
{
    if (g_ctx) {
        LOGI("Freeing cached model");
        whisper_free(g_ctx);
        g_ctx = NULL;
        g_model_path[0] = '\0';
    }
}

// ---- Transcribe WAV file ----
JNIEXPORT jstring JNICALL
Java_com_whisperkeyboard_WhisperEngine_nativeTranscribe(
    JNIEnv *env, jobject thiz,
    jstring jModelPath, jstring jWavPath, jstring jLang)
{
    const char *model_path = (*env)->GetStringUTFChars(env, jModelPath, NULL);
    const char *wav_path = (*env)->GetStringUTFChars(env, jWavPath, NULL);
    const char *lang = (*env)->GetStringUTFChars(env, jLang, NULL);

    LOGI("Transcribe: model=%s wav=%s lang=%s", model_path, wav_path, lang);

    // Clear a stale cancel BEFORE the (possibly slow) model load. Previously this
    // happened after ensure_context(), so a cancel requested while the model was
    // loading was silently discarded and the whole transcription ran anyway.
    g_cancel = 0;

    // Use cached context (reloads only if model path changed)
    struct whisper_context *ctx = ensure_context(model_path);

    if (!ctx) {
        LOGE("Failed to init context");
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Failed to load whisper model");
    }

    // Read WAV file
    // WAV header: 44 bytes, then PCM data
    FILE *fp = fopen(wav_path, "rb");
    if (!fp) {
        LOGE("Failed to open WAV: %s", wav_path);
        // ctx stays cached (freed via nativeFree)
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Failed to open WAV file");
    }

    // Read WAV header
    unsigned char header[44];
    if (fread(header, 1, 44, fp) != 44) {
        fclose(fp);
        // ctx stays cached (freed via nativeFree)
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Invalid WAV header");
    }

    // Parse WAV format. Every field is little-endian; data_size is read as
    // unsigned because a signed read turns corrupt or oversized values into
    // negative sample counts.
    int channels = header[22] | (header[23] << 8);
    int sample_rate = (int)((unsigned int)header[24] | ((unsigned int)header[25] << 8) |
                            ((unsigned int)header[26] << 16) | ((unsigned int)header[27] << 24));
    int bits_per_sample = header[34] | (header[35] << 8);
    long data_size = (long)((unsigned int)header[40] | ((unsigned int)header[41] << 8) |
                            ((unsigned int)header[42] << 16) | ((unsigned int)header[43] << 24));

    LOGI("WAV: channels=%d rate=%d bits=%d data_size=%ld", channels, sample_rate, bits_per_sample, data_size);

    if (channels < 1 || channels > 2 || sample_rate <= 0 ||
        (bits_per_sample != 16 && bits_per_sample != 32) || data_size <= 0) {
        LOGE("Rejecting WAV: channels=%d rate=%d bits=%d data=%ld",
             channels, sample_rate, bits_per_sample, data_size);
        fclose(fp);
        // ctx stays cached (freed via nativeFree)
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Unsupported WAV format");
    }

    // Clamp the declared payload to the bytes that really exist. A header that
    // overstates data_size used to hand uninitialised heap to whisper_full,
    // which produced confident hallucinated text instead of a clean error.
    long file_bytes = 0;
    if (fseek(fp, 0, SEEK_END) == 0) {
        file_bytes = ftell(fp);
    }
    if (file_bytes > 44 && data_size > file_bytes - 44) {
        LOGE("WAV data_size %ld exceeds available %ld - clamping", data_size, file_bytes - 44);
        data_size = file_bytes - 44;
    }
    if (fseek(fp, 44, SEEK_SET) != 0) {
        fclose(fp);
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Invalid WAV header");
    }

    long bytes_per_frame = (long)channels * (long)(bits_per_sample / 8);
    long frames = bytes_per_frame > 0 ? data_size / bytes_per_frame : 0;
    if (frames <= 0 || frames > 16000L * 60L * 30L) {
        LOGE("Refusing WAV with %ld frames", frames);
        fclose(fp);
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Empty WAV file");
    }

    int num_samples = (int)frames;

    // calloc, not malloc: every float must be initialised before whisper_full
    // sees it, so a short read degrades to silence instead of noise.
    float *audio_data = (float *)calloc((size_t)num_samples, sizeof(float));
    if (!audio_data) {
        fclose(fp);
        // ctx stays cached (freed via nativeFree)
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: Out of memory");
    }

    // Read PCM data and convert to float [-1, 1]
    if (bits_per_sample == 16) {
        size_t want = (size_t)num_samples * (size_t)channels;
        short *raw = (short *)malloc(want * sizeof(short));
        if (!raw) {
            free(audio_data);
            fclose(fp);
            (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
            (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
            (*env)->ReleaseStringUTFChars(env, jLang, lang);
            return (*env)->NewStringUTF(env, "ERROR: Out of memory");
        }
        size_t got = fread(raw, sizeof(short), want, fp);
        if (got < want) {
            LOGE("Short read: %zu of %zu frames present",
                 got / (size_t)channels, want / (size_t)channels);
            num_samples = (int)(got / (size_t)channels);
        }
        for (int i = 0; i < num_samples; i++) {
            if (channels == 2) {
                audio_data[i] = (((float)raw[i*2] + (float)raw[i*2+1]) / 2.0f) / 32768.0f;
            } else {
                audio_data[i] = (float)raw[i] / 32768.0f;
            }
        }
        free(raw);
    } else if (bits_per_sample == 32) {
        size_t want = (size_t)num_samples * (size_t)channels;
        int *raw = (int *)malloc(want * sizeof(int));
        if (!raw) {
            free(audio_data);
            fclose(fp);
            (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
            (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
            (*env)->ReleaseStringUTFChars(env, jLang, lang);
            return (*env)->NewStringUTF(env, "ERROR: Out of memory");
        }
        size_t got = fread(raw, sizeof(int), want, fp);
        if (got < want) {
            LOGE("Short read: %zu of %zu frames present",
                 got / (size_t)channels, want / (size_t)channels);
            num_samples = (int)(got / (size_t)channels);
        }
        for (int i = 0; i < num_samples; i++) {
            if (channels == 2) {
                audio_data[i] = (((float)raw[i*2] + (float)raw[i*2+1]) / 2.0f) / 2147483648.0f;
            } else {
                audio_data[i] = (float)raw[i] / 2147483648.0f;
            }
        }
        free(raw);
    }

    fclose(fp);

    // Resample to 16kHz if needed
    float *resampled = NULL;
    if (sample_rate != 16000) {
        float ratio = (float)sample_rate / 16000.0f;
        int new_count = (int)(num_samples / ratio);
        resampled = (float *)malloc(new_count * sizeof(float));
        if (resampled) {
            for (int i = 0; i < new_count; i++) {
                int src_idx = (int)(i * ratio);
                if (src_idx >= num_samples) src_idx = num_samples - 1;
                resampled[i] = audio_data[src_idx];
            }
            free(audio_data);
            audio_data = resampled;
            num_samples = new_count;
        }
    }

    LOGI("Audio ready: %d samples (%.1f seconds)", num_samples, (float)num_samples / 16000.0f);

    // Configure whisper params.
    // whisper.cpp defaults are tuned for desktop batch work and are hostile to a
    // phone: greedy.best_of=2 decodes every segment twice, temperature_inc=0.2
    // triggers up to five additional full re-decodes on any low-confidence chunk,
    // and audio_ctx always encodes a full 30 s window even for a 4 s chunk.
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = true;        // word timings are never consumed here
    params.temperature_inc = 0.0f;      // no 5x fallback re-decode on weak chunks
    params.greedy.best_of = 1;          // decode once instead of twice
    params.n_threads = g_threads;
    // NOTE: this vendored whisper.cpp predates n_threads_batch; the encoder uses
    // params.n_threads as well here.
    params.single_segment = num_samples <= WHISPER_SAMPLE_RATE * 30;
    params.audio_ctx = num_samples >= WHISPER_SAMPLE_RATE * 30
            ? 0
            : (int)((1500.0f * (float)num_samples) / (30.0f * (float)WHISPER_SAMPLE_RATE));
    if (params.audio_ctx < 64) params.audio_ctx = 64;
    params.abort_callback = abort_cb;
    params.abort_callback_user_data = NULL;
    params.encoder_begin_callback = encoder_begin_cb;
    params.encoder_begin_callback_user_data = NULL;

    // Set language
    if (strcmp(lang, "auto") != 0 && strlen(lang) > 0) {
        params.language = lang;
    } else {
        params.language = NULL; // auto-detect
    }

    // Run transcription (g_cancel was already cleared before the model load)
    whisper_reset_timings(ctx);
    LOGI("Starting whisper_full...");

    if (whisper_full(ctx, params, audio_data, num_samples) != 0) {
        const char *why = g_cancel ? "ERROR: cancelled" : "ERROR: whisper_full failed";
        LOGE("whisper_full failed (cancel=%d)", g_cancel);
        free(audio_data);
        // ctx stays cached (freed via nativeFree)
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, why);
    }

#ifndef NDEBUG
    // Release builds stay quiet: printing timings on every chunk is logcat spam
    // and measurable CPU on a phone.
    whisper_print_timings(ctx);
#endif

    // Collect all segment text
    int n_segments = whisper_full_n_segments(ctx);
    LOGI("Got %d segments", n_segments);

    // Build result string
    size_t total_len = 0;
    for (int i = 0; i < n_segments; i++) {
        const char *text = whisper_full_get_segment_text(ctx, i);
        total_len += strlen(text);
    }

    char *result = (char *)calloc(total_len + 1, 1);
    if (result) {
        for (int i = 0; i < n_segments; i++) {
            const char *text = whisper_full_get_segment_text(ctx, i);
            strcat(result, text);
        }
    }

    // Cleanup
    free(audio_data);
    // ctx stays cached (freed via nativeFree)
    (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
    (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
    (*env)->ReleaseStringUTFChars(env, jLang, lang);

    jstring jresult = (*env)->NewStringUTF(env, result ? result : "");
    free(result);

    LOGI("Transcription complete: %zu chars", total_len);
    return jresult;
}

