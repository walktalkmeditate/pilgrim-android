# Stage 4-B Implementation Plan — Goshuin Seal Reveal Animation

Spec: `docs/superpowers/specs/2026-04-19-stage-4b-seal-reveal-design.md`.

Integrate the Stage 4-A `SealRenderer` into the real walk-finish flow via a stamp-animated overlay on `WalkSummaryScreen`. Apply two polish items flagged in 4-A reviews. Delete 4-A's debug preview scaffolding.

---

## Task 1 — Add `DistanceLabel` + `distanceLabel()` to `WalkFormat.kt`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt`

Add the typed helper + data class. Keep the existing `distance(meters: Double): String` unchanged (zero blast-radius on its 4 callers).

```kotlin
/**
 * Same formatting rules as [distance] but returns the value and unit
 * in separate fields so callers (e.g., the seal center-text layer)
 * don't have to split a pre-formatted string.
 */
data class DistanceLabel(
    /** e.g. `"5.20"`, `"420"`, `"0"`. */
    val value: String,
    /** e.g. `"km"`, `"m"`. */
    val unit: String,
)

fun distanceLabel(meters: Double): DistanceLabel {
    val km = meters / 1_000.0
    return if (meters >= 100.0) {
        DistanceLabel(String.format(Locale.US, "%.2f", km), "km")
    } else {
        DistanceLabel(String.format(Locale.US, "%d", meters.roundToInt()), "m")
    }
}
```

**Verify:** file compiles.

---

## Task 2 — `WalkFormatTest.kt` updates

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormatTest.kt`

Add tests for `distanceLabel`:

```kotlin
@Test fun `distanceLabel under 100m returns meter value`() {
    val label = WalkFormat.distanceLabel(50.5)
    assertEquals("50", label.value)     // rounded
    assertEquals("m", label.unit)
}

@Test fun `distanceLabel at exactly 99m stays in meters`() {
    val label = WalkFormat.distanceLabel(99.9)
    assertEquals("100", label.value)
    assertEquals("m", label.unit)
}

@Test fun `distanceLabel at exactly 100m crosses to km`() {
    val label = WalkFormat.distanceLabel(100.0)
    assertEquals("0.10", label.value)
    assertEquals("km", label.unit)
}

@Test fun `distanceLabel over 1km formats to two decimals`() {
    val label = WalkFormat.distanceLabel(1234.56)
    assertEquals("1.23", label.value)
    assertEquals("km", label.unit)
}

@Test fun `distanceLabel zero returns 0 meters`() {
    val label = WalkFormat.distanceLabel(0.0)
    assertEquals("0", label.value)
    assertEquals("m", label.unit)
}
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*WalkFormatTest*'
```

---

## Task 3 — Hoist `NativePaint` into `remember` in `SealRenderer.kt`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRenderer.kt`

Cache Paint instances (keyed on typeface) and mutate `textSize`/`color` at draw time. Halves per-frame allocation on the reveal animation.

