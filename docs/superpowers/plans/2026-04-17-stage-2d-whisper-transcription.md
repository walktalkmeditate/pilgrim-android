# Stage 2-D Implementation Plan — whisper.cpp transcription

**Spec:** [2026-04-17-stage-2d-whisper-transcription-design.md](../specs/2026-04-17-stage-2d-whisper-transcription-design.md)

**Test command** (run after every Kotlin-touching task):
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

**Build-only check** (faster, after CMake/NDK changes):
```bash
JAVA_HOME=... PATH=... ./gradlew assembleDebug
```

Task order: 1 → 17. Build-only checks between native-config tasks; full CI gate after Kotlin tasks 7-16.

**Single-PR scope.** ~17 tasks. Native build steps are upfront; Kotlin layer is the bulk; tests at the end.

---

## Task 1 — Add Hilt-Work + WorkManager dependencies

**File:** `gradle/libs.versions.toml` (modify)

Add versions:
```toml
[versions]
hilt-work = "1.3.0"
work = "2.10.5"

[libraries]
androidx-hilt-work = { group = "androidx.hilt", name = "hilt-work", version.ref = "hilt-work" }
androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "hilt-work" }
androidx-work-runtime-ktx = { group = "androidx.work", name = "work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { group = "androidx.work", name = "work-testing", version.ref = "work" }
```

**File:** `app/build.gradle.kts` (modify dependencies block)

Add:
```kotlin
implementation(libs.androidx.work.runtime.ktx)
implementation(libs.androidx.hilt.work)
ksp(libs.androidx.hilt.compiler)
testImplementation(libs.androidx.work.testing)
```

Verify: `./gradlew assembleDebug` succeeds (no native code yet).

---

## Task 2 — Configure NDK + CMake in app/build.gradle.kts

**File:** `app/build.gradle.kts` (modify android block)

Add to `defaultConfig`:
```kotlin
ndk {
    abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
}
externalNativeBuild {
    cmake {
        cppFlags += "-std=c++17 -O3"
        arguments += "-DANDROID_STL=c++_static"
    }
}
```

Add at android block top-level:
```kotlin
ndkVersion = "28.2.13676358"
externalNativeBuild {
    cmake {
        path = file("src/main/cpp/CMakeLists.txt")
        version = "3.22.1"
    }
}
```

(Version pins match what's installed at `~/Library/Android/sdk/ndk/28.2.13676358` and `~/Library/Android/sdk/cmake/3.22.1`.)

Add packaging rule for 16 KB page size (Android 15+):
```kotlin
packaging {
    jniLibs {
        useLegacyPackaging = false
    }
}
```

Verify: `./gradlew assembleDebug` will FAIL until task 4 vendors the source. That's fine; we wire CMake first.

---

## Task 3 — PilgrimApp implements Configuration.Provider

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt` (modify)

```kotlin
@HiltAndroidApp
class PilgrimApp : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // existing onCreate body...
    }
}
```

Add imports for `androidx.work.Configuration` and `androidx.hilt.work.HiltWorkerFactory`.

**File:** `app/src/main/AndroidManifest.xml` (modify)

Disable WorkManager's default initializer (we provide via Configuration.Provider):
```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    android:exported="false"
    tools:node="merge">
    <meta-data
        android:name="androidx.work.WorkManagerInitializer"
        android:authorities="${applicationId}.workmanager-init"
        tools:node="remove" />
</provider>
```

(Add `xmlns:tools` if not already on the manifest root.)

---

## Task 4 — Vendor whisper.cpp source files

**Source:** upstream `examples/whisper.android/lib/src/main/jni/whisper/` from the v1.7.5 release tag.

```bash
mkdir -p /tmp/whisper-vendor
cd /tmp/whisper-vendor
curl -L https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v1.7.5.tar.gz | tar xz
mkdir -p /Users/rubberduck/GitHub/momentmaker/pilgrim-android/app/src/main/cpp
cp -r whisper.cpp-1.7.5/examples/whisper.android/lib/src/main/jni/whisper /Users/rubberduck/GitHub/momentmaker/pilgrim-android/app/src/main/cpp/
```

That folder includes a CMakeLists.txt and the source subset needed for Android. Inspect the layout after copy; remove any non-Android example references.

Add a `app/src/main/cpp/VENDORING.md` documenting:
- Source: `https://github.com/ggml-org/whisper.cpp/releases/tag/v1.7.5`
- Path within tarball: `examples/whisper.android/lib/src/main/jni/whisper/`
- Date vendored: 2026-04-17
- Re-vendor: bump tag, repeat copy, verify CMake still builds

