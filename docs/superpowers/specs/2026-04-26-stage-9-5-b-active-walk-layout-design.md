# Stage 9.5-B — Active Walk Layout Rebuild Design Spec (Revised)

## Goal

Match the iOS Active Walk visual structure: full-screen map background with a draggable bottom sheet overlay containing live stats + circular action controls. This is the most-used screen during walks; biggest user-facing UX impact in the parity work.

## High-level approach

`Box(fillMaxSize)` layers a full-screen `PilgrimMap` underneath a custom-rolled `WalkStatsSheet` aligned `BottomCenter`. Sheet has two states (`Minimized` / `Expanded`) controlled by `rememberSaveable SheetState`, driven by walk state transitions (auto-minimize on Active, auto-expand on Paused/Meditating with 800ms debounce on Paused via LaunchedEffect cancellation). Drag gesture: 40dp distance OR 300dp/s flick to commit a state change; 100dp clamp; rubber-band on cancel; haptic on commit.

**Both content variants are always composed**, switched between via `Modifier.graphicsLayer { alpha = ... }` + animated heights — this avoids `AnimatedContent`'s re-mount that would tear down the running mic recording during a sheet collapse.

Sheet expanded content: drag handle pill → timer (large) → contemplative caption ("every step is enough") → 3-stat row (Distance / Steps / Ascent — Steps and Ascent both **placeholder "—"** for 9.5-B) → 3 time-chip pills (Walk / Talk / Meditate) → action button row.

Action buttons: 4 circular pills per state with **disabled greyed-out placeholders** in unavailable slots (preserves layout stability; communicates "unavailable here" clearly). All states show 4 buttons: `[Pause/Resume] [Meditate/EndMeditation] [Mic] [End]`.

## Stats provenance — REVISED

- **Distance**: existing `WalkStats.distanceMeters` ✓
- **Steps**: `"—"` placeholder. Adding TYPE_STEP_COUNTER + ACTIVITY_RECOGNITION permission + accumulator field + Walk entity migration is a multi-stage feature deferred to a future "Sensor stack" stage.
- **Ascent**: `"—"` placeholder. **Verified: no production code writes `AltitudeSample` rows** — `WalkRepository.recordAltitude` exists but has zero callers. Wiring altitude collection requires location-pipeline changes (FusedLocationSource → handle `location.hasAltitude()` → `verticalAccuracyMeters` filter → debounce → recordAltitude) which is its own scope. Defer with the same `"—"` treatment as Steps.
- **Walk time**: existing `WalkStats.activeWalkingMillis` ✓
- **Talk time**: sum of voice-recording durations from `repository.observeVoiceRecordings(walkId)` (verified: returns `Flow<List<VoiceRecording>>`, not Int)
- **Meditate time**: NEW `WalkStats.totalMeditatedMillis(state, nowMillis)` (does NOT exist; mandated add — see §9)

