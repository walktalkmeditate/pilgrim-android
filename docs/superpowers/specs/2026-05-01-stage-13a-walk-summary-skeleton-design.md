# Stage 13-A — Walk Summary skeleton overhaul (iOS parity, layout-first)

## Context

Post-walk Walk Summary screen on Android currently shows ~8 sections in a vertical
label-value layout. iOS `WalkSummaryView` (974 LOC + 12 supporting files) shows 20
sections with hero typography, contextual quote, 3-column stats, 3-card time breakdown,
and a circular masked map. This is the first of 7 sub-stages (13-A through 13-G) to
close the parity gap.

**13-A scope:** Layout skeleton. Replaces the vertical stats list with iOS-faithful
hero typography + contextual sections, repositions Done into the toolbar, and prepares
section ordering for 13-B…G to slot into.

iOS reference: `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift`

## Goal

Walk Summary on Android matches iOS section ordering, typography hierarchy, and
control placement for the layout-only sections. Five new sections (toolbar pattern,
journey quote, duration hero, intention card, stats row 3-col, time breakdown 3-card),
one section dropped (vertical SummaryStats label-value list), one control moved
(Done bottom→top-right).

## Non-goals (deferred to later sub-stages)

- **Reveal animation** (zoomed→bounds, animated distance count-up) → 13-B
- **Map polish** (circular radial mask, route segment colors, height 200dp→320dp) → 13-B
- **Map annotations** (start/end/meditation/voice/waypoint pins) → 13-D
- **Activity timeline bar** + insights + list → 13-C
- **Favicon selector** (Mark this walk: Transformative/Peaceful/Extraordinary) → 13-E
- **Milestone text callout** (separate from grid halo) + **celestial line** + **elevation profile sparkline** → 13-F
- **Details section** (paused-time row when > 0) → 13-G
- **AI Prompts button** (text generation, not LLM — portable but a separate stage) → 13-X
- **Kanji turning marker in title** (`SeasonalMarker` not yet ported) → defer
- **Steps in stats row** (`Walk.steps` column not yet on entity; iOS shows conditionally) → defer to a stage that adds step counter

## Architecture

Six new Composables in `app/src/main/java/.../ui/walk/summary/`:

| Composable | Purpose | iOS reference |
|---|---|---|
| `WalkSummaryTopBar` | Date title (centered) + Done (top-right) | WalkSummaryView.swift:106-116 |
| `WalkIntentionCard` | Leaf icon + walk.intention text, moss-tinted bg | WalkSummaryView.swift:295-312 |
| `WalkJourneyQuote` | Contextual sentence, fog-tinted, centered | WalkSummaryView.swift:314-322, 396-417 |
| `WalkDurationHero` | Large timer (timer style, 48sp) | WalkSummaryView.swift:324-330 |
| `WalkStatsRow` | 3-col Distance \| (Steps?) \| Elevation | WalkSummaryView.swift:463-488 |
| `WalkTimeBreakdownGrid` | 3-card Walk \| Talk \| Meditate, icons + values | WalkSummaryView.swift:535-548 |

Modifies `WalkSummaryScreen.kt` to wire new sections in iOS-faithful order +
remove the bottom Done + remove vertical `SummaryStats`. Modifies
`WalkSummaryViewModel.kt` to expose talk/meditate/walk durations + ascend.

## VM additions

Add to `WalkSummary` data class:

```kotlin
@Immutable
data class WalkSummary(
    // existing fields…
    val talkMillis: Long,        // sum of voiceRecording.durationMillis
    val activeMillis: Long,      // totalElapsedMillis - totalPausedMillis (matches iOS walk.activeDuration)
    val ascendMeters: Double,    // sum of positive altitude deltas across samples
)
```

Existing fields cover:
- `activeWalkingMillis` — Walk card value (iOS `walkDuration = activeDuration - meditateDuration`; Android already computes this exclusive of pause + meditate)
- `totalMeditatedMillis` — Meditate card value
- `distanceMeters` — distance stat
- `totalElapsedMillis` — wall-clock total (start → end)

**Duration Hero arithmetic** (verified against screenshot 04: Walk 2:50:00 + Meditate 25:00 = 3:15:00 = hero):
iOS `walk.activeDuration` = `endTimestamp - startTimestamp - pausedTime` (paused-excluded, meditate-included). Android does NOT yet expose this. New `activeMillis = totalElapsedMillis - totalPausedMillis` field added to `WalkSummary` and used by Hero. Walk-card stays on `activeWalkingMillis` (which is `activeMillis - totalMeditatedMillis`).

