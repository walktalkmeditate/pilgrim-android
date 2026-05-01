# Stage 13-D — Map annotations + segment-tap-to-zoom

## Context

Stage 13-A landed the Walk Summary skeleton. Stage 13-B added the reveal sequence
+ map polish (circular mask, segment-tinted polyline, 320dp height). Stage 13-C
landed activity views with a placeholder for "Segment-tap-to-zoom-map" deferred
to 13-D. Now 13-D pins activity markers on the map AND wires the timeline-bar
segment taps to the map camera.

iOS reference:
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:863-891` (computeAnnotations)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift:11-87` (mapSection + handleAnnotationTap + combinedAnnotations)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityTimelineBar.swift:11-13` (onSegmentTapped/onSegmentDeselected callbacks)
- `pilgrim-ios/Pilgrim/Models/Walk/MapManagement/PilgrimAnnotation.swift` (Kind enum)

## Goal

Walk Summary map shows pins at the start, end, meditation centroids, and voice-
recording centroids — visually tinted moss/dawn/rust to match the polyline
segment colors. Tapping a timeline-bar talk or meditation segment zooms the
map camera to that segment's GPS bounds; tapping again deselects and returns
to the full-route fit-bounds.

## Non-goals (deferred)

- **Photo pins** (iOS `combinedAnnotations` adds photo locations from EXIF).
  Android's `WalkPhoto` entity has no `captured_lat`/`captured_lng` columns;
  adding them requires a schema migration + EXIF read at pin time. Defer to a
  later stage (13-D-2 or rolled into 13-X).
- **Whisper / Cairn pins** (iOS `whisper(...)` / `cairn(...)` kinds). Phase N
  features not yet ported.
- **Annotation tap callbacks** (iOS `handleAnnotationTap`). Stage 13-D ships
  pins as visual markers only — no tap behavior on pins themselves. Photo-pin
  tap routes to the carousel on iOS; defer with photo-pin support.
- **Per-pin label tooltips** (iOS shows the meditation duration / voice "Recording" label). Visual marker only for 13-D; tooltip-on-tap deferred.

## Architecture

### Annotation classifier (pure helper)

New file `data/walk/MapAnnotations.kt`:

```kotlin
@Immutable
data class WalkMapAnnotation(
    val kind: WalkMapAnnotationKind,
    val latitude: Double,
    val longitude: Double,
)

sealed class WalkMapAnnotationKind {
    data object StartPoint : WalkMapAnnotationKind()
    data object EndPoint : WalkMapAnnotationKind()
    data class Meditation(val durationMillis: Long) : WalkMapAnnotationKind()
    data class VoiceRecording(val durationMillis: Long) : WalkMapAnnotationKind()
}

fun computeWalkMapAnnotations(
    routeSamples: List<RouteDataSample>,
    meditationIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): List<WalkMapAnnotation>
```

Algorithm (verbatim port of iOS `computeAnnotations`):
1. If `routeSamples.isEmpty()` return empty (cannot place start/end without GPS).
2. Add `StartPoint` at `samples.first()`.
3. Add `EndPoint` at `samples.last()` if `samples.size > 1`.
4. For each meditation interval: find sample with `min |sample.timestamp - interval.startTimestamp|`. Add `Meditation(durationMillis)` annotation at that coordinate.
5. For each voice recording: find sample with `min |sample.timestamp - recording.startTimestamp|`. Add `VoiceRecording(durationMillis)` annotation at that coordinate.

Pure function. Tests: 5 cases (empty / single-sample / start-end / meditation-pin-at-closest / voice-pin-at-closest).

### Annotation rendering (PilgrimMap extension)

`PilgrimMap` signature gains:

```kotlin
walkAnnotations: List<WalkMapAnnotation> = emptyList(),
walkAnnotationColors: WalkAnnotationColors? = null,
```

`WalkAnnotationColors` data class (`@Immutable`) lives in
`ui/walk/summary/RevealAnimation.kt` next to `RouteSegmentColors`:

```kotlin
@Immutable
data class WalkAnnotationColors(
    val startEnd: Color,    // pilgrimColors.stone — neutral terminus color
    val meditation: Color,  // pilgrimColors.dawn
    val voice: Color,       // pilgrimColors.rust
)
```

Inside the existing `update` lambda, after the waypoint sync block, add:

```kotlin
val annoMgr = annotationManager  // separate PointAnnotationManager
if (annoMgr != null && walkAnnotations.isNotEmpty() && walkAnnotationColors != null) {
    if (annotationsRendered.isNotEmpty()) {
        annotationsRendered.forEach { annoMgr.delete(it) }
    }
    annotationsRendered = walkAnnotations.map { ann ->
        val bitmap = bitmapFor(ann.kind, walkAnnotationColors, density, isDark)
        annoMgr.create(
            PointAnnotationOptions()
                .withPoint(Point.fromLngLat(ann.longitude, ann.latitude))
                .withIconImage(bitmap),
        )
    }
}
```

Reuses the existing waypoint-pin pattern. `bitmapFor` is a new private helper
in `PilgrimMap.kt`:

```kotlin
private fun bitmapFor(
    kind: WalkMapAnnotationKind,
    colors: WalkAnnotationColors,
    density: Density,
    darkMode: Boolean,
): Bitmap
```

Renders a 56px circle (matching `WAYPOINT_BITMAP_SIZE_PX`) tinted by kind:
- StartPoint / EndPoint → `colors.startEnd` filled, white inset glyph (small triangle for start, square for end — simple shapes, no SF Symbol port needed)
- Meditation → `colors.meditation` filled, white inset circle
- VoiceRecording → `colors.voice` filled, white inset waveform-bars (3 vertical bars: short/tall/short)

Cache the bitmaps via `remember(darkMode, colors)` since they're reused across
all annotations of the same kind. Same memoization pattern as
`createWaypointBitmap`.

### Snapshot-rebuild gate (annotations)

Mirror the Stage 13-B `renderedSegments` gate so phase-transition recomposes
don't tear-and-recreate every annotation:

```kotlin
var renderedAnnotations by remember { mutableStateOf<List<WalkMapAnnotation>?>(null) }
// rebuild only when walkAnnotations OR colors change
val annotationsNeedRebuild =
    renderedAnnotations != walkAnnotations || renderedAnnotationColors != walkAnnotationColors
