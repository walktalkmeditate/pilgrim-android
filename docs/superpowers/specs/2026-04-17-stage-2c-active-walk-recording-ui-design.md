# Stage 2-C — Active Walk record-button UI + level meter

## Context

Stage 2-A shipped the `voice_recordings` table. Stage 2-B shipped
`VoiceRecorder` (AudioRecord → WAV + `audioLevel: StateFlow<Float>` +
typed error Result). Stage 2-C wires the two together: a record button
+ live level meter on the Active Walk screen that persists a
`VoiceRecording` row when the user taps stop.

No transcription (Stage 2-D). No Summary-screen playback (Stage 2-E).

## What ships

1. `WalkViewModel` extended with recording surface: a
   `VoiceRecorderUiState` sealed (`Idle` / `Recording` /
   `Error(message, kind)`), an `audioLevel: StateFlow<Float>` relay,
   a `recordingsCount: StateFlow<Int>` for the "N saved" indicator,
   and `toggleRecording()` / `emitPermissionDenied()` methods.
2. A new `RecordControl` composable on `ActiveWalkScreen` below the
   stats row: 56 dp circular button (rust when recording, stone when
   idle), animated radial level-meter ring, disabled when walk state
   isn't in-progress, with a transient error banner above it.
3. Just-in-time `RECORD_AUDIO` request via
   `rememberLauncherForActivityResult(RequestPermission())` — the
   launcher lives in the composable, not the ViewModel, so the
   ViewModel stays platform-agnostic.
4. Auto-stop when `WalkController.state` transitions to `Finished`
   mid-recording, so a walk finalize never leaves a ghost recording.
5. Unit tests covering the state machine, error mapping, auto-stop,
   and permission-denied surface.

## Architecture decisions

### One ViewModel, not two

The user's brief called it "ActiveWalkViewModel extension" but the
project today has a single `WalkViewModel`. Two options:

- **Extend `WalkViewModel`** — one DI-injected VM, walk state and
  recording state co-located, single `collectAsStateWithLifecycle`
  binding from the Compose side. Matches existing pattern
  (`WalkViewModel` already handles start/pause/resume/finish +
  `uiState` + `routePoints`).
- **Separate `ActiveWalkRecordingViewModel`** — purer separation, but
  requires coordinating walkId lookup, doubles the `hiltViewModel()`
  calls in `ActiveWalkScreen`, and the recording surface is small
  enough (~60 LOC) to not justify the split.

**Picked: extend `WalkViewModel`.** Matches the precedent; recording
is a walk-scoped concern; no benefit to splitting for scope this small.

### No interface refactor on `VoiceRecorder`

Could introduce a `VoiceRecorder` interface + `VoiceRecorderImpl`
concrete to enable simpler test mocking. Rejected — the existing
Stage 2-B tests construct `VoiceRecorder` with `FakeAudioCapture` +
`FakeClock` and exercise real behavior. `WalkViewModelTest` extensions
for recording will do the same. Matches how `WalkViewModelTest` tests
`WalkController` integration rather than mocking the controller.

Tradeoff: the ViewModel tests exercise real `VoiceRecorder` threading
(the Executor + CountDownLatch). That's been proven reliable in Stage
2-B's 11-test suite; carrying it through to ViewModel tests is
acceptable.

### Threading — ViewModel is the `Dispatchers.IO` boundary

`VoiceRecorder.stop()` is a blocking call (100 ms on `doneLatch.await()`
per the Stage 2-B kdoc). The ViewModel's `toggleRecording()` launches
into `viewModelScope` with `withContext(Dispatchers.IO)` wrapping the
VoiceRecorder call + the Room insert. The Compose `onClick` never
touches IO.

### Permission just-in-time — launcher in Compose

```kotlin
val permLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
    if (granted) viewModel.toggleRecording()
    else viewModel.emitPermissionDenied()
}
val onRecordTap = {
    if (PermissionChecks.isMicrophoneGranted(context)) {
        viewModel.toggleRecording()
    } else {
        permLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
}
```

ViewModel is platform-agnostic; the Compose layer owns the launcher.
If permission is denied, the UI calls `emitPermissionDenied()` which
flips the ViewModel to `VoiceRecorderUiState.Error("microphone permission required", PermissionDenied)`.

### Error UX — transient banner, auto-dismiss 4 s

Error state holds a user-readable message + a `kind` enum
(`PermissionDenied` / `CaptureInitFailed` / `Cancelled` / `Other`).
The banner surfaces only for `kind != Cancelled` — an `EmptyRecording`
from VoiceRecorder (user tapped stop too fast, or background-kill per
the Stage 2-B FGS forward-carry) maps to `Cancelled` and is silently
swallowed.

A LaunchedEffect keyed on the error instance schedules a 4 s auto-
dismiss (sets state back to `Idle`). Manual dismiss via tap on the
banner's close icon.