(The "—" rendering on two of three columns is the honest representation of what we're shipping. Future stage(s) wire Steps and Ascent independently. The visible-but-empty columns establish the layout that the future data will fill, avoiding a second layout reshuffle when those land.)

## What I considered and rejected

- **Material3 `BottomSheetScaffold`** — wrong styling defaults; no two-detent peek model; fighting M3 internals for parchment fidelity costs more than rolling 150 LOC.
- **Hide Pause/Resume entirely to match iOS's 3-button row** — Android lacks iOS's auto-pause subsystem; without it, removing manual Pause strands users.
- **Add SensorManager TYPE_STEP_COUNTER in 9.5-B** — multi-stage feature.
- **Wire AltitudeSample writes in 9.5-B** — multi-stage (FusedLocationSource changes + accuracy gating + debounce + GPS-noise filter); defer.
- **Animate the map's bottom-inset to track sheet height** — see §11 below; the spec NOW includes a `bottomInsetDp` parameter on `PilgrimMap` so the user puck stays visible above the expanded sheet (revised from "defer").
- **`AnimatedContent` for sheet-content cross-fade** — re-mounts the entire ExpandedContent subtree, tearing down the active mic recording. Always-mount both variants and animate alpha instead.
- **iOS-only luxuries**: weather/celestial overlays, floating greetings, ambient pace sparkline, options sheet, peek-hint animation, turning-day kanji watermark, intention card.

---

## Spec

### 1. New domain helper: `WalkStats.totalMeditatedMillis` (MANDATED ADD)

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkStats.kt` (modify)

Add to the existing `WalkStats` object:

```kotlin
/**
 * Total meditation time including the in-progress meditation if any.
 * For Active/Paused/Finished, returns accumulator's totalMeditatedMillis
 * (the reducer adds the just-completed slice on MeditateEnd / Finish).
 * For Meditating, adds (now - meditationStartedAt) on top.
 */
fun totalMeditatedMillis(state: WalkState, now: Long): Long = when (state) {
    is WalkState.Meditating -> state.walk.totalMeditatedMillis + (now - state.meditationStartedAt).coerceAtLeast(0L)
    is WalkState.Active -> state.walk.totalMeditatedMillis
    is WalkState.Paused -> state.walk.totalMeditatedMillis
    is WalkState.Finished -> state.walk.totalMeditatedMillis
    WalkState.Idle -> 0L
}
```

`coerceAtLeast(0L)` defends against clock-skew (now < meditationStartedAt) returning a negative running total.

Tests:
- Idle → 0
- Active with accum.totalMeditatedMillis=60_000 → 60_000
- Paused with accum.totalMeditatedMillis=120_000 → 120_000
- Meditating started 30s ago with accum.totalMeditatedMillis=90_000 → 120_000
- Meditating with now < startedAt (clock skew) → accum.totalMeditatedMillis (no negative)

### 2. New `WalkViewModel` flows: `voiceRecordings` (single source) + derive `recordingsCount` + `talkMillis`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` (modify)

To avoid double-subscribing to `repository.observeVoiceRecordings(walkId)`, expose a single internal flow + derive both consumers:

```kotlin
/** Single source for voice-recording rows. Both recordingsCount and
 *  talkMillis derive from this flow to avoid two upstream Room subscriptions. */
private val voiceRecordings: StateFlow<List<VoiceRecording>> = controller.state
    .map { walkIdOrNull(it) }
    .distinctUntilChanged()
    .flatMapLatest { walkId ->
        if (walkId == null) flowOf(emptyList())
        else repository.observeVoiceRecordings(walkId)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = emptyList(),
    )

/** EXISTING flow at WalkViewModel.kt:222 — REPLACE its body to derive
 *  from voiceRecordings instead of opening its own observeVoiceRecordings call. */
val recordingsCount: StateFlow<Int> = voiceRecordings
    .map { it.size }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), 0)

/** NEW: live total voice-recording duration. Sums durationMillis across rows. */
val talkMillis: StateFlow<Long> = voiceRecordings
    .map { rows -> rows.sumOf { it.durationMillis } }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), 0L)
```

The existing `recordingsCount` test should continue to pass; add a new test for `talkMillis` summing rows correctly + going to 0 between walks.

### 3. New `SheetState` enum

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetState.kt` (new)

```kotlin
/**
 * Two-detent state for the Active Walk bottom sheet.
 *
 * Kotlin enums implement [java.io.Serializable] by default, so plain
 * `rememberSaveable { mutableStateOf(SheetState.Expanded) }` survives
 * config changes via the bundle's Serializable saver. No custom Saver
 * required.
 */
enum class SheetState { Minimized, Expanded }
```

### 4. Rewrite `ActiveWalkScreen` layout

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` (significantly rewrite)

```kotlin
@Composable
fun ActiveWalkScreen(
    onFinished: (walkId: Long) -> Unit,
    onEnterMeditation: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // navWalkState reads the direct passthrough flow, NOT uiState's
    // WhileSubscribed(5s) cache. Stage 5G memory: nav observers must
    // not read stale-cached flows.
    val navWalkState by viewModel.walkState.collectAsStateWithLifecycle()
    val routePoints by viewModel.routePoints.collectAsStateWithLifecycle()
    val recorderState by viewModel.voiceRecorderState.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val recordingsCount by viewModel.recordingsCount.collectAsStateWithLifecycle()
    val talkMillis by viewModel.talkMillis.collectAsStateWithLifecycle()
    val initialCameraCenter by viewModel.initialCameraCenter.collectAsStateWithLifecycle()
    val meditateMillis = WalkStats.totalMeditatedMillis(ui.walkState, ui.nowMillis)

    val context = LocalContext.current
    BackHandler(enabled = ui.walkState.isInProgress) {
        (context as? Activity)?.moveTaskToBack(true)
    }

    LaunchedEffect(navWalkState::class) {
        when (val state = navWalkState) {
            is WalkState.Finished -> onFinished(state.walk.walkId)
            is WalkState.Meditating -> onEnterMeditation()
            else -> Unit
        }
    }

    var sheetState by rememberSaveable { mutableStateOf(SheetState.Expanded) }
    // Drive sheet auto-state from the PASSTHROUGH walkState so we don't
    // act on stale uiState during the brief window after returning from
    // MeditationScreen (Stage 5G stale-cache trap, generalized).
    SheetStateController(
        walkState = navWalkState,
        onUpdateState = { sheetState = it },
    )

    // Sheet height (Expanded) used for map bottom-inset so the user puck
    // stays visible above the sheet. Approximation rather than measured —
    // measurement via SubcomposeLayout is heavier than the visual win.
    val sheetExpandedDp = if (sheetState == SheetState.Expanded) 340.dp else 88.dp

    Box(modifier = Modifier.fillMaxSize()) {
        PilgrimMap(
            points = routePoints,
            followLatest = true,
            initialCenter = initialCameraCenter,
            bottomInsetDp = sheetExpandedDp,
            modifier = Modifier.fillMaxSize(),
        )
        WalkStatsSheet(
            state = sheetState,
            onStateChange = { sheetState = it },
            walkState = ui.walkState,
            totalElapsedMillis = ui.totalElapsedMillis,
            distanceMeters = ui.distanceMeters,
            walkMillis = ui.activeWalkingMillis,
            talkMillis = talkMillis,
            meditateMillis = meditateMillis,
            recorderState = recorderState,
            audioLevel = audioLevel,
            recordingsCount = recordingsCount,
            onPause = viewModel::pauseWalk,
            onResume = viewModel::resumeWalk,
            onStartMeditation = viewModel::startMeditation,
            onEndMeditation = viewModel::endMeditation,
            onToggleRecording = viewModel::toggleRecording,
            onPermissionDenied = viewModel::emitPermissionDenied,
            onDismissError = viewModel::dismissRecorderError,
            onFinish = viewModel::finishWalk,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
```

### 5. `SheetStateController` — debounce via cancellation, no dead `if`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateController.kt` (new)

```kotlin
private const val PAUSE_DEBOUNCE_MS = 800L

@Composable
fun SheetStateController(
    walkState: WalkState,
    onUpdateState: (SheetState) -> Unit,
) {
    // The auto-state logic only fires on state-class transitions, not
    // on every recomposition. Pause-debounce is implemented purely
    // through LaunchedEffect's cancel-on-key-change semantics: when the
    // state flips back to Active mid-debounce, the in-flight Paused
    // coroutine is cancelled BEFORE delay() returns, the new Active
    // coroutine launches and immediately calls Minimized. There is no
    // "re-check after delay" branch — that pattern doesn't work because
    // the captured walkState parameter is whatever it was at launch.
    LaunchedEffect(walkState::class) {
        when (walkState) {
            is WalkState.Active -> onUpdateState(SheetState.Minimized)
            is WalkState.Paused -> {
                delay(PAUSE_DEBOUNCE_MS)
                onUpdateState(SheetState.Expanded)
            }
            is WalkState.Meditating -> onUpdateState(SheetState.Expanded)
            // Idle (initialValue / cold-start) and Finished (about to nav
            // away) are no-ops; the screen pops away in those cases.
            WalkState.Idle, is WalkState.Finished -> Unit
        }
    }
}
```

Test (`SheetStateControllerTest.kt`) under `runTest` virtual time:
- Active emission → Minimized called
- Paused emission, advance 800ms → Expanded called
- Paused emission, advance 400ms, Active emission → Minimized called; Expanded NOT called
- Meditating emission → Expanded called immediately

### 6. `WalkStatsSheet` — graphicsLayer translation, always-mount both contents, manual upward shadow

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt` (new)

```kotlin
@Composable
fun WalkStatsSheet(
    state: SheetState,
    onStateChange: (SheetState) -> Unit,
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canDrag = walkState is WalkState.Active
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 40.dp.toPx() } }
    val flickPx = remember(density) { with(density) { 300.dp.toPx() } }
    val clampPx = remember(density) { with(density) { 100.dp.toPx() } }

    // mutableFloatStateOf avoids autoboxing on every drag tick.
    var dragOffset by remember { mutableFloatStateOf(0f) }

    // rememberUpdatedState wraps state + canDrag so the lambdas captured
    // by rememberDraggableState see the LATEST values, not the values
    // at first composition.
    val currentState by rememberUpdatedState(state)
    val currentCanDrag by rememberUpdatedState(canDrag)

    val draggableState = rememberDraggableState { delta ->
        if (!currentCanDrag) return@rememberDraggableState
        val proposed = (dragOffset + delta).coerceIn(-clampPx, clampPx)
        dragOffset = when {
            currentState == SheetState.Minimized && delta < 0 -> proposed
            currentState == SheetState.Expanded && delta > 0 -> proposed
            else -> dragOffset
        }
    }

    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffset }
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (!currentCanDrag) {
                        dragOffset = 0f
                        return@draggable
                    }
                    val shouldExpand = currentState == SheetState.Minimized &&
                        (dragOffset < -thresholdPx || velocity < -flickPx)
                    val shouldCollapse = currentState == SheetState.Expanded &&
                        (dragOffset > thresholdPx || velocity > flickPx)
                    when {
                        shouldExpand -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStateChange(SheetState.Expanded)
                        }
                        shouldCollapse -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onStateChange(SheetState.Minimized)
                        }
                    }
                    dragOffset = 0f
                },
            ),
    ) {
        // Manual upward shadow via drawBehind. Compose Surface(elevation)
        // casts shadow downward — the iOS reference uses y: -4 offset to
        // cast shadow UPWARD onto the map. We emulate via a soft gradient
        // strip drawn just above the sheet's top edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
        // Sheet body. clip(sheetShape) so children stay inside the rounded
        // top corners; background paints parchment.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(pilgrimColors.parchment)
                .navigationBarsPadding()
                .padding(bottom = PilgrimSpacing.normal),
        ) {
            DragHandle(canDrag = canDrag)
            // Always-compose both content variants; toggle visibility via
            // alpha + animated height. AnimatedContent would tear down +
            // re-mount ExpandedContent on every state flip, killing the
            // active mic recording's LaunchedEffects + audio observers.
            SheetContentSwitcher(
                state = state,
                minimizedContent = {
                    MinimizedContent(
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        onTap = { onStateChange(SheetState.Expanded) },
                    )
                },
                expandedContent = {
                    ExpandedContent(
                        walkState = walkState,
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        walkMillis = walkMillis,
                        talkMillis = talkMillis,
                        meditateMillis = meditateMillis,
                        recorderState = recorderState,
                        audioLevel = audioLevel,
                        recordingsCount = recordingsCount,
                        onPause = onPause,
                        onResume = onResume,
                        onStartMeditation = onStartMeditation,
                        onEndMeditation = onEndMeditation,
                        onToggleRecording = onToggleRecording,
                        onPermissionDenied = onPermissionDenied,
                        onDismissError = onDismissError,
                        onFinish = onFinish,
                    )
                },
            )
        }
    }
}

