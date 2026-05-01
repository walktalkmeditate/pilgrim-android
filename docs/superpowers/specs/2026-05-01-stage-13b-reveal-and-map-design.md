# Stage 13-B — Reveal animation + map polish (iOS parity)

## Context

Stage 13-A landed the post-walk Walk Summary layout skeleton in iOS-faithful section
order. Stage 13-B layers in the **reveal sequence** + **map polish** that brings the
screen alive on first open: zoom-into-route → fan-out-to-bounds, animated distance
count-up, opacity fade-in for the lower sections, circular radial-gradient mask on the
map, and route segments tinted by activity (walk = moss, talk = rust, meditate = dawn).

This is item 2 of the seven 13-X sub-stages tracked in the Stage 13 audit
(13-A complete; 13-B now; 13-C…G + 13-X queued).

iOS reference:
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:350-392` (reveal sequence + count-up)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView+Map.swift:48-87` (map mask + segments)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:830-902` (computeSegments + activityType classifier)

## Goal

Walk Summary opens with an animated reveal that feels intentional: a brief zoomed-in
camera plant on the start point, a 2.5s ease-out to fit the full route bounds, and a
0.6s opacity fade for sections below the map (distance hero, journey quote, stats row,
weather, time breakdown). Distance value in the stats row counts up 0 → final over 2s
with smooth-step easing. Map renders at 320dp height (was 200dp), masked by a circular
radial gradient (matches iOS `RadialGradient` mask), with route polyline split into
activity-typed segments tinted moss / rust / dawn.

## Non-goals (deferred to later sub-stages)

- **Map annotations** (start/end pins, meditation markers, voice-rec markers,
  waypoint pins) → 13-D
- **Section-stagger delays for individual fade-ins** (iOS uses 0.2/0.3/0.4s per-section
  delays — 13-B uses a single shared fade-in for everything below the hero;
  per-section delays are a polish pass for 13-Z)
- **Activity timeline bar / insights / list** → 13-C
- **Favicon selector** → 13-E
- **Milestone callout / celestial line / elevation profile sparkline** → 13-F
- **Details section** → 13-G
- **Walking-color seasonal turning tint** (iOS `walkTurning?.uiColor ?? .moss`) —
  Android doesn't yet have `TurningDayService`/`SeasonalMarker` ported; fall back to
  `pilgrimColors.moss` for the walk segment. When 13-F lands the seasonal calendar
  surface, the moss tint can pick up a turning shift.

## Architecture

### Reveal phase machine (composable-local)

```kotlin
internal enum class RevealPhase { Hidden, Zoomed, Revealed }
```

Lives in `WalkSummaryScreen.kt` as a `var revealPhase by remember(walkId) { mutableStateOf(RevealPhase.Hidden) }`.

State transitions (`LaunchedEffect(s.summary.walk.id)`, only fires on first
composition for a given walk):

```
Hidden  --routePoints.isEmpty()--> Revealed (skip animation)
Hidden  --otherwise--> Zoomed (camera at first GPS point, zoom=16)
Zoomed  --delay 800ms--> Revealed (camera bounds animation 2500ms + opacity 600ms ease-in)
```

Reveal phase drives:
- **Map camera:** Zoomed → instant set to first coord at zoom 16. Revealed → fit-bounds with `cameraForCoordinates` over a 2.5s ease.
- **Below-the-map sections** (`WalkJourneyQuote`, `WalkDurationHero`, `WalkStatsRow`,
  `WalkSummaryWeatherLine`, `WalkTimeBreakdownGrid`): wrapped in
  `AnimatedVisibility(visible = revealPhase == Revealed, enter = fadeIn(...))` — single
  shared fade for 13-B.
- **Animated distance** in `WalkStatsRow`: `animateFloatAsState` from 0f to
  `distanceMeters` keyed on revealPhase, 2000ms with smooth-step easing
  (`progress * progress * (3 - 2*progress)`).

Reduce-motion support: when `LocalAccessibilityManager.current` reports
`accessibilityServiceState.accessibilityEnabled == true` AND the system "remove
animations" pref is on (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`), the reveal
short-circuits — `revealPhase = Revealed` directly, no zoom-and-fan, no count-up.
iOS uses `@Environment(\.accessibilityReduceMotion)`. Android equivalent:
`Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f`.

