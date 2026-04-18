# Stage 2-C Implementation Plan — Active Walk recording UI

**Spec:** [2026-04-17-stage-2c-active-walk-recording-ui-design.md](../specs/2026-04-17-stage-2c-active-walk-recording-ui-design.md)

**Test command:**
```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

**One deviation from the intent:** The spec called for `Icons.Default.Mic` / `Stop`, but those live in `material-icons-extended` (~35 MB). Using text labels ("REC" / "STOP") inside the circular button instead — matches Pilgrim's text-forward aesthetic and keeps APK lean. Revisit in Phase 10 polish if icons become necessary.

Task order: 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8. Green build/test between each.

---

## Task 1 — `VoiceRecorderUiState` sealed class

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/VoiceRecorderUiState.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

/**
 * UI-layer representation of the VoiceRecorder state machine. The
 * Compose layer switches on this to render the record button color /
 * label and the optional error banner.
 */
sealed class VoiceRecorderUiState {
    data object Idle : VoiceRecorderUiState()
    data object Recording : VoiceRecorderUiState()
    data class Error(val message: String, val kind: Kind) : VoiceRecorderUiState()

    enum class Kind {
        /** RECORD_AUDIO not granted at tap time. */
        PermissionDenied,
        /** AudioRecord failed to initialize (mic busy, OEM quirk). */
        CaptureInitFailed,
        /** User tapped stop before any PCM was captured. Silent path — do not banner. */
        Cancelled,
        /** Anything else (FS error, Room insert failure, concurrent state). */
        Other,
    }
}
```

---

## Task 2 — Extend `WalkViewModel` with recording surface

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` (modify)

Add imports:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
```

(Some of these — `flatMapLatest`, `flowOf` — are already imported for routePoints.)

Extend the constructor:

```kotlin
@HiltViewModel
class WalkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: WalkController,
    private val repository: WalkRepository,
    private val clock: Clock,
    private val voiceRecorder: VoiceRecorder,
) : ViewModel() {
```

Add the recording surface (place after the existing `routePoints` block, before `private fun walkIdOrNull`):

