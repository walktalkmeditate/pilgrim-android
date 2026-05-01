# Stage 13-B — Reveal animation + map polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Layer reveal sequence (zoom→bounds + count-up + fade-in) and map polish (circular mask + segment-tinted polyline + 320dp height) onto Stage 13-A's Walk Summary skeleton.

**Architecture:** New `RouteSegments.kt` (data + classifier port) + `RevealAnimation.kt` (RevealPhase enum + SmoothStepEasing + colors). VM gains `routeSegments` field. `PilgrimMap` extended with multi-polyline + reveal-camera path. `WalkSummaryScreen` adds reveal phase machine, animated distance, AnimatedVisibility wrap, mask, height bump.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3 (animateFloatAsState, AnimatedVisibility, drawWithCache, BlendMode, CompositingStrategy), Mapbox Maps Android SDK v11 (PolylineAnnotationManager, easeTo, MapAnimationOptions). Reuses existing `pilgrimColors`, `PilgrimSpacing`, `WalkFormat`.

**Spec:** `docs/superpowers/specs/2026-05-01-stage-13b-reveal-and-map-design.md`

---

## Task 1: Branch off main

**Files:** none

- [ ] **Step 1: Verify clean tree on main**

Run: `git status && git branch --show-current`
Expected: `On branch main / nothing to commit, working tree clean` (after Stage 13-A merge).

- [ ] **Step 2: Sync main**

Run: `git fetch origin main && git pull --ff-only origin main`
Expected: Up to date or fast-forward.

- [ ] **Step 3: Create feature branch**

Run: `git checkout -b feat/stage-13b-reveal-and-map`

- [ ] **Step 4: Commit spec + plan**

```bash
git add docs/superpowers/specs/2026-05-01-stage-13b-reveal-and-map-design.md \
        docs/superpowers/plans/2026-05-01-stage-13b-reveal-and-map.md
git commit -m "docs(walk-summary): Stage 13-B spec + plan (reveal + map polish)"
```

---

## Task 2: RouteSegments classifier + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/RouteSegments.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/RouteSegmentsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/RouteSegmentsTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

class RouteSegmentsTest {

    private fun sample(t: Long, lat: Double = 0.0, lng: Double = 0.0) =
        RouteDataSample(walkId = 1L, timestamp = t, latitude = lat, longitude = lng, altitude = 0.0)

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L,
        startTimestamp = start,
        endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    private fun recording(start: Long, durationMs: Long) = VoiceRecording(
        walkId = 1L,
        startTimestamp = start,
        endTimestamp = start + durationMs,
        durationMillis = durationMs,
        fileRelativePath = "recordings/x.wav",
        transcription = null,
    )

