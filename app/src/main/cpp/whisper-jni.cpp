// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <string>
#include <vector>
#include <fstream>
#include <thread>
#include "whisper/include/whisper.h"

#define LOG_TAG "PilgrimWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_org_walktalkmeditate_pilgrim_audio_JniWhisperNative_nativeInit(
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
// Walks RIFF chunks to locate the `data` subchunk rather than assuming
// the canonical 44-byte header — Pilgrim's WavWriter writes the
// canonical layout, but a future import path or a hand-edited file
// might include LIST/INFO chunks that push the data offset later.
// Validates RIFF/WAVE magic and rejects unsupported encodings so a
// malformed file produces an empty result (mapped upstream to a no-
// speech placeholder + log) rather than silent garbage.
static std::vector<float> readWavPcmF32(const char* path) {
    std::ifstream file(path, std::ios::binary);
    std::vector<float> samples;
    if (!file.is_open()) return samples;

    char magic[4];
    uint32_t riffSize = 0;
    if (!file.read(magic, 4) || std::string(magic, 4) != "RIFF") return samples;
    if (!file.read(reinterpret_cast<char*>(&riffSize), 4)) return samples;
    if (!file.read(magic, 4) || std::string(magic, 4) != "WAVE") return samples;

    bool fmtOk = false;
    while (file.read(magic, 4)) {
        uint32_t chunkSize = 0;
        if (!file.read(reinterpret_cast<char*>(&chunkSize), 4)) return samples;
        if (std::string(magic, 4) == "fmt ") {
            if (chunkSize < 16) return samples;
            uint16_t audioFormat = 0, numChannels = 0, bitsPerSample = 0;
            uint32_t sampleRate = 0;
            file.read(reinterpret_cast<char*>(&audioFormat), 2);
            file.read(reinterpret_cast<char*>(&numChannels), 2);
            file.read(reinterpret_cast<char*>(&sampleRate), 4);
            file.seekg(6, std::ios::cur);
            file.read(reinterpret_cast<char*>(&bitsPerSample), 2);
            if (chunkSize > 16) file.seekg(chunkSize - 16, std::ios::cur);
            // PCM (1) + mono + 16-bit + 16 kHz only — matches WavWriter.
            if (audioFormat != 1 || numChannels != 1 || bitsPerSample != 16 || sampleRate != 16000) {
                LOGW("unsupported WAV: fmt=%u ch=%u rate=%u bits=%u",
                     audioFormat, numChannels, sampleRate, bitsPerSample);
                return samples;
            }
            fmtOk = true;
        } else if (std::string(magic, 4) == "data") {
            if (!fmtOk) return samples;
            samples.reserve(chunkSize / 2);
            int16_t s;
            uint32_t bytesRead = 0;
            while (bytesRead < chunkSize && file.read(reinterpret_cast<char*>(&s), sizeof(s))) {
                samples.push_back(s / 32768.0f);
                bytesRead += 2;
            }
            return samples;
        } else {
            // Skip unknown chunks (LIST/INFO/etc.). Chunks are 2-byte
            // aligned per RIFF spec — pad if the size is odd. Promote
            // to uint64_t before adding the pad byte: chunkSize is
            // uint32_t, so 0xFFFFFFFF + 1 would wrap to 0 → infinite
            // re-read of the same chunk header on a crafted file.
            const uint64_t skip = static_cast<uint64_t>(chunkSize) + (chunkSize & 1u);
            file.seekg(static_cast<std::streamoff>(skip), std::ios::cur);
        }
    }
    return samples;
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_walktalkmeditate_pilgrim_audio_JniWhisperNative_nativeTranscribe(
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
    // Forced English is iOS parity, NOT a leftover from the tiny.en era:
    // iOS v1.9.0 passes no language and WhisperKit 0.16.0's defaults
    // prefill the multilingual base with <|en|> (detectLanguage defaults
    // to false when usePrefillPrompt is true). "auto" here would diverge
    // from shipped iOS and mis-decodes on the transitional English-only
    // tiny. See docs/parity/2026-07-26-port-engine-switch-u10.md L2.
    wparams.language = "en";
    // Cap threads at 4 (diminishing returns above) and at the device's
    // hardware concurrency so 2-core budget devices don't oversubscribe.
    unsigned hw = std::thread::hardware_concurrency();
    wparams.n_threads = std::max(1u, std::min(4u, hw == 0 ? 2u : hw));
    int rc = whisper_full(ctx, wparams, samples.data(), samples.size());
    if (rc != 0) {
        LOGW("whisper_full failed rc=%d", rc);
        return nullptr;
    }
    std::string out;
    int n = whisper_full_n_segments(ctx);
    for (int i = 0; i < n; ++i) {
        out += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(out.c_str());
}

// Additive (Phase 20 U5): a second, parallel decode entry point returning
// per-segment text/timing/no-speech-probability so the Thought Threads
// analyzer can compute hallucination-flag ranges. Deliberately NOT
// refactored to share code with nativeTranscribe above (which stays
// byte-for-byte unchanged) — the wparams setup here is duplicated on
// purpose so the pinned original entry point can never be affected by a
// change made for this one.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_org_walktalkmeditate_pilgrim_audio_JniWhisperNative_nativeTranscribeSegments(
    JNIEnv* env, jobject /*this*/, jlong ctxHandle, jstring wavPath) {
    auto ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (!ctx) return nullptr;

    jclass segmentClass = env->FindClass("org/walktalkmeditate/pilgrim/audio/WhisperSegment");
    if (!segmentClass) {
        env->ExceptionClear();
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(segmentClass, "<init>", "(Ljava/lang/String;JJF)V");
    if (!ctor) {
        env->ExceptionClear();
        return nullptr;
    }

    const char* path = env->GetStringUTFChars(wavPath, nullptr);
    auto samples = readWavPcmF32(path);
    env->ReleaseStringUTFChars(wavPath, path);
    if (samples.empty()) {
        LOGW("readWavPcmF32 returned empty (segments)");
        return env->NewObjectArray(0, segmentClass, nullptr);
    }

    auto wparams = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wparams.print_realtime = false;
    wparams.print_progress = false;
    wparams.print_timestamps = false;
    wparams.print_special = false;
    wparams.translate = false;
    // Forced English — see the identical comment on nativeTranscribe
    // above; this is iOS parity, not a leftover.
    wparams.language = "en";
    unsigned hw = std::thread::hardware_concurrency();
    wparams.n_threads = std::max(1u, std::min(4u, hw == 0 ? 2u : hw));

    int rc = whisper_full(ctx, wparams, samples.data(), samples.size());
    if (rc != 0) {
        LOGW("whisper_full failed rc=%d (segments)", rc);
        return nullptr;
    }

    int n = whisper_full_n_segments(ctx);
    jobjectArray result = env->NewObjectArray(n, segmentClass, nullptr);
    for (int i = 0; i < n; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        // t0/t1 are whisper.cpp's native 10ms units (centiseconds) —
        // sample_to_timestamp()/to_timestamp() in whisper.cpp confirm the
        // ×10 scale to milliseconds.
        auto t0Ms = static_cast<jlong>(whisper_full_get_segment_t0(ctx, i) * 10);
        auto t1Ms = static_cast<jlong>(whisper_full_get_segment_t1(ctx, i) * 10);
        float noSpeechProb = whisper_full_get_segment_no_speech_prob(ctx, i);

        jstring jtext = env->NewStringUTF(text ? text : "");
        jobject segment = env->NewObject(segmentClass, ctor, jtext, t0Ms, t1Ms, noSpeechProb);
        env->SetObjectArrayElement(result, i, segment);
        env->DeleteLocalRef(jtext);
        env->DeleteLocalRef(segment);
    }
    return result;
}

// Frees a whisper_context so its ~75 MB of model weights are released
// back to the OS without waiting for process exit. Called after a
// transcription batch (AF33). The Kotlin side serializes this against
// nativeTranscribe via nativeLock, so the context is never freed mid-
// inference. A 0 handle (never loaded) is a no-op.
extern "C" JNIEXPORT void JNICALL
Java_org_walktalkmeditate_pilgrim_audio_JniWhisperNative_nativeFree(
    JNIEnv* /*env*/, jobject /*this*/, jlong ctxHandle) {
    auto ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (!ctx) return;
    whisper_free(ctx);
    LOGI("whisper context freed");
}
