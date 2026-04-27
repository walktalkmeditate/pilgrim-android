# Stage 9.5-C — Active Walk Parity Round 2 Design Spec

## Goal

Three contained parity fixes carrying Stage 9.5-B's Active Walk surface to true iOS feature parity for the operations that matter most:

1. **`discardWalk`** — wire the existing X button on the active-walk map to actually leave the walk without saving (currently routes to `finishWalk` which saves; copy says "This walk will not be saved" — semantic mismatch).
2. **Audio waveform** — render the 5-bar live waveform inside the mic button when recording (iOS `AudioWaveformView`; `audioLevel: Float` is already plumbed end-to-end but unused).
3. **WalkOptionsSheet (minimal)** — wire the existing ellipsis button (currently no-op) to a Material 3 `ModalBottomSheet` containing two rows: Set Intention + Drop Waypoint. Soundscape / Voice Guide pickers + Whisper / Stone deferred to later stages (orchestrator surface gaps + missing Android equivalents).

## What I considered and rejected

- **Full WalkOptionsSheet (5 rows)** — soundscape + voice-guide rows would require new orchestrator APIs (pause/resume/replay for voice-guide, "play during walk" mode for soundscape that currently only plays during Meditating). Out of scope; defer until orchestrator design owns those modes.
- **Waypoint chip-grid + custom-text marking sheet (iOS parity)** — iOS has 6 preset chips + 50-char text input. Our `controller.recordWaypoint()` hardcodes label/icon to null. Adding the chip UI is ~200 LOC of itself; defer the visual port and ship the simpler "drop pin at current location" path that matches what the controller already supports. This is a documented divergence; iOS visual fidelity here is a follow-up stage.
- **Voice-recorded intention via WhisperKit (iOS)** — iOS's IntentionSettingView pipes through WhisperKit for spoken intentions. Our Android stack has whisper.cpp for post-walk transcription but no in-app live-dictation flow. Use a plain TextField for 9.5-C; voice-recorded intention is a separate stage.
- **Routing discardWalk through `Finished` state then deleting the row** — would fire `WalkFinalizationObserver` (transcription, collective POST, widget refresh) for a walk that the user explicitly chose NOT to keep. A new state path that goes Active → Idle directly, bypassing Finished, is the correct semantic.

---

## Spec

### 1. `WalkController.discardWalk()` + reducer + effect plumbing

**Files modified:** `WalkController.kt`, `WalkAction.kt`, `WalkEffect.kt`, `WalkReducer.kt`, `WalkTrackingService.kt`, `WalkFinalizationObserver.kt`, `WalkRepository.kt` (already has `deleteWalk`, may need `deleteWalkById` for atomic flow).

**New action + effect:**

```kotlin
// WalkAction.kt
data class Discard(val at: Long) : WalkAction

// WalkEffect.kt
data class PurgeWalk(val walkId: Long) : WalkEffect
```

**Reducer (`reduceActive`/`reducePaused`/`reduceMeditating` only — Idle/Finished discard is a no-op):**

```kotlin
fun reduceActive(state: WalkState.Active, action: WalkAction): Pair<WalkState, WalkEffect> = when (action) {
    is WalkAction.Discard -> WalkState.Idle to WalkEffect.PurgeWalk(state.walk.walkId)
    // ... existing branches
}
// (mirror for reducePaused, reduceMeditating)
```

**Effect handler in `WalkController.handleEffect`:**

```kotlin
is WalkEffect.PurgeWalk -> {
    repository.deleteWalkById(effect.walkId)  // cascade-deletes all 7 child tables
}
```

**Service teardown — widen `WalkTrackingService` self-stop condition:**

`WalkTrackingService` currently calls `stopSelf()` only on `state is WalkState.Finished`. Discard transitions Active → Idle. Widen to:

```kotlin
private var hasBeenActive = false

private fun handleStateChange(state: WalkState) {
    if (state is WalkState.Active || state is WalkState.Paused || state is WalkState.Meditating) {
        hasBeenActive = true
    }
    if (state is WalkState.Finished || (hasBeenActive && state is WalkState.Idle)) {
        stopSelf()
        return
    }
    // ... existing notification update logic
}
```

The `hasBeenActive` latch ensures cold-start Idle (initial state before startWalk) doesn't trigger stopSelf.

**Voice recorder auto-stop — factor out of `WalkFinalizationObserver` into a new `WalkLifecycleObserver`:**

`WalkFinalizationObserver` currently keys on `state is WalkState.Finished` and runs voice auto-stop alongside transcription scheduling, collective POST, and widget refresh. The voice auto-stop block must also fire on discard. Two options:

