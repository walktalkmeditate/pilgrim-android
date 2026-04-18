# Stage 2-D — On-device transcription via whisper.cpp

## Context

Stage 2-A shipped the `voice_recordings` Room table with a nullable
`transcription` field. Stage 2-B captures audio to WAV. Stage 2-C
persists VoiceRecording rows on stop. Stage 2-D adds the missing
piece: post-walk, every recording with `transcription IS NULL` gets
transcribed locally via whisper.cpp and the row is updated.

Privacy-first: audio never leaves the device.

## Source-of-truth alignment

iOS `pilgrim-ios` uses **WhisperKit** (Apple's CoreML-based wrapper)
with the **`ggml-tiny.en`** model bundled. Android matches: same model
weights, same on-device inference, same one-shot batch transcription
on walk-finish.

## Binding decision

Researched three paths:

| Path | Verdict |
|---|---|
| **Vendor upstream whisper.cpp + JNI shim** | ✅ Picked. Only production-grade option for Android-as-of-today. |
| Maven binding (whisper-jni, WhisperKitAndroid, WhisperCore_Android, etc.) | ❌ Rejected. `whisper-jni` ships Linux .so only. `WhisperKitAndroid` is **archived** (succeeded by argmax-sdk-kotlin). `WhisperCore_Android` has 2 stars, single contributor, August 2025 first commit. No mature, actively-maintained Android-targeted binding exists. |
| ONNX Runtime Mobile + Whisper ONNX | ❌ Rejected. Different model format than iOS (which uses GGML), so cross-platform consistency suffers. ORT runtime is ~10 MB extra on top of the model. |

Vendoring upstream whisper.cpp's `examples/whisper.android` reference
is the canonical path. It already has CMakeLists.txt for
arm64-v8a / armeabi-v7a / x86_64 and a JNI shim we can adopt as-is.

## Scope flag — this is a 17-task stage

A single Stage 2-D doing both the orchestration pipeline AND the JNI
build exceeds the autopilot 15-task heuristic. Two options:

**Option Single (recommended):** ship 2-D as one PR. The orchestration
+ JNI are tightly coupled — the Worker has nothing useful to do
without the engine — and splitting risks merging a half-built feature
that's harder to reason about than the cohesive whole. Accept the
scope; review cycles will catch what they catch.

**Option Split:**
- **2-D-i**: orchestration only. WhisperEngine interface +
  TranscriptionRunner + TranscriptionWorker + WorkManager wiring +
  WalkViewModel enqueue. Production binding is `FakeWhisperEngine`
  returning canned text — ship the data pipeline, no real transcripts
  yet.
- **2-D-ii**: replace Fake with `WhisperCppEngine`. NDK + CMake +
  vendored whisper.cpp + model bundling.

I'll present both at the CHECKPOINT and let you pick. The spec below
covers the Single approach; if we split, the spec splits at the
WhisperEngine binding line.

## Architecture

```
┌──────────────────────────────────────────────────────┐
│ WalkViewModel.finishWalk()                           │
│   ├── controller.finishWalk()  (existing)            │
│   └── transcriptionScheduler.scheduleForWalk(walkId) │
└──────────────────────┬───────────────────────────────┘
                       │ enqueues
                       ▼
┌──────────────────────────────────────────────────────┐
│ TranscriptionWorker (CoroutineWorker)                │
│   - reads walkId from inputData                      │
│   - delegates to TranscriptionRunner                 │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│ TranscriptionRunner (@Singleton)                     │
│   - voiceRecordingsFor(walkId).filter { txn null }   │
│   - for each: engine.transcribe(wavPath)             │
│   - on success: row.copy(transcription, wpm) → repo │
└──────────────────────┬───────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────┐
│ WhisperEngine (interface)                            │
│   suspend fun transcribe(wavPath): Result<...>       │
└─────┬───────────────────────────────────────┬────────┘
      │ production                            │ tests
      ▼                                       ▼
┌──────────────────────┐          ┌─────────────────────┐
│ WhisperCppEngine     │          │ FakeWhisperEngine   │
│  - JNI to libwhisper │          │  - canned results   │
│  - lazy-loads model  │          │  - configurable     │
│    on first call     │          │    delay + errors   │
└──────────────────────┘          └─────────────────────┘
        │
        ▼
┌────────────────────────────────────────┐
│ libwhisper.so (CMake-built from        │
│ vendored whisper.cpp + ggml sources)   │
└────────────────────────────────────────┘
```

## Components

### `WhisperEngine` interface

```kotlin
interface WhisperEngine {
    suspend fun transcribe(wavPath: Path): Result<TranscriptionResult>
}

data class TranscriptionResult(
    val text: String,
    val wordsPerMinute: Double?,
)
```

The `Result<T>` carries typed errors:

```kotlin
sealed class WhisperError : Exception() {
    data class ModelLoadFailed(override val cause: Throwable?) : WhisperError()
    data class AudioReadFailed(override val cause: Throwable?) : WhisperError()
    data class InferenceFailed(val nativeCode: Int) : WhisperError()
    data object EmptyTranscript : WhisperError()
}
```

`EmptyTranscript` ≠ failure semantically but the empty case shouldn't
clobber the DB row with `""` (UI couldn't distinguish "transcribed but
silent" from "in-progress"). Map to a `null` transcription update so
Stage 2-E can show "transcribing…" → "(no speech detected)" once.

Actually: store empty as the literal string `"(no speech detected)"`
so the row is recorded as transcribed and the Runner doesn't retry it
forever. Done in TranscriptionRunner, not the engine.

### `WhisperCppEngine`

```kotlin
@Singleton
class WhisperCppEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelInstaller: WhisperModelInstaller,
) : WhisperEngine {
    private val nativeLock = Any()
    private var nativeHandle: Long = 0L  // opaque pointer to whisper_context

    private fun ensureLoaded(): Long = synchronized(nativeLock) {
        if (nativeHandle != 0L) return@synchronized nativeHandle
        val modelPath = modelInstaller.installIfNeeded()  // assets → filesDir
        nativeHandle = nativeInit(modelPath.absolutePathString())
        check(nativeHandle != 0L) { "whisper init returned null" }
        nativeHandle
    }

    override suspend fun transcribe(wavPath: Path): Result<TranscriptionResult> =
        withContext(Dispatchers.Default) {  // CPU-bound, not IO
            try {
                val handle = ensureLoaded()
                val text = nativeTranscribe(handle, wavPath.absolutePathString())
                    ?: return@withContext Result.failure(WhisperError.InferenceFailed(-1))
                Result.success(TranscriptionResult(text = text.trim(), wordsPerMinute = null))
            } catch (e: Throwable) {
                Result.failure(WhisperError.InferenceFailed(-1).also { it.initCause(e) })
            }
        }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(ctx: Long, wavPath: String): String?
    private external fun nativeRelease(ctx: Long)

    companion object { init { System.loadLibrary("pilgrim-whisper") } }
}
```

WPM left null at the engine level — TranscriptionRunner computes it
from the recording's `durationMillis` + word count of the result.

**Lazy load** matters: a user who never records voice notes shouldn't
pay the ~40 MB RAM cost of loading the model.

### `WhisperModelInstaller`

```kotlin
@Singleton
class WhisperModelInstaller @Inject constructor(@ApplicationContext private val context: Context) {
    fun installIfNeeded(): Path {
        val target = context.filesDir.toPath().resolve("whisper-model/$MODEL_FILE")
        if (Files.exists(target) && Files.size(target) > 0) return target
        Files.createDirectories(target.parent)
        context.assets.open("models/$MODEL_FILE").use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }
    private companion object { const val MODEL_FILE = "ggml-tiny.en.bin" }
}
```

Bundled at `app/src/main/assets/models/ggml-tiny.en.bin` (~39 MB).
Asset-only; we don't need to download.

**APK size impact:** ~39 MB → final APK growth from current ~30 MB
to ~70 MB. Acceptable for MVP per the Stage 2-A spec memory: "bundle
for MVP; lazy-download is a Phase 10 optimization if Play Store
review flags size."

### `TranscriptionRunner`

```kotlin
@Singleton
class TranscriptionRunner @Inject constructor(
    private val repository: WalkRepository,
    private val engine: WhisperEngine,
) {
    suspend fun transcribePending(walkId: Long): Result<Int> = runCatching {
        val pending = repository.voiceRecordingsFor(walkId).filter { it.transcription == null }
        var count = 0
        for (recording in pending) {
            val absolute = /* resolve from filesDir + relativePath */
            engine.transcribe(absolute).fold(
                onSuccess = { result ->
                    val text = if (result.text.isBlank()) NO_SPEECH_PLACEHOLDER else result.text
                    val wpm = computeWpm(text, recording.durationMillis)
                    repository.updateVoiceRecording(
                        recording.copy(transcription = text, wordsPerMinute = wpm),
                    )
                    count++
                },
                onFailure = { Log.w(TAG, "transcribe failed for ${recording.id}", it) },
            )
        }
        count
    }

    companion object { const val NO_SPEECH_PLACEHOLDER = "(no speech detected)" }
}
```

Best-effort batch — per-recording failure logged, batch continues.
The Worker reports overall success regardless (else WorkManager would
retry, repeatedly burning CPU on a busted recording).

### `TranscriptionWorker` (Hilt-aware CoroutineWorker)

```kotlin
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

Constraints when enqueued:

```kotlin
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
```

**Unique-by-walkId** prevents double-enqueue if the user finishes,
returns to the walk somehow, finishes again. REPLACE means a re-run
re-attempts any failed transcriptions from the prior run.

### `TranscriptionScheduler` indirection

WalkViewModel doesn't talk to WorkManager directly (Hilt + WorkManager
needs a top-level Singleton or we'd inject WorkManager into the VM,
which makes test setup painful). A `TranscriptionScheduler` interface
+ `WorkManagerTranscriptionScheduler` impl + `FakeTranscriptionScheduler`
test double mirrors the pattern.

```kotlin
interface TranscriptionScheduler {
    fun scheduleForWalk(walkId: Long)
}
```

WalkViewModel.finishWalk additionally calls
`transcriptionScheduler.scheduleForWalk(walkId)` after the controller
reaches Finished.

### Hilt + WorkManager integration

Add `androidx.hilt:hilt-work` + `androidx.hilt:hilt-compiler`. Update
`PilgrimApp` to implement `Configuration.Provider` and inject
`HiltWorkerFactory`. Disable WorkManager's default initializer in
`AndroidManifest.xml` (provider tools:node="remove" pattern).

## Build configuration

Add to `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17 -O3"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            // 16 KB page alignment for Android 15+
            useLegacyPackaging = false
        }
    }
}
```

NDK path: rely on Android Studio's bundled NDK; add `ndkVersion =
"25.2.9519653"` to pin.

### Vendored sources

`app/src/main/cpp/`:
```
CMakeLists.txt
whisper-jni.cpp        ← our shim
whisper.cpp/           ← upstream sources (vendored, NOT submodule)
  ggml.c
  ggml.h
  ggml-alloc.c
  ggml-alloc.h
  ggml-backend.c
  ggml-backend.h
  ggml-quants.c
  ggml-quants.h
  whisper.cpp
  whisper.h