### Map polish

#### Height bump

`SummaryMap` Card: `Modifier.height(200.dp)` → `Modifier.height(320.dp)`.

#### Circular radial-gradient mask

iOS:
```swift
.mask(RadialGradient(
    gradient: Gradient(colors: [.white, .white, .white.opacity(0)]),
    center: .center,
    startRadius: 80,
    endRadius: 180))
```

The mask is a soft-edged circle: opaque from center to ~80pt, fading to transparent
at ~180pt. Compose equivalent uses `Modifier.graphicsLayer { compositingStrategy =
CompositingStrategy.Offscreen }` paired with `.drawWithCache` that paints a
`RadialGradient` brush via `BlendMode.DstIn`:

```kotlin
.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
.drawWithCache {
    val brush = Brush.radialGradient(
        colorStops = arrayOf(
            0.0f to Color.White,
            0.45f to Color.White,
            1.0f to Color.Transparent,
        ),
        center = size.center,
        radius = size.minDimension / 2f,
    )
    onDrawWithContent {
        drawContent()
        drawRect(brush = brush, blendMode = BlendMode.DstIn)
    }
}
```

The 0.45 stop matches iOS's 80/180 ratio (≈0.44). Color values are alpha-only
(white=opaque, transparent=clear) — `BlendMode.DstIn` keeps the destination pixel
where the source brush is opaque and clears it where the brush is transparent.

`CompositingStrategy.Offscreen` is mandatory — without it, `BlendMode.DstIn`
operates on the immediate parent's composition layer and blanks unrelated content.

#### Route segments tinted by activity

iOS computes a `[RouteSegment]` list from the route + activity intervals + voice
recordings via `WalkSummaryView.computeSegments`. The classifier rule (verbatim port
to Android):

```kotlin
private fun classifyActivity(
    timestampMs: Long,
    meditationIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): RouteActivity = when {
    meditationIntervals.any { iv ->
        iv.activityType == ActivityType.MEDITATING &&
            timestampMs >= iv.startTimestamp &&
            timestampMs <= iv.endTimestamp
    } -> RouteActivity.Meditating
    voiceRecordings.any { rec ->
        timestampMs >= rec.startTimestamp &&
            timestampMs <= rec.endTimestamp
    } -> RouteActivity.Talking
    else -> RouteActivity.Walking
}
```

`RouteSegment` data class:

```kotlin
@Immutable
data class RouteSegment(
    val activity: RouteActivity,
    val points: List<LocationPoint>,
)

enum class RouteActivity { Walking, Talking, Meditating }
```

Both live in a new file `data/walk/RouteSegments.kt`.

`computeRouteSegments(samples, intervals, recordings): List<RouteSegment>` is the
verbatim port of iOS `computeSegments`. Walks the samples in order, classifies each
by activity, accumulates runs of same type into segments. Boundary point shared
between two consecutive segments (so the rendered polylines connect).

VM exposes the result on `WalkSummary` as `val routeSegments: List<RouteSegment> =
emptyList()` (default for back-compat with seal/etegami callers that build a
WalkSummary without segments — none today, but defensive).

`PilgrimMap` extends signature:

```kotlin
@Composable
fun PilgrimMap(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
    initialCenter: LocationPoint? = null,
    bottomInsetDp: Dp = 0.dp,
    waypoints: List<Waypoint> = emptyList(),
    routeSegments: List<RouteSegment> = emptyList(),  // NEW
    revealPhase: RevealPhase? = null,                 // NEW (null = legacy single-render mode)
    initialCameraTarget: LocationPoint? = null,       // NEW (revealPhase==Zoomed plant point)
)
```

When `routeSegments` is non-empty, `PilgrimMap` renders one `PolylineAnnotation` per
segment with the activity-tinted color. When empty, falls back to the existing
single-color polyline path (Active Walk + Walk Share use this fallback).

Color tokens (read at composition site, passed in via signature so `PilgrimMap`
stays theme-agnostic on its own):

```kotlin
data class RouteSegmentColors(
    val walking: Color,    // pilgrimColors.moss
    val talking: Color,    // pilgrimColors.rust
    val meditating: Color, // pilgrimColors.dawn
)
```

When the caller passes `routeSegments` it must also pass `segmentColors:
RouteSegmentColors`. Default not provided (compile-time forcing for callers that
opt in to multi-segment).

