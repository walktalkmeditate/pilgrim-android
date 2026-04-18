// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include "whisper/include/whisper.h"

#define LOG_TAG "PilgrimWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_org_walktalkmeditate_pilgrim_audio_WhisperCppEngine_nativeInit(
    JNIEnv* env, jobject /*this*/, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    auto cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(modelPath, path);
    if (!ctx) {
        LOGW("whisper_init_from_file_with_params returned null");
        return 0;
    }
    LOGI("whisper context initialized");
    return reinterpret_cast<jlong>(ctx);
}

// Reads a 16-bit mono PCM WAV at 16 kHz, returns float[] in [-1, 1].
// Matches the format produced by VoiceRecorder's WavWriter (44-byte
// RIFF header, 16-bit signed little-endian PCM, mono, 16 kHz).
static std::vector<float> readWavPcmF32(const char* path) {
    std::ifstream file(path, std::ios::binary);
    std::vector<float> samples;
    if (!file.is_open()) return samples;
    file.seekg(44);
    int16_t s;
    while (file.read(reinterpret_cast<char*>(&s), sizeof(s))) {
        samples.push_back(s / 32768.0f);
    }
    return samples;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_walktalkmeditate_pilgrim_audio_WhisperCppEngine_nativeTranscribe(
    JNIEnv* env, jobject /*this*/, jlong ctxHandle, jstring wavPath) {
    auto ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (!ctx) return nullptr;
    const char* path = env->GetStringUTFChars(wavPath, nullptr);
    auto samples = readWavPcmF32(path);
    env->ReleaseStringUTFChars(wavPath, path);
    if (samples.empty()) {
        LOGW("readWavPcmF32 returned empty");
        return env->NewStringUTF("");
    }
    auto wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    wparams.language = "en";
    wparams.n_threads = 4;
    if (whisper_full(ctx, wparams, samples.data(), samples.size()) != 0) {
        LOGW("whisper_full failed");
        return nullptr;
    }
    std::string out;
    int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; ++i) {
        out += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_org_walktalkmeditate_pilgrim_audio_WhisperCppEngine_nativeRelease(
    JNIEnv* /*env*/, jobject /*this*/, jlong ctxHandle) {
    if (ctxHandle == 0) return;
    auto ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    whisper_free(ctx);
    LOGI("whisper context released");
}