---

## Task 5 — JNI shim

**File:** `app/src/main/cpp/whisper-jni.cpp` (new)

```cpp
// SPDX-License-Identifier: GPL-3.0-or-later
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <fstream>
#include "whisper/whisper.cpp/whisper.h"

#define LOG_TAG "PilgrimWhisper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_org_walktalkmeditate_pilgrim_audio_WhisperCppEngine_nativeInit(
    JNIEnv* env, jobject /*this*/, jstring modelPath) {
    const char* path = env->GetStringUTFChars(modelPath, nullptr);
    auto cparams = whisper_context_default_params();
    cparams.use_gpu = false;  // CPU-only on Android for predictability
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
static std::vector<float> readWavPcmF32(const char* path) {
    std::ifstream file(path, std::ios::binary);
    std::vector<float> samples;
    if (!file.is_open()) return samples;
    file.seekg(44);  // skip 44-byte WAV header (matches our WavWriter)
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
```

---

## Task 6 — CMakeLists.txt at the cpp root

**File:** `app/src/main/cpp/CMakeLists.txt` (new — replaces or wraps any vendored one)

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(pilgrim-whisper)

set(CMAKE_CXX_STANDARD 17)

# 16 KB page size alignment for Android 15+
add_link_options("-Wl,-z,max-page-size=16384")

# Pull in the vendored whisper subtree's CMake (it builds the static
# whisper library from the bundled source).
add_subdirectory(whisper)

add_library(pilgrim-whisper SHARED whisper-jni.cpp)
target_link_libraries(pilgrim-whisper whisper log)
```

If the vendored whisper subdir's CMakeLists.txt references targets we don't need (test binaries, examples), prune them.

Verify: `./gradlew assembleDebug` builds successfully with NDK + CMake.

---

## Task 7 — Bundle ggml-tiny.en.bin asset

```bash
mkdir -p /Users/rubberduck/GitHub/momentmaker/pilgrim-android/app/src/main/assets/models
curl -L -o /Users/rubberduck/GitHub/momentmaker/pilgrim-android/app/src/main/assets/models/ggml-tiny.en.bin \
    https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin
```

~39 MB binary. Commit directly (no LFS — single binary, GitHub allows up to 100MB per file).

---

## Task 8 — TranscriptionResult + WhisperError + WhisperEngine

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/WhisperEngine.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.file.Path

interface WhisperEngine {
    suspend fun transcribe(wavPath: Path): Result<TranscriptionResult>
}

data class TranscriptionResult(
    val text: String,
    val wordsPerMinute: Double?,
)

sealed class WhisperError : Exception() {
    data class ModelLoadFailed(override val cause: Throwable? = null) : WhisperError()
    data class AudioReadFailed(override val cause: Throwable? = null) : WhisperError()
    data class InferenceFailed(val nativeCode: Int) : WhisperError() {
        override val message: String = "whisper inference failed (code=$nativeCode)"
    }
}
```

---

## Task 9 — WhisperModelInstaller

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/WhisperModelInstaller.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the bundled whisper model from APK assets into filesDir on
 * first call. whisper.cpp's `whisper_init_from_file` needs a real
 * filesystem path — APK asset entries can't be read directly.
 */
