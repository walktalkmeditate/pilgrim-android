# Stage 13-D — Map annotations + segment-tap-zoom Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Pin start/end + meditation + voice annotations on Walk Summary map; wire timeline-bar segment taps to zoom the map camera.

**Spec:** `docs/superpowers/specs/2026-05-01-stage-13d-map-annotations-design.md`

---

## Task 1: Branch + commit spec/plan

- [ ] Verify clean tree on main; `git checkout -b feat/stage-13d-map-annotations`.
- [ ] Commit spec + plan: `docs(walk-summary): Stage 13-D spec + plan`.

---

## Task 2: WalkMapAnnotation classifier + tests

**Files:** `app/src/main/java/.../data/walk/MapAnnotations.kt`, `app/src/test/java/.../data/walk/MapAnnotationsTest.kt`

- [ ] **Step 1: Test first.**

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

class MapAnnotationsTest {
    private fun sample(t: Long, lat: Double = 0.0, lng: Double = 0.0) =
        RouteDataSample(walkId = 1L, timestamp = t, latitude = lat, longitude = lng,
            altitudeMeters = 0.0)

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L, startTimestamp = start, endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    private fun recording(start: Long, dur: Long) = VoiceRecording(
        walkId = 1L, startTimestamp = start, endTimestamp = start + dur,
        durationMillis = dur, fileRelativePath = "x.wav", transcription = null,
    )

    @Test fun emptySamples_returnsEmpty() {
        assertTrue(computeWalkMapAnnotations(emptyList(), emptyList(), emptyList()).isEmpty())
    }

