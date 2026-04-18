# Stage 2-E Design — Walk Summary voice-recording surface + orphan-WAV sweeper

**Date:** 2026-04-17
**Stage:** 2-E
**Closes:** Phase 2 (voice recording + transcription) UX surface; sets up Stage 2-F for the device-level integration test

## Intent

After a walk, the user opens the Walk Summary and sees the voice notes they recorded during the walk — each as a row showing the recording's offset into the walk, its duration, the on-device transcription (or "transcribing…" while Stage 2-D's worker hasn't finished yet), and a play button (ExoPlayer). Quietly, in the background, the app cleans up orphaned WAV files and Room rows that get out of sync from the various process-kill / timeout / crash paths Stages 2-B through 2-D enumerate.

## Two pieces, one PR

1. **Walk Summary voice-recording list** — read-only display + ExoPlayer playback of recordings for the displayed walk.
2. **Orphan-recording sweeper** — reconciles `recordings/<walkUuid>/*.wav` files on disk with `voice_recordings` rows in Room.

## Architecture

```
WalkSummaryScreen (Compose)
    └─ VoiceRecordingsSection (LazyColumn-style — actually a Column, since
                               Stage 2-E recordings count is small in practice
                               and we're already inside a verticalScroll on
                               WalkSummaryScreen — nested LazyColumn would
                               throw. Stage 2-F's instrumented test caps at
                               ~5 recordings; if a future stage produces
                               50+, we restructure WalkSummaryScreen to
                               LazyColumn at that point.)
        └─ VoiceRecordingRow (one per recording)
            ├─ RelativeTimeText — "+12:34"
            ├─ DurationText — "0:45"
            ├─ TranscriptionDisplay — text | "transcribing…" | italic-no-speech
            ├─ WpmCaption — "120 wpm" when wordsPerMinute non-null
            └─ PlayPauseButton — observes VoicePlaybackController.state

WalkSummaryViewModel
    ├─ state: StateFlow<WalkSummaryUiState>             (existing)
    ├─ recordings: StateFlow<List<VoiceRecording>>       (NEW)
    ├─ playback: StateFlow<PlaybackUiState>              (NEW)
    ├─ play(recording), pause(), stop()                  (NEW — delegates)
    └─ init { sweeper.sweep(walkId) }                    (NEW — fire and forget)

VoicePlaybackController (interface, @Singleton-bound)
    ├─ state: StateFlow<PlaybackState>
    ├─ play(recording: VoiceRecording)  — stops current, starts new
    ├─ pause()
    ├─ stop()
    └─ release() — called from VM.onCleared

ExoPlayerVoicePlaybackController (production impl)
    ├─ Holds androidx.media3.exoplayer.ExoPlayer instance
    ├─ Holds AudioFocusCoordinator reference
    ├─ Listens to ExoPlayer.Listener for completion → state=Idle
    └─ Lazy-creates ExoPlayer on first play() (mirrors WhisperCppEngine's lazy init)

FakeVoicePlaybackController (test impl)
    ├─ Records play/pause/stop calls
    └─ Manual transition control: completePlayback() helper

OrphanRecordingSweeper (@Singleton)
    ├─ suspend fun sweep(walkId: Long): SweepResult
    ├─ suspend fun sweepAll(): SweepResult — for the periodic worker
    └─ Cases (a) (b) (c) (d) — see below

OrphanSweeperWorker (@HiltWorker, CoroutineWorker)
    ├─ Runs daily via WorkManager.enqueueUniquePeriodicWork
    ├─ Constraints: storage NOT low; battery NOT low (cleanup is best-effort)
    ├─ Backoff: exponential, default
    └─ Output: SweepResult fields written to Data for adb-debugability

OrphanSweeperScheduler (interface; FakeOrphanSweeperScheduler for tests)
    ├─ scheduleDaily() — called from PilgrimApp.onCreate
    └─ WorkManagerOrphanSweeperScheduler impl
```