```

Reset to `null` on style reload + onRelease (same teardown pattern as
`renderedSegments`).

### Segment-tap-to-zoom map

Stage 13-C `WalkActivityTimelineCard` exposes internal `selectedId` state. Add
optional callbacks:

```kotlin
@Composable
fun WalkActivityTimelineCard(
    // existing params…
    onSegmentSelected: ((startMs: Long, endMs: Long) -> Unit)? = null,
    onSegmentDeselected: (() -> Unit)? = null,
)
```

The internal tap handler at line 147 fires `onSegmentSelected(seg.startMillis,
seg.endMillis)` when selecting AND `onSegmentDeselected()` when toggling off.

`WalkSummaryScreen` tracks the selected interval at the screen level:

```kotlin
var zoomTargetBounds by remember(loadedWalkId) {
    mutableStateOf<MapCameraBounds?>(null)
}
```

`MapCameraBounds` data class (`@Immutable`) in `ui/walk/summary/RevealAnimation.kt`:

```kotlin
@Immutable
data class MapCameraBounds(
    val swLat: Double, val swLng: Double,
    val neLat: Double, val neLng: Double,
)

fun computeBoundsForTimeRange(
    samples: List<RouteDataSample>,
    startMs: Long,
    endMs: Long,
): MapCameraBounds?
```

Algorithm: filter samples in `[startMs, endMs]`, return null if empty, else
compute min/max lat/lng with ~15% padding (matches iOS `boundsForRoute`
formula: `latPad = (maxLat - minLat) * 0.15 + 0.001`).

`SummaryMap` accepts `zoomTargetBounds: MapCameraBounds?` and forwards to
`PilgrimMap`. `PilgrimMap` reveal LaunchedEffect re-keys to include
`zoomTargetBounds`:

```kotlin
LaunchedEffect(mapView, revealPhase, points.firstOrNull(), reduceMotion, zoomTargetBounds) {
    // Hidden / Zoomed branches unchanged
    // Revealed branch: if zoomTargetBounds != null → easeTo those bounds, else fit-bounds
}
```

Camera ease duration on segment-tap zoom matches iOS `withAnimation` default
(0.35s) — much shorter than the reveal's 2.5s. Use `MapAnimationOptions.duration(SEGMENT_ZOOM_EASE_MS = 350L)`.

### Wire-up at WalkSummaryScreen

```kotlin
// 13. Activity timeline bar (Stage 13-C, gains 13-D segment-zoom callbacks)
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

And the `SummaryMap` call at section 1:

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

## VM additions

`WalkSummary` adds:

```kotlin
val walkAnnotations: List<WalkMapAnnotation> = emptyList(),
```

`buildState` populates from already-hoisted locals — no new repo calls:

```kotlin
val walkAnnotations = computeWalkMapAnnotations(
    routeSamples = samples,
    meditationIntervals = activityIntervals.filter { it.activityType == ActivityType.MEDITATING },
    voiceRecordings = voiceRecordings,
)
```

Hopped to `Dispatchers.Default` like `routeSegments` was in 13-B (defensive
ANR avoidance for long walks with many intervals).

## Files

### Create

| Path | Purpose |
|---|---|
| `app/src/main/java/.../data/walk/MapAnnotations.kt` | `WalkMapAnnotation` data + `WalkMapAnnotationKind` sealed + `computeWalkMapAnnotations` pure |
| `app/src/main/java/.../ui/walk/summary/MapCameraBounds.kt` | `MapCameraBounds` data class + `computeBoundsForTimeRange` pure |
| `app/src/test/java/.../data/walk/MapAnnotationsTest.kt` | 5 classifier tests |
| `app/src/test/java/.../ui/walk/summary/MapCameraBoundsTest.kt` | 4 bounds tests |