@Composable
private fun SheetContentSwitcher(
    state: SheetState,
    minimizedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val showExpanded = state == SheetState.Expanded
    // Use Box with both children always present; alpha-fade between them.
    // Heights collapse via animateContentSize so the parent re-measures.
    Box {
        Box(modifier = Modifier.graphicsLayer { alpha = if (showExpanded) 0f else 1f }) {
            minimizedContent()
        }
        Box(modifier = Modifier.graphicsLayer { alpha = if (showExpanded) 1f else 0f }) {
            expandedContent()
        }
    }
}
```

(Note: SheetContentSwitcher uses overlapping children inside Box. The taller content determines the Box's height — so when state is Minimized, the Box is still tall enough for ExpandedContent. To collapse height when Minimized, wrap each child in `Modifier.heightIn(max = ...)` OR use `Modifier.alpha` + `Modifier.size(...)` per state OR accept that the sheet stays at expanded height even when content is "minimized." For 9.5-B, accept the latter as a trade-off: the sheet's measured height is constant; the visual content swaps via alpha. The map's bottom-inset uses the constant Expanded height. This is simpler than measuring per state. Document as "sheet height is constant; alpha-faded content variants" in the file kdoc.)

#### 6.1 `DragHandle`

```kotlin
@Composable
private fun DragHandle(canDrag: Boolean) {
    val opacity = if (canDrag) 0.35f else 0.12f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 5.dp)
                .background(
                    color = pilgrimColors.fog.copy(alpha = opacity),
                    shape = RoundedCornerShape(percent = 50),
                ),
        )
    }
}
```

#### 6.2 `MinimizedContent`

```kotlin
@Composable
private fun MinimizedContent(
    totalElapsedMillis: Long,
    distanceMeters: Double,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // suppress Material ripple; iOS-parity tap
                onClick = onTap,
            )
            .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatColumn(value = WalkFormat.duration(totalElapsedMillis),
                   label = stringResource(R.string.walk_stat_time))
        StatColumn(value = WalkFormat.distance(distanceMeters),
                   label = stringResource(R.string.walk_stat_distance))
        StatColumn(value = "—", label = stringResource(R.string.walk_stat_steps))
    }
}
```

#### 6.3 `ExpandedContent`

```kotlin
@Composable
private fun ExpandedContent(
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = PilgrimSpacing.big),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Column(modifier = Modifier.fillMaxWidth(),
               horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = WalkFormat.duration(totalElapsedMillis),
                style = pilgrimType.timer,
                color = pilgrimColors.ink,
            )
            Spacer(Modifier.height(PilgrimSpacing.xs))
            Text(
                text = stringResource(R.string.walk_caption_every_step),
                style = pilgrimType.caption,
                color = pilgrimColors.fog.copy(alpha = 0.6f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatColumn(value = WalkFormat.distance(distanceMeters),
                       label = stringResource(R.string.walk_stat_distance),
                       modifier = Modifier.weight(1f))
            StatColumn(value = "—", // Steps placeholder
                       label = stringResource(R.string.walk_stat_steps),
                       modifier = Modifier.weight(1f))
            StatColumn(value = "—", // Ascent placeholder
                       label = stringResource(R.string.walk_stat_ascent),
                       modifier = Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            TimeChip(label = stringResource(R.string.walk_chip_walk),
                     icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                     value = WalkFormat.shortDuration(walkMillis),
                     active = walkState is WalkState.Active,
                     modifier = Modifier.weight(1f))
            TimeChip(label = stringResource(R.string.walk_chip_talk),
                     icon = Icons.Filled.Mic,
                     value = WalkFormat.shortDuration(talkMillis),
                     active = recorderState is VoiceRecorderUiState.Recording,
                     modifier = Modifier.weight(1f))
            TimeChip(label = stringResource(R.string.walk_chip_meditate),
                     icon = Icons.Outlined.SelfImprovement,
                     value = WalkFormat.shortDuration(meditateMillis),
                     active = walkState is WalkState.Meditating,
                     modifier = Modifier.weight(1f))
        }
        ActionButtonRow(
            walkState = walkState,
            recorderState = recorderState,
            audioLevel = audioLevel,
            recordingsCount = recordingsCount,
            onPause = onPause,
            onResume = onResume,
            onStartMeditation = onStartMeditation,
            onEndMeditation = onEndMeditation,
            onToggleRecording = onToggleRecording,
            onPermissionDenied = onPermissionDenied,
            onDismissError = onDismissError,
            onFinish = onFinish,
        )
    }
}
```

#### 6.4 Action button row — disabled greyed-out placeholders

```kotlin
@Composable
private fun ActionButtonRow(
    walkState: WalkState,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        // Pause/Resume slot — disabled when Meditating (only End-meditation
        // routes back to Active, which is when Pause becomes available).
        when (walkState) {
            is WalkState.Active -> CircularActionButton(
                label = stringResource(R.string.walk_action_pause),
                icon = Icons.Filled.Pause,
                color = pilgrimColors.stone,
                onClick = onPause,
                modifier = Modifier.weight(1f),
            )
            is WalkState.Paused -> CircularActionButton(
                label = stringResource(R.string.walk_action_resume),
                icon = Icons.Filled.PlayArrow,
                color = pilgrimColors.stone,
                onClick = onResume,
                modifier = Modifier.weight(1f),
            )
            else -> CircularActionButton(
                label = stringResource(R.string.walk_action_pause),
                icon = Icons.Filled.Pause,
                color = pilgrimColors.fog,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        // Meditate/EndMeditation slot — disabled when Paused.
        when (walkState) {
            is WalkState.Active -> CircularActionButton(
                label = stringResource(R.string.walk_action_meditate_short),
                icon = Icons.Outlined.SelfImprovement,
                color = pilgrimColors.dawn,
                onClick = onStartMeditation,
                modifier = Modifier.weight(1f),
            )
            is WalkState.Meditating -> CircularActionButton(
                label = stringResource(R.string.walk_action_end_meditation_short),
                icon = Icons.Filled.Stop,
                color = pilgrimColors.dawn,
                onClick = onEndMeditation,
                modifier = Modifier.weight(1f),
            )
            else -> CircularActionButton(
                label = stringResource(R.string.walk_action_meditate_short),
                icon = Icons.Outlined.SelfImprovement,
                color = pilgrimColors.fog,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        MicActionButton(
            recorderState = recorderState,
            audioLevel = audioLevel,
            onToggle = onToggleRecording,
            onPermissionDenied = onPermissionDenied,
            onDismissError = onDismissError,
            modifier = Modifier.weight(1f),
        )
        CircularActionButton(
            label = stringResource(R.string.walk_action_finish_short),
            icon = Icons.Filled.Stop,
            color = pilgrimColors.fog,
            onClick = onFinish,
            modifier = Modifier.weight(1f),
        )
    }
}
```

`CircularActionButton` accepts `enabled: Boolean = true`. When disabled, color desaturates to `pilgrimColors.fog`, label dims to 0.4 alpha, click is no-op.

#### 6.5 `CircularActionButton` + `MicActionButton`

```kotlin
@Composable
private fun CircularActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val effectiveColor = if (enabled) color else pilgrimColors.fog.copy(alpha = 0.4f)
    Column(
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(color = effectiveColor.copy(alpha = 0.06f), shape = CircleShape)
                .border(1.5.dp, effectiveColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = effectiveColor)
        }
        Spacer(Modifier.height(PilgrimSpacing.xs))
        Text(label, style = pilgrimType.caption, color = effectiveColor, maxLines = 1)
    }
}
```

`MicActionButton` is a separate Composable that wraps the existing voice-record state machine in a circular treatment matching `CircularActionButton`'s shape. It uses the existing `recorderState` + `audioLevel` to decide between mic icon (Idle) and the existing audio waveform (Recording). Reuses the existing permission/error handling from `RecordControl` — extract that logic into a shared private function.

(Note: existing `RecordControl` is rectangular. We rewrite as `MicActionButton` for circular framing; the underlying VM callbacks + state-machine behavior is identical. The existing rectangular `RecordControl` becomes dead code in this stage and can be deleted.)

### 7. New string resources

**File:** `app/src/main/res/values/strings.xml` (modify)

```xml
<!-- Stage 9.5-B: Active Walk layout rebuild -->
<string name="walk_stat_time">Time</string>
<string name="walk_stat_steps">Steps</string>
<string name="walk_stat_ascent">Ascent</string>
<string name="walk_chip_walk">Walk</string>
<string name="walk_chip_talk">Talk</string>
<string name="walk_chip_meditate">Meditate</string>
<!-- TRANSLATORS: contemplative caption shown during active walks. Pilgrim's
     voice is contemplative + slow; aim for a short aphoristic line. -->
<string name="walk_caption_every_step">every step is enough</string>
<string name="walk_action_meditate_short">Meditate</string>
<string name="walk_action_end_meditation_short">End</string>
<string name="walk_action_finish_short">End</string>
```

(No `walk_format_*` resources — the format strings live as `Locale.US`-pinned `String.format` calls in `WalkFormat.kt` per Stage 5-A memory.)

### 8. New `WalkFormat.shortDuration` helper

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt` (modify)

```kotlin
/**
 * Compact duration for time chips. Returns "—" for ≤0, "M:SS" below
 * one hour, and "H:MM" at one hour or more so the chip text fits the
 * narrow 80-100dp pill width even on long walks.
 */
fun shortDuration(millis: Long): String {
    if (millis <= 0) return "—"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d", hours, minutes)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}
```

(`Locale.US` per Stage 5-A memory. Switching to H:MM at the 1-hour boundary prevents chip overflow for long walks; matches iOS rendering.)

### 9. `PilgrimMap.bottomInsetDp` parameter

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt` (modify)

Add a new optional parameter:

```kotlin
@Composable
fun PilgrimMap(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
    initialCenter: LocationPoint? = null,
    bottomInsetDp: Dp = 0.dp,  // NEW
) {
    // ... existing code ...
    val paddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.toPx().toDouble() }
    val bottomInsetPx = with(LocalDensity.current) { bottomInsetDp.toPx().toDouble() }
    // ... where EdgeInsets is constructed:
    EdgeInsets(top = paddingPx, left = paddingPx, bottom = paddingPx + bottomInsetPx, right = paddingPx)
    // ... AND set the camera padding for follow-latest to apply the same bottom inset.
}
```

Verify Mapbox SDK exposes a camera-padding setter. If not, just apply to fit-bounds (which handles the cold-start case) and accept that follow-latest puck may sit slightly under sheet during pans (still better than the no-inset baseline).

### 10. `WalkStats.totalMeditatedMillis` — add per §1.

(Already covered in §1 but listed here for the file-modification index.)

### 11. Tests

**New:**
- `WalkStatsTotalMeditatedMillisTest.kt` — Idle, Active, Paused, Meditating + clock-skew cases per §1.
- `WalkFormatShortDurationTest.kt` — 0 → "—"; 30s → "0:30"; 90s → "1:30"; 65min → "1:05" (NOT "65:00"); 125min → "2:05".
- `SheetStateControllerTest.kt` — Active → Minimized; Paused (with virtual time advance) → Expanded; Paused → Active within debounce → Minimized called, Expanded NOT called; Meditating → Expanded.
- `WalkStatsSheetMinimizedTest.kt` — Robolectric Compose: minimized renders 3 stat columns; tap invokes onTap; "—" placeholder for Steps.
- `WalkStatsSheetExpandedTest.kt` — Robolectric Compose: expanded renders timer, caption, 3-stat row (with both placeholder "—"s), 3 chips, action button row.
- `WalkStatsSheetActionRowTest.kt` — for each WalkState (Active/Paused/Meditating), assert which buttons are enabled vs greyed out.
- `WalkStatsSheetDragGestureTest.kt` — Compose UI test: `composeRule.onNodeWithTag("walk-sheet").performTouchInput { swipeUp(durationMillis = 100) }` → state == Expanded; `swipeDown` → state == Minimized; tap on minimized → state == Expanded.
- `WalkStatsSheetSavedStateTest.kt` — `StateRestorationTester`: set state to Minimized, simulate config change, assert state still Minimized.
- `WalkViewModelVoiceRecordingsTest.kt` — verify `talkMillis` flow sums durations; switching walks → new sum; null walkId → 0L; verify `recordingsCount` and `talkMillis` derive from a single upstream subscription (one Room collector active at a time when both are subscribed).

**Modified:**
- `WalkViewModelTest.kt` — update `recordingsCount` test if any details depend on it being a direct upstream subscription.

### 12. Out of scope (deferred)

- Step counter wiring (SensorManager TYPE_STEP_COUNTER, ACTIVITY_RECOGNITION permission, persistence). Steps stat shows "—".
- AltitudeSample writer (FusedLocationSource → recordAltitude). Ascent stat shows "—".
- iOS-only luxuries: weather/celestial overlays, floating greetings, ambient pace sparkline, options sheet, peek-hint animation, turning-day kanji watermark, intention card.
- Auto-pause subsystem (motion-based pause/resume detection). Manual Pause/Resume retained.
- Inspirational caption rotation per state (single static caption for 9.5-B).
- Sheet-height measurement via SubcomposeLayout (constant Expanded height; map's bottom-inset is approximate). `PilgrimMap.bottomInsetDp` IS added so the puck stays visible.
- Font-scale cap on the sheet (iOS caps at .accessibility3). Document accessibility-text overflow as known issue.
- Pinch-zoom routing through sheet area (single-finger drag on sheet blocks Mapbox pinch initiated within sheet bounds). Document trade-off.
- Custom Saver for `SheetState` if the enum ever becomes a sealed class.

## Verification (manual)

1. Start a walk from Path tab → ACTIVE_WALK opens. Map fills the entire screen. Sheet appears at bottom in Expanded state showing timer 0:00 + caption + 3-stat row (Distance, "—", "—") + 3 chips + 4 action buttons.
2. After ~5 s of recording, sheet auto-collapses to Minimized.
3. Drag handle up from minimized → sheet expands.
4. Drag down from expanded → sheet collapses.
5. Tap Pause → sheet auto-expands after 800 ms; Pause button replaces with Resume; Meditate button greys out.
6. Resume → sheet auto-collapses after ~5 s recording.
7. Tap Meditate → MeditationScreen opens. Return → sheet visible.
8. Tap Mic → recording starts; mic button outline thickens + waveform replaces icon; talk-time chip activates.
9. Walk-time chip increments while Active; pauses while Paused; pauses while Meditating.
10. Distance updates as user walks. Steps + Ascent stay at "—" per spec.
11. User-puck stays visible above the expanded sheet (PilgrimMap.bottomInsetDp).
12. Tap End → finishWalk triggers; nav to walkSummary.
13. System Back during a walk → moveTaskToBack.
14. Rotate device → SheetState (and dragOffset reset) preserved.
15. Reduced-motion: sheet drag still works; commit transitions instant rather than spring.
16. GPS-flap test: pause walk and immediately resume — sheet should NOT visibly expand (debounce cancels the in-flight expand).

## Files

**New:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetState.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateController.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkStatsTotalMeditatedMillisTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormatShortDurationTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateControllerTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetMinimizedTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetExpandedTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetActionRowTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetDragGestureTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetSavedStateTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelVoiceRecordingsTest.kt`

