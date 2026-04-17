# Stage 2-B — Audio capture (AudioRecord + WAV + mic permission)

## Context

Stage 2-A shipped the `voice_recordings` Room table. Stage 2-B adds
the **capture pipeline** that produces rows for that table: tap record
→ AudioRecord reads 16kHz mono PCM → WAV file on disk → `VoiceRecording`
row ready to insert.

No UI (Stage 2-C), no transcription (Stage 2-D), no playback (Stage
2-E).

## Source of truth (iOS)

`pilgrim-ios/Pilgrim/Models/Walk/WalkBuilder/Components/VoiceRecordingManagement.swift`:
- `AVAudioRecorder` at 44.1kHz mono AAC M4A, high quality.
- Files at `Documents/Recordings/{walkUUID}/{recordingUUID}.m4a`.
- Toggle UX (`toggleRecording()`), audio level via metering timer at 20 Hz.
- `AudioSessionCoordinator` reference-counts audio consumers to share an
  `AVAudioSession` between voice recording, voice guide playback, and
  soundscapes.
- On walk-builder flush (pre-snapshot) the current recording is
  finalized via `flushCurrentRecording`.

## Android translations

| iOS | Android |
|---|---|
| `AVAudioRecorder` @ 44.1kHz AAC | `AudioRecord` @ **16kHz mono 16-bit PCM** (Whisper-ready, simpler pipeline; can still play via ExoPlayer). |
| `AudioSessionCoordinator` | `AudioManager.requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT)` on start; `abandonAudioFocusRequest` on stop. No ref-counting this stage — single consumer (`VoiceRecorder`). Future stages may add a shared coordinator if multiple consumers appear. |
| 20 Hz metering timer | RMS over the AudioRecord read-buffer each iteration of the capture loop. At a 100 ms buffer the emit rate is ~10 Hz — plenty for a UI level meter. |
| `Documents/Recordings/...` | `context.filesDir/recordings/{walkUuid}/{recordingUuid}.wav` |
| Walk-builder flush | **Not in 2-B.** Stage 2-C's ViewModel observes `WalkController.state`; if it becomes `Finished` with an active recording, it calls `VoiceRecorder.stop()`. The recorder itself stays lifecycle-agnostic. |

## Architecture

Three components, one interface, clean boundaries.

```
┌──────────────────────────────────────────────────┐
│ VoiceRecorder  (@Singleton)                      │
│  - start(walkId, walkUuid): Result<Path>         │
│  - stop(): Result<VoiceRecording>                │
│  - audioLevel: StateFlow<Float>                  │
│                                                  │
│  owns:                                           │
│    - AudioCapture (interface)                    │
│    - WavWriter                                   │
│    - AudioFocusCoordinator                       │
│    - dedicated single-thread Executor            │
│    - synchronized state: Idle / Active(session)  │
└──────────────────────────────────────────────────┘
       │                       │
       │                       │
       ▼                       ▼
┌───────────────────┐    ┌─────────────────────────┐
│ AudioCapture      │    │ WavWriter               │
│   (interface)     │    │  (pure Kotlin)          │
│                   │    │                         │
│ AudioRecordCapture│    │  - openForWriting(Path) │
│   (production)    │    │  - append(ShortArray)   │
│                   │    │  - closeAndPatchHeader  │
│ FakeAudioCapture  │    └─────────────────────────┘
│   (tests)         │
└───────────────────┘
```

### `AudioCapture` interface (mockability for tests)

```kotlin
interface AudioCapture {
    /** Initialize the underlying recorder. Throws if uninitialized. */
    fun start()
    /** Blocking read of up to [buffer.size] shorts. Returns count read, or -1 on EOF / error. */
    fun read(buffer: ShortArray): Int
    /** Stop + release. */
    fun stop()
    /** Sample rate in Hz (e.g., 16000). */
    val sampleRateHz: Int
    /** Channel count (1 for mono). */
    val channels: Int
}
```

Matches the `LocationSource` / `FusedLocationSource` pattern. Production
impl wraps `android.media.AudioRecord`. Test impl (`FakeAudioCapture`)
feeds pre-defined PCM frames on call.

### `WavWriter` — pure-Kotlin, hardware-independent

```kotlin
class WavWriter(private val path: Path, private val sampleRateHz: Int) {
    fun openForWriting()              // writes 44-byte header placeholder
    fun append(samples: ShortArray, count: Int)   // little-endian
    fun closeAndPatchHeader(): Long   // patches data-size + chunk-size, returns byte count written
}
```

WAV spec is tiny and well-defined. The header is 44 bytes:

| Offset | Bytes | Content |
|---|---|---|
| 0 | 4 | "RIFF" |
| 4 | 4 | chunk size (file size - 8) *← patched on close* |
| 8 | 4 | "WAVE" |
| 12 | 4 | "fmt " |
| 16 | 4 | 16 (subchunk size for PCM) |
| 20 | 2 | 1 (audio format = PCM) |
| 22 | 2 | num channels (1) |
| 24 | 4 | sample rate (16000) |
| 28 | 4 | byte rate (sample rate * channels * bits/8 = 32000) |
| 32 | 2 | block align (channels * bits/8 = 2) |
| 34 | 2 | bits per sample (16) |
| 36 | 4 | "data" |
| 40 | 4 | data size *← patched on close* |
| 44 | N | little-endian 16-bit PCM samples |

All multi-byte fields are little-endian. Tests verify byte-exact header
for an empty file, for a file with 3 samples, and that the data-size
patch is correct.

### `VoiceRecorder` — the orchestrator

State machine:
```
Idle ──start()──> Active(session)
               \── on error → Idle + Err(...)

Active ──stop()──> Idle + Ok(VoiceRecording)
                \── on error → Idle + Err(...)  (best-effort file cleanup)
```

Thread model:
- A dedicated **single-thread Executor** (`Executors.newSingleThreadExecutor("voice-recorder")`). Only this thread touches `AudioCapture.read` + `WavWriter.append`.
- State transitions (`Idle` ↔ `Active`) synchronized on `VoiceRecorder`'s own intrinsic lock. `start()`/`stop()` run on the caller's thread, mutate state, then post the read-loop task to the Executor.
- `audioLevel` is a `MutableStateFlow<Float>` updated from the capture thread. StateFlow is thread-safe for emitters; collectors see the latest value regardless of which thread set it. Reset to `0f` on `stop()` / error.

### Error types (sealed)

```kotlin
sealed class VoiceRecorderError : Exception() {
    data object PermissionMissing : VoiceRecorderError() {
        override val message = "RECORD_AUDIO not granted"
    }
    data object ConcurrentRecording : VoiceRecorderError() {
        override val message = "a recording is already in progress"
    }
    data object NoActiveRecording : VoiceRecorderError() {
        override val message = "stop() called with no active recording"
    }
    data class AudioCaptureInitFailed(override val cause: Throwable? = null) : VoiceRecorderError() {
        override val message = "AudioRecord failed to initialize"
    }
    data class FileSystemError(override val cause: Throwable) : VoiceRecorderError() {
        override val message = "failed to create recording file: ${cause.message}"
    }
}
```

`start()` / `stop()` return `Result<T>`. `Result.getOrThrow()` returns
the typed exception; `Result.exceptionOrNull()` is the fast discrimination
path for Stage 2-C's UI.

### Audio focus

On `start()`:
```kotlin
val request = AudioFocusRequest.Builder(AUDIOFOCUS_GAIN_TRANSIENT)
    .setAudioAttributes(/* USAGE_VOICE_COMMUNICATION, CONTENT_TYPE_SPEECH */)
    .setWillPauseWhenDucked(false)
    .build()
audioManager.requestAudioFocus(request)
```

On `stop()` and all error-rollback paths:
```kotlin
audioManager.abandonAudioFocusRequest(request)
```

Encapsulate in an `AudioFocusCoordinator` helper so the request-object
lifetime is managed in one place.

### File layout

```
{filesDir}/
  recordings/
    {walkUuid1}/
      {recordingUuid1}.wav
      {recordingUuid2}.wav
    {walkUuid2}/
      {recordingUuid3}.wav
```

Stored with **relative path from filesDir** in the DB row:
`recordings/{walkUuid}/{uuid}.wav`. This matches iOS's `fileRelativePath`
convention and keeps the row portable across backup/restore (if/when
Stage 10 ships export/import).

### The returned `VoiceRecording`

```kotlin
VoiceRecording(
    walkId = walkId,
    startTimestamp = /* clock.now() at start */,
    endTimestamp = /* clock.now() at stop */,
    durationMillis = end - start,                 // invariant-safe
    fileRelativePath = "recordings/$walkUuid/$uuid.wav",
    // transcription, wordsPerMinute, isEnhanced stay null / default
)
```

`VoiceRecorder` does NOT insert into Room — it returns a valid entity.
Stage 2-C's ViewModel decides when to persist. This keeps the recorder
pure and lets the UI choose to discard a recording the user cancels
without a DB write.

## What this stage does NOT do

- No UI. Record button lives in Stage 2-C.
- No Room insert. Caller handles persistence.
- No file-cleanup sweeper for orphaned files (rows without files, files
  without rows). Stage 2-E will pair playback with a sweeper.
- No FGS type=microphone. Voice recording happens with screen on and
  the app in the foreground per the forward-carry note below.