```kotlin
// ---- Voice recording (Stage 2-C) ----

private val _voiceRecorderState = MutableStateFlow<VoiceRecorderUiState>(VoiceRecorderUiState.Idle)
val voiceRecorderState: StateFlow<VoiceRecorderUiState> = _voiceRecorderState.asStateFlow()

/** Per-buffer RMS level published by VoiceRecorder. 0f..1f. */
val audioLevel: StateFlow<Float> = voiceRecorder.audioLevel

/**
 * Live count of VoiceRecording rows for the current walk. Swapped
 * via flatMapLatest whenever walkIdOrNull changes so we don't leak
 * a DAO subscription across walks.
 */
val recordingsCount: StateFlow<Int> = controller.state
    .map { walkIdOrNull(it) }
    .distinctUntilChanged()
    .flatMapLatest { walkId ->
        if (walkId == null) flowOf(0)
        else repository.observeVoiceRecordings(walkId).map { it.size }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = 0,
    )

/**
 * Toggle recording on/off. Caller must ensure mic permission is
 * granted before invocation (the Compose layer's permission
 * launcher owns that check) — if it isn't, we surface
 * PermissionDenied defensively.
 *
 * Dispatches to IO because VoiceRecorder.stop() is blocking on a
 * CountDownLatch while the capture loop finishes its last buffer.
 */
fun toggleRecording() {
    viewModelScope.launch(Dispatchers.IO) {
        val current = _voiceRecorderState.value
        if (current is VoiceRecorderUiState.Recording) {
            stopRecording()
        } else {
            startRecording()
        }
    }
}

fun emitPermissionDenied() {
    _voiceRecorderState.value = VoiceRecorderUiState.Error(
        message = "microphone permission required to record",
        kind = VoiceRecorderUiState.Kind.PermissionDenied,
    )
}

fun dismissRecorderError() {
    if (_voiceRecorderState.value is VoiceRecorderUiState.Error) {
        _voiceRecorderState.value = VoiceRecorderUiState.Idle
    }
}

private suspend fun startRecording() {
    val walkInfo = walkInfoOrNull() ?: return // walk ended between tap and dispatch
    val result = voiceRecorder.start(walkId = walkInfo.walkId, walkUuid = walkInfo.walkUuid)
    result.fold(
        onSuccess = { _voiceRecorderState.value = VoiceRecorderUiState.Recording },
        onFailure = { _voiceRecorderState.value = mapStartFailure(it) },
    )
}

private suspend fun stopRecording() {
    val result = voiceRecorder.stop()
    result.fold(
        onSuccess = { recording ->
            // Best effort — if the insert fails, the user sees an error
            // and the orphan .wav is Stage 2-E's sweeper problem.
            try {
                repository.recordVoice(recording)
                _voiceRecorderState.value = VoiceRecorderUiState.Idle
            } catch (e: Exception) {
                _voiceRecorderState.value = VoiceRecorderUiState.Error(
                    message = "couldn't save the recording",
                    kind = VoiceRecorderUiState.Kind.Other,
                )
            }
        },
        onFailure = { _voiceRecorderState.value = mapStopFailure(it) },
    )
}

private fun mapStartFailure(err: Throwable): VoiceRecorderUiState.Error = when (err) {
    is VoiceRecorderError.PermissionMissing -> VoiceRecorderUiState.Error(
        "microphone permission required to record",
        VoiceRecorderUiState.Kind.PermissionDenied,
    )
    is VoiceRecorderError.AudioCaptureInitFailed -> VoiceRecorderUiState.Error(
        "couldn't start the microphone",
        VoiceRecorderUiState.Kind.CaptureInitFailed,
    )
    is VoiceRecorderError.FileSystemError -> VoiceRecorderUiState.Error(
        "couldn't save the recording",
        VoiceRecorderUiState.Kind.Other,
    )
    is VoiceRecorderError.ConcurrentRecording -> VoiceRecorderUiState.Error(
        "a recording is already in progress",
        VoiceRecorderUiState.Kind.Other,
    )
    else -> VoiceRecorderUiState.Error(
        err.message ?: "recording failed",
        VoiceRecorderUiState.Kind.Other,
    )
}

private fun mapStopFailure(err: Throwable): VoiceRecorderUiState {
    // EmptyRecording = the user tapped stop before AudioRecord's
    // first buffer fully filled (or a silent background-kill). Both
    // are "cancelled" from the user's perspective — no banner.
    return if (err is VoiceRecorderError.EmptyRecording) {
        VoiceRecorderUiState.Idle
    } else {
        VoiceRecorderUiState.Error(
            message = err.message ?: "stop failed",
            kind = VoiceRecorderUiState.Kind.Other,
        )
    }
}

private data class WalkInfo(val walkId: Long, val walkUuid: String)

private suspend fun walkInfoOrNull(): WalkInfo? {
    // Read walk row via the repository to get the uuid — controller
    // state only has walkId.
    val walkId = walkIdOrNull(controller.state.value) ?: return null
    val walk = repository.getWalk(walkId) ?: return null
    return WalkInfo(walkId = walkId, walkUuid = walk.uuid)
}

init {
    // Auto-stop when the walk finalizes mid-recording. Runs in
    // viewModelScope; toggleRecording dispatches to IO so there's no
    // main-thread blocking here.
    viewModelScope.launch {
        controller.state.collect { state ->
            if (state is WalkState.Finished && _voiceRecorderState.value is VoiceRecorderUiState.Recording) {
                toggleRecording()
            }
        }
    }
}
```

**Note:** `WalkAccumulator` has `walkId` but not `walkUuid`; we look up the
`uuid` via `repository.getWalk(id)` to populate `VoiceRecorder.start`'s
regex-validated parameter.

---

## Task 3 — Update DI + tests that construct `WalkViewModel`

`WalkViewModel` is `@HiltViewModel`, so Hilt auto-wires the new
`VoiceRecorder` dependency. No `DatabaseModule` / `AudioModule` change
needed.