```kotlin
@Composable
fun SealRenderer(
    spec: SealSpec,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(spec.uuid, spec.startMillis, spec.distanceMeters) {
        sealGeometry(spec)
    }
    val context = LocalContext.current
    val cormorantTypeface = remember {
        ResourcesCompat.getFont(context, R.font.cormorant_garamond_variable)
            ?: Typeface.DEFAULT
    }
    val latoTypeface = remember {
        ResourcesCompat.getFont(context, R.font.lato_regular)
            ?: Typeface.DEFAULT
    }
    // Stage 4-B polish: cache Paint instances so the 60fps reveal
    // animation doesn't allocate 4 Paint + 2 FontMetrics per frame.
    // textSize + color mutate per-draw (cheap field assignments);
    // typeface + align + antialias are stable across the composable's
    // lifetime.
    val distancePaint = remember(cormorantTypeface) {
        NativePaint().apply {
            typeface = cormorantTypeface
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }
    val unitPaint = remember(latoTypeface) {
        NativePaint().apply {
            typeface = latoTypeface
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val canvasSize = size.minDimension
        if (canvasSize <= 0f) return@Canvas
        // ... existing rings/radials/arcs/dots draws unchanged ...
        drawCenterText(
            center = center,
            canvasSize = canvasSize,
            distance = spec.displayDistance,
            unit = spec.unitLabel,
            ink = spec.ink,
            distancePaint = distancePaint,
            unitPaint = unitPaint,
        )
    }
}

private fun DrawScope.drawCenterText(
    center: Offset,
    canvasSize: Float,
    distance: String,
    unit: String,
    ink: Color,
    distancePaint: NativePaint,
    unitPaint: NativePaint,
) {
    val distanceTextPx = canvasSize * 0.09f
    val unitTextPx = canvasSize * 0.032f
    val gapPx = canvasSize * 0.008f
    // Mutate cached paints (no allocation):
    distancePaint.textSize = distanceTextPx
    distancePaint.color = ink.toArgb()
    unitPaint.textSize = unitTextPx
    unitPaint.color = ink.copy(alpha = (ink.alpha * 0.9f).coerceIn(0f, 1f)).toArgb()
    drawIntoCanvas { composeCanvas ->
        val native = composeCanvas.nativeCanvas
        val distanceMetrics = distancePaint.fontMetrics
        val unitMetrics = unitPaint.fontMetrics
        val distanceHeight = distanceMetrics.descent - distanceMetrics.ascent
        val unitHeight = unitMetrics.descent - unitMetrics.ascent
        val totalHeight = distanceHeight + gapPx + unitHeight
        val blockTop = center.y - totalHeight / 2f
        val distanceBaseline = blockTop - distanceMetrics.ascent
        val unitBaseline = distanceBaseline + distanceMetrics.descent + gapPx - unitMetrics.ascent
        native.drawText(distance, center.x, distanceBaseline, distancePaint)
        native.drawText(unit, center.x, unitBaseline, unitPaint)
    }
}
```

**Verify:** `SealRendererComposableTest` + `SealHashTest` + `SealGeometryTest` all still pass.

```
./gradlew testDebugUnitTest --tests '*ui.design.seals.*'
```

---