- **Option A (preferred):** New `WalkLifecycleObserver` (app-scope @Singleton) keyed on `Active|Paused|Meditating → Idle|Finished` transitions, owns voice auto-stop. `WalkFinalizationObserver` keeps its Finished-only side effects (transcription/collective/widget).
- **Option B:** Add `controller.discardWalk()` precondition that calls `voiceRecorder.stop()` directly inline before dispatching `Discard`. Simpler but forks the auto-stop logic.

Use Option A. Minor refactor; keeps the observer pattern clean.

**`WalkController.discardWalk()` public method:**

```kotlin
suspend fun discardWalk() = dispatchMutex.withLock {
    Log.i(TAG, "discardWalk invoked from state=${_state.value::class.simpleName}")
    dispatch(WalkAction.Discard(at = clock.now()))
}
```

**`WalkRepository.deleteWalkById(walkId: Long)`:**

```kotlin
suspend fun deleteWalkById(walkId: Long) {
    walkDao.deleteById(walkId)  // new @Query("DELETE FROM walks WHERE id = :walkId") + ON DELETE CASCADE
}
```

**`WalkViewModel.discardWalk()`:**

```kotlin
fun discardWalk() {
    viewModelScope.launch { controller.discardWalk() }
}
```

**ActiveWalkScreen — re-wire the X button's confirm action from `viewModel::finishWalk` to `viewModel::discardWalk`. Drop the TODO comment.**

**Tests:**

- `WalkRepositoryDiscardTest.kt` — Robolectric + in-memory PilgrimDatabase. Insert a Walk + child rows in every table (RouteDataSample, AltitudeSample, WalkEvent, ActivityInterval, Waypoint, VoiceRecording, WalkPhoto). Call `deleteWalkById(walkId)`. Assert all child tables are empty. **Locks in cascade-delete contract** for current + future child tables.
- `WalkControllerDiscardTest.kt` — discard transitions Active→Idle, Paused→Idle, Meditating→Idle. discard from Idle/Finished is a no-op. Verifies effect emits PurgeWalk with correct walkId.
- `WalkTrackingServiceDiscardTest.kt` — state Active → discardWalk() → assert service stopSelf's via the `hasBeenActive` latch path. Cold-start Idle does NOT trigger stopSelf.
- `WalkLifecycleObserverTest.kt` — voice recorder auto-stop fires on Active→Idle (discard) AND on Active→Finished (normal end). Same assertion for both paths.

### 2. Audio waveform inside mic button

**Files modified:** `WalkStatsSheet.kt` (MicActionButton), one new file: `AudioWaveformView.kt` in `ui/walk/`.

**New composable:**

```kotlin
// AudioWaveformView.kt
@Composable
internal fun AudioWaveformView(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val barWeights = remember { listOf(0.6f, 0.8f, 1.0f, 0.8f, 0.6f) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        barWeights.forEach { weight ->
            val targetHeight = (4.dp + (level.coerceIn(0f, 1f) * weight * 20f).dp)
            val animatedHeight by animateDpAsState(
                targetValue = targetHeight,
                animationSpec = tween(durationMillis = 80, easing = FastOutLinearInEasing),
                label = "bar-height",
            )
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(pilgrimColors.rust),
            )
        }
    }
}
```

**MicActionButton integration:**

When `isRecording`, replace the `Icon(Icons.Filled.Stop, ...)` icon slot with `AudioWaveformView(level = audioLevel)`. The label "REC" stays. Both fit comfortably in the 80dp circle (waveform is ~12dp tall vs 22dp icon; use `Box(Modifier.height(22.dp), contentAlignment = Center)` to occupy the same vertical space so the label position doesn't shift).

```kotlin
// In MicActionButton's Column, replace Icon block with:
Box(
    modifier = Modifier.height(22.dp),
    contentAlignment = Alignment.Center,
) {
    if (isRecording) {
        AudioWaveformView(level = audioLevel)
    } else {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = effectiveColor,
            modifier = Modifier.size(22.dp),
        )
    }
}
```

**Tests:**

- `AudioWaveformViewTest.kt` — pure unit + Robolectric Compose: assert 5 Box children present, assert bar-height function maps `level=0f` to 4dp (each bar) and `level=1f` to (4 + 20*weight)dp per bar via testTag inspection.
- Update `WalkStatsSheetActionRowTest.kt` if any test asserted the Stop icon during recording — replace with waveform-presence assertion.

### 3. WalkOptionsSheet (minimal: intention + waypoint)

**Files added:**

- `WalkOptionsSheet.kt` (new, in `ui/walk/`)
- `IntentionSettingDialog.kt` (new, in `ui/walk/`)

**`WalkOptionsSheet` Composable:**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkOptionsSheet(
    intention: String?,
    waypointCount: Int,
    canDropWaypoint: Boolean,  // true when Active or Paused; controller will no-op otherwise
    onSetIntention: () -> Unit,
    onDropWaypoint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.walk_options_title),  // "Options"
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = PilgrimSpacing.small),
            )
            OptionRow(
                icon = Icons.Outlined.EditNote,
                title = stringResource(R.string.walk_options_intention_title),
                subtitle = intention?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.walk_options_intention_unset),
                onClick = onSetIntention,
            )
            OptionRow(
                icon = Icons.Outlined.LocationOn,
                title = stringResource(R.string.walk_options_waypoint_title),
                subtitle = pluralStringResource(
                    R.plurals.walk_options_waypoint_count, waypointCount, waypointCount,
                ),
                enabled = canDropWaypoint,
                onClick = onDropWaypoint,
            )
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) pilgrimColors.moss else pilgrimColors.fog
    val titleColor = if (enabled) pilgrimColors.ink else pilgrimColors.fog
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.4f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = pilgrimType.body, color = titleColor)
            Text(subtitle, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = pilgrimColors.fog)
    }
}
```

**`IntentionSettingDialog`:**

Material 3 `AlertDialog` with a `TextField` (max 140 chars), Cancel / Save actions.

```kotlin
@Composable
fun IntentionSettingDialog(
    initial: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.walk_options_intention_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= MAX_INTENTION_CHARS) text = it },
                placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
                singleLine = false,
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.walk_options_intention_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.walk_options_intention_cancel))
            }
        },
        containerColor = pilgrimColors.parchment,
    )
}