    @Test fun singleSample_yieldsStartOnly() {
        val result = computeWalkMapAnnotations(
            routeSamples = listOf(sample(100L, 1.0, 1.0)),
            meditationIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals(1, result.size)
        assertEquals(WalkMapAnnotationKind.StartPoint, result[0].kind)
    }

    @Test fun multipleSamples_yieldsStartAndEnd() {
        val result = computeWalkMapAnnotations(
            routeSamples = listOf(sample(100L, 1.0, 1.0), sample(200L, 2.0, 2.0)),
            meditationIntervals = emptyList(),
            voiceRecordings = emptyList(),
        )
        assertEquals(2, result.size)
        assertEquals(WalkMapAnnotationKind.StartPoint, result[0].kind)
        assertEquals(1.0, result[0].latitude, 0.0)
        assertEquals(WalkMapAnnotationKind.EndPoint, result[1].kind)
        assertEquals(2.0, result[1].latitude, 0.0)
    }

    @Test fun meditation_pinAtClosestSampleToStart() {
        val samples = listOf(
            sample(100L, 1.0, 1.0),
            sample(500L, 2.0, 2.0),
            sample(900L, 3.0, 3.0),
        )
        val result = computeWalkMapAnnotations(
            routeSamples = samples,
            meditationIntervals = listOf(meditation(start = 480L, end = 700L)),
            voiceRecordings = emptyList(),
        )
        // start + end + 1 meditation
        assertEquals(3, result.size)
        val medAnn = result.first { it.kind is WalkMapAnnotationKind.Meditation }
        // Closest to t=480 is sample at t=500 (lat=2.0)
        assertEquals(2.0, medAnn.latitude, 0.0001)
        assertEquals(220L, (medAnn.kind as WalkMapAnnotationKind.Meditation).durationMillis)
    }

    @Test fun voiceRecording_pinAtClosestSampleToStart() {
        val samples = listOf(
            sample(100L, 1.0, 1.0),
            sample(500L, 2.0, 2.0),
            sample(900L, 3.0, 3.0),
        )
        val result = computeWalkMapAnnotations(
            routeSamples = samples,
            meditationIntervals = emptyList(),
            voiceRecordings = listOf(recording(start = 850L, dur = 50L)),
        )
        assertEquals(3, result.size)
        val voiceAnn = result.first { it.kind is WalkMapAnnotationKind.VoiceRecording }
        // Closest to t=850 is sample at t=900 (lat=3.0)
        assertEquals(3.0, voiceAnn.latitude, 0.0001)
        assertEquals(50L, (voiceAnn.kind as WalkMapAnnotationKind.VoiceRecording).durationMillis)
    }
}
```

- [ ] **Step 2: Run test → expect FAIL.**

- [ ] **Step 3: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

/**
 * Pin marker on the post-walk map. iOS-faithful port of
 * `PilgrimAnnotation.Kind` (subset — photo / whisper / cairn pins
 * deferred to later stages).
 */
sealed class WalkMapAnnotationKind {
    data object StartPoint : WalkMapAnnotationKind()
    data object EndPoint : WalkMapAnnotationKind()
    data class Meditation(val durationMillis: Long) : WalkMapAnnotationKind()
    data class VoiceRecording(val durationMillis: Long) : WalkMapAnnotationKind()
}

@Immutable
data class WalkMapAnnotation(
    val kind: WalkMapAnnotationKind,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Build the Walk Summary map's pin set. Verbatim port of iOS
 * `WalkSummaryView.computeAnnotations` (`WalkSummaryView.swift:863-891`):
 *   - Start pin at first GPS sample.
 *   - End pin at last GPS sample (only when route has > 1 sample).
 *   - Meditation pin at the GPS sample closest in time to each
 *     meditation interval's start.
 *   - Voice recording pin at the GPS sample closest in time to each
 *     recording's start.
 *
 * Returns empty when the route is empty (cannot place start/end without
 * GPS). Pure function — caller is responsible for ordering samples by
 * timestamp (Room's DAO already does).
 */
fun computeWalkMapAnnotations(
    routeSamples: List<RouteDataSample>,
    meditationIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): List<WalkMapAnnotation> {
    if (routeSamples.isEmpty()) return emptyList()
    val out = mutableListOf<WalkMapAnnotation>()

    val first = routeSamples.first()
    out += WalkMapAnnotation(WalkMapAnnotationKind.StartPoint, first.latitude, first.longitude)

    if (routeSamples.size > 1) {
        val last = routeSamples.last()
        out += WalkMapAnnotation(WalkMapAnnotationKind.EndPoint, last.latitude, last.longitude)
    }

    for (m in meditationIntervals) {
        if (m.activityType != ActivityType.MEDITATING) continue
        val closest = routeSamples.minByOrNull { abs(it.timestamp - m.startTimestamp) }
            ?: continue
        out += WalkMapAnnotation(
            kind = WalkMapAnnotationKind.Meditation(m.endTimestamp - m.startTimestamp),
            latitude = closest.latitude,
            longitude = closest.longitude,
        )
    }

    for (r in voiceRecordings) {
        val closest = routeSamples.minByOrNull { abs(it.timestamp - r.startTimestamp) }
            ?: continue
        out += WalkMapAnnotation(
            kind = WalkMapAnnotationKind.VoiceRecording(r.durationMillis),
            latitude = closest.latitude,
            longitude = closest.longitude,
        )
    }

    return out
}
```

- [ ] **Step 4: Re-run → 5 passing.**
- [ ] **Step 5:** Commit `feat(walk-summary): WalkMapAnnotations classifier (Stage 13-D task 2)`.

---

## Task 3: MapCameraBounds + computeBoundsForTimeRange

**Files:** `app/src/main/java/.../ui/walk/summary/MapCameraBounds.kt`, `app/src/test/java/.../ui/walk/summary/MapCameraBoundsTest.kt`

- [ ] **Step 1: Test.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

class MapCameraBoundsTest {
    private fun sample(t: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = 1L, timestamp = t, latitude = lat, longitude = lng, altitudeMeters = 0.0,
    )

    @Test fun emptySamples_returnsNull() {
        assertNull(computeBoundsForTimeRange(emptyList(), 0L, 100L))
    }

    @Test fun samplesOutsideTimeRange_returnsNull() {
        val samples = listOf(sample(50L, 1.0, 1.0), sample(150L, 2.0, 2.0))
        assertNull(computeBoundsForTimeRange(samples, 200L, 300L))
    }

    @Test fun singleSampleInRange_yieldsZeroSpanWithMinPadding() {
        val samples = listOf(sample(100L, 1.0, 2.0))
        val bounds = computeBoundsForTimeRange(samples, 0L, 200L)
        assertNotNull(bounds)
        // 0 span * 0.15 + 0.001 = 0.001 padding either side
        assertEquals(0.999, bounds!!.swLat, 0.0001)
        assertEquals(1.001, bounds.neLat, 0.0001)
        assertEquals(1.999, bounds.swLng, 0.0001)
        assertEquals(2.001, bounds.neLng, 0.0001)
    }