```

Copy from upstream `whisper.cpp` repo at a pinned tag (`v1.7.5` or
later). NOT a git submodule — Pilgrim is a single-repo project; we
take the bytes once. Re-vendor manually when we want to bump.

CMakeLists.txt (minimal):
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(pilgrim-whisper)

set(CMAKE_CXX_STANDARD 17)
add_compile_options(-O3 -ffast-math)

# 16 KB page size alignment (Android 15+)
add_link_options("-Wl,-z,max-page-size=16384")

add_library(whisper STATIC
    whisper.cpp/ggml.c
    whisper.cpp/ggml-alloc.c
    whisper.cpp/ggml-backend.c
    whisper.cpp/ggml-quants.c
    whisper.cpp/whisper.cpp
)
target_include_directories(whisper PUBLIC whisper.cpp)

add_library(pilgrim-whisper SHARED whisper-jni.cpp)
target_link_libraries(pilgrim-whisper whisper log)
```

`whisper-jni.cpp` is ~80 lines — wraps `whisper_init_from_file_with_params`,
`whisper_full`, `whisper_get_segment_text`, `whisper_free`.

## Testing

**Unit tests (no device required):**

1. `TranscriptionRunnerTest`:
   - transcribes pending rows, skips already-transcribed
   - empty result → stored as "(no speech detected)"
   - per-recording failure logged, batch continues
   - WPM computed from text + duration
