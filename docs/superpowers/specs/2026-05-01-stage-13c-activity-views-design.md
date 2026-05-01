# Stage 13-C — Activity views (timeline bar + insights + list)

## Context

Stage 13-A landed the layout skeleton with placeholders for sections 13/14/15
(activity timeline + insights + list). Stage 13-B layered reveal animation +
map polish. Stage 13-C now fills the three placeholder slots — the heaviest
single block in the iOS reference (~437 LOC across 3 SwiftUI files +
PaceSparklineView).

iOS reference:
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityTimelineBar.swift` (264 LOC)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityInsightsView.swift` (67 LOC)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityListView.swift` (107 LOC)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/PaceSparklineView.swift` (101 LOC)

## Goal

Three new card-style sections render between the time-breakdown grid (section
11) and the voice-recordings section (16), populating the iOS slots 13/14/15:

- **Timeline bar:** horizontal track showing meditation + talk segments laid
  over a moss-tinted base, with relative-time labels (toggleable) + pace
  sparkline + legend.
- **Insights:** "Insights" card with up to two text observations — meditation
  count/duration + talk percentage.
- **List:** "Activities" card listing every meditation interval and voice
  recording sorted by start time, each with icon + name + time range +
  duration.

## Non-goals (deferred)

- **Segment-tap-to-zoom-map interaction** (iOS `onSegmentTapped` re-zooms the
  map to the interval's bounds, `onSegmentDeselected` zooms back). Defers to
  13-D (map annotations) since that stage already touches the map. 13-C ships
  the timeline bar with the visual segments + tooltip but no map callbacks.
- **Pace sparkline absolute pace label format ("Pace M:SS /km")** — 13-C
  matches iOS verbatim. Already covered by `WalkFormat.pace`.
- **Stagger-fade per section** (already deferred to 13-Z polish).
- **Walking-color seasonal turning tint** (waits for `TurningDayService`).

## Architecture

### `WalkActivityTimelineCard`

```kotlin
@Composable
fun WalkActivityTimelineCard(
    startTimestamp: Long,
    endTimestamp: Long,
    voiceRecordings: List<VoiceRecording>,
    activityIntervals: List<ActivityInterval>,
    routeSamples: List<RouteDataSample>,
    units: UnitSystem,
    modifier: Modifier = Modifier,
)
```

Container: `Card(parchmentSecondary)` with `PilgrimSpacing.normal` padding.

Layout (vertical Column, `spacedBy(PilgrimSpacing.xs)`):
1. Bar — `Box(fillMaxWidth, height=16dp)`:
   - Background: moss @ 0.4 alpha, `RoundedCornerShape(4dp)`
   - Layered ZStack:
     - Meditation segments (height 16dp, `dawn` color, alpha 0.7 normal / 0.95 selected)
     - Talk segments (height 10dp, `rust` color, alpha 0.7 / 0.95 selected)
   - Each segment positioned via `offset(x = startFraction * totalWidth)`
     and sized via `width = max(2dp, widthFraction * totalWidth)`
   - Tap detection: `pointerInput { detectTapGestures { offset -> ... } }`
     translates tap-x to fraction, finds segment, toggles `selectedId`
2. Optional tooltip (when `selectedId != null`):
   - Row: small dot of segment color + segment label (Talk/Meditate) +
     formatted duration ("22m" / "45s") + optional absolute time
   - `pilgrimType.caption`, `pilgrimColors.fog/ink`
3. Time labels Row (`SpaceBetween`):
   - Left: `formattedStartTime`
   - Right: `formattedEndTime`
   - Tappable: toggles `showRelativeTime` between relative ("0:00" / "1:23:45")
     and absolute (HH:mm formatted via `DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)`)
4. Pace sparkline (40dp tall, only when `routeSamples.count >= 3`):
   - Avg pace label: "Pace 6:30 /km" via `WalkFormat.pace(secondsPerKm, units)`
   - Spline path: filter samples by `speedMetersPerSecond > 0.3f`, sort by
     timestamp, bucket by `samples.count / 50` (clamped at min 1), draw
     fill (linear gradient stone alpha 0.12→0.02) + stroke (stone alpha 0.45)
5. Legend Row: 3 colored dots with labels (Walk/Talk/Meditate)

Internal state:
- `var selectedId by remember { mutableStateOf<Int?>(null) }`
- `var showRelativeTime by remember { mutableStateOf(true) }`

Segments computed via `remember(startTimestamp, endTimestamp, intervals,
recordings)`:

```kotlin
internal data class TimelineSegment(
    val id: Int,
    val type: TimelineSegmentType, // Meditating, Talking
    val startFraction: Float,
    val widthFraction: Float,
    val startMillis: Long,
    val endMillis: Long,
)

internal fun computeTimelineSegments(
    startMs: Long, endMs: Long,
    meditations: List<ActivityInterval>,
    recordings: List<VoiceRecording>,
): List<TimelineSegment>
```

Pure function — testable without Compose host.

### `WalkActivityInsightsCard`

```kotlin
@Composable
fun WalkActivityInsightsCard(
    talkMillis: Long,
    activeMillis: Long,
    meditationIntervals: List<ActivityInterval>,
    modifier: Modifier = Modifier,
)
```

Caller-side guard: caller skips rendering when both `talkMillis == 0L` AND
`meditationIntervals.isEmpty()`. (iOS: `if walk.talkDuration > 0 ||
walk.meditateDuration > 0`.)

Layout: `Card(parchmentSecondary, PilgrimSpacing.normal)`:
1. Header Row: sparkles icon (`Icons.Rounded.AutoAwesome`, stone tint) + "Insights" text (heading style, ink)
2. Optional meditation insight Text (body style, fog):
   - `intervals.size == 1` → `"Meditated once for {longestFormatted}"`
   - else → `"Meditated {count} times (longest: {longestFormatted})"`
   - Hidden when `intervals.isEmpty()`
3. Optional talk insight Text:
   - `"Talked for {pct}% of the walk"` where `pct = (talkMillis * 100 / activeMillis).toInt()`
   - Hidden when `talkMillis == 0` or `activeMillis == 0`

`formatCompactDuration(millis): String`:
- `< 60s` → `"{N} sec"`
- `seconds == 0` → `"{m} min"`
- else → `"{m} min {s} sec"`

### `WalkActivityListCard`

```kotlin
@Composable
fun WalkActivityListCard(
    walkStartTimestamp: Long,
    voiceRecordings: List<VoiceRecording>,
    meditationIntervals: List<ActivityInterval>,
    modifier: Modifier = Modifier,
)
```

Caller-side guard: caller skips rendering when both lists empty. (iOS:
`if !walk.activityIntervals.isEmpty || !walk.voiceRecordings.isEmpty`.)

Layout: `Card(parchmentSecondary, PilgrimSpacing.normal)`:
1. Header Row: bullet-list icon (`Icons.Rounded.FormatListBulleted`, stone tint) + "Activities" text
2. For each entry sorted by `startTimestamp`:
   - Row(spacedBy(PilgrimSpacing.small), CenterVertically), padding-vertical PilgrimSpacing.xs:
     - Circle 24dp: tinted (rust for Talk, dawn for Meditate), white-tinted icon centered
     - Column: name (Talk/Meditate, heading style ink) + time range (caption fog, e.g. "07:27 – 07:49")
     - Spacer(weight=1f)
     - Duration text (statLabel fog, monospace via `fontFamily=Text` already handles, e.g. "22:00")

`ActivityListEntry` internal sealed: `Talk(start, end, durationMillis)` /
`Meditate(start, end, durationMillis)`. Sort merge from
`voiceRecordings.map(::Talk) + meditationIntervals.filter(MEDITATING).map(::Meditate)`.

### Pace sparkline path math

Pure helper in `WalkActivityTimelineCard.kt` private file:

```kotlin
internal data class PaceSparklinePoint(val xFraction: Float, val yFraction: Float)

internal fun computePaceSparklinePoints(
    samples: List<RouteDataSample>,
    walkStartMs: Long,
    walkEndMs: Long,
): List<PaceSparklinePoint>
```

Algorithm (verbatim port of iOS `PaceSparklineView.sparklinePoints`):
1. Filter `samples.filter { (it.speedMetersPerSecond ?: 0f) > 0.3f }`
2. Sort by timestamp
3. Guard `samples.size >= 3` else return empty
4. `step = max(1, samples.size / 50)`
5. Bucket by stride; per bucket: `avgSpeed = window.avg`, `midSample = window[window.size/2]`
6. `xFraction = (midSample.timestamp - walkStartMs) / totalMs`, clamped 0..1
7. `maxSpeed = max of avgSpeed`; guard `maxSpeed > 0` else empty
8. `yFraction = 1f - (avgSpeed / maxSpeed) * 0.85f` (iOS: leaves 15% top headroom)

Then `Canvas` renders both fill (closed path with linear-gradient brush stone
alpha 0.12 → 0.02) and stroke path.

### Section ordering update

In `WalkSummaryScreen.kt`'s Loaded branch, fill the existing placeholders for
sections 13/14/15:

```kotlin
// 13. Activity timeline bar (Stage 13-C)
WalkActivityTimelineCard(
    startTimestamp = s.summary.walk.startTimestamp,
    endTimestamp = s.summary.walk.endTimestamp ?: s.summary.walk.startTimestamp,
    voiceRecordings = ..., // need to expose from VM
    activityIntervals = ..., // need to expose from VM
    routeSamples = ..., // need to expose from VM
    units = distanceUnits,
)

// 14. Activity insights — guarded
if (s.summary.talkMillis > 0L || s.summary.meditationIntervals.isNotEmpty()) {
    WalkActivityInsightsCard(
        talkMillis = s.summary.talkMillis,
        activeMillis = s.summary.activeMillis,
        meditationIntervals = s.summary.meditationIntervals,
    )
}

// 15. Activity list — guarded
if (s.summary.voiceRecordings.isNotEmpty() || s.summary.meditationIntervals.isNotEmpty()) {
    WalkActivityListCard(
        walkStartTimestamp = s.summary.walk.startTimestamp,
        voiceRecordings = s.summary.voiceRecordings,
        meditationIntervals = s.summary.meditationIntervals,
    )
}
```

These three sections live INSIDE the `AnimatedVisibility` reveal wrapper from
13-B (sections 5/6/8/9/11/13/14/15 all fade in together at Revealed).

## VM additions

`WalkSummary` data class adds:

```kotlin
val voiceRecordings: List<VoiceRecording> = emptyList(),
val meditationIntervals: List<ActivityInterval> = emptyList(),
val routeSamples: List<RouteDataSample> = emptyList(),
```

`buildState` already hoisted `voiceRecordings`, `activityIntervals`,
`samples` from prior stages. Pass them through:

```kotlin
WalkSummary(
    // existing fields…
    voiceRecordings = voiceRecordings,
    meditationIntervals = activityIntervals.filter { it.activityType == ActivityType.MEDITATING },
    routeSamples = samples,
)
```

`@Immutable` annotation on `WalkSummary` covers transitive list fields. Same
pattern as Stage 13-B's `routeSegments`.

## Files

### Create

| Path | Purpose |
|---|---|
| `app/src/main/java/.../ui/walk/summary/WalkActivityTimelineCard.kt` | Timeline bar + tooltip + time labels + pace sparkline + legend |
| `app/src/main/java/.../ui/walk/summary/WalkActivityInsightsCard.kt` | Insights card |
| `app/src/main/java/.../ui/walk/summary/WalkActivityListCard.kt` | Activity list card |
| `app/src/main/java/.../ui/walk/summary/TimelineSegments.kt` | `TimelineSegment` data class + `TimelineSegmentType` enum + `computeTimelineSegments` pure function |
| `app/src/main/java/.../ui/walk/summary/PaceSparkline.kt` | `PaceSparklinePoint` + `computePaceSparklinePoints` pure function |
| `app/src/test/java/.../ui/walk/summary/TimelineSegmentsTest.kt` | 6 cases (empty / one talk / one meditate / both / boundary fractions / clamp) |
| `app/src/test/java/.../ui/walk/summary/PaceSparklineTest.kt` | 5 cases (empty / under-3-samples / monotonic / variable / threshold filter) |
| `app/src/test/java/.../ui/walk/summary/WalkActivityInsightsCardTest.kt` | 3 Robolectric Compose cases (one-meditation / multiple / talk-only) |
| `app/src/test/java/.../ui/walk/summary/WalkActivityListCardTest.kt` | 2 Robolectric cases (renders both / sorts by start) |

### Modify

| Path | Change |
|---|---|
| `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` | Add 3 fields to `WalkSummary`; populate from existing hoisted locals |
| `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` | Wire 3 new sections into Loaded branch sections 13/14/15 |
| `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` | Add 2 tests verifying new fields populate |
| `app/src/main/res/values/strings.xml` | Add insights/list/timeline strings (header text + insight templates + legend labels) |

### Strings to add

```xml
<!-- Stage 13-C activity views -->
<string name="summary_insights_header">Insights</string>
<string name="summary_insight_meditated_once">Meditated once for %1$s</string>
<string name="summary_insight_meditated_multiple">Meditated %1$d times (longest: %2$s)</string>
<string name="summary_insight_talked_pct">Talked for %1$d%% of the walk</string>
<string name="summary_activities_header">Activities</string>
<string name="summary_activity_talk">Talk</string>
<string name="summary_activity_meditate">Meditate</string>
<string name="summary_timeline_legend_walk">Walk</string>
<string name="summary_timeline_legend_talk">Talk</string>
<string name="summary_timeline_legend_meditate">Meditate</string>
<string name="summary_timeline_pace_label">Pace %1$s</string>
<!-- Compact duration formats for the insights card -->
<string name="summary_compact_duration_seconds">%1$d sec</string>
<string name="summary_compact_duration_minutes">%1$d min</string>
<string name="summary_compact_duration_minutes_seconds">%1$d min %2$d sec</string>
```

## Behavioral details

- **Locale:** all `DateTimeFormatter` calls use `Locale.ENGLISH` (matches Stage 13-A pattern; defer to `Locale.getDefault()` when real translations land).
- **Time-range format** in ActivityListCard rows: `"h:mm a"` per iOS (e.g., "7:27 AM – 7:49 AM"). Uses `Locale.ENGLISH` for AM/PM glyph stability.
- **Numeric formatting:** `String.format(Locale.US, "%d", ...)` for the percentage + count + duration components (Stage 5-A memory: locale-pinned numeric).
- **Tap-toggle on time labels:** `Modifier.clickable(...) { showRelativeTime = !showRelativeTime }` with `MutableInteractionSource` + `indication = null` so the press doesn't ripple the labels.
- **Tap-toggle on segments:** `Modifier.pointerInput(segments) { detectTapGestures { offset -> handleTap(offset.x, size.width.toFloat()) } }`. Re-key `pointerInput` on segments so a fresh segment list rebinds the gesture detector.
- **Selected segment fill alpha 0.95 vs 0.7 unselected:** matches iOS visual delta.
- **Talk segment height 10dp vs meditation 16dp:** iOS visually layers — talk reads as smaller within the bar so meditation isn't fully obscured.
- **`@Immutable` on `TimelineSegment`** + ensure data class — Compose stability inference handles the rest via fields-are-primitives rule.
- **Pace sparkline 40dp height** matches iOS `.frame(height: 40)`.

## Tests

8 pure-function tests + 5 Robolectric Compose tests:

```kotlin
// TimelineSegmentsTest
@Test fun emptyInputs_returnsEmpty()
@Test fun singleTalk_yieldsOneTalkSegmentAtCorrectFraction()
@Test fun singleMeditation_yieldsOneMeditationSegment()
@Test fun bothInOneWalk_returnsBothSortedByStart()
@Test fun boundaryFractions_clampedZeroToOne()  // intervals starting before walkStart, ending after walkEnd
@Test fun nonMeditationActivityType_excluded()  // WALKING/TALKING entity type ignored

// PaceSparklineTest
@Test fun emptySamples_returnsEmpty()
@Test fun underThreeSamples_returnsEmpty()
@Test fun monotonicAcceleration_yieldsAscendingY()  // higher speed → lower y in fraction
@Test fun mixedSpeeds_filtersBelowThreshold()  // samples with speed <= 0.3 dropped
@Test fun maxSpeedZero_returnsEmpty()  // all samples below threshold

// WalkActivityInsightsCardTest (Robolectric)
@Test fun rendersMeditationOnce_withSingularPhrase()
@Test fun rendersMeditationMultiple_withCountAndLongest()
@Test fun rendersTalkPercentage()

// WalkActivityListCardTest (Robolectric)
@Test fun rendersBothTalkAndMeditateRows()
@Test fun sortsEntriesByStartTimestamp()

// WalkSummaryViewModelTest (extend)
@Test fun voiceRecordings_populatedFromRepo()
@Test fun meditationIntervals_filtered_excludesNonMeditationTypes()
```

## Risks + mitigations

- **`pointerInput` segment-tap precision** — narrow segments (< 8dp wide) might be hard to hit on small screens. iOS doesn't widen tap targets. Accept; revisit during device QA. Tooltip provides feedback so user knows hit registered.

- **Pace sparkline allocates `List<PaceSparklinePoint>` on every recompose** — gate via `remember(samples)`. List is bounded at ≤50 elements (the bucket cap) so allocation cost is trivial.

- **Activity bar ZStack ordering** — iOS draws meditation BEFORE talk. With Compose `Box` children, last-drawn sits on top. Order: Meditation segments first, talk segments second. iOS layers correctly because meditation is taller (16dp) and talk is shorter (10dp); shorter draws over taller. Match.

- **Timeline `pointerInput` re-binds on segments change** — `key = segments` ensures fresh gesture binding. Without this, the FIRST segment list captures the closure; subsequent segment changes (rare in summary — segments are immutable post-load) wouldn't take effect. Defensive.

- **List sort stability when two entries have equal startTimestamp** — Kotlin `sortedBy` is stable. Acceptable; matches iOS `.sorted { $0.start < $1.start }` semantic which is also stable.

- **Insights percent rounding** — `(talkMillis * 100 / activeMillis).toInt()` is integer division, drops fractional. `Long * 100` could overflow at 92Pmillis = 2.9M years; not a concern. iOS uses `Int(...)` which is the same semantic.

- **Empty-state guards at caller** — caller-side, NOT inside the composable. Matches Stage 13-A `WalkIntentionCard` pattern.

## Success criteria

- Open Walk Summary on a finished walk with talk + meditation → timeline bar shows colored segments at correct positions; tap on a segment shows tooltip with type + duration; tap labels toggle relative/absolute time; pace sparkline renders.
- Open Walk Summary on talk-only walk → insights shows "Talked for X% of the walk"; activities list shows talk rows.
- Open Walk Summary on meditation-only walk → insights shows "Meditated once/N times for/longest X min"; list shows meditate rows.
- Open Walk Summary on a walk with neither talk nor meditation → insights + list cards both absent.
- Stage 13-A and 13-B regressions pass (TopBar, IntentionCard, JourneyQuote, DurationHero, StatsRow, TimeBreakdown, reveal, count-up, segment polyline, mask, height).

`./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest` clean.