**Modified:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` (rewrite layout)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt` (add voiceRecordings + talkMillis + refactor recordingsCount; remove uses of removed routePoints/initialCameraCenter NO — keep those, sheet just doesn't need them)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt` (add shortDuration)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt` (add bottomInsetDp parameter)
- `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkStats.kt` (add totalMeditatedMillis)
- `app/src/main/res/values/strings.xml` (new strings)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/RecordControl.kt` — DELETE; superseded by `MicActionButton` inside `WalkStatsSheet.kt`. Verify no other callers.

## Open questions resolved

All previous open questions resolved:
- Q1 (sheet shadow direction) → manual upward shadow via gradient strip above sheet's top edge. See §6.
- Q2 (MicActionButton reuse vs rewrite) → rewrite as circular variant; delete `RecordControl.kt`.
- Q3 (totalMeditatedMillis exists?) → verified does NOT exist; mandated add per §1.
- Q4 (observeVoiceRecordings returns rows?) → verified returns `Flow<List<VoiceRecording>>`. Single-source pattern in §2 derives both `recordingsCount` and `talkMillis`.
- Q5 (AltitudeSamples populated?) → verified NOT populated by any production code. Ascent stat shows "—" placeholder same as Steps.

## Known limitations (documented for shipping)

- **Steps and Ascent are placeholder "—"** until separate stages wire SensorManager and FusedLocationSource respectively.
- **Sheet height is constant** (~340dp regardless of state); content variants alpha-fade. Map's `bottomInsetDp` uses constant 340dp Expanded height. Trade-off: simpler implementation than measured-per-state heights via SubcomposeLayout. Visual consequence: minimized state has unused vertical space at the top of the sheet area. Acceptable for 9.5-B; revisit if device QA flags.
- **Pinch-zoom blocked when initiated on the sheet area**. Mostly a non-issue (sheet covers ~25% of screen); one-finger-on-sheet, one-above is the only real edge case. Document.
- **Accessibility text overflow**: at ax3+ font scale, the chip row + action labels may overflow. iOS caps at .accessibility3; Android does not. Acknowledge as known issue, defer.
- **Single static caption** ("every step is enough") rather than iOS's per-state rotation. Caption rotation may land in a follow-up polish stage.