**Test files constructing WalkViewModel manually:**

The sole one is `WalkViewModelTest.kt`. Add `voiceRecorder =` to its
`setUp()`. Because the real `VoiceRecorder` needs `AudioCapture` +
`AudioFocusCoordinator`, construct them from the test fakes:

```kotlin
// In setUp():
private lateinit var fakeAudioCapture: FakeAudioCapture
private lateinit var voiceRecorder: VoiceRecorder

// Inside setUp() body, after db + repository but before viewModel:
fakeAudioCapture = FakeAudioCapture(bursts = listOf(ShortArray(1_600) { 500 }))
val audioFocus = AudioFocusCoordinator(context.getSystemService(AudioManager::class.java))
voiceRecorder = VoiceRecorder(context, fakeAudioCapture, audioFocus, clock)

// Grant RECORD_AUDIO for tests that exercise the recording path:
shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)

// Then:
viewModel = WalkViewModel(context, controller, repository, clock, voiceRecorder)
```

Add imports at the top of the test file:

```kotlin
import android.Manifest
import android.media.AudioManager
import org.robolectric.Shadows.shadowOf
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
```

---

## Task 4 — `RecordControl` composable

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/RecordControl.kt` (new)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@Composable
fun RecordControl(
    walkState: WalkState,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = walkState.isInProgress
    val isRecording = recorderState is VoiceRecorderUiState.Recording

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onToggle() else onPermissionDenied()
    }

    // Transient banner auto-dismisses after 4s. Keyed on the error
    // instance so re-emitting the same kind resets the timer.
    val err = recorderState as? VoiceRecorderUiState.Error
    LaunchedEffect(err) {
        if (err != null && err.kind != VoiceRecorderUiState.Kind.Cancelled) {
            delay(ERROR_BANNER_DURATION_MS)
            onDismissError()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (err != null && err.kind != VoiceRecorderUiState.Kind.Cancelled) {
            ErrorBanner(message = err.message, onDismiss = onDismissError)
            Spacer(Modifier.height(PilgrimSpacing.normal))
        }

        // Ring + button
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(BUTTON_PLUS_RING_DP.dp)) {
            // Animated ring: 0f level → no ring; 1f level → full 24dp ring.
            val ringRadius by animateFloatAsState(
                targetValue = if (isRecording) audioLevel.coerceIn(0f, 1f) else 0f,
                animationSpec = tween(durationMillis = LEVEL_RING_TWEEN_MS),
                label = "recordRingRadius",
            )
            if (isRecording) {
                val ringSize = BUTTON_DP + (ringRadius * RING_MAX_EXTRA_DP * 2).toInt()
                Box(
                    modifier = Modifier
                        .size(ringSize.dp)
                        .clip(CircleShape)
                        .background(pilgrimColors.rust.copy(alpha = 0.2f)),
                )
            }
            // The button itself
            Box(
                modifier = Modifier
                    .size(BUTTON_DP.dp)
                    .clip(CircleShape)
                    .background(
                        if (isRecording) pilgrimColors.rust
                        else if (enabled) pilgrimColors.stone
                        else pilgrimColors.fog,
                    )
                    .then(
                        if (enabled) Modifier.clickable {
                            if (isRecording || PermissionChecks.isMicrophoneGranted(context)) {
                                onToggle()
                            } else {
                                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isRecording) "STOP" else "REC",
                    style = pilgrimType.statLabel,
                    color = pilgrimColors.parchment,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = "$recordingsCount recordings saved",
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.rust.copy(alpha = 0.15f),
            contentColor = pilgrimColors.ink,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = message,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(end = PilgrimSpacing.normal),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = pilgrimColors.rust)
            }
        }
    }
}

private const val BUTTON_DP = 56
private const val RING_MAX_EXTRA_DP = 24
private const val BUTTON_PLUS_RING_DP = BUTTON_DP + RING_MAX_EXTRA_DP * 2
private const val LEVEL_RING_TWEEN_MS = 80
private const val ERROR_BANNER_DURATION_MS = 4_000L
```

---