### Reveal-aware camera control

Today `PilgrimMap` does fit-bounds once via `didFitBounds` flag on first frame with
`points.size >= 2`. For the reveal sequence:

```
revealPhase == Zoomed   → setCamera(first GPS point, zoom=16) immediately
revealPhase == Revealed → easeTo(cameraForCoordinates(allPoints), duration=2500ms, ease=EASE_IN_OUT)
revealPhase == null     → existing fit-bounds path (Active Walk, Walk Share, Stage 13-A summary fallback)
```

A new `LaunchedEffect(revealPhase, points)` inside `PilgrimMap` drives this. The
existing `didFitBounds` flag is removed when `revealPhase != null`; the reveal
machine takes precedence.

## VM additions

`WalkSummary` data class adds:

```kotlin
@Immutable
data class WalkSummary(
    // existing fields…
    val routeSegments: List<RouteSegment> = emptyList(),
)
```

`buildState` adds (using already-hoisted `voiceRecordings` from Stage 13-A — same
hoisting now grows to also hoist `activityIntervals`):

```kotlin
val activityIntervals = repository.activityIntervalsFor(walkId)  // hoisted out of etegami runCatching too
val routeSegments = computeRouteSegments(
    samples = samples,
    intervals = activityIntervals,
    recordings = voiceRecordings,
)
```

The etegami `runCatching` block reuses the now-also-hoisted `activityIntervals` (no
re-fetch). `WalkSummary.routeSegments` populated; passed to `WalkSummaryScreen`'s
`SummaryMap` slot.

## Component changes

### `SummaryMap` (in WalkSummaryScreen.kt)

Becomes:

```kotlin
@Composable
private fun SummaryMap(
    points: List<LocationPoint>,
    routeSegments: List<RouteSegment>,
    revealPhase: RevealPhase,
    segmentColors: RouteSegmentColors,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp)             // was 200.dp
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache { /* radial gradient mask, see Architecture */ },
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        if (points.isEmpty()) {
            // unchanged: "No route recorded for this walk." text
        } else {
            PilgrimMap(
                points = points,
                routeSegments = routeSegments,
                segmentColors = segmentColors,
                revealPhase = revealPhase,
                initialCameraTarget = points.firstOrNull(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

`SummaryMapPlaceholder` also bumps to 320.dp for layout consistency during Loading.

### Below-map sections (in `Loaded` branch of WalkSummaryScreen.kt)

Wrap the contiguous block from `WalkJourneyQuote` through `WalkTimeBreakdownGrid` (and
the conditional weather line within it) in a single `AnimatedVisibility`:

```kotlin
AnimatedVisibility(
    visible = revealPhase == RevealPhase.Revealed,
    enter = fadeIn(animationSpec = tween(durationMillis = REVEAL_FADE_MS)),
    exit = ExitTransition.None,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
        WalkJourneyQuote(…)
        WalkDurationHero(…)
        WalkStatsRow(distanceMeters = animatedDistance, …)  // distance animated separately
        // weather, time breakdown
    }
}
```

Note: the `Spacer(PilgrimSpacing.normal)` separators between these specific sections
are subsumed into a parent `verticalArrangement = Arrangement.spacedBy(...)` since
they're now inside an `AnimatedVisibility` with a single `Column` child. Sections
above (Map, Reliquary, Intention) and below (Voice Recordings, Light Reading,
Etegami) keep their existing inline `Spacer(PilgrimSpacing.normal)` separators —
they're outside the reveal block.

### Animated distance count-up

```kotlin
val animatedDistanceMeters by animateFloatAsState(
    targetValue = if (revealPhase == RevealPhase.Revealed) {
        s.summary.distanceMeters.toFloat()
    } else 0f,
    animationSpec = tween(
        durationMillis = COUNT_UP_DURATION_MS,
        easing = SmoothStepEasing,
    ),
    label = "summary-distance-countup",
)