@Singleton
class WhisperModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun installIfNeeded(): Path {
        val target = context.filesDir.toPath().resolve("$DIR/$FILE")
        if (Files.exists(target) && Files.size(target) > 0) return target
        Files.createDirectories(target.parent)
        context.assets.open("$ASSET_DIR/$FILE").use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    private companion object {
        const val ASSET_DIR = "models"
        const val DIR = "whisper-model"
        const val FILE = "ggml-tiny.en.bin"
    }
}
```

---

## Task 10 — WhisperCppEngine production impl

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/WhisperCppEngine.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.absolutePathString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [WhisperEngine] backed by whisper.cpp via JNI. Lazy-
 * loads the model on first transcribe — a user who never records
 * voice notes never pays the ~40 MB RAM cost.
 *
 * Single-instance scoped (@Singleton) so the loaded model survives
 * across multiple transcriptions; the native handle is released when
 * the singleton is destroyed (process tear-down, never explicitly).
 */
@Singleton
class WhisperCppEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: WhisperModelInstaller,
) : WhisperEngine {

    private val nativeLock = Any()

    @Volatile
    private var nativeHandle: Long = 0L

    private fun ensureLoaded(): Long {
        synchronized(nativeLock) {
            if (nativeHandle != 0L) return nativeHandle
            val modelPath = try {
                modelInstaller.installIfNeeded()
            } catch (e: Throwable) {
                Log.w(TAG, "model install failed", e)
                throw WhisperError.ModelLoadFailed(e)
            }
            val handle = nativeInit(modelPath.absolutePathString())
            if (handle == 0L) throw WhisperError.ModelLoadFailed()
            nativeHandle = handle
            return handle
        }
    }

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> =
        withContext(Dispatchers.Default) {
            try {
                val handle = ensureLoaded()
                val text = nativeTranscribe(handle, wavPath.absolutePathString())
                    ?: return@withContext Result.failure(WhisperError.InferenceFailed(-1))
                Result.success(TranscriptionResult(text = text.trim(), wordsPerMinute = null))
            } catch (e: WhisperError) {
                Result.failure(e)
            } catch (e: Throwable) {
                Log.w(TAG, "transcribe failed", e)
                Result.failure(WhisperError.InferenceFailed(-1).also { it.initCause(e) })
            }
        }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctx: Long, wavPath: String): String?
    private external fun nativeRelease(ctx: Long)

    private companion object {
        const val TAG = "WhisperCppEngine"
        init { System.loadLibrary("pilgrim-whisper") }
    }
}
```

---

## Task 11 — TranscriptionRunner

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/TranscriptionRunner.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.WalkRepository

/**
 * Best-effort batch transcription orchestrator. Reads pending
 * recordings for a walk, transcribes each via [WhisperEngine], and
 * updates the row with the result via the read-then-`.copy()`-then-
 * `updateVoiceRecording` pattern (per Stage 2-A's full-row @Update
 * convention).
 */
@Singleton
class TranscriptionRunner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val engine: WhisperEngine,
) {
    suspend fun transcribePending(walkId: Long): Result<Int> = runCatching {
        val pending = repository.voiceRecordingsFor(walkId).filter { it.transcription == null }
        var count = 0
        for (recording in pending) {
            val absolute = context.filesDir.toPath().resolve(recording.fileRelativePath)
            engine.transcribe(absolute).fold(
                onSuccess = { result ->
                    val text = if (result.text.isBlank()) NO_SPEECH_PLACEHOLDER else result.text
                    val wpm = computeWpm(text, recording.durationMillis)
                    repository.updateVoiceRecording(
                        recording.copy(transcription = text, wordsPerMinute = wpm),
                    )
                    count++
                },
                onFailure = {
                    Log.w(TAG, "transcribe failed for recording ${recording.id}", it)
                },
            )
        }
        count
    }

    private fun computeWpm(text: String, durationMillis: Long): Double? {
        if (durationMillis <= 0) return null
        val words = text.trim().split(WORD_SPLIT).count { it.isNotBlank() }
        if (words == 0) return null
        val minutes = durationMillis / 60_000.0
        return (words / minutes).takeIf { it.isFinite() }
    }

    companion object {
        const val NO_SPEECH_PLACEHOLDER = "(no speech detected)"
        private const val TAG = "TranscriptionRunner"
        private val WORD_SPLIT = Regex("\\s+")
    }
}
```

---

## Task 12 — TranscriptionWorker

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/TranscriptionWorker.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runner: TranscriptionRunner,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val walkId = inputData.getLong(KEY_WALK_ID, -1L).takeIf { it > 0 }
            ?: return Result.failure()
        val outcome = runner.transcribePending(walkId)
        return outcome.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.failure() },
        )
    }

    companion object { const val KEY_WALK_ID = "walk_id" }
}
```

---

## Task 13 — TranscriptionScheduler interface + WorkManager impl

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/TranscriptionScheduler.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface TranscriptionScheduler {
    fun scheduleForWalk(walkId: Long)
}

@Singleton
class WorkManagerTranscriptionScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : TranscriptionScheduler {

    override fun scheduleForWalk(walkId: Long) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_WALK_ID to walkId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "transcribe-walk-$walkId",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
```

---

## Task 14 — TranscriptionModule DI

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/di/TranscriptionModule.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.WhisperCppEngine
import org.walktalkmeditate.pilgrim.audio.WhisperEngine
import org.walktalkmeditate.pilgrim.audio.WorkManagerTranscriptionScheduler

@Module
@InstallIn(SingletonComponent::class)
abstract class TranscriptionModule {

    @Binds
    @Singleton
    abstract fun bindWhisperEngine(impl: WhisperCppEngine): WhisperEngine

    @Binds
    @Singleton
    abstract fun bindTranscriptionScheduler(
        impl: WorkManagerTranscriptionScheduler,
    ): TranscriptionScheduler
}
```

---

## Task 15 — Wire WalkViewModel.finishWalk

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` (modify)

Add to constructor:
```kotlin
private val transcriptionScheduler: TranscriptionScheduler,
```

Modify `finishWalk`:
```kotlin
fun finishWalk() {
    viewModelScope.launch {
        controller.finishWalk()
        // After Finished is reached, the controller's walkIdOrNull
        // still resolves (Finished retains walk). Schedule transcription
        // for the just-finished walk.
        walkIdOrNull(controller.state.value)?.let { walkId ->
            transcriptionScheduler.scheduleForWalk(walkId)
        }
    }
}
```

Update tests' WalkViewModel construction with the new param.

---

## Task 16 — Tests

### Task 16a — FakeWhisperEngine

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/FakeWhisperEngine.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import java.nio.file.Path
import kotlinx.coroutines.delay

class FakeWhisperEngine(
    var resultText: String = "hello world from the fake engine",
    var failure: Throwable? = null,
    var delayMs: Long = 0L,
) : WhisperEngine {

    val transcribeCalls = mutableListOf<Path>()

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> {
        transcribeCalls += wavPath
        if (delayMs > 0) delay(delayMs)
        failure?.let { return Result.failure(it) }
        return Result.success(TranscriptionResult(text = resultText, wordsPerMinute = null))
    }
}
```

### Task 16b — FakeTranscriptionScheduler

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/FakeTranscriptionScheduler.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

class FakeTranscriptionScheduler : TranscriptionScheduler {
    val scheduledWalkIds = mutableListOf<Long>()
    override fun scheduleForWalk(walkId: Long) {
        scheduledWalkIds += walkId
    }
}
```

### Task 16c — TranscriptionRunnerTest

**File:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/TranscriptionRunnerTest.kt` (new)

Robolectric test exercising:
1. `transcribePending transcribes only null-transcription rows`
2. `empty whisper result stored as NO_SPEECH_PLACEHOLDER`
3. `per-recording failure logged, batch continues with rest`
4. `WPM computed from word count and durationMillis`
5. `WPM null when text is blank or duration is zero`
6. `returns count of successfully-transcribed recordings`

### Task 16d — WalkViewModelTest extension

Add tests:
1. `finishWalk schedules transcription for the just-finished walkId`

Inject `FakeTranscriptionScheduler` in setUp via the new constructor param.

---

## Task 17 — Full CI gate + commit

```bash
./gradlew assembleDebug lintDebug testDebugUnitTest
```

**Expected:** BUILD SUCCESSFUL, 129 → ~138 tests passing, no new lint.

**APK size verification:**
```bash
ls -lh app/build/outputs/apk/debug/app-debug.apk
```
Target: ~70 MB (was ~30 MB pre-2-D, +39 MB model + ~2 MB native libs).

Commit:
```
feat(audio): Stage 2-D — on-device whisper.cpp transcription

After a walk finalizes, every VoiceRecording row with transcription
IS NULL gets transcribed locally via whisper.cpp and the row
updated. Privacy-first: audio never leaves the device.

- Vendored whisper.cpp v1.7.5 (no maven binding is mature for
  Android — researched four candidates, all rejected). NDK + CMake
  build; arm64-v8a / armeabi-v7a / x86_64 ABIs; 16 KB page-aligned
  for Android 15+.
- ggml-tiny.en.bin (~39 MB) bundled as APK asset; copied to
  filesDir on first transcribe. APK grew from ~30 MB → ~70 MB.
- WhisperCppEngine lazy-loads the model on first transcribe so
  users who never record voice notes don't pay the RAM cost.
- TranscriptionRunner batches pending rows for a walk; per-
  recording failures don't abort the batch.
- TranscriptionWorker (Hilt-aware CoroutineWorker) wraps the
  runner, enqueued unique-by-walkId via TranscriptionScheduler
  interface (FakeTranscriptionScheduler for tests).
- WalkViewModel.finishWalk now also enqueues transcription.

Empty results stored as "(no speech detected)" so the runner
doesn't retry forever; UI handles as a special case in Stage 2-E.

Tests: NN/NN green. WhisperCppEngine itself is NOT unit-tested
(JNI loading needs a device); Stage 2-F's device test verifies
real inference end-to-end.
```
