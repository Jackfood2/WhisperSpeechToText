#include <jni.h>
#include <string.h>
#include <stdlib.h>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ---- Cached model context (loaded once, reused across transcriptions) ----
static struct whisper_context *g_ctx = NULL;
static char g_model_path[1024] = {0};

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
    return ctx ? (jlong)(intptr_t)ctx : -1;
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

    // Parse WAV format
    int channels = header[22] | (header[23] << 8);
    int sample_rate = header[24] | (header[25] << 8) | (header[26] << 16) | (header[27] << 24);
    int bits_per_sample = header[34] | (header[35] << 8);
    int data_size = header[40] | (header[41] << 8) | (header[42] << 16) | (header[43] << 24);

    LOGI("WAV: channels=%d rate=%d bits=%d data_size=%d", channels, sample_rate, bits_per_sample, data_size);

    int num_samples = data_size / (bits_per_sample / 8);
    if (channels == 2) num_samples /= 2;

    float *audio_data = (float *)malloc(num_samples * sizeof(float));
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
        short *raw = (short *)malloc(num_samples * channels * sizeof(short));
        if (raw) {
            int read_count = fread(raw, sizeof(short), num_samples * channels, fp);
            (void)read_count;
            for (int i = 0; i < num_samples; i++) {
                if (channels == 2) {
                    audio_data[i] = ((float)(raw[i*2] + raw[i*2+1]) / 2.0f) / 32768.0f;
                } else {
                    audio_data[i] = (float)raw[i] / 32768.0f;
                }
            }
            free(raw);
        }
    } else if (bits_per_sample == 32) {
        int *raw = (int *)malloc(num_samples * channels * sizeof(int));
        if (raw) {
            int read_count = fread(raw, sizeof(int), num_samples * channels, fp);
            (void)read_count;
            for (int i = 0; i < num_samples; i++) {
                if (channels == 2) {
                    audio_data[i] = ((float)(raw[i*2] + raw[i*2+1]) / 2.0f) / 2147483648.0f;
                } else {
                    audio_data[i] = (float)raw[i] / 2147483648.0f;
                }
            }
            free(raw);
        }
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

    // Configure whisper params
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = false;
    params.print_progress = false;
    params.print_timestamps = false;
    params.print_special = false;
    params.translate = false;
    params.single_segment = false;
    params.no_context = true;
    params.n_threads = 4;

    // Set language
    if (strcmp(lang, "auto") != 0 && strlen(lang) > 0) {
        params.language = lang;
    } else {
        params.language = NULL; // auto-detect
    }

    // Run transcription
    whisper_reset_timings(ctx);
    LOGI("Starting whisper_full...");

    if (whisper_full(ctx, params, audio_data, num_samples) != 0) {
        LOGE("whisper_full failed");
        free(audio_data);
        // ctx stays cached (freed via nativeFree)
        (*env)->ReleaseStringUTFChars(env, jModelPath, model_path);
        (*env)->ReleaseStringUTFChars(env, jWavPath, wav_path);
        (*env)->ReleaseStringUTFChars(env, jLang, lang);
        return (*env)->NewStringUTF(env, "ERROR: whisper_full failed");
    }

    whisper_print_timings(ctx);

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

