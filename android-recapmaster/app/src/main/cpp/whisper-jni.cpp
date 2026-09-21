#include <jni.h>
#include <string>
#include <vector>
#include <sstream>
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

    whisper_full_params wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime   = false;
    wparams.print_progress   = false;
    wparams.print_timestamps = false;
    wparams.print_special    = false;
    wparams.translate        = false;
    wparams.language         = lang;
    wparams.n_threads        = 4;
    wparams.offset_ms        = 0;

    LOGI("Starting whisper transcription (%zu samples, lang: %s)...", pcm.size(), lang);
    int ret = whisper_full(ctx, wparams, pcm.data(), pcm.size());
    env->ReleaseStringUTFChars(language_j, lang);

    if (ret != 0) {
        LOGE("whisper_full failed with code %d", ret);
        return env->NewStringUTF("{\"error\":\"Inference failed\"}");
    }

    const int n_segments = whisper_full_n_segments(ctx);
    LOGI("Transcription finished: %d segments detected", n_segments);

    std::ostringstream json;
    json << "{\"segments\":[";
    for (int i = 0; i < n_segments; ++i) {
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10; // convert to ms
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);

        // Escape JSON text
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

        if (i > 0) json << ",";
        json << "{\"id\":" << i
             << ",\"start\":" << (t0 / 1000.0)
             << ",\"end\":" << (t1 / 1000.0)
             << ",\"text\":\"" << escaped << "\"}";
    }
    json << "]}";

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
