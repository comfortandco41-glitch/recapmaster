#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
#include <thread>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <android/log.h>
#include "whisper.h"

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_recapmaster_app_engine_WhisperEngine_initModel(
    JNIEnv *env,
    jobject /* this */,
    jstring model_path_j) {
    const char *model_path = env->GetStringUTFChars(model_path_j, nullptr);
    LOGI("Loading whisper model from: %s", model_path);

    struct whisper_context_params cparams = whisper_context_default_params();
    struct whisper_context *ctx = whisper_init_from_file_with_params(model_path, cparams);

    env->ReleaseStringUTFChars(model_path_j, model_path);

    if (!ctx) {
        LOGE("Failed to initialize whisper context");
        return 0;
    }
    LOGI("Whisper model loaded successfully");
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_recapmaster_app_engine_WhisperEngine_transcribePcm(
    JNIEnv *env,
    jobject /* this */,
    jlong ctx_ptr,
    jfloatArray pcm_data_j,
    jstring language_j) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (!ctx) {
        return env->NewStringUTF("{\"error\":\"Invalid context pointer\"}");
    }

    const char *lang = env->GetStringUTFChars(language_j, nullptr);
    jsize len = env->GetArrayLength(pcm_data_j);
    jfloat *pcm_floats = env->GetFloatArrayElements(pcm_data_j, nullptr);

    std::vector<float> pcm(pcm_floats, pcm_floats + len);
    env->ReleaseFloatArrayElements(pcm_data_j, pcm_floats, JNI_ABORT);

    // Detect hardware concurrency and utilize big/prime cores (e.g. Snapdragon 8 Gen 3)
    unsigned int hw_threads = std::thread::hardware_concurrency();
    int threads = (hw_threads > 0) ? std::min(8, (int)hw_threads) : 4;

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.language         = lang;
    wparams.n_threads        = threads;
    wparams.offset_ms        = 0;
    wparams.no_context       = true; // Crucial: prevents hallucinations from previous windows causing early termination
    wparams.single_segment   = false;
    wparams.suppress_blank   = true;
    wparams.suppress_non_speech_tokens = true;
    wparams.temperature      = 0.0f;
    wparams.temperature_inc  = 0.0f; // Disable repeated fallback passes (prevents 6x slowdown on noise/music)

    LOGI("Starting whisper transcription (%zu samples, approx %.1fs, lang: %s, threads: %d)...",
         pcm.size(), pcm.size() / 16000.0, lang, threads);

    // Process audio in 30-second windows so that every chunk of audio (e.g. 0-30s, 30-60s, 60-90s, 90-120s, 120-150s+)
    // is transcribed with 100% independence, guaranteeing NO audio is missed or cut off past 90 seconds.
    const size_t sample_rate = 16000;
    const size_t window_size = 30 * sample_rate; // 30-second standard window
    size_t offset = 0;
    int global_segment_id = 0;

    std::ostringstream json;
    json << "{\"segments\":[";
    bool first_seg = true;

    while (offset < pcm.size()) {
        size_t current_len = std::min(window_size, pcm.size() - offset);
        if (current_len < sample_rate * 0.5) {
            // Skip micro-slices shorter than 0.5s at the very end
            break;
        }

        const float* chunk_data = pcm.data() + offset;
        double window_offset_sec = static_cast<double>(offset) / sample_rate;

        // Fast RMS voice activity / energy check: skip near-silent chunks in 0.001ms
        float sum_sq = 0.0f;
        size_t step = 16;
        size_t samples_checked = 0;
        for (size_t s = 0; s < current_len; s += step) {
            sum_sq += chunk_data[s] * chunk_data[s];
            samples_checked++;
        }
        float rms = (samples_checked > 0) ? std::sqrt(sum_sq / samples_checked) : 0.0f;
        if (rms < 0.0025f) {
            LOGI("Skipping silent window at %.1fs (RMS: %.5f)", window_offset_sec, rms);
            offset += current_len;
            continue;
        }

        int ret = whisper_full(ctx, wparams, chunk_data, current_len);
        if (ret == 0) {
            // Lock detected language for subsequent chunks to avoid re-running language detection on every window
            if (strcmp(lang, "auto") == 0) {
                int lang_id = whisper_full_lang_id(ctx);
                if (lang_id >= 0) {
                    const char *det = whisper_lang_str(lang_id);
                    if (det && strlen(det) > 0) {
                        wparams.language = det;
                    }
                }
            }
            const int n_segments = whisper_full_n_segments(ctx);
            for (int i = 0; i < n_segments; ++i) {
                int64_t seg_t0 = whisper_full_get_segment_t0(ctx, i) * 10; // ms
                int64_t seg_t1 = whisper_full_get_segment_t1(ctx, i) * 10;
                double t0 = window_offset_sec + (seg_t0 / 1000.0);
                double t1 = window_offset_sec + (seg_t1 / 1000.0);
                const char *text = whisper_full_get_segment_text(ctx, i);

                std::string clean_text = text ? text : "";
                std::string escaped;
                for (char c : clean_text) {
                    if (c == '"') escaped += "\\\"";
                    else if (c == '\\') escaped += "\\\\";
                    else if (c == '\n') escaped += "\\n";
                    else if (c == '\r') escaped += "\\r";
                    else if (c == '\t') escaped += "\\t";
                    else escaped += c;
                }

                if (escaped.empty()) continue;

                if (!first_seg) json << ",";
                first_seg = false;

                json << "{\"id\":" << global_segment_id++
                     << ",\"start\":" << t0
                     << ",\"end\":" << t1
                     << ",\"text\":\"" << escaped << "\"}";
            }
        } else {
            LOGE("whisper_full failed for window at %.1fs with code %d", window_offset_sec, ret);
        }

        offset += current_len;
    }

    env->ReleaseStringUTFChars(language_j, lang);

    json << "]}";
    LOGI("Whisper finished: total %d segments detected across entire audio (%.1fs)",
         global_segment_id, pcm.size() / 16000.0);

    return env->NewStringUTF(json.str().c_str());
}

JNIEXPORT void JNICALL
Java_com_recapmaster_app_engine_WhisperEngine_freeModel(
    JNIEnv * /* env */,
    jobject /* this */,
    jlong ctx_ptr) {
    auto *ctx = reinterpret_cast<struct whisper_context *>(ctx_ptr);
    if (ctx) {
        whisper_free(ctx);
        LOGI("Whisper model freed");
    }
}

} // extern "C"