2. `WalkViewModelTest`: extend with "finishWalk schedules transcription"
   asserting `FakeTranscriptionScheduler` recorded the walkId
3. NO unit test for `WhisperCppEngine` — JNI loading needs a device.
   Stage 2-F's device test verifies real inference end-to-end.

**Stage 2-F device test target:**
- Record 3-5 voice notes during a 20-min walk
- Tap Finish
- Within 30 s, all `transcription` fields populated in Room

## What this stage does NOT do

- No Stage 2-E UI: transcription field stays invisible until 2-E adds
  the row display + edit
- No retry policy beyond WorkManager's default exponential backoff
- No transcription progress indicator (Stage 2-E if useful)
- No language detection — `tiny.en` is English-only by definition
- No model size variants (small/medium) — single model bundled
- No streaming transcription mid-recording — post-walk batch only

## Forward-carry for Stage 2-E

- Display `transcription` if non-null, otherwise "transcribing…"
- "(no speech detected)" is a literal string the UI should treat as
  "transcribed but empty" — don't render as if it were a quote
- Edit UI must call `WalkRepository.updateVoiceRecording` with the
  full row (read-then-`.copy(transcription = newText)`)
- Sweeper must clean transcription if user deletes recording — DB
  cascade handles row deletion; file cleanup is sweeper's job

## Success criteria

- `./gradlew assembleDebug lintDebug testDebugUnitTest` green
- 129 → ~138 tests passing
- APK builds with vendored whisper.cpp + bundled model
- APK size delta documented (~+39 MB)
- WalkViewModel.finishWalk schedules a TranscriptionWorker (assertable
  via `WorkManagerTestInitHelper`)
- TranscriptionRunner correctly batches with skip-on-non-null and
  best-effort error handling
- 16 KB page-size alignment verified (Android 15+ compatibility)