WalkStatsRow(
    distanceMeters = animatedDistanceMeters.toDouble(),
    ascendMeters = s.summary.ascendMeters,
    units = distanceUnits,
)
```

`SmoothStepEasing` is a custom `Easing` matching iOS's smooth-step:

```kotlin
internal val SmoothStepEasing = Easing { fraction ->
    fraction * fraction * (3f - 2f * fraction)
}
```

Lives in a new `ui/walk/summary/RevealAnimation.kt` file alongside the
`RevealPhase` enum and the constants.

`WalkStatsRow` itself does NOT change signature — it already accepts a `Double`
for `distanceMeters`. Caller animates the value.

Edge case: When `points.isEmpty()` the reveal machine short-circuits to `Revealed`
in one frame and `animatedDistanceMeters` snaps to `distanceMeters.toFloat()`
immediately (no count-up animation visible since target = source). Safe — matches
iOS behavior.

## Float precision

iOS uses `Double` for `animatedDistance`. Compose `animateFloatAsState` is `Float`.
For walk distances ≥ 22 km, Float loses meters of precision (`Float.MAX_VALUE_AT_2DECIMALS
≈ 22 km`). For Pilgrim's contemplative walks this is fine — typical < 10 km. To
defend against pathological 50+ km walks, the spec accepts the precision loss —
the count-up animation is a visual flourish, the persisted value is a Double on
the row, and the final-frame snap renders the un-rounded `distanceMeters`. No
visual artifact at typical scale.

## Tests

### `app/src/test/java/.../data/walk/RouteSegmentsTest.kt`

```kotlin
@Test fun emptySamples_returnsEmptyList()
@Test fun singleSample_returnsEmptyList()  // segments need ≥2 points
@Test fun allWalking_returnsOneSegmentSpanningEntireRoute()
@Test fun talkOnly_splitsIntoWalkTalkWalk()  // walks at start, talk in middle, walk at end
@Test fun meditateOnly_splitsIntoWalkMeditateWalk()
@Test fun meditationOverlapsRecording_meditationWins()  // priority test
@Test fun boundaryPointSharedBetweenAdjacentSegments()  // last point of segment N = first point of segment N+1
@Test fun simultaneousTransitions_classifyByPriority()  // both intervals start at same sample
```

### `app/src/test/java/.../ui/walk/summary/RevealPhaseTest.kt`

Tests the phase machine via a pure-state test (Compose virtual time):

```kotlin
@Test fun emptyRoute_skipsToRevealedImmediately()
@Test fun nonEmptyRoute_startsAtZoomed_thenAdvancesToRevealedAfter800ms()
@Test fun reduceMotion_short_circuitsToRevealed()
```

Uses `composeRule.mainClock.autoAdvance = false` + `advanceTimeBy(...)` for
deterministic phase-transition assertions.

### `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` (extend)

```kotlin
@Test fun routeSegments_classifiesWalkOnlyAsSingleSegment()
@Test fun routeSegments_splitsAtMeditationBoundaries()
@Test fun routeSegments_splitsAtVoiceRecordingBoundaries()
@Test fun routeSegments_meditationOverridesTalking()  // priority
```

## Files

### Create

| Path | Purpose |
|---|---|
| `app/src/main/java/.../data/walk/RouteSegments.kt` | `RouteActivity`, `RouteSegment` data class, `computeRouteSegments` |
| `app/src/main/java/.../ui/walk/summary/RevealAnimation.kt` | `RevealPhase` enum, `SmoothStepEasing`, timing constants, `RouteSegmentColors` data class |
| `app/src/test/java/.../data/walk/RouteSegmentsTest.kt` | 8 classifier cases |
| `app/src/test/java/.../ui/walk/summary/RevealPhaseTest.kt` | 3 phase-machine cases |

### Modify

| Path | Change |
|---|---|
| `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` | Add `routeSegments` to `WalkSummary`; hoist `activityIntervals` out of etegami runCatching; compute via `computeRouteSegments` |
| `app/src/main/java/.../ui/walk/PilgrimMap.kt` | Add `routeSegments` + `segmentColors` + `revealPhase` + `initialCameraTarget` params; multi-polyline path; reveal-driven camera control |
| `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` | Wire reveal phase machine; bump map height; add radial gradient mask; wrap below-map sections in `AnimatedVisibility`; animate distance count-up |
| `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` | Add 4 routeSegments tests |

## Behavioral details

- **Reveal phase scope:** keyed on `walkId`. Re-entering the same walk replays the
  reveal once per fresh composition (matches iOS — the screen replays on back-nav +
  re-entry; no persistent flag).
- **Reveal vs. SealRevealOverlay:** SealRevealOverlay (Stage 4-B) renders ABOVE the
  summary content. The 13-B reveal sequence runs the summary's own animation
  underneath while the seal overlay is visible. Acceptable — when the seal dismisses
  (auto at 2.5s or tap), the summary's reveal is already at `Revealed`.
- **Empty-route case:** When `routePoints.isEmpty()`, `SummaryMap` renders the
  "No route recorded" Card and the reveal machine immediately goes Revealed.
  Distance count-up shows 0 (the actual distance for a zero-route walk).
- **Reduce-motion:** when `Settings.Global.ANIMATOR_DURATION_SCALE == 0f`, the
  reveal machine skips Zoomed and goes straight to Revealed in the first frame.
  No count-up animation. AnimatedVisibility's `fadeIn` collapses to zero duration.
- **Polyline width** stays at the existing `POLYLINE_WIDTH_DP = 4.0`. iOS uses
  the same.
- **Mapbox camera ease curve:** `easeTo` accepts `MapAnimationOptions.Builder().duration(2500).build()`. Mapbox v11's ease is built-in `EaseInOut`. Matches iOS
  `withAnimation(.easeInOut)`.

## Risks + mitigations

- **`BlendMode.DstIn` mask via `drawWithCache` requires `CompositingStrategy.Offscreen`.**
  Without it, the mask blanks unrelated UI underneath. Guard with explicit
  `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` BEFORE
  `drawWithCache`. Document inline.

- **Mapbox `setCamera` vs `easeTo` race during Zoomed → Revealed transition.** On
  Zoomed entry we want an immediate plant; on Revealed entry we want a 2.5s ease.
  If a recomposition fires while ease is in flight (e.g., theme toggle), Mapbox's
  ease is interrupted and replaced. Acceptable. The `LaunchedEffect(revealPhase,
  points)` keys re-fire only on actual phase + points change. Theme toggles only
  re-key `LaunchedEffect(mapView, styleUri)`, not the reveal effect.

- **`PilgrimMap` is shared with Active Walk + Walk Share screens.** New `revealPhase`
  param defaults to `null` so existing callers keep their fit-bounds-once behavior.
  The reveal-driven branch is gated `revealPhase != null`.

- **`AnimatedVisibility` with `fadeIn` for a Column wrapper.** Until visible, the
  Column is composed but invisible — child composables ARE composed. Acceptable —
  composition is cheap; the alternative (don't compose at all until Revealed) makes
  layout jump as children flicker in. Match iOS where `.opacity(0)` keeps layout.

- **`RouteSegment.points: List<LocationPoint>` stability.** `LocationPoint` is a
  data class with three primitive fields — Compose treats it as stable. `RouteSegment`
  is `@Immutable`, the `List<LocationPoint>` field needs the annotation to walk
  through. List stability for read-only access is fine — Compose's stability
  inference handles `List` with stable elements correctly post-Compose-1.5.
  Defensive: keep `routeSegments` immutable in the VM, don't mutate after creation.

- **iOS turning-marker color tint deferred.** iOS uses `walkTurning?.uiColor ?? .moss`
  for the walking segment. Android's port doesn't yet have `TurningDayService`.
  Spec uses `pilgrimColors.moss` directly. Document inline so the future seasonal-
  marker stage can wire it through without re-discovering this gap.

## Success criteria

- Open Walk Summary on a finished walk with a real route → map shows zoomed-in
  camera at start point for ~800ms, then animates over 2.5s to fit-bounds. Below-
  map sections fade in over 600ms. Distance value counts up 0 → final over 2s.
- Map is now 320dp tall (was 200dp) with a circular radial-gradient mask (visible
  soft-edged circle, content faded near edges).
- A walk that mixes walking, talking, and meditation shows the route polyline split
  into colored segments: moss (walking), rust (talking), dawn (meditating).
- Empty-route walk or any walk with `points.size < 2` skips the reveal entirely;
  Card shows "No route recorded".
- Active Walk + Walk Share screens unchanged (no `revealPhase` arg).
- `Settings.Global.ANIMATOR_DURATION_SCALE = 0f` (system "remove animations") →
  reveal goes straight to Revealed; no zoom-in or count-up.
- All Stage 13-A regressions pass (TopBar, IntentionCard, JourneyQuote, etc.).

`./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` clean.