## Sweeper case handling

For each walk's `recordings/<walkUuid>/` directory:

**Case (a): WAV file on disk with no Room row**
- Source of orphan: WalkController crashed mid-INSERT, or the user manually deleted the row via a future trash UI without deleting the file.
- Detection: `Files.list(walkDir)` produces files; cross-reference with `voiceRecordingsFor(walkId).map { it.fileRelativePath }`.
- Action: delete file. Defensive guard: file path must resolve under `filesDir/recordings/<walkUuid>/` (no `..` traversal); file must end in `.wav`; never delete a directory.

**Case (b): Room row pointing to a missing WAV**
- Source: user cleared app data partially (wiped filesDir but kept Room — happens via adb), or the file was deleted by a future cleanup that didn't clean the row.
- Detection: for each row in `voiceRecordingsFor(walkId)`, check `Files.exists(filesDir.resolve(row.fileRelativePath))`.
- Action: delete row via `repository.deleteVoiceRecording(row)`. CASCADE-safe — no FK currently points at `voice_recordings.id`.

**Case (c): Row whose `transcription` is null AND WAV has `dataSize == 0`**
- Source: process killed mid-capture; `withTimeoutOrNull` in `WalkViewModel.finishWalk` fired before VoiceRecorder.stop's loop completed; the WAV header was written at start (44 bytes) but the writer never finalized the data subchunk size.
- Detection: read 4 bytes at WAV file offset 40 (the `data` subchunk size field, little-endian uint32). If 0 AND row's `transcription` is null, treat as zombie.
- Action: delete both row and file.
- Why the `transcription == null` predicate: a fully-transcribed row whose WAV has `dataSize == 0` is impossible (whisper returned text from 0 bytes? would have produced NO_SPEECH_PLACEHOLDER, not real text — but to be safe, only sweep rows with no transcription so we never delete a row the user might value).

**Case (d): Recording has `transcription == null` AND WAV has `dataSize > 0` AND no transcription is currently scheduled for the walk**
- Source: the auto-stop completed AFTER `finishWalk`'s 5s `withTimeoutOrNull` fired. The transcription was scheduled at timeout, the worker ran (didn't see this row because it wasn't inserted yet), then the row landed.
- Detection: row's `transcription` is null, WAV exists with non-zero data size, NOT currently in (c) above.
- Action: re-call `transcriptionScheduler.scheduleForWalk(walkId)`. KEEP policy means a no-op if a worker is already in flight; otherwise enqueues a fresh batch.

**SweepResult:**
```kotlin
data class SweepResult(
    val orphanFilesDeleted: Int,    // case (a)
    val orphanRowsDeleted: Int,     // case (b)
    val zombieRowsDeleted: Int,     // case (c)
    val rescheduledWalks: Int,      // case (d) — count of distinct walkIds rescheduled
)
```

Per-case errors are logged but do not abort the sweep — best-effort, idempotent.

## Path safety

The case (a) deletion path is the single destructive operation in this design and gets defensive belt-and-suspenders treatment:

1. Resolve candidate file path against `context.filesDir.toPath().toAbsolutePath().normalize()`.
2. Resolve again with `recordings/$walkUuid/` prefix.
3. Verify `candidate.startsWith(recordingsRoot)` where `recordingsRoot` is `filesDir/recordings`.
4. Verify `candidate.fileName.toString().endsWith(".wav")`.
5. Verify `Files.isRegularFile(candidate)`.
6. Only THEN call `Files.delete(candidate)`.

Path traversal protection is the same pattern Stage 2-D used in `TranscriptionRunner`.

## ExoPlayer wiring

**Why ExoPlayer (media3) over MediaPlayer:**
- MediaPlayer is unmaintained per AOSP guidance; new code goes to media3.
- ExoPlayer's `Player.Listener` exposes `onPlaybackStateChanged(state)` and `onIsPlayingChanged(isPlaying)` cleanly — MediaPlayer's `OnCompletionListener` requires manual state machine bookkeeping.
- ExoPlayer handles WAV out of the box (no extra extractor module needed for our 16 kHz mono PCM).