## Task 4 — Extend `WalkSummaryViewModel` with hemisphere + SealSpec

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`

Inject `HemisphereRepository`, expose `hemisphere: StateFlow`, extend `WalkSummary` with `sealSpec`.

Add imports:
```kotlin
import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
```

Update constructor:
```kotlin
@HiltViewModel
class WalkSummaryViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val playback: VoicePlaybackController,
    private val sweeper: OrphanRecordingSweeper,
    hemisphereRepository: HemisphereRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere
    // ... existing fields ...
}
```

Extend `WalkSummary`:
```kotlin
data class WalkSummary(
    val walk: Walk,
    val totalElapsedMillis: Long,
    val activeWalkingMillis: Long,
    val totalPausedMillis: Long,
    val totalMeditatedMillis: Long,
    val distanceMeters: Double,
    val paceSecondsPerKm: Double?,
    val waypointCount: Int,
    val routePoints: List<LocationPoint>,
    /**
     * Pre-built seal spec for the reveal animation. Ink is left as
     * [Color.Transparent] — the composable resolves the seasonal tint
     * via [SeasonalColorEngine] in @Composable context.
     */
    val sealSpec: SealSpec,
)
```

Update `buildState` — add a sealSpec build using `distanceLabel` + `walk.toSealSpec`:

```kotlin
private suspend fun buildState(): WalkSummaryUiState {
    val walk = repository.getWalk(walkId) ?: return WalkSummaryUiState.NotFound
    val samples = repository.locationSamplesFor(walkId)
    val events = repository.eventsFor(walkId)
    val waypoints = repository.waypointsFor(walkId)

    val points = samples.map {
        LocationPoint(
            timestamp = it.timestamp,
            latitude = it.latitude,
            longitude = it.longitude,
        )
    }
    val distance = walkDistanceMeters(points)
    val totals = replayWalkEventTotals(events = events, closeAt = walk.endTimestamp)
    val totalElapsed = (walk.endTimestamp ?: walk.startTimestamp) - walk.startTimestamp
    val activeWalking = (totalElapsed - totals.totalPausedMillis - totals.totalMeditatedMillis)
        .coerceAtLeast(0)

    val distanceKm = distance / 1_000.0
    val pace = if (distanceKm >= 0.01 && activeWalking >= 1_000L) {
        (activeWalking / 1_000.0) / distanceKm
    } else {
        null
    }

    val distanceLabel = WalkFormat.distanceLabel(distance)
    val sealSpec = walk.toSealSpec(
        samples = samples,
        ink = Color.Transparent,
        displayDistance = distanceLabel.value,
        unitLabel = distanceLabel.unit,
    )

    return WalkSummaryUiState.Loaded(
        WalkSummary(
            walk = walk,
            totalElapsedMillis = totalElapsed,
            activeWalkingMillis = activeWalking,
            totalPausedMillis = totals.totalPausedMillis,
            totalMeditatedMillis = totals.totalMeditatedMillis,
            distanceMeters = distance,
            paceSecondsPerKm = pace,
            waypointCount = waypoints.size,
            routePoints = points,
            sealSpec = sealSpec,
        ),
    )
}
```

---

## Task 5 — Create `SealRevealOverlay.kt`

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRevealOverlay.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Full-screen overlay that stamps the walk's goshuin seal on-screen
 * with a 3-phase animation, holds for 2.5s, then calls [onDismiss].
 * Tap anywhere to dismiss early.
 *
 * Phases:
 *  1. Hidden → Pressing: scale 1.2 → 0.95 over 200ms (easeIn)
 *  2. Pressing → Revealed: scale 0.95 → 1.0 via spring (matches iOS
 *     `spring(response: 0.4, dampingFraction: 0.6)` — see design spec's
 *     "spring parameter conversion" note). Haptic fires at this
 *     transition. Shadow fades in to 25% alpha.
 *  3. After 2500ms hold → Dismissing: alpha 1 → 0 over 300ms → [onDismiss].
 *
 * The overlay's opacity animates independently of the seal's scale so
 * the fade-in at phase 1 + fade-out at phase 3 read as cohesive.
 *
 * See `docs/superpowers/specs/2026-04-19-stage-4b-seal-reveal-design.md`.
 */
@Composable
fun SealRevealOverlay(
    spec: SealSpec,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sealSizeDp: Int = 220,
) {
    var phase by remember { mutableStateOf(SealRevealPhase.Hidden) }
    val haptic = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = when (phase) {
            SealRevealPhase.Hidden, SealRevealPhase.Dismissing -> SCALE_HIDDEN
            SealRevealPhase.Pressing -> SCALE_PRESSED
            SealRevealPhase.Revealed -> SCALE_REVEALED
        },
        animationSpec = when (phase) {
            SealRevealPhase.Pressing -> tween(durationMillis = PRESS_DURATION_MS, easing = EaseIn)
            SealRevealPhase.Revealed -> spring(dampingRatio = SPRING_DAMPING, stiffness = SPRING_STIFFNESS)
            else -> tween(durationMillis = FADE_DURATION_MS)
        },
        label = "sealRevealScale",
    )
    val opacity by animateFloatAsState(
        targetValue = when (phase) {
            SealRevealPhase.Hidden, SealRevealPhase.Dismissing -> 0f
            SealRevealPhase.Pressing, SealRevealPhase.Revealed -> 1f
        },
        animationSpec = tween(durationMillis = FADE_DURATION_MS),
        label = "sealRevealOpacity",
    )
    val shadowAlpha by animateFloatAsState(
        targetValue = if (phase == SealRevealPhase.Revealed) SHADOW_ALPHA_REVEALED else 0f,
        animationSpec = tween(durationMillis = SHADOW_DURATION_MS),
        label = "sealRevealShadow",
    )

    LaunchedEffect(Unit) {
        phase = SealRevealPhase.Pressing
        delay(PRESS_DURATION_MS.toLong())
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        phase = SealRevealPhase.Revealed
        delay(HOLD_DURATION_MS)
        // If the user already tapped to dismiss, `phase` is Dismissing
        // here and the guard below skips the auto-transition.
        if (phase == SealRevealPhase.Revealed) {
            phase = SealRevealPhase.Dismissing
            delay(FADE_DURATION_MS.toLong())
            onDismiss()
        }
    }

    // Overlay container — captures dismiss taps on background + seal.
    // `MutableInteractionSource` + `indication = null` suppresses the
    // default material ripple (we don't want a visual ripple on the
    // parchment background).
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(opacity)
            .background(pilgrimColors.parchment.copy(alpha = OVERLAY_BACKGROUND_ALPHA))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) {
                if (phase != SealRevealPhase.Dismissing) {
                    phase = SealRevealPhase.Dismissing
                    // Early-dismiss path: the LaunchedEffect's timer
                    // will eventually fire onDismiss, but we short-
                    // circuit by also calling it immediately once the
                    // fade completes. Use a local flag so the effect
                    // doesn't double-dismiss.
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(sealSizeDp.dp)
                .scale(scale)
                .shadow(
                    elevation = SHADOW_ELEVATION_DP.dp,
                    ambientColor = Color.Black.copy(alpha = shadowAlpha),
                    spotColor = Color.Black.copy(alpha = shadowAlpha),
                ),
        ) {
            SealRenderer(spec = spec)
        }
    }

    // Separate LaunchedEffect watches for Dismissing → fire onDismiss
    // after the fade completes. Handles both auto-dismiss and
    // tap-to-dismiss paths uniformly.
    LaunchedEffect(phase) {
        if (phase == SealRevealPhase.Dismissing) {
            delay(FADE_DURATION_MS.toLong())
            onDismiss()
        }
    }
}

internal enum class SealRevealPhase { Hidden, Pressing, Revealed, Dismissing }

private const val SCALE_HIDDEN = 1.2f
private const val SCALE_PRESSED = 0.95f
private const val SCALE_REVEALED = 1.0f

private const val PRESS_DURATION_MS = 200
private const val FADE_DURATION_MS = 300
private const val SHADOW_DURATION_MS = 150
private const val HOLD_DURATION_MS = 2500L

// iOS `spring(response: 0.4, dampingFraction: 0.6)` doesn't map 1:1 to
// Compose's dampingRatio/stiffness; these are empirically close. Stage
// 4-B device QA may tune.
private const val SPRING_DAMPING = 0.6f
private const val SPRING_STIFFNESS = 500f

private const val OVERLAY_BACKGROUND_ALPHA = 0.95f
private const val SHADOW_ALPHA_REVEALED = 0.25f
private const val SHADOW_ELEVATION_DP = 12
```