`buildState` adds (with hoisting — see [Hoisting](#hoisting) below):

```kotlin
val voiceRecordings = repository.voiceRecordingsFor(walkId)
val altitudeSamples = repository.altitudeSamplesFor(walkId)

val talkMillis = voiceRecordings.sumOf { it.durationMillis }
val activeMillis = totalElapsed - totals.totalPausedMillis
val ascendMeters = computeAscend(altitudeSamples)
```

### Hoisting

`voiceRecordingsFor` + `altitudeSamplesFor` currently called INSIDE the etegami `runCatching` block (lines 637-656 of WalkSummaryViewModel.kt). Hoist BOTH calls outside the block so they're reused for `talkMillis` + `ascendMeters`. The etegami `composeEtegamiSpec` reuses the hoisted locals — net zero extra repo calls, just relocation. `activityIntervals` stays inside runCatching (only consumed by etegami).

Where `computeAscend(samples: List<AltitudeSample>): Double` is a new pure function:

```kotlin
internal fun computeAscend(samples: List<AltitudeSample>): Double =
    if (samples.size < 2) 0.0
    else samples.zipWithNext().sumOf { (a, b) ->
        (b.altitudeMeters - a.altitudeMeters).coerceAtLeast(0.0)
    }
```

Lives in a new file `data/walk/ElevationCalc.kt` (top-level fun, no class).

## Section detail

### `WalkSummaryTopBar`

```kotlin
@Composable
fun WalkSummaryTopBar(
    startTimestamp: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
)
```

- Centered title: `formatLongDate(startTimestamp)` — `LocalDate` of `startTimestamp` formatted via `DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault())` (long form: "March 16, 2026"). Style `pilgrimType.heading` (17sp SemiBold Cormorant), color `pilgrimColors.ink`.
- Trailing button: `TextButton(onClick = onDone) { Text("Done", color = pilgrimColors.stone) }` — text-button style (no fill), `pilgrimColors.stone` text color. Uses `stringResource(R.string.summary_action_done)` (existing key — keep).
- Height matches Material 3 small TopAppBar (~64dp). Uses `Row` with `Box(weight=1f)` for centered title + trailing button — NOT `TopAppBar` (which expects nav-host / Activity-edge integration; the screen is composed inside a sheet/modal-like container).
- Backed by `pilgrimColors.parchment` to match iOS `.toolbarBackground(Color.parchment, for: .navigationBar)`.

### `WalkIntentionCard`

```kotlin
@Composable
fun WalkIntentionCard(intention: String, modifier: Modifier = Modifier)
```

Caller responsible for `intention.isNotBlank()` guard — does not render anything when string is empty. Inside:
- Vertical Column, `PilgrimSpacing.small` between leaf icon and text.
- Leaf icon: `Icons.Rounded.Eco` (Material), tint `pilgrimColors.moss`, size 16dp (caption-equivalent).
- Text: `pilgrimType.body`, `pilgrimColors.ink`, `textAlign = TextAlign.Center`.
- Container: `Modifier.fillMaxWidth()`, padding `PilgrimSpacing.normal`, `pilgrimColors.moss.copy(alpha = 0.06f)` background, `RoundedCornerShape(PilgrimCornerRadius.normal)`.

### `WalkJourneyQuote`

Two-layer split: pure classifier (testable without Compose) + composable that resolves the case to a stringResource.

```kotlin
internal sealed class JourneyQuoteCase {
    data object WalkTalkMeditate : JourneyQuoteCase()
    data object MeditateShort : JourneyQuoteCase()
    data class MeditateWithDistance(val distanceMeters: Double) : JourneyQuoteCase()
    data object TalkOnly : JourneyQuoteCase()
    data object LongRoad : JourneyQuoteCase()
    data object SmallArrival : JourneyQuoteCase()
    data object QuietWalk : JourneyQuoteCase()
}

internal fun classifyJourneyQuote(
    talkMillis: Long,
    meditateMillis: Long,
    distanceMeters: Double,
): JourneyQuoteCase {
    val hasTalk = talkMillis > 0L
    val hasMed = meditateMillis > 0L
    val distanceKm = distanceMeters / 1000.0
    return when {
        hasTalk && hasMed -> JourneyQuoteCase.WalkTalkMeditate
        hasMed && distanceKm < 0.1 -> JourneyQuoteCase.MeditateShort
        hasMed -> JourneyQuoteCase.MeditateWithDistance(distanceMeters)
        hasTalk -> JourneyQuoteCase.TalkOnly
        distanceKm > 5.0 -> JourneyQuoteCase.LongRoad
        distanceKm > 1.0 -> JourneyQuoteCase.SmallArrival
        else -> JourneyQuoteCase.QuietWalk
    }
}

@Composable
fun WalkJourneyQuote(
    talkMillis: Long,
    meditateMillis: Long,
    distanceMeters: Double,
    distanceUnits: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val text = when (val case = classifyJourneyQuote(talkMillis, meditateMillis, distanceMeters)) {
        JourneyQuoteCase.WalkTalkMeditate -> stringResource(R.string.summary_quote_walk_talk_meditate)
        JourneyQuoteCase.MeditateShort -> stringResource(R.string.summary_quote_meditate_short_distance)
        is JourneyQuoteCase.MeditateWithDistance -> stringResource(
            R.string.summary_quote_meditate_with_distance,
            WalkFormat.distance(case.distanceMeters, distanceUnits),
        )
        JourneyQuoteCase.TalkOnly -> stringResource(R.string.summary_quote_talk_only)
        JourneyQuoteCase.LongRoad -> stringResource(R.string.summary_quote_long_road)
        JourneyQuoteCase.SmallArrival -> stringResource(R.string.summary_quote_small_arrival)
        JourneyQuoteCase.QuietWalk -> stringResource(R.string.summary_quote_quiet_walk)
    }
    Text(text, /* style + color + align */ ...)
}
```

Six cases ported verbatim from `WalkSummaryView.swift:396-417`. Strings (Android XML format strings use `%1$s` positional placeholder for portability across translations):

```xml
<string name="summary_quote_walk_talk_meditate">You walked, spoke your mind, and found stillness.</string>
<string name="summary_quote_meditate_short_distance">A moment of stillness, right where you are.</string>
<string name="summary_quote_meditate_with_distance">A journey inward, %1$s along the way.</string>
<string name="summary_quote_talk_only">You walked and gave voice to your thoughts.</string>
<string name="summary_quote_long_road">A long road, well traveled.</string>
<string name="summary_quote_small_arrival">Every step, a small arrival.</string>
<string name="summary_quote_quiet_walk">A quiet walk, a gentle return.</string>
```

Visual: `pilgrimType.body`, `pilgrimColors.fog`, `textAlign = TextAlign.Center`, horizontal padding `PilgrimSpacing.big`. No background.

Tests target `classifyJourneyQuote()` only — pure function, no Compose host needed.

### `WalkDurationHero`

```kotlin
@Composable
fun WalkDurationHero(durationMillis: Long, modifier: Modifier = Modifier)
```

- Single Text composable.
- Style `pilgrimType.timer` (already 48sp Light per existing `Type.kt:35`).
- Color `pilgrimColors.ink`.
- Format via `WalkFormat.duration(durationMillis)` (existing) — already produces `H:MM:SS` for ≥ 1hr, `M:SS` otherwise.
- Centered: `Modifier.fillMaxWidth()`, then text uses `textAlign = TextAlign.Center`.

### `WalkStatsRow`

```kotlin
@Composable
fun WalkStatsRow(
    distanceMeters: Double,
    ascendMeters: Double,
    units: UnitSystem,
    modifier: Modifier = Modifier,
)
```

(Steps deferred — when added later, this signature gains `stepCount: Int?` with conditional rendering.)

`Row` with `horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.big)`:

For each stat:
```
Column(horizontalAlignment = CenterHorizontally, modifier = Modifier.weight(1f)):
  Text(value, style = pilgrimType.statValue, color = pilgrimColors.ink)
  Spacer(2.dp)
  Text(label, style = pilgrimType.statLabel, color = pilgrimColors.fog)
```

- Distance value: `WalkFormat.distance(distanceMeters, units)`
- Elevation value: `WalkFormat.altitude(ascendMeters, units)` (existing helper — handles Imperial→ft, Metric→m)

Conditional rendering:
- Distance: always shown
- Elevation: shown only when `ascendMeters > 1.0` (matches iOS `walk.ascend > 1`)

If only Distance qualifies, the row's single stat takes the full width with text centered horizontally. With both, two equal-weight columns. Reuses the same `UnitSystem` enum already on the screen — no new altitude-unit type. Altitude semantics on Android: when the user's `distanceUnits` is `Imperial`, elevation displays in feet; `Metric` → meters. Matches iOS de-facto coupling on this summary (the iOS `UserPreferences.altitudeMeasurementType` is a separate pref but is generally aligned with distance pref; explicit altitude pref is deferred to a future stage).

### `WalkTimeBreakdownGrid`

```kotlin
@Composable
fun WalkTimeBreakdownGrid(
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    modifier: Modifier = Modifier,
)
```

Three equal-width cards in a Row (iOS uses `LazyVGrid` with 3 flexible columns — Compose Row + weight produces the same effect without LazyVGrid overhead).

Each card:
```
Card(colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary)):
  Column(horizontalAlignment = CenterHorizontally, verticalArrangement = spacedBy(PilgrimSpacing.small)):
    Icon(painter = ..., tint = pilgrimColors.stone, size = 24dp)
    Text(formatDuration(millis), style = pilgrimType.statValue, color = pilgrimColors.ink, maxLines = 1)
    Text(label, style = pilgrimType.statLabel, color = pilgrimColors.fog, maxLines = 1)
  .padding(PilgrimSpacing.normal)
  .fillMaxWidth (via weight)
```

Icons (Material extended icons inline — no new drawable XMLs in 13-A):
- Walk: `Icons.Rounded.DirectionsWalk` (port of iOS `figure.walk`)
- Talk: `Icons.Rounded.GraphicEq` (port of iOS `waveform`)
- Meditate: `Icons.Rounded.SelfImprovement` (port of iOS `brain.head.profile`; if Stage 5 added a meditation drawable already, prefer it)

If any of these Material icons aren't included in `androidx.compose.material:material-icons-extended`, add the dep — most projects already pull it via Compose BOM. Implementer's first task should `grep` for any of these icons in the existing codebase to confirm availability before depending on them.

Card values shown unconditionally — even at 0 the card renders with `0:00`. Matches iOS where `Walk` always shows (active walking duration is always > 0 for any finished walk). Alternative: hide a card when value is 0. **Decision: always show all three** — matches iOS visual consistency (the screenshot shows all three even when one is 0).

## Section ordering after 13-A

Replace the current `WalkSummaryScreen.kt` Loaded branch with this order (placeholders in italics for future stages):

1. Map (existing — height stays 200dp until 13-B)
2. Photo Reliquary (existing)
3. **WalkIntentionCard** (NEW — guarded by `walk.intention?.isNotBlank() == true`)
4. _Elevation profile (13-F)_
5. **WalkJourneyQuote** (NEW)
6. **WalkDurationHero** (NEW)
7. _Milestone text callout (13-F)_
8. **WalkStatsRow** (NEW)
9. WalkSummaryWeatherLine (existing — Stage 12)
10. _Celestial line (13-F)_
11. **WalkTimeBreakdownGrid** (NEW)
12. _Favicon selector (13-E)_
13. _Activity timeline bar (13-C)_
14. _Activity insights (13-C)_
15. _Activity list (13-C)_
16. VoiceRecordingsSection (existing — Stage 2-E)
17. _AI Prompts button (13-X)_
18. _Details section (13-G)_
19. WalkLightReadingCard (existing — Stage 6-B)
20. WalkEtegamiCard + share row + WalkShareJourneyRow (existing — Stage 7-D + 8-A)

Done button moves into `WalkSummaryTopBar` at the top. Bottom `Button(onClick = onDone)` deletes.

`Spacer(Modifier.height(PilgrimSpacing.normal))` (16dp) between every two adjacent visible sections — matches iOS `VStack(spacing: Constants.UI.Padding.normal)` from `WalkSummaryView.swift:55`. Using `big` (24dp) would visibly diverge.

## Drops

Delete from `WalkSummaryScreen.kt`:
- `SummaryStats` private composable + `SummaryRow` private composable.
- `LoadingRow` private composable's misuse (CircularProgressIndicator in Row alone) — can stay as-is; not Stage 13-A's concern.
- Bottom `Button(onClick = onDone) { Text("Done") }` block.
- The `Text(stringResource(R.string.summary_title), ..., displayMedium)` at the top of the column — replaced by `WalkSummaryTopBar`.

Delete from `res/values/strings.xml`:
- `<string name="summary_title">Walk complete.</string>` — only consumer is the deleted Text above; verified via grep, no other call sites.

Keep:
- `<string name="summary_action_done">Done</string>` — used by `WalkSummaryTopBar`'s trailing TextButton.

The dropped vertical-list rows (Pace, Paused Time, Waypoints) move out of summary entirely. Pace + Waypoints have NO equivalent placement in iOS Walk Summary. Paused-time relocates to a "Details" section in 13-G. Stage 13-A does NOT carry Pace + Waypoints anywhere — they disappear from the summary, available only via the existing `WalkStatsSheet` (mid-walk stats sheet).

## Tests

New test files:

### `app/src/test/java/.../ui/walk/summary/JourneyQuoteCaseTest.kt`

Tests `classifyJourneyQuote()` only — pure function, no Compose host, no string resources:

```kotlin
@Test fun walkAndTalkAndMeditate_returnsWalkTalkMeditate()
@Test fun meditateOnly_underHundredMeters_returnsMeditateShort()
@Test fun meditateOnly_overHundredMeters_returnsMeditateWithDistance_carryingMeters()
@Test fun talkOnly_returnsTalkOnly()
@Test fun walkOnly_overFiveKm_returnsLongRoad()
@Test fun walkOnly_overOneKm_returnsSmallArrival()
@Test fun walkOnly_underOneKm_returnsQuietWalk()
```

### `app/src/test/java/.../data/walk/ElevationCalcTest.kt`

```kotlin
@Test fun emptySamples_returnsZero()
@Test fun singleSample_returnsZero()
@Test fun monotonicAscent_sumsDeltas()
@Test fun mixedDeltas_sumsOnlyPositive()
@Test fun negativeOnly_returnsZero()
```

### `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` (extend existing)

```kotlin
@Test fun talkMillis_sumsVoiceRecordingDurations()
@Test fun ascendMeters_sumsPositiveAltitudeDeltas()
@Test fun ascendMeters_zeroForFlatRoute()
@Test fun activeMillis_excludesPausedTime_includesMeditation()
```

### Robolectric Compose smoke tests

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HiltTestApplication::class)
class WalkSummaryTopBarTest {
    @Test fun rendersFormattedDate()
    @Test fun doneButtonInvokesCallback()
}

class WalkIntentionCardTest {
    @Test fun rendersIntentionText()
}

class WalkStatsRowTest {
    @Test fun rendersDistanceAlone_whenNoElevation()
    @Test fun rendersDistanceAndElevation_whenAscendOverThreshold()
}

class WalkTimeBreakdownGridTest {
    @Test fun rendersAllThreeCards_evenWhenZero()
    @Test fun durationsFormatCorrectly()
}
```

Use existing test infrastructure (`HiltTestApplication`, `composeTestRule`).

## Files

### Create

| Path | Purpose |
|---|---|
| `app/src/main/java/.../ui/walk/summary/WalkSummaryTopBar.kt` | Top bar |
| `app/src/main/java/.../ui/walk/summary/WalkIntentionCard.kt` | Intention card |
| `app/src/main/java/.../ui/walk/summary/WalkJourneyQuote.kt` | Quote + `journeyQuoteText` |
| `app/src/main/java/.../ui/walk/summary/WalkDurationHero.kt` | Hero timer |
| `app/src/main/java/.../ui/walk/summary/WalkStatsRow.kt` | 3-col mini-stats |
| `app/src/main/java/.../ui/walk/summary/WalkTimeBreakdownGrid.kt` | 3-card grid |
| `app/src/main/java/.../data/walk/ElevationCalc.kt` | `computeAscend` |
| `app/src/test/java/.../ui/walk/summary/JourneyQuoteCaseTest.kt` | Quote parity tests |
| `app/src/test/java/.../data/walk/ElevationCalcTest.kt` | Ascend tests |
| `app/src/test/java/.../ui/walk/summary/WalkSummaryTopBarTest.kt` | Top-bar tests |
| `app/src/test/java/.../ui/walk/summary/WalkIntentionCardTest.kt` | Intention tests |
| `app/src/test/java/.../ui/walk/summary/WalkStatsRowTest.kt` | Stats row tests |
| `app/src/test/java/.../ui/walk/summary/WalkTimeBreakdownGridTest.kt` | Time-breakdown tests |

### Modify

| Path | Change |
|---|---|
| `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` | Wire all new sections in iOS order; drop `SummaryStats`, `SummaryRow`, bottom Done button, top "Summary" Text |
| `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` | Add `talkMillis` + `activeMillis` + `ascendMeters` to `WalkSummary`; hoist `voiceRecordingsFor` + `altitudeSamplesFor` outside etegami runCatching; compute new fields in `buildState` |
| `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` | Add 4 tests (talk, ascend×2, active) |
| `app/src/main/res/values/strings.xml` | Add 7 quote strings; delete `summary_title` |

## Behavioral details

- **Intention card guard:** Caller-side null/blank check. Component itself does not gate.
- **Stats row weights:** Use `Modifier.weight(1f)` per child. With N=1 the single stat takes full width with text centered horizontally (Column's `horizontalAlignment = CenterHorizontally`); with N=2 each gets half. Never use `fillMaxWidth` on individual children — that breaks weighted distribution.
- **Time-breakdown card height:** Cards size to content. Tallest card determines row height naturally via Row's IntrinsicSize semantics if needed; default to `Modifier.height(IntrinsicSize.Min)` on the parent Row OR rely on identical content (icon + 1-line value + 1-line label) keeping heights aligned. Prefer the second — simpler, matches iOS.
- **Done button label:** Just "Done", no icon. Style: text button, `pilgrimColors.stone` text. Press feedback via M3 default ripple.
- **Date title locale:** Use `Locale.getDefault()` (per Stage 3-A memory: "user-facing day/month names use `Locale.getDefault()`; numeric formatting uses `Locale.US`"). Long-form `"MMMM d, yyyy"` matches iOS `.dateStyle = .long`.
- **Time zone:** `ZoneId.systemDefault()` for the date conversion (matches existing pattern in WalkSummaryViewModel `LightReading.from(..., zoneId = ZoneId.systemDefault())`).

## Risks + mitigations

- **Duration Hero semantics.** Verified by reading the screenshot: Walk 2:50 + Meditate 25:00 = 3:15:00 = hero. iOS `walk.activeDuration` is paused-excluded but meditate-included. Android adds new `activeMillis = totalElapsedMillis - totalPausedMillis` field for parity. Implementer test: walk-only test scenario (no meditation, no talk) — hero = activeMillis; and pause-and-resume scenario — hero excludes paused gap. **No fallback needed** — formula is direct.

- **Material icon vs. vector drawable for time-breakdown.** Material's `DirectionsWalk` / `GraphicEq` / `SelfImprovement` are close enough to iOS SFSymbols. **Decision: use Material icons for 13-A**; defer custom vector drawables to a polish pass (13-Z) if visual review flags them.

- **Altitude unit coupling.** Stage 13-A reuses existing `UnitSystem` enum (Metric/Imperial) for elevation display via `WalkFormat.altitude(meters, units)`. iOS has a separate `UserPreferences.altitudeMeasurementType`; in practice it's almost always aligned with distance pref. Adding a separate Android `altitudeUnits` DataStore key is deferred to a future stage. **No new enum, no `AltitudeUnit` type.**

## Success criteria

- Walk Summary shows date title (centered, top) + Done (top-right, replaces bottom button)
- Section order from top: Map → Reliquary → Intention (when set) → Quote → Hero → Stats Row → Weather → Time Breakdown → Voice Recordings → Light Reading → Etegami + Share Journey
- Vertical SummaryStats list is gone (Pace + Waypoints + Paused-time disappear from summary)
- Distance hero displays the wall-clock duration in `H:MM:SS` for ≥1hr, `M:SS` otherwise
- Stats row shows 1, 2, or 3 stats balanced with `weight(1f)`
- Time breakdown cards show all three even at 0
- Quote text matches iOS for the same input across all 6 cases
- Existing functionality (Stage 12 weather, 7-A reliquary, 6-B light reading, 7-D etegami, 8-A journey share, 4-B reveal overlay, 2-E voice recordings) continues to work unchanged

Tests:
- 7 JourneyQuoteCaseTest cases (all 6 iOS cases + boundary at 0.1km)
- 5 ElevationCalcTest cases
- 4 WalkSummaryViewModelTest cases (talk + ascend×2 + active)
- 8 Robolectric Compose smoke tests across the 4 new top-level composables
- All existing WalkSummaryViewModelTest + WalkSummaryScreenTest cases pass unchanged

`./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` clean.