private const val MAX_INTENTION_CHARS = 140
```

**WalkViewModel additions:**

```kotlin
val intention: StateFlow<String?> = controller.state
    .map { state ->
        when (state) {
            is WalkState.Active -> state.walk.intention
            is WalkState.Paused -> state.walk.intention
            is WalkState.Meditating -> state.walk.intention
            else -> null
        }
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), null)

val waypointCount: StateFlow<Int> = controller.state
    .map { walkIdOrNull(it) }
    .distinctUntilChanged()
    .flatMapLatest { walkId ->
        if (walkId == null) flowOf(0)
        else repository.observeWaypointCount(walkId)  // new dao @Query("SELECT COUNT(*) FROM waypoints WHERE walk_id = :id")
    }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), 0)

fun setIntention(text: String) {
    viewModelScope.launch { controller.setIntention(text) }
}

fun dropWaypoint() {
    viewModelScope.launch { controller.recordWaypoint() }  // existing API, label/icon=null
}
```

**WalkController additions:**

```kotlin
suspend fun setIntention(text: String) = dispatchMutex.withLock {
    val walkId = walkIdOrNull(_state.value) ?: return@withLock
    val sanitized = text.trim().take(MAX_INTENTION_CHARS)
    repository.updateWalkIntention(walkId, sanitized.takeIf { it.isNotBlank() })
}

private const val MAX_INTENTION_CHARS = 140
```

WalkAccumulator's `intention` field — currently only set at startWalk. Need to update the in-memory accumulator's intention on the next reducer cycle. Simplest approach: emit a `WalkAction.IntentionUpdated(text)` after the repo write, reducer applies to current state's accumulator.

Or skip the in-memory update for 9.5-C and just persist to DB; the next process restart will pick up the new value via restoreActiveWalk. The in-walk Options sheet display reads from the WalkStatsSheet's caption (only shown if `intention != null`); for now the caption uses the static "every step is enough" text, so this minor staleness is invisible until intention rendering is added to the sheet (separate stage).

Decision: persist to DB only for 9.5-C. Don't update WalkAccumulator. Add a follow-up TODO for in-memory state sync.

**WalkRepository additions:**

```kotlin
suspend fun updateWalkIntention(walkId: Long, text: String?) {
    walkDao.updateIntention(walkId, text)
}

fun observeWaypointCount(walkId: Long): Flow<Int> =
    waypointDao.observeCountForWalk(walkId)
```

**ActiveWalkScreen wiring:**

```kotlin
var showOptions by rememberSaveable { mutableStateOf(false) }
var showIntention by rememberSaveable { mutableStateOf(false) }
val intention by viewModel.intention.collectAsStateWithLifecycle()
val waypointCount by viewModel.waypointCount.collectAsStateWithLifecycle()

// MapOverlayButtons.onOptionsClick → showOptions = true
MapOverlayButtons(
    onOptionsClick = { showOptions = true },
    onLeaveClick = { showLeaveConfirm = true },
    ...
)