### Auto-stop on Finished

Inside `WalkViewModel.init {}` (or a companion `LaunchedEffect` in the
screen — I'll pick the ViewModel side so it works regardless of UI
lifecycle):

```kotlin
viewModelScope.launch {
    controller.state.collect { state ->
        if (state is WalkState.Finished && recorderState.value is Recording) {
            toggleRecording()  // will stop + persist
        }
    }
}
```

Must not deadlock: `toggleRecording()` dispatches to IO; the collector
runs on the VM's scope; no same-thread re-entry.

### `recordingsCount` and walkId sourcing

`collectAsStateWithLifecycle` on `observeVoiceRecordings(walkId)` needs
a live walkId. Source from `uiState.value.walkState` — when the state
is `Active`/`Paused`/`Meditating`, the walk is accessible; when
`Idle`/`Finished`, the count is 0.

Implementation: flatMapLatest over walkState to swap the Room
subscription when the walkId changes.

### File on disk, no DB row → orphan on insert failure

If `VoiceRecorder.stop()` returns success but `walkRepository.recordVoice(recording)` throws (DB disk full, etc.), we have a `.wav` on disk with no DB row. This is the Stage 2-E cleanup sweeper's problem; we accept it for 2-C. The error banner surfaces the failure with kind=`Other`.

## What this stage does NOT do

- No Compose UI tests (Paparazzi/Shot lands in Phase N). Unit-test
  the ViewModel only.
- No FGS type=microphone. Screen-on-only recording; Stage 2-B's
  `EmptyRecording` catches background kills.
- No visual polish on the error banner beyond Pilgrim tokens.
- No animation polish on the level meter beyond `animateFloatAsState`.
- No recording list / playback / edit — Stage 2-E.
- No transcription indicator ("transcribing…") — Stage 2-D.

## Error-kind mapping

| VoiceRecorderError | UI kind | Banner message | Behavior |
|---|---|---|---|
| `PermissionMissing` | `PermissionDenied` | "microphone permission required" | surface banner |
| `ConcurrentRecording` | `Other` | "a recording is already in progress" | surface banner (should be impossible via UI) |
| `NoActiveRecording` | `Other` | "no recording to stop" | surface banner (should be impossible via UI) |
| `AudioCaptureInitFailed` | `CaptureInitFailed` | "couldn't start the microphone" | surface banner |
| `FileSystemError` | `Other` | "couldn't save the recording" | surface banner |
| `EmptyRecording` | `Cancelled` | (none) | silent, state → Idle |

Compose UI layer pre-denies via permission launcher if `PermissionMissing`
fires, covering the "user dug into Settings and revoked mid-session"
case.

## Tests (Robolectric + JUnit 4 + Turbine, no Compose UI)

Extend `WalkViewModelTest`:

1. `toggleRecording when idle starts recording and emits Recording` — assert
   `voiceRecorderState` goes Idle → Recording.
2. `toggleRecording when recording stops and inserts a row into Room` —
   assert the row lands in `voiceRecordingsFor(walkId)` and state
   returns to Idle.
3. `stop on an empty recording maps to Cancelled and no DB row` — drive
   `VoiceRecorder` via a `FakeAudioCapture(bursts = emptyList())`.
4. `permission denied emits Error with kind=PermissionDenied` — call
   `emitPermissionDenied()` directly.
5. `AudioCapture init failure emits Error with kind=CaptureInitFailed` —
   `FakeAudioCapture(startThrowable = IllegalStateException())`.
6. `WalkState transitioning to Finished while recording auto-stops` —
   controller → finishWalk; assert the in-flight recording is stopped
   and persisted.
7. `recordingsCount reflects DB state for the active walk` — insert
   rows directly; assert count flow updates.
8. `error auto-dismisses after 4 seconds` — advance test dispatcher by
   4 s; assert state returns to Idle.

Fake strategy: the existing `FakeAudioCapture` + `AudioFocusCoordinator`
(with the real `AudioManager`) construct a real `VoiceRecorder`. This
matches how `WalkControllerTest` uses a real `WalkController` with a
`FakeClock`. The ViewModel test thus exercises end-to-end.

## Success criteria

- `./gradlew assembleDebug lintDebug testDebugUnitTest` green.
- 120 → ~128 tests passing.
- User on a device can tap record, see the level meter respond,
  tap stop, and see "1 recording saved" appear below the button.
- DB row inserts via `WalkRepository.recordVoice`.
- Walk finalize (tap Finish) auto-stops any in-flight recording.
- Zero regressions on existing 120 tests.

## Deferred / forward-carry

- **Stage 2-D** will read rows where `transcription IS NULL` and
  transcribe via whisper.cpp.
- **Stage 2-E** will add playback, transcript edit, swipe-to-delete
  + file sweeper.
- **Phase N** will add Paparazzi snapshot tests for the record button
  / level meter.