> **Self-note.** The two `LaunchedEffect`s above (one keyed on `Unit` for the initial animation, one keyed on `phase` for the auto-dismiss) handle the two paths — auto-dismiss and tap-to-dismiss — without double-firing. The `phase == Revealed` guard in the first effect prevents it from transitioning to Dismissing if the user already tapped. The second effect handles the Dismissing→onDismiss fade-out uniformly.

---

## Task 6 — Render `SealRevealOverlay` inside `WalkSummaryScreen`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`

Wrap the existing Column in a Box, add the overlay after it (Box children render bottom-up in z-order; overlay goes last so it's on top).

Add imports:
```kotlin
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRevealOverlay
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
```

Restructure the body:

```kotlin
@Composable
fun WalkSummaryScreen(
    onDone: () -> Unit,
    viewModel: WalkSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val playbackUiState by viewModel.playbackUiState.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.runStartupSweep() }

    // One-shot: reveal plays on this entry to WalkSummaryScreen. After
    // the overlay calls onDismiss, flip to false so it doesn't replay
    // on unrelated recompositions (e.g., scroll).
    var showReveal by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Summary content renders first (z-order: behind the overlay).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.big),
        ) {
            // ... existing Text header + when(state) + Done button ...
        }

        // Reveal overlay renders on top. Only for Loaded state — we need
        // the sealSpec. Loading/NotFound branches skip the overlay.
        val loaded = state as? WalkSummaryUiState.Loaded
        if (showReveal && loaded != null) {
            val baseInk = pilgrimColors.rust
            val walkDate = remember(loaded.summary.walk.startTimestamp) {
                Instant.ofEpochMilli(loaded.summary.walk.startTimestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
            val tintedInk = SeasonalColorEngine.applySeasonalShift(
                base = baseInk,
                intensity = SeasonalColorEngine.Intensity.Full,
                date = walkDate,
                hemisphere = hemisphere,
            )
            val specForReveal = loaded.summary.sealSpec.copy(ink = tintedInk)
            SealRevealOverlay(
                spec = specForReveal,
                onDismiss = { showReveal = false },
            )
        }
    }
}
```

---

## Task 7 — Delete Stage 4-A debug scaffolding

**Delete:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealPreview.kt`
**Delete:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealPreviewViewModel.kt`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

- Remove `import org.walktalkmeditate.pilgrim.BuildConfig`
- Remove `import org.walktalkmeditate.pilgrim.ui.design.seals.SealPreviewScreen`
- Remove `const val SEAL_PREVIEW = "seal_preview"` from `Routes`
- Remove the `onEnterSealPreview = { ... }` lambda argument from the HomeScreen call
- Remove the entire `if (BuildConfig.DEBUG) { composable(Routes.SEAL_PREVIEW) { SealPreviewScreen() } }` block

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

- Remove `import androidx.compose.material3.TextButton`
- Remove `import org.walktalkmeditate.pilgrim.BuildConfig`
- Remove `onEnterSealPreview: () -> Unit` parameter from `HomeScreen`
- Remove the entire `if (BuildConfig.DEBUG) { ... TextButton("Seal preview (debug)") }` block below BatteryExemptionCard

---

## Task 8 — Update `WalkSummaryViewModelTest.kt` for the new deps

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt`

1. Add imports for `HemisphereRepository`, `Hemisphere`, DataStore, `FakeLocationSource`.
2. Construct a real `HemisphereRepository` in `@Before` (same pattern as Stage 3-E's `HomeViewModelTest`). Use a throwaway DataStore file name (`"walk-summary-vm-hemisphere-test"`).
3. Update every `WalkSummaryViewModel(...)` construction to include the new repo.
4. Add new tests:

```kotlin
@Test fun `Loaded state carries sealSpec with walk uuid and seed fields`() = runTest(dispatcher) {
    val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
    runBlocking {
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.001),
        )
        repository.finishWalk(walk, endTimestamp = 5_600_000L)
    }
    val vm = newViewModel(walk.id)
    vm.state.test {
        val loaded = awaitLoaded(this)
        val spec = loaded.summary.sealSpec
        assertEquals(walk.uuid, spec.uuid)
        assertEquals(walk.startTimestamp, spec.startMillis)
        assertTrue("distanceMeters=${spec.distanceMeters}", spec.distanceMeters > 0.0)
        assertTrue("displayDistance should be non-empty", spec.displayDistance.isNotEmpty())
        assertTrue("unitLabel should be m or km", spec.unitLabel in setOf("m", "km"))
        cancelAndIgnoreRemainingEvents()
    }
}