## Task 5 — Wire `RecordControl` into `ActiveWalkScreen`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` (modify)

Add reads inside the screen composable (after the existing
`collectAsStateWithLifecycle` calls):

```kotlin
val recorderState by viewModel.voiceRecorderState.collectAsStateWithLifecycle()
val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
val recordingsCount by viewModel.recordingsCount.collectAsStateWithLifecycle()
```

Insert the `RecordControl` after the `StatRow` + `Spacer` block, before
the `Controls`:

```kotlin
Spacer(Modifier.height(PilgrimSpacing.breathingRoom))
RecordControl(
    walkState = ui.walkState,
    recorderState = recorderState,
    audioLevel = audioLevel,
    recordingsCount = recordingsCount,
    onToggle = viewModel::toggleRecording,
    onPermissionDenied = viewModel::emitPermissionDenied,
    onDismissError = viewModel::dismissRecorderError,
)
Spacer(Modifier.height(PilgrimSpacing.breathingRoom))
Controls(
    walkState = ui.walkState,
    onPause = viewModel::pauseWalk,
    ...
)
```

---

## Task 6 — New unit tests in `WalkViewModelTest`

Extend `WalkViewModelTest.kt` with eight new tests. Add a helper at
class level:

```kotlin
private fun assertRecording() {
    assertTrue(viewModel.voiceRecorderState.value is VoiceRecorderUiState.Recording)
}
```

**Tests (run after existing ones):**

```kotlin
@Test
fun `toggleRecording when idle starts recording`() = runTest(dispatcher) {
    controller.startWalk(intention = null)
    viewModel.voiceRecorderState.test {
        assertEquals(VoiceRecorderUiState.Idle, awaitItem())
        viewModel.toggleRecording()
        assertEquals(VoiceRecorderUiState.Recording, awaitItem())
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `toggleRecording when recording stops and inserts a row`() = runTest(dispatcher) {
    controller.startWalk(intention = null)
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    viewModel.toggleRecording()
    // Wait for Recording state
    viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
    // Let FakeAudioCapture produce at least one buffer
    viewModel.audioLevel.first { it > 0f }

    viewModel.toggleRecording()
    viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }

    val recordings = repository.voiceRecordingsFor(walkId)
    assertEquals(1, recordings.size)
    assertEquals(walkId, recordings[0].walkId)
}

@Test
fun `stop on empty recording maps to Cancelled Idle and no DB row`() = runTest(dispatcher) {
    // Replace with empty bursts → read() returns -1 immediately →
    // VoiceRecorder.stop returns EmptyRecording.
    fakeAudioCapture = FakeAudioCapture(bursts = emptyList())
    voiceRecorder = VoiceRecorder(
        context, fakeAudioCapture,
        AudioFocusCoordinator(context.getSystemService(AudioManager::class.java)),
        clock,
    )
    viewModel = WalkViewModel(context, controller, repository, clock, voiceRecorder)

    controller.startWalk(intention = null)
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    viewModel.toggleRecording()
    viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
    viewModel.toggleRecording()

    // Empty recording maps to Idle, not Error
    viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }
    assertEquals(0, repository.voiceRecordingsFor(walkId).size)
}

@Test
fun `emitPermissionDenied flips state to Error with PermissionDenied kind`() = runTest(dispatcher) {
    viewModel.emitPermissionDenied()
    val state = viewModel.voiceRecorderState.value
    assertTrue(state is VoiceRecorderUiState.Error)
    assertEquals(
        VoiceRecorderUiState.Kind.PermissionDenied,
        (state as VoiceRecorderUiState.Error).kind,
    )
}

@Test
fun `AudioCapture init failure maps to Error with CaptureInitFailed kind`() = runTest(dispatcher) {
    fakeAudioCapture = FakeAudioCapture(
        startThrowable = IllegalStateException("mic busy"),
    )
    voiceRecorder = VoiceRecorder(
        context, fakeAudioCapture,
        AudioFocusCoordinator(context.getSystemService(AudioManager::class.java)),
        clock,
    )
    viewModel = WalkViewModel(context, controller, repository, clock, voiceRecorder)

    controller.startWalk(intention = null)
    viewModel.toggleRecording()

    val errState = viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Error } as VoiceRecorderUiState.Error
    assertEquals(VoiceRecorderUiState.Kind.CaptureInitFailed, errState.kind)
}

@Test
fun `WalkState transitioning to Finished while recording auto-stops`() = runTest(dispatcher) {
    controller.startWalk(intention = null)
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    viewModel.toggleRecording()
    viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Recording }
    viewModel.audioLevel.first { it > 0f }

    controller.finishWalk()

    viewModel.voiceRecorderState.first { it is VoiceRecorderUiState.Idle }
    assertEquals(1, repository.voiceRecordingsFor(walkId).size)
}

@Test
fun `recordingsCount reflects rows for the active walk`() = runTest(dispatcher) {
    controller.startWalk(intention = null)
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    viewModel.recordingsCount.test {
        assertEquals(0, awaitItem())
        repository.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 1_000L,
                endTimestamp = 2_000L,
                durationMillis = 1_000L,
                fileRelativePath = "recordings/x/a.wav",
            ),
        )
        assertEquals(1, awaitItem())
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `dismissRecorderError returns state to Idle from Error`() {
    viewModel.emitPermissionDenied()
    viewModel.dismissRecorderError()
    assertEquals(VoiceRecorderUiState.Idle, viewModel.voiceRecorderState.value)
}
```