**Lifecycle:**
- `VoicePlaybackController` is `@Singleton` so a single ExoPlayer instance survives across screens (e.g., user navigates Active Walk → Summary → back; the player should not start fresh).
- ExoPlayer is lazy-created on first `play()` — same justification as `WhisperCppEngine` (~XX MB native overhead the user shouldn't pay if they never play a recording).
- `release()` called from `WalkSummaryViewModel.onCleared()` — releases ExoPlayer's native resources. The next `play()` re-creates.

**One-recording-at-a-time:**
- `play(recording)` sequence:
  1. If currently playing: `exoPlayer.stop()` + wait for `onPlaybackStateChanged(STATE_IDLE)`.
  2. `exoPlayer.setMediaItem(MediaItem.fromUri(recording.absoluteFile.toUri()))`.
  3. `exoPlayer.prepare()` + `exoPlayer.play()`.
  4. `state.value = Playing(recordingId = recording.id)`.

**Audio focus:**
- Acquire transient focus via `AudioFocusCoordinator.requestTransient()` on play; abandon on stop/pause/completion.
- ExoPlayer also has built-in focus handling via `setAudioAttributes(..., handleAudioFocus = true)` — use that AND our coordinator? They duplicate. **Decision:** disable ExoPlayer's built-in focus (`handleAudioFocus = false`) and route through our existing coordinator so the recorder + playback share one focus owner and don't trample each other.

## State flows

```kotlin
sealed class PlaybackState {
    data object Idle : PlaybackState()
    data class Playing(val recordingId: Long, val positionMs: Long) : PlaybackState()
    data class Paused(val recordingId: Long, val positionMs: Long) : PlaybackState()
    data class Error(val recordingId: Long, val message: String) : PlaybackState()
}

data class PlaybackUiState(
    val playingRecordingId: Long?,
    val isPlaying: Boolean,           // true only when Playing
    val errorMessage: String?,
)
```

`WalkSummaryViewModel.playback` maps `VoicePlaybackController.state` → `PlaybackUiState` for UI consumption (UI doesn't need positionMs at this stage; a later progress-bar polish would re-introduce it).

## ExoPlayer dependency

Add to `gradle/libs.versions.toml`:
```toml
media3 = "1.5.1"
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
```

Just `media3-exoplayer` — no UI module (we render our own controls), no DASH/HLS extractor (we play local WAV).

## Testing

- **`OrphanRecordingSweeperTest`** (Robolectric + Room in-memory + filesDir manipulation):
  - case (a) — orphan WAV deleted, row preserved
  - case (b) — orphan row deleted, file already missing
  - case (c) — zombie (null transcription + dataSize=0 WAV) → both deleted
  - case (d) — null transcription + valid WAV → scheduler.scheduleForWalk called
  - case (a+b+c+d) mixed in one sweep — counts add correctly
  - case where canonical-path guard rejects: place a `.wav` file ABOVE `recordings/` in filesDir → must NOT be deleted
  - sweepAll: iterates all walks
  - log-and-continue: file delete throws → SweepResult counts the failure (log only) and the rest of the sweep proceeds

- **`VoicePlaybackControllerTest`** (Robolectric):
  - Production `ExoPlayerVoicePlaybackController` is NOT unit-tested (ExoPlayer needs the Android media stack — Stage 2-F's instrumented test). Test the **interface contract via FakeVoicePlaybackController** + a small protocol test.
  - Actually — better: test the **state-machine logic** in a wrapper class. Skip a true ExoPlayer unit test; assert the lifecycle in Stage 2-F. For Stage 2-E unit tests:
    - `FakeVoicePlaybackController` records calls and exposes `state: MutableStateFlow<PlaybackState>` for test manipulation.
    - `WalkSummaryViewModelTest` uses the fake and asserts: play() updates uiState, second play() stops the first, etc.
  - This matches the Stage 2-D pattern (`FakeWhisperEngine` for unit tests; real engine in 2-F).

- **`WalkSummaryViewModelTest`** (extend existing):
  - `recordings flow emits the walk's voice recordings`
  - `transcription null shows transcribing placeholder` — really a UI test, but at the VM level we just verify the field is null in the emitted list
  - `(no speech detected) literal flagged in UI state` — same: VM-level just verifies the field equals the placeholder string
  - `init triggers sweeper for displayed walk`
  - `play() delegates to controller; second play() preempts first`
  - `onCleared releases the controller`

## DI

```kotlin
@Module @InstallIn(SingletonComponent::class)
abstract class PlaybackModule {
    @Binds @Singleton
    abstract fun bindVoicePlaybackController(impl: ExoPlayerVoicePlaybackController): VoicePlaybackController

    @Binds @Singleton
    abstract fun bindOrphanSweeperScheduler(
        impl: WorkManagerOrphanSweeperScheduler,
    ): OrphanSweeperScheduler
}
```

`OrphanRecordingSweeper` is a plain `@Singleton class @Inject constructor(...)` — no interface (no need; we test the production class directly with in-memory Room).

## Manifest

No new permissions. `INTERNET` already declared (Mapbox). ExoPlayer playing local files needs nothing.

## PilgrimApp wiring

Add to `PilgrimApp.onCreate()`:
```kotlin
@Inject lateinit var orphanSweeperScheduler: OrphanSweeperScheduler
override fun onCreate() {
    super.onCreate()
    // ... existing Mapbox setup
    orphanSweeperScheduler.scheduleDaily()
}
```

Hilt injects field-injected dependencies on Application AFTER `super.onCreate()` returns (per `@HiltAndroidApp` docs), so `orphanSweeperScheduler` is available.

## Out of scope / forward-carries

- **Transcript editing** — read-only in Stage 2-E. Inline edit-in-place is a Phase 3 nice-to-have.
- **Recording deletion from UI** — same; Phase 3.
- **Playback position scrubber / progress bar** — Stage 2-F polish if device test reveals user demand.
- **Background playback notification** — out of scope; recordings are short (typically < 60s) and Stage 2-E plays in-foreground only.
- **Sweeper across all walks AT app start** — out of scope; the periodic worker handles this within 24h. Only the displayed walk gets the immediate-on-init sweep.

## Risks

1. **ExoPlayer + AudioFocusCoordinator integration** — if ExoPlayer's internal focus handling fights ours, audio could ducking-loop. Mitigation: explicit `setAudioAttributes(handleAudioFocus = false)` (verified in media3 docs).
2. **Sweeper deletes a real recording** — the canonical-path guard + `.wav` extension check + isRegularFile check should make this impossible. Tested explicitly.
3. **Reactive recordings flow re-renders the entire summary** — the `observeVoiceRecordings(walkId)` Flow emits on every Room write. If a user has many recordings and the transcription worker updates them in rapid succession, the LazyColumn rebuilds 5+ times. Compose handles this efficiently; not a concern for typical loads (3-10 recordings).
4. **Sweeper races with active playback** — sweeper might delete a file ExoPlayer has open. Mitigation: sweeper runs only on walk-summary `init` (before user has tapped play) and on the daily background worker (when app is closed; ExoPlayer instance is released). The only risk is a user navigating Walk Summary → start playback → background → daily worker fires while playback continues — extremely narrow window. If hit, ExoPlayer surfaces an error which the UI maps to a toast. Acceptable.

## Quality gates

- All four sweeper cases covered by deterministic tests.
- Compose summary renders without nested-LazyColumn warnings (Column inside verticalScroll is fine here).
- ExoPlayer's release() is called from `onCleared` (test asserts this).
- 138 → ~150 tests after Stage 2-E.