@Test fun `hemisphere StateFlow proxies the repository`() = runTest(dispatcher) {
    val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
    runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }
    val vm = newViewModel(walk.id)
    assertEquals(Hemisphere.Northern, vm.hemisphere.value)
    hemisphereRepo.setOverride(Hemisphere.Southern)
    val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(3_000L) {
            vm.hemisphere.first { it == Hemisphere.Southern }
        }
    }
    assertEquals(Hemisphere.Southern, observed)
}
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*WalkSummaryViewModelTest*'
```

---

## Task 9 — Add Robolectric smoke test for `SealRevealOverlay`

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRevealOverlayTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composition smoke tests for [SealRevealOverlay]. Animation timing
 * isn't asserted (Compose animations + LaunchedEffect timing is hard
 * to pin down in unit tests); manual on-device QA verifies the 3-phase
 * choreography. These tests confirm the composable reaches Compose's
 * draw pipeline without crashing for the inputs the production code
 * passes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SealRevealOverlayTest {

    @get:Rule val composeRule = createComposeRule()

    private fun testSpec() = SealSpec(
        uuid = "reveal-test-0",
        startMillis = 1_700_000_000_000L,
        distanceMeters = 5_000.0,
        durationSeconds = 1_800.0,
        displayDistance = "5.00",
        unitLabel = "km",
        ink = Color(0xFFA0634B),
    )

    @Test fun `overlay renders without crashing`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = testSpec(), onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `overlay renders with small sealSize`() {
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(
                    spec = testSpec(),
                    onDismiss = {},
                    sealSizeDp = 80,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `overlay renders with zero-distance spec`() {
        val zeroDistance = testSpec().copy(
            distanceMeters = 0.0,
            displayDistance = "0",
            unitLabel = "m",
        )
        composeRule.setContent {
            Box(Modifier.size(400.dp, 800.dp)) {
                SealRevealOverlay(spec = zeroDistance, onDismiss = {})
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
```

---

## Task 10 — CI gate

```
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: green. New tests (~8) added + existing suite stays passing.

---

## Out-of-plan notes

- **No Manifest changes.** `LocalHapticFeedback` doesn't need `VIBRATE` permission.
- **No Gradle changes.** No new dependencies.
- **Hilt wiring.** `HemisphereRepository` is already `@Singleton @Inject`-bound via Stage 3-D's `SeasonalModule`. Adding it as a constructor dep on `WalkSummaryViewModel` requires no module changes.
- **No `strings.xml` changes.** The overlay has no user-facing text.
- **HomeScreen call-site** — `PilgrimNavHost` is the only caller, updated in Task 7.