- No retry on transient errors. Stage 2-C surfaces failures to the user.

## Forward-carry: Android 14+ FGS microphone types

API 34+ requires `FOREGROUND_SERVICE_TYPE_MICROPHONE` for mic access
when the app is not in the visible foreground. Our existing
`WalkTrackingService` is typed as `FOREGROUND_SERVICE_TYPE_LOCATION`.

**MVP decision:** voice recording is **screen-on only**. The iOS
experience is the same — the user taps record when they want to capture
a thought; the screen is on; the app is foregrounded. If we later want
to support background voice notes (e.g., tap record, pocket phone,
keep recording with screen off), we bump the FGS to dual type
`FOREGROUND_SERVICE_TYPE_LOCATION | FOREGROUND_SERVICE_TYPE_MICROPHONE`
and plumb through. Not in 2-B scope.

If the user backgrounds the app mid-recording, Android 14+ will kill
the AudioRecord with a `SecurityException`. Stage 2-C will catch this
and surface a "recording stopped when the app went to the background"
message. For 2-B we document that the stream ends and the `stop()`
path runs the same finalize as a clean stop (best-effort).

## Mic permission

- Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />`
  to `AndroidManifest.xml`.
- Extend `PermissionChecks` with:
  ```kotlin
  fun isMicrophoneGranted(context: Context): Boolean =
      ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
          == PackageManager.PERMISSION_GRANTED
  ```
- Not added to onboarding flow. Stage 2-C requests it just-in-time
  when the user taps the record button.
- `VoiceRecorder.start()` checks permission and returns
  `Result.failure(PermissionMissing)` if not granted. Defensive gate.

## Hilt DI

`AudioModule` in `di/`:
```kotlin
@Provides
fun provideAudioCapture(@ApplicationContext context: Context): AudioCapture =
    AudioRecordCapture(/* sampleRateHz = 16000, channels = 1 */)

@Provides
@Singleton
fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
    context.getSystemService(AudioManager::class.java)
```

The production `AudioRecordCapture` doesn't need Context (we construct
AudioRecord from static constants); included for DI uniformity.

## Tests (Robolectric + JUnit 4 + Turbine, no hardware)

### `WavWriterTest`
1. Empty file: 44-byte header, all fields byte-exact per spec.
2. One sample: header patched to data_size=2, chunk_size=38.
3. Three samples (0x0001, 0x7FFF, 0x8001): little-endian bytes
   `01 00 FF 7F 01 80` after the header.
4. Sample rate and byte rate derived correctly (16000 → 32000 byte rate).
5. Reopening + reading: `RandomAccessFile` reads the same bytes back.

### `VoiceRecorderTest`
Uses `FakeAudioCapture` that emits a canned 8000-sample (0.5s) burst
on `read()`, then -1 on subsequent calls.

1. `start → stop` produces a valid `VoiceRecording` with end > start,
   duration_millis positive, file exists at returned path.
2. `start()` twice → second returns `ConcurrentRecording` error,
   first recording unaffected.
3. `stop()` without `start()` → `NoActiveRecording` error.
4. `VoiceRecorder` with `FakeAudioCapture` that throws on `start()` →
   `start()` returns `AudioCaptureInitFailed`, state returns to Idle.
5. `audioLevel` emits non-zero during recording, resets to 0 on stop.
6. Directory `recordings/{walkUuid}/` is created on first recording;
   second recording for same walk reuses it.
7. `VoiceRecorder.start()` with `RECORD_AUDIO` not granted →
   `PermissionMissing`. (Robolectric `ShadowApplication.grantPermissions`
   covers the granted path; leave default-denied for the negative test.)
8. `stop()` closes the WAV file and patches header (byte-length check).

## Success criteria

- `./gradlew assembleDebug lintDebug testDebugUnitTest` green.
- All tests passing (100 existing + ~13 new = ~113).
- `VoiceRecorder.start()` + `.stop()` → `VoiceRecording` that validates
  against Stage 2-A's `init { require(...) }` invariants.
- Zero existing tests broken.
- No lint warnings beyond pre-existing ones.

## Deferred to later stages

- **Stage 2-C**: record button UI, level-meter UI, mic permission
  just-in-time request, record-while-active-walk gating,
  `WalkController.state` observer that auto-stops on `Finished`.
- **Stage 2-D**: whisper.cpp JNI, transcription runner that reads the
  WAV via `AudioCapture`-like interface.
- **Stage 2-E**: playback via ExoPlayer, orphan-file sweeper,
  transcript edit UI.
- **Future**: FGS type=microphone for background recording, audio
  session ref-counting for concurrent soundscape playback,
  `VoiceEnhancer` equivalent (AI cleanup matching iOS `isEnhanced`).