if (showOptions) {
    WalkOptionsSheet(
        intention = intention,
        waypointCount = waypointCount,
        canDropWaypoint = navWalkState is WalkState.Active || navWalkState is WalkState.Paused,
        onSetIntention = {
            showOptions = false
            showIntention = true
        },
        onDropWaypoint = {
            viewModel.dropWaypoint()
            showOptions = false
        },
        onDismiss = { showOptions = false },
    )
}
if (showIntention) {
    IntentionSettingDialog(
        initial = intention,
        onSave = { text ->
            viewModel.setIntention(text)
            showIntention = false
        },
        onDismiss = { showIntention = false },
    )
}
```

**New strings:**

```xml
<string name="walk_options_title">Options</string>
<string name="walk_options_intention_title">Set Intention</string>
<string name="walk_options_intention_unset">No intention set</string>
<string name="walk_options_intention_dialog_title">Set Intention</string>
<string name="walk_options_intention_placeholder">A line for this walk…</string>
<string name="walk_options_intention_save">Save</string>
<string name="walk_options_intention_cancel">Cancel</string>
<string name="walk_options_waypoint_title">Drop Waypoint</string>
<plurals name="walk_options_waypoint_count">
    <item quantity="zero">None marked</item>
    <item quantity="one">1 marked</item>
    <item quantity="other">%1$d marked</item>
</plurals>
```

**Tests:**

- `WalkOptionsSheetTest.kt` — Compose UI: row visibility (waypoint disabled when canDropWaypoint=false), click-through to onSetIntention / onDropWaypoint / onDismiss.
- `IntentionSettingDialogTest.kt` — TextField max-chars (assert input >140 truncated), Save callback fires with trimmed text, Cancel callback fires.
- `WalkViewModelIntentionTest.kt` — `setIntention("foo")` triggers `controller.setIntention("foo")`; `intention` StateFlow reflects updates.
- `WalkViewModelWaypointCountTest.kt` — observed count from real Room.

### 4. Out of scope (deferred)

- **Soundscape picker row in WalkOptionsSheet** — needs orchestrator change for "play during walk" mode. Defer to dedicated audio-stage.
- **Voice-guide picker / pause / replay row** — needs orchestrator pause/resume/replay APIs. Defer.
- **Whisper / Stone rows** — no Android equivalents (no GeoCacheService). Phase N.
- **Waypoint chip-grid + custom-text marking sheet (iOS visual parity)** — current Android port: drop pin at GPS with no label. Direct iOS UI port is its own follow-up stage.
- **WalkAccumulator in-memory intention update** — DB-only persistence for 9.5-C. WalkStatsSheet caption keeps static "every step is enough" until a separate stage threads `intention` into the caption display.
- **Voice-recorded intention via WhisperKit (iOS)** — TextField only.

## Verification (manual)

1. Start a walk, take a few steps so route + samples + events exist. Tap **X (top-right map button)** → Leave Walk dialog appears → tap **Leave**. Assert:
   - User returns to Path tab (foreground service notification gone).
   - Open Journal: walk does NOT appear in the list.
   - Re-launch app: walk does NOT appear.
2. Same flow but tap **Stay** on the dialog → walk continues normally.
3. Walk → mic record. Mic button shows the **5-bar waveform** instead of the Stop icon. Bars animate as you speak.
4. Tap **ellipsis (top-left map button)** → WalkOptionsSheet opens with two rows.
5. Tap **Set Intention** → dialog opens. Type "test intention" → Save. Re-open Options → intention shows under the row.
6. Tap **Drop Waypoint** → sheet closes. Re-open Options → waypoint count incremented.
7. Mid-walk: kill app via Settings, reopen. Walk restores. Intention persists (visible in Options if reopened).
8. Discard a walk that's mid-meditation: tap End Meditation → return to Active → tap X → Leave. Verify clean teardown.
9. Discard during recording: tap mic → start recording → tap X → Leave → confirm voice file is NOT in DB AND walk row is gone (check via adb logcat or post-restart).

## Files

**New:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformView.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt`
- 5 test files (one per major component)

**Modified:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt` (discardWalk + setIntention)
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt` (factor out voice auto-stop)
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkAction.kt` + `WalkEffect.kt` + `WalkReducer.kt` (Discard + PurgeWalk)
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt` + `WalkDao.kt` (deleteWalkById + updateIntention)
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WaypointDao.kt` (observeCountForWalk)
- `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt` (widen self-stop to Idle-after-active path)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt` (MicActionButton renders waveform when recording)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` (discardWalk + intention + waypointCount + setIntention)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` (wire ellipsis + intention dialog + change Leave action from finishWalk to discardWalk)
- `app/src/main/res/values/strings.xml`

## Estimated scope

~800 LOC new, ~150 modified. About half of 9.5-B's surface. Highest risk is the WalkTrackingService self-stop widening (firstEmission-style guard) and the `WalkLifecycleObserver` extraction (must not break the existing Finished-only voice auto-stop).