    @Test fun emptySamples_returnsEmptyList() {
        val result = computeRouteSegments(emptyList(), emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun singleSample_returnsEmptyList() {
        val result = computeRouteSegments(listOf(sample(0L)), emptyList(), emptyList())
        assertTrue(result.isEmpty())
    }

    @Test fun allWalking_returnsOneSegment() {
        val samples = listOf(sample(0L, 1.0, 1.0), sample(10L, 2.0, 2.0), sample(20L, 3.0, 3.0))
        val result = computeRouteSegments(samples, emptyList(), emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
        assertEquals(3, result[0].points.size)
    }

    @Test fun talkInMiddle_splitsIntoWalkTalkWalk() {
        val samples = (0L..40L step 10L).map { sample(it, it.toDouble(), it.toDouble()) }
        val recordings = listOf(recording(start = 15L, durationMs = 10L)) // covers t=15..25
        val result = computeRouteSegments(samples, emptyList(), recordings)
        assertEquals(3, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
        assertEquals(RouteActivity.Talking, result[1].activity)
        assertEquals(RouteActivity.Walking, result[2].activity)
    }

    @Test fun meditationOverlapsTalking_meditationWins() {
        val samples = listOf(sample(10L), sample(20L), sample(30L))
        val intervals = listOf(meditation(start = 5L, end = 35L))
        val recordings = listOf(recording(start = 10L, durationMs = 15L))
        val result = computeRouteSegments(samples, intervals, recordings)
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Meditating, result[0].activity)
    }

    @Test fun boundaryPointSharedBetweenSegments() {
        val samples = (0L..30L step 10L).map { sample(it, it.toDouble(), it.toDouble()) }
        val intervals = listOf(meditation(start = 15L, end = 25L))
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(3, result.size)
        // Last point of segment 0 == first point of segment 1
        assertEquals(result[0].points.last(), result[1].points.first())
        assertEquals(result[1].points.last(), result[2].points.first())
    }

    @Test fun pureMeditation_returnsOneMeditatingSegment() {
        val samples = listOf(sample(10L), sample(20L), sample(30L))
        val intervals = listOf(meditation(start = 5L, end = 35L))
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Meditating, result[0].activity)
    }

    @Test fun nonMeditationActivityTypeIgnored() {
        // Only MEDITATING intervals classify as meditating; if the entity carried
        // a different activityType (future-proofing) the classifier ignores it.
        val samples = listOf(sample(10L), sample(20L))
        val intervals = listOf(
            ActivityInterval(
                walkId = 1L,
                startTimestamp = 5L,
                endTimestamp = 25L,
                activityType = ActivityType.WALKING,
            ),
        )
        val result = computeRouteSegments(samples, intervals, emptyList())
        assertEquals(1, result.size)
        assertEquals(RouteActivity.Walking, result[0].activity)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.walk.RouteSegmentsTest'`
Expected: FAIL — unresolved references.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/RouteSegments.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Activity classification for a continuous run of route samples on the
 * Walk Summary map. Each segment renders as one colored polyline.
 *
 * Priority on overlap (matches iOS `WalkSummaryView.activityType`,
 * `WalkSummaryView.swift:893-902`):
 *   Meditating > Talking > Walking
 *
 * I.e., a sample whose timestamp falls inside both a meditation interval
 * and a voice recording is classified as Meditating.
 */
enum class RouteActivity { Walking, Talking, Meditating }

@Immutable
data class RouteSegment(
    val activity: RouteActivity,
    val points: List<LocationPoint>,
)

/**
 * Walk the [samples] in timestamp order and group consecutive runs of
 * identical activity into [RouteSegment]s. Boundary points (the sample
 * where the activity changes) are duplicated across the two adjacent
 * segments so the rendered polylines connect seamlessly.
 *
 * Returns an empty list when fewer than 2 samples exist (single point
 * cannot draw a polyline; matches iOS `computeSegments` guard).
 *
 * Pure function — caller is responsible for ordering samples by
 * `timestamp` if needed (Room's `getForWalk` already does this via the
 * DAO's `ORDER BY timestamp` clause).
 */
fun computeRouteSegments(
    samples: List<RouteDataSample>,
    intervals: List<ActivityInterval>,
    recordings: List<VoiceRecording>,
): List<RouteSegment> {
    if (samples.size < 2) return emptyList()

    val meditationIntervals = intervals.filter { it.activityType == ActivityType.MEDITATING }
    val classified = samples.map { classify(it.timestamp, meditationIntervals, recordings) }

    val segments = mutableListOf<RouteSegment>()
    var currentActivity = classified[0]
    var currentIndices = mutableListOf(0)

    for (i in 1 until samples.size) {
        val activity = classified[i]
        if (activity == currentActivity) {
            currentIndices.add(i)
        } else {
            // Boundary point sits in BOTH segments so the rendered
            // polylines connect rather than leaving a visible gap at
            // the activity transition.
            currentIndices.add(i)
            segments.add(buildSegment(currentActivity, currentIndices, samples))
            currentActivity = activity
            currentIndices = mutableListOf(i)
        }
    }
    segments.add(buildSegment(currentActivity, currentIndices, samples))

    return segments
}

private fun classify(
    timestampMs: Long,
    meditationIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): RouteActivity = when {
    meditationIntervals.any { iv ->
        timestampMs in iv.startTimestamp..iv.endTimestamp
    } -> RouteActivity.Meditating
    voiceRecordings.any { rec ->
        timestampMs in rec.startTimestamp..rec.endTimestamp
    } -> RouteActivity.Talking
    else -> RouteActivity.Walking
}

private fun buildSegment(
    activity: RouteActivity,
    indices: List<Int>,
    samples: List<RouteDataSample>,
): RouteSegment = RouteSegment(
    activity = activity,
    points = indices.map { i ->
        LocationPoint(
            timestamp = samples[i].timestamp,
            latitude = samples[i].latitude,
            longitude = samples[i].longitude,
        )
    },
)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.walk.RouteSegmentsTest'`
Expected: 8 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/RouteSegments.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/RouteSegmentsTest.kt
git commit -m "feat(walk-summary): add RouteSegments classifier (Stage 13-B task 2)"
```

---

## Task 3: RevealAnimation primitives

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealAnimation.kt`

- [ ] **Step 1: Write file**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealAnimation.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Phase of the post-walk Walk Summary reveal sequence. Matches iOS
 * `WalkSummaryView.RevealPhase` (`WalkSummaryView.swift:46-48`).
 *
 *  - [Hidden]   — initial state on first composition; map alpha = 0,
 *                 below-map sections invisible, count-up sits at 0.
 *  - [Zoomed]   — camera is planted at the route's first GPS point at
 *                 zoom 16; held for ~800ms.
 *  - [Revealed] — camera animates over 2.5s to fit-bounds; below-map
 *                 sections fade in over 600ms; distance counts up 0 →
 *                 final over 2s with smooth-step easing.
 */
internal enum class RevealPhase { Hidden, Zoomed, Revealed }

/**
 * iOS uses `progress * progress * (3 - 2*progress)` for the count-up
 * fraction. Compose's stock easings don't expose this curve directly;
 * declared here so production + tests share one definition.
 */
internal val SmoothStepEasing = Easing { fraction ->
    fraction * fraction * (3f - 2f * fraction)
}

/** Time the camera holds at the zoomed-in plant before fanning out. */
internal const val ZOOM_HOLD_MS = 800L

/** Camera ease duration for Zoomed → Revealed transition. */
internal const val REVEAL_CAMERA_EASE_MS = 2_500L

/** Below-map sections fade-in duration on Revealed. */
internal const val REVEAL_FADE_MS = 600

/** Distance count-up animation duration. */
internal const val COUNT_UP_DURATION_MS = 2_000

/**
 * Theme-resolved colors for the route polyline segments. Tokens read at
 * the @Composable layer (LocalPilgrimColors), packaged here so
 * [PilgrimMap] doesn't need to depend on the theme module directly.
 */
@Immutable
data class RouteSegmentColors(
    val walking: Color,
    val talking: Color,
    val meditating: Color,
)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealAnimation.kt
git commit -m "feat(walk-summary): add RevealPhase + SmoothStepEasing + RouteSegmentColors (Stage 13-B task 3)"
```

---

## Task 4: VM — `routeSegments` field + hoisting

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`

- [ ] **Step 1: Locate existing hoisting block (lines ~624-638 from Stage 13-A)**

Find:
```kotlin
val voiceRecordings = repository.voiceRecordingsFor(walkId)
val altitudeSamples = repository.altitudeSamplesFor(walkId)
```

- [ ] **Step 2: Hoist `activityIntervals` alongside**

Add right after the existing hoisting:
```kotlin
val activityIntervals = repository.activityIntervalsFor(walkId)
```

Then ADD route-segments computation:
```kotlin
val routeSegments = computeRouteSegments(
    samples = samples,
    intervals = activityIntervals,
    recordings = voiceRecordings,
)
```

(`samples` is the local from earlier in `buildState` — line 540 — `repository.locationSamplesFor(walkId).map { LocationPoint(...) }`. Wait — that maps to `points: List<LocationPoint>`, NOT raw `samples: List<RouteDataSample>`. Use the raw `samples` variable from line 540 BEFORE the `.map` — verify; if the raw `samples` isn't kept around, store it in a separate local.)

Verify by reading the block. The current code at line 540 is:
```kotlin
val samples = repository.locationSamplesFor(walkId)
val events = repository.eventsFor(walkId)
val waypoints = repository.waypointsFor(walkId)

val points = samples.map { LocationPoint(...) }
```

So `samples: List<RouteDataSample>` IS held — pass it directly to `computeRouteSegments`.

- [ ] **Step 3: Remove `activityIntervals = repository.activityIntervalsFor(walkId)` from inside the etegami `runCatching` block**

Inside `val etegamiSpec = runCatching { ... }`, delete the line:
```kotlin
val activityIntervals = repository.activityIntervalsFor(walkId)
```

The hoisted `activityIntervals` from outside the runCatching is referenced by name in the `composeEtegamiSpec(...)` call within. No further change needed.

- [ ] **Step 4: Add `routeSegments` field to `WalkSummary` data class**

Find the `data class WalkSummary` declaration. Add a new field after `ascendMeters`:
```kotlin
val routeSegments: List<org.walktalkmeditate.pilgrim.data.walk.RouteSegment> = emptyList(),
```

(The qualified name keeps the `data` package import out of the data class signature; alternatively add `import org.walktalkmeditate.pilgrim.data.walk.RouteSegment` at the file top and shorten.)

- [ ] **Step 5: Pass `routeSegments` when constructing `WalkSummary`**

Find `return WalkSummaryUiState.Loaded(WalkSummary(...))`. Add `routeSegments = routeSegments,` after `ascendMeters = ascendMeters,`.

- [ ] **Step 6: Add imports**

```kotlin
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.data.walk.computeRouteSegments
```

(Drop the qualified name in step 4 if importing the type.)

- [ ] **Step 7: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Add VM tests**

Open `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt`. After the Stage 13-A tests, append:

```kotlin
@Test
fun routeSegments_classifiesWalkOnlyAsSingleSegment() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
    insertRouteSample(walkId, t = 5_000L, lat = 2.0, lng = 2.0)
    insertRouteSample(walkId, t = 10_000L, lat = 3.0, lng = 3.0)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(1, loaded.summary.routeSegments.size)
    assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[0].activity)
}

@Test
fun routeSegments_splitsAtMeditationBoundaries() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
    insertRouteSample(walkId, t = 20_000L, lat = 2.0, lng = 2.0)
    insertRouteSample(walkId, t = 40_000L, lat = 3.0, lng = 3.0)
    insertActivityInterval(
        walkId,
        startTimestamp = 15_000L,
        endTimestamp = 25_000L,
        type = ActivityType.MEDITATING,
    )

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(3, loaded.summary.routeSegments.size)
    assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[0].activity)
    assertEquals(RouteActivity.Meditating, loaded.summary.routeSegments[1].activity)
    assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[2].activity)
}

@Test
fun routeSegments_splitsAtVoiceRecordingBoundaries() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
    insertRouteSample(walkId, t = 20_000L, lat = 2.0, lng = 2.0)
    insertRouteSample(walkId, t = 40_000L, lat = 3.0, lng = 3.0)
    insertVoiceRecording(walkId, startOffset = 15_000L, durationMillis = 10_000L)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(3, loaded.summary.routeSegments.size)
    assertEquals(RouteActivity.Walking, loaded.summary.routeSegments[0].activity)
    assertEquals(RouteActivity.Talking, loaded.summary.routeSegments[1].activity)
}

@Test
fun routeSegments_meditationOverridesTalking() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertRouteSample(walkId, t = 10_000L, lat = 1.0, lng = 1.0)
    insertRouteSample(walkId, t = 20_000L, lat = 2.0, lng = 2.0)
    insertActivityInterval(
        walkId,
        startTimestamp = 5_000L,
        endTimestamp = 25_000L,
        type = ActivityType.MEDITATING,
    )
    insertVoiceRecording(walkId, startOffset = 10_000L, durationMillis = 10_000L)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(1, loaded.summary.routeSegments.size)
    assertEquals(RouteActivity.Meditating, loaded.summary.routeSegments[0].activity)
}
```

Add helpers if missing (match existing pattern from Stage 13-A):

```kotlin
private suspend fun insertRouteSample(walkId: Long, t: Long, lat: Double, lng: Double) {
    db.routeDataSampleDao().insert(
        RouteDataSample(walkId = walkId, timestamp = t, latitude = lat, longitude = lng, altitude = 0.0),
    )
}

private suspend fun insertActivityInterval(
    walkId: Long,
    startTimestamp: Long,
    endTimestamp: Long,
    type: ActivityType,
) {
    val walk = repository.getWalk(walkId)!!
    db.activityIntervalDao().insert(
        org.walktalkmeditate.pilgrim.data.entity.ActivityInterval(
            walkId = walkId,
            startTimestamp = walk.startTimestamp + startTimestamp,
            endTimestamp = walk.startTimestamp + endTimestamp,
            activityType = type,
        ),
    )
}
```

(Note: the meditation interval timestamps should be ABSOLUTE wall-clock to match `insertVoiceRecording`. Stage 13-A's `createFinishedWalk(durationMillis)` starts walks at `walk.startTimestamp = 0L`, so `start = 5_000L` works directly. Add helpers; if existing helpers conflict, prefer the existing ones.)

Add imports:
```kotlin
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
```

- [ ] **Step 9: Run VM tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModelTest'`
Expected: All passing including 4 new ones.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt
git commit -m "feat(walk-summary): VM exposes routeSegments + hoists activityIntervals (Stage 13-B task 4)"
```

---

## Task 5: PilgrimMap multi-polyline + reveal-camera path

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt`

- [ ] **Step 1: Add imports**

```kotlin
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_CAMERA_EASE_MS
```

- [ ] **Step 2: Extend signature**

Find:
```kotlin
@Composable
fun PilgrimMap(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
    initialCenter: LocationPoint? = null,
    bottomInsetDp: Dp = 0.dp,
    waypoints: List<...Waypoint> = emptyList(),
)
```

Append three new params:
```kotlin
    routeSegments: List<RouteSegment> = emptyList(),
    segmentColors: RouteSegmentColors? = null,
    revealPhase: RevealPhase? = null,
```

`revealPhase` defaults to null so existing callers (Active Walk, Walk Share, current Stage 13-A summary) are unaffected.

- [ ] **Step 3: Add multi-polyline state holders**

Near the existing `polyline` state:
```kotlin
var segmentPolylines by remember { mutableStateOf<List<PolylineAnnotation>>(emptyList()) }
```

- [ ] **Step 4: Replace single-polyline rendering branch when `routeSegments.isNotEmpty()`**

Inside `update = { view -> ... if (points.size >= 2) { ... } }`:

```kotlin
val manager = polylineManager ?: return@AndroidView
if (routeSegments.isNotEmpty() && segmentColors != null) {
    // Multi-segment path: re-create N polylines from the segment list.
    // Wholesale replace each composition pass — segment counts stay
    // small (typically <20 per walk) and rebuilding skips the diffing
    // bookkeeping that single-polyline mutation needs.
    if (segmentPolylines.isNotEmpty()) {
        segmentPolylines.forEach { manager.delete(it) }
    }
    segmentPolylines = routeSegments.map { seg ->
        val mapboxPoints = seg.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val color = when (seg.activity) {
            RouteActivity.Walking -> segmentColors.walking.toArgb()
            RouteActivity.Talking -> segmentColors.talking.toArgb()
            RouteActivity.Meditating -> segmentColors.meditating.toArgb()
        }
        manager.create(
            PolylineAnnotationOptions()
                .withPoints(mapboxPoints)
                .withLineColor(color)
                .withLineWidth(POLYLINE_WIDTH_DP),
        )
    }
} else if (points.size >= 2) {
    // Existing single-polyline path (Active Walk, Walk Share, fallback).
    val mapboxPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    // ... (existing code unchanged) ...
}
```

Add imports:
```kotlin
import androidx.compose.ui.graphics.toArgb
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
```

- [ ] **Step 5: Add reveal-driven camera control**

Right BEFORE the existing `LaunchedEffect(mapView, styleUri)`, add:

```kotlin
LaunchedEffect(mapView, revealPhase, points.firstOrNull()) {
    if (revealPhase == null) return@LaunchedEffect
    val view = mapView ?: return@LaunchedEffect
    when (revealPhase) {
        RevealPhase.Hidden -> { /* no camera change */ }
        RevealPhase.Zoomed -> {
            val first = points.firstOrNull() ?: return@LaunchedEffect
            view.mapboxMap.setCamera(
                CameraOptions.Builder()
                    .center(Point.fromLngLat(first.longitude, first.latitude))
                    .zoom(REVEAL_ZOOM)
                    .build(),
            )
        }
        RevealPhase.Revealed -> {
            if (points.size < 2) return@LaunchedEffect
            val mapboxPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val camera = view.mapboxMap.cameraForCoordinates(
                mapboxPoints,
                CameraOptions.Builder().build(),
                EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx),
                null,
                null,
            )
            val clampedZoom = camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM
            val target = camera.toBuilder().zoom(clampedZoom).build()
            view.mapboxMap.easeTo(
                target,
                MapAnimationOptions.Builder().duration(REVEAL_CAMERA_EASE_MS).build(),
            )
        }
    }
}
```

- [ ] **Step 6: Suppress fit-bounds when revealPhase is non-null**

In the existing `else if (!didFitBounds) { ... }` branch (the fit-bounds-once path), gate it:
```kotlin
} else if (!didFitBounds && revealPhase == null) {
    // existing fit-bounds code...
}
```

- [ ] **Step 7: Add constants near the existing ones**

```kotlin
private const val REVEAL_ZOOM = 16.0
```

- [ ] **Step 8: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt
git commit -m "feat(walk-summary): PilgrimMap multi-polyline + reveal-driven camera (Stage 13-B task 5)"
```

---

## Task 6: WalkSummaryScreen — reveal phase machine + map polish + count-up + fade-in

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`

- [ ] **Step 1: Add imports**

```kotlin
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.ui.walk.summary.COUNT_UP_DURATION_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_FADE_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.SmoothStepEasing
import org.walktalkmeditate.pilgrim.ui.walk.summary.ZOOM_HOLD_MS
```

- [ ] **Step 2: Add reveal phase state inside `WalkSummaryScreen`**

After the existing `val cachedShare by ...` line and BEFORE `LaunchedEffect(Unit) { viewModel.runStartupSweep() }`:

```kotlin
// Stage 13-B: reveal phase machine. Hidden → Zoomed → Revealed.
// Keyed on the loaded walkId so re-entering a different walk replays;
// re-entering the SAME walk via back-nav also replays (matches iOS).
val loadedWalkId = (state as? WalkSummaryUiState.Loaded)?.summary?.walk?.id
var revealPhase by remember(loadedWalkId) { mutableStateOf(RevealPhase.Hidden) }

// Reduce-motion check: when the system "Remove animations" pref is on
// (ANIMATOR_DURATION_SCALE = 0f), skip the reveal entirely. iOS reads
// `@Environment(\.accessibilityReduceMotion)`.
val context = LocalContext.current
val reduceMotion = remember {
    android.provider.Settings.Global.getFloat(
        context.contentResolver,
        android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
}

LaunchedEffect(loadedWalkId) {
    val s = state
    if (s !is WalkSummaryUiState.Loaded) return@LaunchedEffect
    if (s.summary.routePoints.isEmpty() || reduceMotion) {
        revealPhase = RevealPhase.Revealed
        return@LaunchedEffect
    }
    revealPhase = RevealPhase.Zoomed
    delay(ZOOM_HOLD_MS)
    revealPhase = RevealPhase.Revealed
}
```

`val context = LocalContext.current` may already exist further down; verify and dedupe.

- [ ] **Step 3: Compute animated distance**

Right after the LaunchedEffect above:

```kotlin
val targetDistance = (state as? WalkSummaryUiState.Loaded)?.summary?.distanceMeters?.toFloat() ?: 0f
val animatedDistanceMeters by animateFloatAsState(
    targetValue = if (revealPhase == RevealPhase.Revealed) targetDistance else 0f,
    animationSpec = tween(durationMillis = COUNT_UP_DURATION_MS, easing = SmoothStepEasing),
    label = "summary-distance-countup",
)
```

- [ ] **Step 4: Compute segment colors at composition site**

Where `pilgrimColors` is in scope (inside the Loaded branch, near the SummaryMap call):

```kotlin
val segmentColors = RouteSegmentColors(
    walking = pilgrimColors.moss,
    talking = pilgrimColors.rust,
    meditating = pilgrimColors.dawn,
)
```

- [ ] **Step 5: Replace the SummaryMap call inside Loaded branch**

Find:
```kotlin
SummaryMap(points = s.summary.routePoints)
```

Replace with:
```kotlin
SummaryMap(
    points = s.summary.routePoints,
    routeSegments = s.summary.routeSegments,
    revealPhase = revealPhase,
    segmentColors = segmentColors,
)
```

- [ ] **Step 6: Update the `SummaryMap` private composable**

Find the existing `private fun SummaryMap(points: List<LocationPoint>)` definition. Replace with:

```kotlin
@Composable
private fun SummaryMap(
    points: List<org.walktalkmeditate.pilgrim.domain.LocationPoint>,
    routeSegments: List<RouteSegment>,
    revealPhase: RevealPhase,
    segmentColors: RouteSegmentColors,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // iOS RadialGradient mask: opaque center → transparent edge.
                // 0.45 stop matches iOS's 80/180 startRadius/endRadius ratio.
                val brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.White,
                        0.45f to Color.White,
                        1f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = size.minDimension / 2f,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(brush = brush, blendMode = BlendMode.DstIn)
                }
            },
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        if (points.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.walk_map_no_route),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
        } else {
            PilgrimMap(
                points = points,
                routeSegments = routeSegments,
                segmentColors = segmentColors,
                revealPhase = revealPhase,
                followLatest = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

- [ ] **Step 7: Bump `SummaryMapPlaceholder` height**

Change `.height(200.dp)` → `.height(320.dp)`.

- [ ] **Step 8: Wrap below-map sections in AnimatedVisibility**

In the `is WalkSummaryUiState.Loaded -> { ... }` block, the contiguous block from
`WalkJourneyQuote` through `WalkTimeBreakdownGrid` (sections 5, 6, 8, 9, 11) needs
to be wrapped in a single `AnimatedVisibility`. Replace:

```kotlin
// 5. Journey quote
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkJourneyQuote(...)

// 6. Duration hero
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkDurationHero(durationMillis = s.summary.activeMillis)

// 7. Milestone callout — placeholder for Stage 13-F.
// ...

// 8. Stats row
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkStatsRow(
    distanceMeters = s.summary.distanceMeters,
    ascendMeters = s.summary.ascendMeters,
    units = distanceUnits,
)

// 9. Weather line
s.summary.walk.weatherCondition?.let { conditionRaw ->
    val condition = WeatherCondition.fromRawValue(conditionRaw) ?: return@let
    val temperature = s.summary.walk.weatherTemperature ?: return@let
    Spacer(Modifier.height(PilgrimSpacing.normal))
    WalkSummaryWeatherLine(...)
}

// 10. Celestial line — placeholder for Stage 13-F

// 11. Time breakdown grid
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkTimeBreakdownGrid(...)
```

With:

```kotlin
Spacer(Modifier.height(PilgrimSpacing.normal))
AnimatedVisibility(
    visible = revealPhase == RevealPhase.Revealed,
    enter = fadeIn(animationSpec = tween(durationMillis = REVEAL_FADE_MS)),
    exit = ExitTransition.None,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        // 5. Journey quote
        WalkJourneyQuote(
            talkMillis = s.summary.talkMillis,
            meditateMillis = s.summary.totalMeditatedMillis,
            distanceMeters = s.summary.distanceMeters,
            distanceUnits = distanceUnits,
        )

        // 6. Duration hero
        WalkDurationHero(durationMillis = s.summary.activeMillis)

        // 7. Milestone callout — placeholder for Stage 13-F.
        // The Stage 4-B SealRevealOverlay below already adds an
        // extra haptic + hold for milestone walks; the iOS textual
        // callout above the stats row is the new surface deferred
        // to 13-F.

        // 8. Stats row — distance animated with smooth-step count-up.
        WalkStatsRow(
            distanceMeters = animatedDistanceMeters.toDouble(),
            ascendMeters = s.summary.ascendMeters,
            units = distanceUnits,
        )

        // 9. Weather line (Stage 12-A, conditional).
        s.summary.walk.weatherCondition?.let { conditionRaw ->
            val condition = WeatherCondition.fromRawValue(conditionRaw) ?: return@let
            val temperature = s.summary.walk.weatherTemperature ?: return@let
            WalkSummaryWeatherLine(
                condition = condition,
                temperatureCelsius = temperature,
                imperial = distanceUnits == UnitSystem.Imperial,
            )
        }

        // 10. Celestial line — placeholder for Stage 13-F

        // 11. Time breakdown grid.
        WalkTimeBreakdownGrid(
            walkMillis = s.summary.activeWalkingMillis,
            talkMillis = s.summary.talkMillis,
            meditateMillis = s.summary.totalMeditatedMillis,
        )
    }
}
```

The Spacer BEFORE the AnimatedVisibility provides the gap from the section above (Reliquary or IntentionCard). The wrapped Column uses `Arrangement.spacedBy(PilgrimSpacing.normal)` for inter-section spacing. The original individual `Spacer` separators are subsumed.

- [ ] **Step 9: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Run unit tests**

Run: `./gradlew :app:testDebugUnitTest`
Expected: all passing.

- [ ] **Step 11: Run lint**

Run: `./gradlew :app:lintDebug`
Expected: BUILD SUCCESSFUL (no new findings).

- [ ] **Step 12: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt
git commit -m "feat(walk-summary): reveal sequence + circular mask + segment colors (Stage 13-B task 6)"
```

---

## Task 7: RevealPhase Robolectric tests

**Files:**
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealPhaseTest.kt`

- [ ] **Step 1: Write tests**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealPhaseTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Test

class RevealAnimationTest {

    @Test
    fun smoothStepEasing_atZero_returnsZero() {
        assertEquals(0f, SmoothStepEasing.transform(0f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atOne_returnsOne() {
        assertEquals(1f, SmoothStepEasing.transform(1f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atHalf_returnsHalf() {
        // smooth-step(0.5) = 0.5 * 0.5 * (3 - 2*0.5) = 0.25 * 2 = 0.5
        assertEquals(0.5f, SmoothStepEasing.transform(0.5f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atQuarter_returnsLessThanQuarter() {
        // smooth-step at 0.25: 0.25 * 0.25 * (3 - 0.5) = 0.0625 * 2.5 = 0.15625
        // (slower acceleration at the start = ease-in characteristic)
        assertEquals(0.15625f, SmoothStepEasing.transform(0.25f), 0.0001f)
    }

    @Test
    fun smoothStepEasing_atThreeQuarter_returnsMoreThanThreeQuarter() {
        // smooth-step at 0.75: 0.75 * 0.75 * (3 - 1.5) = 0.5625 * 1.5 = 0.84375
        assertEquals(0.84375f, SmoothStepEasing.transform(0.75f), 0.0001f)
    }

    @Test
    fun revealPhase_enumOrder() {
        val values = RevealPhase.values()
        assertEquals(RevealPhase.Hidden, values[0])
        assertEquals(RevealPhase.Zoomed, values[1])
        assertEquals(RevealPhase.Revealed, values[2])
    }
}
```

(Pure-function test — no Robolectric needed. The phase-machine integration test happens implicitly via the existing WalkSummaryViewModelTest's `awaitLoaded` polling.)

- [ ] **Step 2: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.RevealAnimationTest'`
Expected: 6 passing.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealPhaseTest.kt
git commit -m "test(walk-summary): SmoothStepEasing + RevealPhase enum coverage (Stage 13-B task 7)"
```

---

## Task 8: Final verification + push

**Files:** none

- [ ] **Step 1: Full build + lint + tests**

Run: `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. No new findings.

- [ ] **Step 2: Release build smoke test**

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Push branch**

```bash
git push -u origin feat/stage-13b-reveal-and-map
```

---

## Self-Review Notes

**Spec coverage check:**
- ✅ RouteSegments classifier (Task 2)
- ✅ RevealAnimation primitives (RevealPhase, SmoothStepEasing, RouteSegmentColors, constants) (Task 3)
- ✅ VM `routeSegments` field + activityIntervals hoisting (Task 4) + 4 VM tests
- ✅ PilgrimMap multi-polyline + reveal camera (Task 5)
- ✅ WalkSummaryScreen reveal phase + animated count-up + AnimatedVisibility wrap + 320dp height + radial mask + segment colors (Task 6)
- ✅ SmoothStepEasing tests (Task 7)
- ✅ Build + lint + test verification (Task 8)

**No placeholders.** Every task has explicit code blocks. Type names consistent across tasks (`RouteSegment`, `RouteActivity`, `RevealPhase`, `RouteSegmentColors`).

**Type consistency:** `RouteSegment.points: List<LocationPoint>` flows from VM to PilgrimMap; `LocationPoint` is the existing domain type used throughout the screen. `RouteActivity` enum used by both classifier and PilgrimMap rendering.

**Reduce-motion handling:** Single check via `Settings.Global.ANIMATOR_DURATION_SCALE` at composable composition. Short-circuits to Revealed phase; AnimatedVisibility's `fadeIn(tween(0))` would still play a frame but `tween(0)` is effectively instant. If the test suite later wants stricter reduce-motion isolation, the `reduceMotion` flag can also gate `animateFloatAsState`'s animationSpec to `snap()`.