Add imports to the test file:

```kotlin
import kotlinx.coroutines.flow.first
import org.walktalkmeditate.pilgrim.audio.AudioFocusCoordinator
import org.walktalkmeditate.pilgrim.audio.FakeAudioCapture
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.walk.VoiceRecorderUiState
```

**Note on the dispatcher:** the existing test uses
`UnconfinedTestDispatcher`. That's compatible with the new tests — the
`viewModelScope.launch(Dispatchers.IO)` block inside `toggleRecording`
will be intercepted by the test dispatcher and run inline. No
additional setup needed.

---

## Task 7 — Full CI gate

```bash
JAVA_HOME=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8 ANDROID_HOME=/Users/rubberduck/Library/Android/sdk PATH=/Users/rubberduck/.asdf/installs/java/temurin-17.0.18+8/bin:$PATH ./gradlew assembleDebug lintDebug testDebugUnitTest
```

**Expected:** 120 → ~128 tests passing, BUILD SUCCESSFUL, no new lint
warnings.

---

## Task 8 — Commit

```
feat(ui): Stage 2-C — Active Walk record button + level meter

Wires Stage 2-B's VoiceRecorder into ActiveWalkScreen. Tap REC,
capture a voice note with a live radial level meter, tap STOP to
persist a VoiceRecording row. Just-in-time RECORD_AUDIO permission
via ActivityResultContracts. Auto-stop when the walk finalizes
mid-recording.

- WalkViewModel gains voiceRecorderState / audioLevel / recordingsCount
  StateFlows + toggleRecording() + error mapping. Recording calls
  dispatch to IO because VoiceRecorder.stop() blocks on doneLatch.
- RecordControl composable: circular 56dp button (rust when recording,
  stone when idle, fog when disabled), radial ripple ring that tracks
  audioLevel via animateFloatAsState (80ms tween), transient error
  banner with 4s auto-dismiss.
- "N recordings saved" indicator below the button, backed by a
  flatMapLatest over observeVoiceRecordings.
- Empty recordings (user tapped stop too fast, or background-kill
  per Stage 2-B's EmptyRecording guard) map to Cancelled → silent
  return to Idle; only real failures surface the banner.

Used text labels (REC/STOP) instead of Icons.Default.Mic/Stop to
avoid the ~35MB material-icons-extended dependency. Matches Pilgrim's
text-forward aesthetic.

Tests: 8 new ViewModel tests covering start/stop/empty/permission/
capture-init-fail/auto-stop-on-finished/count-flow/dismiss. All use
real VoiceRecorder + FakeAudioCapture to exercise end-to-end
behavior.
```