    @Test fun multiSampleInRange_yieldsBoundsWithFifteenPercentPadding() {
        val samples = listOf(
            sample(100L, 1.0, 1.0),
            sample(200L, 3.0, 5.0),
        )
        val bounds = computeBoundsForTimeRange(samples, 50L, 250L)
        assertNotNull(bounds)
        // span lat = 2.0, pad = 2.0 * 0.15 + 0.001 = 0.301
        // span lng = 4.0, pad = 4.0 * 0.15 + 0.001 = 0.601
        assertEquals(0.699, bounds!!.swLat, 0.0001)
        assertEquals(3.301, bounds.neLat, 0.0001)
        assertEquals(0.399, bounds.swLng, 0.0001)
        assertEquals(5.601, bounds.neLng, 0.0001)
    }
}
```

- [ ] **Step 2: Run → FAIL.**

- [ ] **Step 3: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

/**
 * Geographic bounds for a Mapbox camera fit. Verbatim port of iOS
 * `MapCameraBounds` (`PilgrimAnnotation.swift:21-31`).
 */
@Immutable
data class MapCameraBounds(
    val swLat: Double,
    val swLng: Double,
    val neLat: Double,
    val neLng: Double,
)

/**
 * Compute camera bounds covering all GPS samples whose timestamp falls
 * inside `[startMs, endMs]`. Returns null when no samples land in the
 * range — caller falls back to the full-route fit-bounds. iOS-faithful
 * port of `boundsForTimeRange` + `boundsForRoute`
 * (`WalkSummaryView.swift:911-931`):
 *   - 15% padding on each axis
 *   - +0.001 floor so a degenerate single-point range still produces a
 *     visible span (otherwise the camera fits to a zero-area rectangle
 *     and Mapbox returns the global default zoom).
 */
fun computeBoundsForTimeRange(
    samples: List<RouteDataSample>,
    startMs: Long,
    endMs: Long,
): MapCameraBounds? {
    val inRange = samples.filter { it.timestamp in startMs..endMs }
    if (inRange.isEmpty()) return null
    val lats = inRange.map { it.latitude }
    val lngs = inRange.map { it.longitude }
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLng = lngs.min()
    val maxLng = lngs.max()
    val latPad = (maxLat - minLat) * 0.15 + 0.001
    val lngPad = (maxLng - minLng) * 0.15 + 0.001
    return MapCameraBounds(
        swLat = minLat - latPad,
        swLng = minLng - lngPad,
        neLat = maxLat + latPad,
        neLng = maxLng + lngPad,
    )
}
```

- [ ] **Step 4: Re-run → 4 passing.**
- [ ] **Step 5:** Commit `feat(walk-summary): MapCameraBounds + computeBoundsForTimeRange (Stage 13-D task 3)`.

---

## Task 4: VM additions + RevealAnimation `WalkAnnotationColors` + 1 VM test

**Files:**
- Modify: `app/src/main/java/.../ui/walk/summary/RevealAnimation.kt` — add `WalkAnnotationColors` data class
- Modify: `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` — add `walkAnnotations` field on `WalkSummary`; populate via `withContext(Dispatchers.Default)`
- Modify: `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` — add 1 test

- [ ] **Step 1:** Add `WalkAnnotationColors` to `RevealAnimation.kt`:

```kotlin
@Immutable
data class WalkAnnotationColors(
    val startEnd: Color,
    val meditation: Color,
    val voice: Color,
)
```

(Include alongside the existing `RouteSegmentColors` data class.)

- [ ] **Step 2:** Add `walkAnnotations` field to `WalkSummary` immediately after `routeSegments`:

```kotlin
val walkAnnotations: List<org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation> = emptyList(),
```

(or import the type and shorten.)

- [ ] **Step 3:** In `buildState`, add the computation (within `withContext(Dispatchers.Default)` adjacent to `routeSegments`):

```kotlin
val (routeSegments, walkAnnotations) = withContext(Dispatchers.Default) {
    val seg = computeRouteSegments(samples, activityIntervals, voiceRecordings)
    val ann = computeWalkMapAnnotations(samples, activityIntervals, voiceRecordings)
    seg to ann
}
```

(Replaces the existing `val routeSegments = withContext(Dispatchers.Default) { computeRouteSegments(...) }` block. Pair return keeps both computations on Default in a single hop.)

Add import:
```kotlin
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.walk.computeWalkMapAnnotations
```

- [ ] **Step 4:** Pass `walkAnnotations` when constructing `WalkSummary`:
```kotlin
walkAnnotations = walkAnnotations,
```

- [ ] **Step 5:** Compile.

- [ ] **Step 6: VM test.**

```kotlin
@Test
fun walkAnnotations_populated_includesStartEndMeditationVoice() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertRouteSample(walkId, t = 1_000L, lat = 1.0, lng = 1.0)
    insertRouteSample(walkId, t = 30_000L, lat = 2.0, lng = 2.0)
    insertRouteSample(walkId, t = 60_000L, lat = 3.0, lng = 3.0)
    insertActivityInterval(walkId, startTimestamp = 28_000L, endTimestamp = 32_000L,
        type = ActivityType.MEDITATING)
    insertVoiceRecording(walkId, startOffset = 55_000L, durationMillis = 5_000L)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    val annotations = loaded.summary.walkAnnotations
    assertEquals(4, annotations.size) // start + end + meditation + voice
    assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.StartPoint })
    assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.EndPoint })
    assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.Meditation })
    assertTrue(annotations.any { it.kind is WalkMapAnnotationKind.VoiceRecording })
}
```

Add imports:
```kotlin
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
```

- [ ] **Step 7:** Run tests. Note: helper `insertRouteSample` and `insertActivityInterval` already exist from prior stages.
- [ ] **Step 8:** Commit `feat(walk-summary): VM exposes walkAnnotations + WalkAnnotationColors (Stage 13-D task 4)`.

---

## Task 5: PilgrimMap — annotation rendering + zoomTargetBounds reveal-camera

**Files:** Modify `app/src/main/java/.../ui/walk/PilgrimMap.kt`

- [ ] **Step 1: Imports.**

```kotlin
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkAnnotationColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.SEGMENT_ZOOM_EASE_MS
```

- [ ] **Step 2: Add 3 new params to `PilgrimMap` signature** (defaults so existing callers unaffected):

```kotlin
walkAnnotations: List<WalkMapAnnotation> = emptyList(),
walkAnnotationColors: WalkAnnotationColors? = null,
zoomTargetBounds: MapCameraBounds? = null,
```

- [ ] **Step 3: Add state holders + cache near existing `waypointManager`:**

```kotlin
var annotationManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
var renderedWalkAnnotations by remember {
    mutableStateOf<List<PointAnnotation>>(emptyList())
}
var renderedWalkAnnotationsKey by remember {
    mutableStateOf<Pair<List<WalkMapAnnotation>, WalkAnnotationColors?>?>(null)
}
val annotationBitmaps = remember(walkAnnotationColors, darkMode) {
    walkAnnotationColors?.let { colors ->
        mapOf(
            "startEnd" to createCircleBitmap(colors.startEnd, darkMode),
            "meditation" to createCircleBitmap(colors.meditation, darkMode),
            "voice" to createCircleBitmap(colors.voice, darkMode),
        )
    }
}
```

(Wait — `WalkMapAnnotationKind` has 4 cases not 3. Use 4 bitmaps keyed by kind class. Actually a single circle per color works — Start + End share `startEnd` color. Use 3 bitmaps as written.)

- [ ] **Step 4:** In the existing `LaunchedEffect(mapView, styleUri)` block, after `waypointManager = view.annotations.createPointAnnotationManager()`, add:

```kotlin
annotationManager = view.annotations.createPointAnnotationManager()
```

And in the same block's teardown (top of effect):
```kotlin
annotationManager?.let { view.annotations.removeAnnotationManager(it) }
annotationManager = null
renderedWalkAnnotations = emptyList()
renderedWalkAnnotationsKey = null
```

Also in `onRelease` teardown:
```kotlin
annotationManager = null
renderedWalkAnnotations = emptyList()
renderedWalkAnnotationsKey = null
```

- [ ] **Step 5:** In the `update` lambda, after the waypoint sync block, add annotation render block:

```kotlin
val annoMgr = annotationManager
val bitmaps = annotationBitmaps
if (annoMgr != null && walkAnnotations.isNotEmpty() && bitmaps != null) {
    val key = walkAnnotations to walkAnnotationColors
    if (renderedWalkAnnotationsKey != key) {
        if (renderedWalkAnnotations.isNotEmpty()) {
            renderedWalkAnnotations.forEach { annoMgr.delete(it) }
        }
        renderedWalkAnnotations = walkAnnotations.map { ann ->
            val bitmap = when (ann.kind) {
                WalkMapAnnotationKind.StartPoint, WalkMapAnnotationKind.EndPoint ->
                    bitmaps.getValue("startEnd")
                is WalkMapAnnotationKind.Meditation -> bitmaps.getValue("meditation")
                is WalkMapAnnotationKind.VoiceRecording -> bitmaps.getValue("voice")
            }
            annoMgr.create(
                PointAnnotationOptions()
                    .withPoint(Point.fromLngLat(ann.longitude, ann.latitude))
                    .withIconImage(bitmap),
            )
        }
        renderedWalkAnnotationsKey = key
    }
}
```

- [ ] **Step 6:** Update reveal-camera `LaunchedEffect` keys + Revealed branch to honor `zoomTargetBounds`:

```kotlin
LaunchedEffect(mapView, revealPhase, points.firstOrNull(), reduceMotion, zoomTargetBounds) {
    if (revealPhase == null) return@LaunchedEffect
    val view = mapView ?: return@LaunchedEffect
    when (revealPhase) {
        RevealPhase.Hidden -> { /* no-op */ }
        RevealPhase.Zoomed -> { /* unchanged */ }
        RevealPhase.Revealed -> {
            val target = if (zoomTargetBounds != null) {
                cameraOptionsForBounds(view, zoomTargetBounds, paddingPx)
            } else {
                if (points.size < 2) return@LaunchedEffect
                cameraOptionsForFitBounds(view, points, paddingPx)
            }
            val duration = if (zoomTargetBounds != null) SEGMENT_ZOOM_EASE_MS else REVEAL_CAMERA_EASE_MS
            if (reduceMotion) {
                view.mapboxMap.setCamera(target)
            } else {
                view.mapboxMap.easeTo(
                    target,
                    MapAnimationOptions.Builder().duration(duration).build(),
                )
            }
        }
    }
}
```

Add helper functions at the top of the file or near the bottom:

```kotlin
private fun cameraOptionsForBounds(
    view: MapView,
    bounds: MapCameraBounds,
    paddingPx: Double,
): CameraOptions {
    val sw = Point.fromLngLat(bounds.swLng, bounds.swLat)
    val ne = Point.fromLngLat(bounds.neLng, bounds.neLat)
    val camera = view.mapboxMap.cameraForCoordinates(
        listOf(sw, ne),
        CameraOptions.Builder().build(),
        EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx),
        null, null,
    )
    val clamped = camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM
    return camera.toBuilder().zoom(clamped).build()
}

private fun cameraOptionsForFitBounds(
    view: MapView,
    points: List<LocationPoint>,
    paddingPx: Double,
): CameraOptions {
    val mapboxPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    val camera = view.mapboxMap.cameraForCoordinates(
        mapboxPoints,
        CameraOptions.Builder().build(),
        EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx),
        null, null,
    )
    val clamped = camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM
    return camera.toBuilder().zoom(clamped).build()
}
```

- [ ] **Step 7:** Add `createCircleBitmap` helper near existing `createWaypointBitmap`:

```kotlin
private fun createCircleBitmap(color: Color, darkMode: Boolean): Bitmap {
    val size = WAYPOINT_BITMAP_SIZE_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val strokeWidth = size * 0.08f
    val parchment = if (darkMode) 0xFF1A1814.toInt() else 0xFFF5F0E6.toInt()
    val fill = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        style = Paint.Style.FILL
    }
    val stroke = Paint().apply {
        isAntiAlias = true
        this.color = parchment
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val radius = (size / 2f) - strokeWidth
    canvas.drawCircle(cx, cy, radius, fill)
    canvas.drawCircle(cx, cy, radius, stroke)
    return bitmap
}
```

(Imports: `android.graphics.Bitmap`, `android.graphics.Canvas`, `android.graphics.Paint`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.graphics.toArgb`. Some already present.)

- [ ] **Step 8:** Add `SEGMENT_ZOOM_EASE_MS` constant to `RevealAnimation.kt`:

```kotlin
internal const val SEGMENT_ZOOM_EASE_MS = 350L
```

- [ ] **Step 9:** Compile + lint + tests.
- [ ] **Step 10:** Commit `feat(walk-summary): PilgrimMap renders WalkMapAnnotations + zoomTargetBounds (Stage 13-D task 5)`.

---

## Task 6: WalkActivityTimelineCard — onSegmentSelected/Deselected callbacks

**Files:** Modify `app/src/main/java/.../ui/walk/summary/WalkActivityTimelineCard.kt`

- [ ] **Step 1:** Add 2 optional callback params to `WalkActivityTimelineCard`:

```kotlin
onSegmentSelected: ((startMs: Long, endMs: Long) -> Unit)? = null,
onSegmentDeselected: (() -> Unit)? = null,
```

- [ ] **Step 2:** Use `rememberUpdatedState` to capture current callbacks (defensive — same pattern as existing `currentOnTap`):

```kotlin
val currentOnSelected by rememberUpdatedState(onSegmentSelected)
val currentOnDeselected by rememberUpdatedState(onSegmentDeselected)
```

- [ ] **Step 3:** In the existing inline tap-handler `onSegmentTapped = { id -> ... }`, fire the new callbacks:

```kotlin
onSegmentTapped = { id ->
    val newSelectedId = if (selectedId == id) null else id
    selectedId = newSelectedId
    if (newSelectedId == null) {
        currentOnDeselected?.invoke()
    } else {
        segments.firstOrNull { it.id == newSelectedId }?.let { seg ->
            currentOnSelected?.invoke(seg.startMillis, seg.endMillis)
        }
    }
},
```

- [ ] **Step 4:** Compile.
- [ ] **Step 5:** Commit `feat(walk-summary): TimelineCard onSegmentSelected/Deselected callbacks (Stage 13-D task 6)`.

---

## Task 7: WalkSummaryScreen — wire annotations + zoom-target

**Files:** Modify `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt`

- [ ] **Step 1:** Imports.

```kotlin
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkAnnotationColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.computeBoundsForTimeRange
```

- [ ] **Step 2:** Inside the `Loaded` branch, near where `segmentColors` is constructed, also build:

```kotlin
val walkAnnotationColors = WalkAnnotationColors(
    startEnd = pilgrimColors.stone,
    meditation = pilgrimColors.dawn,
    voice = pilgrimColors.rust,
)
```

And track zoom target state (re-key on walkId so back-nav resets):

```kotlin
var zoomTargetBounds by remember(loadedWalkId) {
    mutableStateOf<MapCameraBounds?>(null)
}
```

(Place near where `revealPhase` is declared.)

- [ ] **Step 3:** Update `SummaryMap` call to pass new params:

```kotlin
SummaryMap(
    points = s.summary.routePoints,
    routeSegments = s.summary.routeSegments,
    revealPhase = revealPhase,
    segmentColors = segmentColors,
    reduceMotion = reduceMotion,
    walkAnnotations = s.summary.walkAnnotations,
    walkAnnotationColors = walkAnnotationColors,
    zoomTargetBounds = zoomTargetBounds,
)
```

- [ ] **Step 4:** Update the private `SummaryMap` composable signature + forward to `PilgrimMap`:

```kotlin
@Composable
private fun SummaryMap(
    points: List<...>,
    routeSegments: List<RouteSegment>,
    revealPhase: RevealPhase,
    segmentColors: RouteSegmentColors,
    reduceMotion: Boolean,
    walkAnnotations: List<WalkMapAnnotation>,
    walkAnnotationColors: WalkAnnotationColors,
    zoomTargetBounds: MapCameraBounds?,
) { ... }
```

Forward all new params to `PilgrimMap`.

- [ ] **Step 5:** Update `WalkActivityTimelineCard` call to pass callbacks:

```kotlin
WalkActivityTimelineCard(
    // existing args…
    onSegmentSelected = { startMs, endMs ->
        zoomTargetBounds = computeBoundsForTimeRange(
            samples = s.summary.routeSamples,
            startMs = startMs,
            endMs = endMs,
        )
    },
    onSegmentDeselected = { zoomTargetBounds = null },
)
```

- [ ] **Step 6:** Compile + lint + tests.
- [ ] **Step 7:** Commit `feat(walk-summary): wire annotations + segment-tap-zoom (Stage 13-D task 7)`.

---

## Task 8: Final verification + push

- [ ] `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` BUILD SUCCESSFUL.
- [ ] `./gradlew :app:assembleRelease` BUILD SUCCESSFUL.
- [ ] `git push -u origin feat/stage-13d-map-annotations`.

---

## Self-Review

**Coverage:**
- ✅ `WalkMapAnnotation` + `computeWalkMapAnnotations` (Task 2 + 5 tests)
- ✅ `MapCameraBounds` + `computeBoundsForTimeRange` (Task 3 + 4 tests)
- ✅ VM `walkAnnotations` field + `WalkAnnotationColors` data class (Task 4 + 1 VM test)
- ✅ `PilgrimMap` annotation rendering + zoomTargetBounds reveal-camera + bitmaps (Task 5)
- ✅ TimelineCard onSegmentSelected/Deselected callbacks (Task 6)
- ✅ Screen wiring (Task 7)
- ✅ Verification + push (Task 8)

**No placeholders.** Type names consistent. Caller-side guards, snapshot-rebuild gates, `rememberUpdatedState` defensive patterns all match Stage 13-B/C precedents.