### Modify

| Path | Change |
|---|---|
| `app/src/main/java/.../ui/walk/PilgrimMap.kt` | Add `walkAnnotations` + `walkAnnotationColors` + `zoomTargetBounds` params; new annotation manager; render + cache pins; reveal-camera LaunchedEffect respects zoom target |
| `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` | Add `walkAnnotations` field; populate via withContext(Default) |
| `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` | Track `zoomTargetBounds` state; wire timeline callbacks; pass annotations + colors + bounds to SummaryMap |
| `app/src/main/java/.../ui/walk/summary/WalkActivityTimelineCard.kt` | Add `onSegmentSelected` + `onSegmentDeselected` callback params; fire from tap handler |
| `app/src/main/java/.../ui/walk/summary/RevealAnimation.kt` | Add `WalkAnnotationColors` data class |
| `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` | Add 1 test verifying walkAnnotations populates |

## Tests

```kotlin
// MapAnnotationsTest
@Test fun emptySamples_returnsEmpty()
@Test fun singleSample_yieldsStartOnly()
@Test fun multipleSamples_yieldsStartAndEnd()
@Test fun meditation_pinAtClosestSampleToStart()
@Test fun voiceRecording_pinAtClosestSampleToStart()

// MapCameraBoundsTest
@Test fun emptySamples_returnsNull()
@Test fun samplesOutsideTimeRange_returnsNull()
@Test fun singleSampleInRange_yieldsZeroSpanWithPadding()
@Test fun multiSampleInRange_yieldsBoundsWith15PercentPadding()

// WalkSummaryViewModelTest (extend)
@Test fun walkAnnotations_populated_includesStartEndMeditationVoice()
```

## Behavioral details

- **`walkAnnotations` reference stability:** Computed once in `buildState`,
  held in `WalkSummary`. Same single-emission flow as 13-C `routeSegments`.
- **Segment-tap-zoom interaction with reveal:** When the user taps a segment
  during the Hidden/Zoomed phase, `zoomTargetBounds` updates but the camera
  LaunchedEffect's Revealed branch hasn't fired yet. Acceptable — once the
  reveal completes, the camera lands on the requested bounds. Matches iOS.
- **`zoomTargetBounds == null` (deselected) returns to fit-bounds:** Triggers
  the existing fit-bounds path in the Revealed branch.
- **Ease durations:** Reveal Hidden→Zoomed = setCamera (instant); Zoomed→Revealed = 2.5s `REVEAL_CAMERA_EASE_MS` (existing); segment-tap zoom = 350ms `SEGMENT_ZOOM_EASE_MS` (new, faster). All collapse to setCamera under reduce-motion.
- **Pin bitmap cache key:** `remember(walkAnnotationColors, darkMode)` so
  theme toggles + color swaps refresh; segment-tap zooms don't.

## Risks + mitigations

- **Mapbox annotation manager interleaving:** PilgrimMap already has
  `polylineManager` + `waypointManager`. Adding a third (annotation marker
  manager) means three managers attached to the same MapView. Each annotation
  type uses its own manager so deletes don't affect siblings. Existing
  pattern; safe.

- **Bitmap allocation cost:** Each WalkMapAnnotation allocates a 56×56 ARGB
  bitmap. For a walk with 5 meditations + 100 recordings + start/end = 107
  bitmaps. Cache the per-kind bitmap once via `remember(walkAnnotationColors,
  darkMode)` so all 100 voice pins share one bitmap. Memory bounded.

- **`computeBoundsForTimeRange` for partial overlap:** When a segment's
  timestamps fall between GPS samples, `samples.filter { it.timestamp in
  [start, end] }` returns the inner subset. Bounds derive from inner samples
  only — segment edge fractions don't extrapolate. Acceptable; matches iOS
  `boundsForTimeRange`.

- **Reduce-motion with segment-tap:** When reduce-motion is on, segment tap
  should `setCamera` not `easeTo`. PilgrimMap's existing reduce-motion guard
  in the Revealed branch already does this; same code path applies when
  `zoomTargetBounds != null`.

## Success criteria

- Open Walk Summary on a finished walk with talk + meditation → map shows
  pins at start, end, meditation centroids (dawn-tinted), voice rec
  centroids (rust-tinted).
- Tap a meditation segment in the timeline bar → map camera animates to
  that meditation interval's GPS bounds.
- Tap the same segment again → map returns to full-route fit-bounds.
- Tap a talk segment → map zooms to recording's GPS bounds.
- Reduce-motion on → segment-tap zooms snap (setCamera) instead of easeTo.
- Stage 13-A/B/C regressions pass (TopBar, hero, count-up, segments,
  timeline tooltip, list sort, etc.).

`./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest` clean.
