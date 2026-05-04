# Stage 14 — Journal / Pilgrim Log iOS-parity port

## Context

Phase 1 UNDERSTAND audited the iOS `HomeView` / `InkScrollView` stack against
Android's current Stage 3-A→3-F + 9.5-A Home surface and produced a 23-gap
list. The Android Journal today shows opaque Material 3 cards over a static
calligraphy-thread Canvas — the cards carry per-walk text, the thread is
decorative, and the dots iOS draws **on the thread** are absent. The iOS
surface is dot-primary: the thread connects walks, dots ARE the walks (with
favicon glyph + activity arcs + halo + newest-walk ripple), and rich
information lives in an on-tap expand card. Around the thread iOS layers
date dividers, lunar markers (full + new moons), milestone bars (every
100 km / 500 km / 1 Mm / Nx Mm), turning-day banner, journey-summary
cyclable header, animated scenery (7 shapes, 35% per-walk chance), scroll
haptics, cascading fade-in, and a GoshuinFAB whose icon is the latest
walk's seal thumbnail.

This stage closes that gap. We do **not** port iOS verbatim — Android picks
up six documented deviations (one inherited from Stage 3-C, three Android-
idiomatic, two scope cuts) — but algorithm constants, opacity gradients,
delay timings, threshold magic numbers, and English copy are character-
perfect ports.

This is the LAST big port stage on the parity-frozen-at-iOS-v1.5.0 (`db4196e`)
plan. Any iOS work landed after that tag is out of scope.

## Goal

Replace the Stage 3-A card list with a faithful Android port of `InkScrollView`:
LazyColumn-virtualized walks rendered as dots-on-a-thread, with chrome (top
title, journey-summary cycler, expand sheet, GoshuinFAB seal thumbnail),
overlays (turning banner, lunar markers, milestone bars, date dividers), and
polish (scenery, fade-in, ripple, scroll haptics, reduce-motion). One PR,
~15-25 implementation tasks bundled into 4 internal task-buckets (14-A
foundation / 14-B chrome / 14-C overlays / 14-D polish).

## Non-goals

- **iOS DEBUG seed/clear menu** — Stage 14 ships without it. Android already
  has `androidTest`-only seed paths sufficient for QA. (Audit Gap 2.)
- **Long-press preview** — vestigial on iOS (Audit Gap 13). The expand card
  on tap covers the use case.
- **Per-walk live unit toggle reactivity beyond what `HomeViewModel` already
  combines** — units already flow through `unitsPreferences.distanceUnits`
  combine; Stage 14 reuses that path. iOS's `id(unitKey)` viewport reset is
  not needed because Compose recomposes naturally on the StateFlow flip.
- **Live `TimelineView(.animation)` scenery framerates above 30 fps** — iOS
  uses 30 fps for tree/grass/butterfly, 20 fps for lantern/torii, 15 fps for
  moon/mountain. Android matches those rates verbatim via `withFrameNanos`
  throttling, but does NOT exceed them (no 60/120 fps targeting).
- **Verbatim iOS `simultaneousGesture` long-press attached to dots** — Gap
  13 dropped, so dots are tap-only; long-press preview overlay is not built.
- **Exact iOS `truncatingRemainder` byte-for-byte equivalence on the hash
  function** — Stage 3-C decision (FNV variant differs because Swift hashes
  raw UUID bytes vs Kotlin hashing UTF-16 code units). Carries forward.
- **`@retroactive Identifiable` UUID sentinel pattern** — Android uses
  `walkId: Long` directly from Room; UUID sentinel is iOS-CoreData ergonomics.
- **iOS planetary-hour symbol + moon-sign symbol composite text in expand
  card header** — only when `celestialAwarenessEnabled = true`. Android uses
  the same gating via `PracticePreferencesRepository`. SF Symbol planetary-
  hour glyphs + zodiac symbols port to Unicode codepoints already shipped
  in Stage 13-Cel.
- **iOS `RippleEffectView` 1/10 fps `TimelineView` Canvas** — Android matches
  via `withFrameNanos` at 100 ms cadence (10 fps); does NOT step up to 60
  fps. Reduce-motion collapses to a static stroke (matches iOS).
- **Parity beyond v1.5.0 (`db4196e`)** — anything iOS shipped after that
  commit is OUT OF SCOPE per `CLAUDE.md` parity-frozen rule. Phase 1 audit
  confirmed no post-v1.5.0 Journal additions to flag.

## Architecture

### Layout shape

The Stage 3-A `Column { card, card, card }` over a `CalligraphyPath` Canvas
is replaced by a **LazyColumn** whose items emit zero-height "anchor" rows
positioned above a single `behindCanvas` that draws **everything**: ribbon
segments, walk dots (favicon + arcs + halo), distance labels, scenery,
date dividers, lunar markers, milestone markers. The LazyColumn handles
virtualization; `behindCanvas` reads `LazyListState.firstVisibleItemIndex`
+ `firstVisibleItemScrollOffset` to compute viewport-relative draw Ys.

Cards are **gone**. Per-walk content collapses to:
- The dot itself (8-22 dp, color-coded by season + turning-day, favicon
  glyph overlay, activity-duration arcs, optional newest ripple, optional
  shared-ring stroke).
- A small distance label (`pilgrimType.micro`, `fog @ 0.5α`) at the dot's
  Y position offset 32 dp on the side OPPOSITE the dot's X meander.
- A month/year date divider (`pilgrimType.caption`, `fog @ 0.5α`) at the
  first walk-row of each new month, anchored to the OPPOSITE side from
  the dot.

Tap a dot → Material 3 ModalBottomSheet (skipPartiallyExpanded = false)
opens the **expand card**: `[footprint] [favicon icon] [date+time]
[shared link] [planetary-hour + moon-sign Unicode] [weather glyph]` row,
1 dp seasonal divider, 3-stat row (distance / duration / pace), mini
activity bar (capsule with 3 frac segments), activity pills (walk +
optional talk + optional meditate), then a `Button("View details →")`
that closes the sheet and navigates to `WalkSummary(walkId)`. The
ModalBottomSheet replaces iOS's overlay-from-bottom transition; iOS's
ultraThinMaterial backdrop maps to `BottomSheetDefaults.containerColor`
(`Color.parchmentSecondary @ surfaceTint blend`).

### Data flow

`HomeViewModel` switches from emitting `HomeWalkRow` (single per-walk row)
to emitting `WalkSnapshot` (the iOS-parity per-walk struct + the journey-
summary aggregates). The flow becomes:

```kotlin
val journalState: StateFlow<JournalUiState> = combine(
    repository.observeAllWalks(),
    unitsPreferences.distanceUnits,
    cachedShareStore.observeAll(),                  // Map<uuid, CachedShare>
    practicePreferences.celestialAwarenessEnabled,  // for expand-card moon-sign
) { walks, units, shareCache, celestialEnabled ->
    val finished = walks.filter { it.endTimestamp != null }
    if (finished.isEmpty()) return@combine JournalUiState.Empty
    // buildSnapshots issues per-walk DAO reads (IO) for route samples + activitySumsFor;
    // flowOn(IO) below handles dispatch. The cumulative-distance reduce + per-snapshot
    // dotSize/haptic-position precompute is CPU work — wrap that subroutine in
    // withContext(Default) inside buildSnapshots, NOT here, so the IO reads aren't
    // bounced through the Default pool.
    buildSnapshots(finished, units, shareCache, nowMs = clock.now())
}.flowOn(Dispatchers.IO)
 .stateIn(viewModelScope, Eagerly, JournalUiState.Loading)
```

`buildSnapshots(walks, ...)` per-walk:
1. Reads route samples → haversine distance (already in
   `walkDistanceMeters(samples)`).
2. Issues an aggregate-sum query through the new
   `WalkRepository.activitySumsFor(walkId)` returning
   `Map<ActivityType, Long>` (sum of `end - start` ms per type). NO schema
   change — pure DAO `@Query` aggregating `activity_intervals`.
3. Reduces cumulative distance left-to-right (oldest → newest), assigns to
   the snapshot, then reverses the list so the LazyColumn renders newest
   first.
4. Resolves `isShared` from `shareCache[walk.uuid]?.isExpiredAt(nowMs) ==
   false` (and the cache key existing).
5. Picks `favicon`, `weatherCondition` from the existing `Walk` columns.
6. Pre-computes haptic dot-Y positions via the **dotSize** formula so the
   `ScrollHapticEngine` Android equivalent doesn't need to re-derive on
   every viewport offset emit.

The journey-summary aggregates (`totalTalkSec`, `totalMeditateSec`,
`talkers`, `meditators`, `firstWalkStartMs`) live on the same VM emission
to avoid a second collect on the same flow.

### iOS-Android type mapping

| iOS | Android |
|---|---|
| `WalkSnapshot: Identifiable` (Swift struct) | `data class WalkSnapshot` (`@Immutable`) |
| `walkSnapshots: [WalkSnapshot]` | `journalState.snapshots: List<WalkSnapshot>` |
| `walks: [Walk]` (CoreStore) | `journalState.walks: List<Walk>` (Room) |
| `loadWalks()` (manual fetch) | `combine(observeAllWalks, ...)` Flow |
| `Calendar.current.dateComponents` | `LocalDate` / `YearMonth` (java.time) |
| `walk.activeDuration` | `(walk.endTimestamp - walk.startTimestamp) / 1000.0` |
| `walk.talkDuration`, `walk.meditateDuration` (computed) | `activitySumsFor(walkId)[TALKING]`, `activitySumsFor(walkId)[MEDITATING]` |
| `walk.routeData.first?.latitude` (hemisphere seed) | `repository.locationSamplesFor(walk.id).firstOrNull()?.latitude` (already used by `WalkViewModel.finishWalk`) |
| `ScrollViewReader` + `onScrollGeometryChange` | `LazyListState.firstVisibleItemIndex` + `firstVisibleItemScrollOffset` |
| `sensoryFeedback(.impact(weight: .light))` | `VibrationEffect.Composition.PRIMITIVE_TICK` (intensity 1.0) |
| `sensoryFeedback(.impact(weight: .medium))` | `PRIMITIVE_CLICK` (intensity 0.7) |
| `sensoryFeedback(.impact(weight: .heavy, intensity: 0.8))` | `PRIMITIVE_CLICK` (intensity 1.0) + `PRIMITIVE_LOW_TICK` |
| `UIAccessibility.isReduceMotionEnabled` | `AnimationsScale` from `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` (existing accessor) |
| `TimelineView(.animation(minimumInterval: 1/30))` | `produceState + withFrameNanos { now -> if (now - last >= 33 ms) emit }` |
| `phaseAnimator([false, true])` | `rememberInfiniteTransition + animateFloat` |
| `RadialGradient` (SwiftUI) | `Brush.radialGradient` (Compose) |
| `Path { … }` SwiftUI | `androidx.compose.ui.graphics.Path` |
| `AnyShape` type-eraser | `(DrawScope.(Size) -> Unit)` lambda |
| `.sheet(item: $expandedSnapshot)` | `ModalBottomSheet(onDismissRequest = ...)` |
| `.toolbar { ToolbarItem(placement: .principal) { Text("Pilgrim Log") } }` | `CenterAlignedTopAppBar(title = { Text("Pilgrim Log") }, colors = transparent over parchment)` |
| `GoshuinFAB.thumbnail: UIImage?` | `ImageBitmap?` rendered via off-screen Canvas snapshot of `SealRenderer` at 44 dp |
| `SealGenerator.thumbnail(from: input)` | `SealRenderer.renderToImageBitmap(spec, sizePx)` (new helper, drawn on `Dispatchers.Default`) |
| `WalkFavicon.icon` (SF Symbol name) | `WalkFavicon.materialIcon` (Material `ImageVector`) — already mapped in Stage 4-D |
| `WeatherCondition.icon` (SF Symbol) | `WeatherCondition.materialIcon` (already exists from Stage 12-A) |
| `Color.parchment`, `.ink`, `.fog`, `.stone`, `.moss`, `.rust`, `.dawn` | `pilgrimColors.*` tokens (already mapped) |
| `SeasonalColorEngine.seasonalColor(named:intensity:on:)` | `SeasonalInkFlavor.X.toBaseColor() + SeasonalColorEngine.applySeasonalShift(...)` (already exists) |
| `TurningDayService.turning(for:hemisphere:)` | new `TurningDayService.turningFor(localDate, hemisphere)` Kotlin object |
| `TurningDayService.turningForToday()` | `turningFor(LocalDate.now(zone), hemisphere)` |
| `SeasonalMarker.color` (asset color) | `SeasonalMarker.turningColor()` extension reading from `PilgrimColors.turningJade/Gold/Claret/Indigo` |
| `SeasonalMarker.kanji` | `SeasonalMarker.kanji: String?` extension (verbatim copy) |
| `SeasonalMarker.bannerText` | `SeasonalMarker.bannerTextRes: Int?` (`R.string.turning_equinox_banner` / `R.string.turning_solstice_banner`) |
| `LunarPhase.current(date:)` | `MoonCalc.moonPhase(instant)` (already exists) — note iOS uses `synodicMonth = 29.53058770576` while Android uses `29.530588770576` (carry-over from iOS port; trailing-zero only, no behavior difference) |
| `SceneryGenerator.scenery(for:)` | `object SceneryGenerator.scenery(snapshot)` (verbatim) |
| `SceneryItemView` (TimelineView per type) | `SceneryItem(type, tintColor, sizePx, walkDate)` Composable |
| `SceneryType` enum + `AnyShape` | `enum class SceneryType` + per-type Compose `Path` builder fns |
| `MilestoneMarkerView` | `@Composable MilestoneMarker(width, distance)` |
| `ToriiGateShape` | `toriiGatePath(rect)` extension fn (verbatim path math) |
| `FootprintShape` | `footprintPath(rect)` extension fn |

### Sub-stage groupings

**14-A foundation** (data + virtualization + dot composable):
WalkSnapshot data class, `activitySumsFor` DAO, `HomeViewModel` rewrite,
LazyColumn migration, WalkDot composable (size formula + favicon overlay
+ activity arcs + halo + shared-ring), per-walk distance label, scroll
haptics state-machine + `VibrationEffect.Composition` dispatcher. Reduce-
motion guards on the haptics path.

**14-B chrome** (top + bottom + journey-summary + expand card):
Top "Pilgrim Log" `CenterAlignedTopAppBar`, journey-summary 3-state
cyclable header (above-list overlay positioned at `y = 16 dp +
turningOffset`), expand-card ModalBottomSheet content, GoshuinFAB seal
thumbnail rendering pipeline (off-screen ImageBitmap of the latest walk's
SealSpec), minimal-row layout swap (delete `HomeWalkRowCard` wholesale).

**14-C overlays** (banner + service + lunar + milestones + date
dividers):
`TurningDayService` Kotlin object port, top-of-scroll banner Composable,
lunar markers (full + new moon interpolated between dots by date),
milestone markers (100k/500k/1M/Nx1M cumulative thresholds), month/year
date dividers (drawn opposite the dot at the first walk per
year-month). Additionally adds `SeasonalMarker.kanji` and
`SeasonalMarker.bannerTextRes` extensions + `R.string.turning_*` strings.

**14-D polish** (animation + accessibility + empty state):
Scenery (7 types — tree/grass/lantern/butterfly/mountain/torii/moon —
each as a sub-Composable; scenery animation throttled by `withFrameNanos`
at iOS framerates), parallax horizontal offset by viewport-vertical
distance, cascading fade-in (segment delay = `index * 30 + 200 ms`,
duration 1200 ms; dot delay = `index * 30 + 300 ms`, duration 500 ms),
newest-walk ripple (Canvas with breathing circles + glow at 10 fps),
reduce-motion checks on ALL animation entry points (collapse to static),
empty-state tail + stone dot composable + `R.string.home_empty_begin`
("Begin"). Replaces the `R.string.home_empty_message` Text.

## Files to create

### 14-A foundation

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshot.kt` —
  `@Immutable data class` with id (Long), uuid, startMs, distanceM,
  durationSec, averagePaceSecPerKm, cumulativeDistanceM, talkDurationSec,
  meditateDurationSec, walkOnlyDurationSec (computed), favicon (String?),
  isShared (Boolean), weatherCondition (String?), hasTalk/hasMeditate
  (computed). Extends `WalkRepository` aggregate-sum result. **Mark
  `@Immutable`** (Stage 4-C/13-Cel cascade lesson — has computed properties +
  potentially List-typed future extensions).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalUiState.kt` —
  sealed class: Loading / Empty / Loaded (snapshots + summary aggregates).
  Replaces `HomeUiState` Loaded variant.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JourneySummary.kt` —
  `@Immutable data class JourneySummary` carrying `totalDistanceM`,
  `totalTalkSec`, `totalMeditateSec`, `talkerCount`, `meditatorCount`,
  `walkCount`, `firstWalkStartMs`. Methods/extensions to format the 3
  cycle-states.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/StatMode.kt` —
  enum with WALKS / TALKS / MEDITATIONS. Cycle order matches iOS verbatim.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDot.kt` —
  Composable that draws the dot at a `Modifier.offset` derived from the
  parent Canvas's `yOffset / xOffset` math. Fields: snapshot, opacity,
  isNewest, onTap, sceneryView (nullable Composable). Internal helpers
  for favicon overlay, activity arcs, halo, shared ring.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMath.kt` —
  pure-Kotlin `dotSize(durationSec): Float` + `dotColor(snapshot, hemisphere,
  pilgrimColors): Color`. Exposed `internal` so a Robolectric test exercises
  the size + color formulas without composition.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticState.kt` —
  port of iOS `ScrollHapticState`: holds `dotPositionsPx: List<Float>`,
  `dotSizesPx: List<Float>`, `milestonePositionsPx: List<Float>`,
  `lastTriggeredDot: Int?`, `lastTriggeredMilestone: Int?`. Method
  `handleScrollOffset(offsetPx, viewportHeightPx)` returns a sealed
  `HapticEvent` (None / LightDot / HeavyDot / Milestone). Threshold: 20 px
  for dots, 25 px for milestones (verbatim iOS); large-dot cutoff:
  `dotSize > 15 px`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcher.kt` —
  `class @Singleton @Inject` wrapping `Vibrator`; takes a `HapticEvent` and
  dispatches the right `VibrationEffect.Composition` (Stage 4-D pattern,
  with `areAllPrimitivesSupported` guard + `createOneShot` fallback).
  Reduce-motion + `soundsEnabled` gating.
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkRepository.kt` —
  ADD `open suspend fun activitySumsFor(walkId: Long): Map<ActivityType, Long>`.
  Implementation: new DAO method `@Query("SELECT activity_type, SUM(end_timestamp - start_timestamp) AS dur_ms FROM activity_intervals WHERE walk_id = :walkId GROUP BY activity_type")` returning a list of small DTOs the repo flattens to a map. Defaults missing types to 0 in the map.

### 14-B chrome

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeader.kt` —
  `@Composable` reading `journalState.summary` + cycling `StatMode` on tap
  (rememberSaveable so rotation preserves cycle position). Fade-in on first
  appear (delay 500 ms, duration 800 ms). Number formatting via
  `Locale.US` for "%.1f km", `Locale.getDefault()` for "%d walks · %d months"
  (Stage 5-A locale convention).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt` —
  `@Composable ExpandCardSheet(snapshot, celestialSnapshot?, onViewDetails,
  onDismissRequest)` rendering a `ModalBottomSheet` with verbatim iOS
  layout. Wraps the 7 internal composables: header row, divider,
  3-stat row, mini activity bar, activity pills, "View details →" button.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt` —
  Capsule-clipped 6-dp Row of 3 `RoundedRectangle(2.dp)` segments scaled by
  `walkOnlyDuration / total`, `talkDuration / total`, `meditateDuration / total`.
  Hides any segment whose fraction `< 0.01` (verbatim iOS).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt` —
  Row of 3 conditional pills (walk always shown, talk if `hasTalk`, meditate
  if `hasMeditate`). Color dot 5×5 dp + `pilgrimType.micro` text.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalTopBar.kt` —
  `CenterAlignedTopAppBar` with title `R.string.journal_title` ("Pilgrim
  Log"), transparent container so the parchment shows through.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnail.kt` —
  `@Composable LatestSealThumbnail(latestSealSpec, sizeDp = 44.dp)` or a
  helper `suspend fun renderSealToImageBitmap(spec, sizePx, density): ImageBitmap`
  that uses `Picture` + `Canvas` from `androidx.compose.ui.graphics.Canvas`
  to snapshot the existing `SealRenderer` to an ImageBitmap on
  `Dispatchers.Default`. Cached by `SealSpec` identity in the VM.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/GoshuinFAB.kt` —
  Replaces the inline FAB code in `HomeScreen.kt` with a dedicated
  composable that observes `homeViewModel.latestSealSpec` and renders the
  thumbnail (falling back to the existing `Icons.Outlined.Explore` glyph
  while loading, matching iOS's `Image(systemName: "seal")` fallback).

### 14-C overlays

- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/TurningDayService.kt` —
  Kotlin object porting iOS service. Methods:
  `fun turningFor(localDate: LocalDate, hemisphere: Hemisphere, zone: ZoneId): SeasonalMarker?`
  and `fun turningForToday(hemisphere: Hemisphere, zone: ZoneId, clock: Clock): SeasonalMarker?`.
  Uses `SeasonalMarkerCalc.seasonalMarker(sunLon)` plus the existing
  `SunCalc.solarLongitude(julianCenturies)` helpers. **No DEBUG
  testingDate variable** (Android relies on Robolectric instrumented dates;
  the iOS DEBUG-only stub is for simulator parity QA).
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/SeasonalMarkerTurnings.kt` —
  Kotlin extension functions on `SeasonalMarker`:
  `fun isTurning(): Boolean`, `fun kanji(): String?` (verbatim 春分/夏至/秋分/冬至),
  `fun bannerTextRes(): Int?` (returns one of `R.string.turning_equinox_banner`
  or `R.string.turning_solstice_banner`), `fun turningColor(pilgrimColors): Color?`
  reading from new `pilgrimColors.turningJade/turningGold/turningClaret/turningIndigo`
  (added to `PilgrimColors.kt` — see "Files to modify").
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt` —
  `@Composable TurningDayBanner(marker)` rendering the row HStack from iOS
  (text · kanji). Returns 0 dp height when `marker == null` so the parent's
  `turningOffset` math collapses correctly.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt` —
  `data class LunarMarker(idTag: String, xPx: Float, yPx: Float, illumination: Double, isWaxing: Boolean)`
  + `fun computeLunarMarkers(snapshots, dotPositions, viewportWidthPx): List<LunarMarker>`.
  Verbatim port: outer loop checks `isNearNew = age < 1.5 || age > 28.0` and
  `isNearFull = abs(age - 14.76) < 1.5`; if neither, advance by 1 day; else
  refine peak via `±36 hour` window in 6-hour steps maximizing illumination
  (full) or 1−illumination (new), record event, advance by `Int(halfCycle) -
  1 = 13` days. Constant `halfCycle = 14.76` verbatim. Then for each event
  date, locate adjacent dot positions whose start dates bracket it, lerp
  position along Y and X, offset X by ±20 px on the side opposite the dot
  meander.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt` —
  `@Composable` 10×10 dp Circle. Full moon: filled ellipse with α 0.4 light /
  0.6 dark (`isSystemInDarkTheme` gate); new moon: stroke-only with α 0.5
  light / 0.7 dark, 1 dp. Color in light: `Color(0.55f, 0.58f, 0.65f)`;
  in dark: `Color(0.85f, 0.82f, 0.72f)` (verbatim iOS RGBs).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt` —
  `@Composable MilestoneMarker(widthPx, distanceM)` rendering an HStack of
  `Box(0.5 dp height, fog @ 0.15α)` + `ToriiGateShape(16×14 dp, stone @ 0.25α)`
  + Text "%d km" (`Locale.US`, fog @ 0.4α) + another fog hairline.
  Constrained to `widthPx * 0.7f`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt` —
  pure functions `fun milestoneThresholds(): List<Double>` returning
  `[100_000.0, 500_000.0, 1_000_000.0]` then a generated tail
  `2_000_000.0 .. 100_000_000.0 step 1_000_000.0`. Plus
  `fun computeMilestonePositions(snapshots, dotYPositions): List<MilestonePosition(distance, yPx)>`
  iterating thresholds × snapshots, picking the first crossing-walk per threshold.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt` —
  `data class DateDivider(idTag: Int, text: String, xPx: Float, yPx: Float)`
  + `fun computeDateDividers(snapshots, dotPositions, viewportWidthPx, locale): List<DateDivider>`.
  Iterates snapshots, tracking last `YearMonth`; emits a divider at the first
  walk per new month. X = OPPOSITE the dot meander, padded 36 dp from
  the edge (verbatim iOS). Text format: `DateTimeFormatter.ofPattern("MMM",
  locale).withZone(zone).format(...)` — `Locale.getDefault()` (this is
  user-facing display copy, Stage 6-B locale lesson).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt` —
  `fun toriiGatePath(size: Size): Path` (used by both MilestoneMarker and
  scenery torii type). Verbatim port of iOS path math.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt` —
  `fun footprintPath(size: Size): Path` (used by ExpandCardSheet header row).

### 14-D polish

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt` —
  Kotlin object port. `data class SceneryPlacement(type, side, offset)`,
  `fun scenery(snapshot): SceneryPlacement?` with the FNV deterministic seed
  + 4-roll verbatim algorithm. `sceneryChance = 0.35`. Weights table verbatim.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItem.kt` —
  `@Composable SceneryItem(type, tintColor, sizePx, walkDate, reduceMotion)`
  switch-dispatching to per-type composables.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/TreeScenery.kt`,
  `LanternScenery.kt`, `ButterflyScenery.kt`, `MoonScenery.kt`,
  `MountainScenery.kt`, `GrassScenery.kt`, `ToriiScenery.kt` — one per type.
  Each draws via `Canvas + Path`, animation throttled by `withFrameNanos`
  at the iOS-matching framerate. **Reduce-motion**: each composable has a
  static fallback (no time-driven sin/cos terms; static `Path.fill` only).
  iOS-verbatim ratios (size × 1.08 echo, size × 0.88 inner, etc.)
  preserved as constants per file.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryShapes.kt` —
  pure path builders: `treePath`, `lanternPath`, `lanternWindowPath`,
  `mountainPath`, `moonPath`, `winterTreePath`, `butterflyPath` (the
  butterfly is built from primitives in `ButterflyScenery.kt`, no Path
  needed). Each takes `(size: Size): Path` so reuse + Robolectric tests
  exercise them directly.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffect.kt` —
  `@Composable RippleEffect(color, dotSizePx, reduceMotion)`. Reduce-motion
  short-circuit → `Canvas { drawCircle(stroke, color α 0.15) }`. Else
  `produceState(initial = 0L) + withFrameNanos { now -> if (now - last >=
  100 ms) emit(now); else null }` driving a Canvas that traces 2 expanding
  circles (phase wraps at 1.0, radius `= dotSizePx * 0.5 + phase *
  dotSizePx * 1.2`, opacity `= (1 − phase) * 0.2`) plus a breathing glow
  (`sin(time * 1.2) * 0.5 + 0.5`, glowRadius = `dotSizePx * 1.5`, fill α
  `= 0.04 + breath * 0.04`). Verbatim coefficients.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt` —
  `@Composable EmptyJournalState(widthPx)` drawing a tapered tail (path
  120 dp tall, half-width 1 dp top tapering to 0.2 dp bottom and back to
  1 dp — verbatim) and a 14×14 dp stone-filled circle + Text("Begin",
  pilgrimType.caption, fog).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreen.kt` —
  Replaces `HomeScreen.kt`'s body. Composes:
  ```
  Box(parchment background)
    Scaffold(topBar = JournalTopBar)
      LazyColumn behind a single drawBehind/Canvas
      [TurningDayBanner top]
      [JourneySummaryHeader at y = 16dp + turningOffset]
      [Content: walks via LazyColumn, scenery, etc.]
    ExpandCardSheet (ModalBottomSheet, conditional on expandedSnapshot)
    GoshuinFAB (BottomEnd)
  ```
  Uses `LaunchedEffect(uiState)` for hemisphere first-walk seeding to match
  iOS `updateHemisphereIfNeeded()` (already done by `HemisphereRepository`
  on Android via `WalkViewModel.finishWalk`; this `LaunchedEffect` is a
  no-op, kept commented out as a reference comment for the parity
  reviewer).
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMathTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticStateTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGeneratorTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/turning/TurningDayServiceTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelJournalTest.kt`,
  `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcherTest.kt`
  (Robolectric for `VibrationEffect.Composition.build()` exercise — Stage
  2-F lesson).

## Files to modify

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` —
  Renamed to `JournalScreen.kt` (or kept as a shim that calls the new
  composable). Body wholly replaced. The tab-route still says
  `Routes.HOME` → keep that string for backward compat with
  `PilgrimNavHost`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt` —
  Rewrite the `uiState` flow to emit `JournalUiState` with `WalkSnapshot`s.
  Add `latestSealSpec: StateFlow<SealSpec?>` (newest finished walk → SealSpec
  via the existing `SealSpec.from(walk)` helper). Add `expandedSnapshotId:
  MutableStateFlow<Long?>` + `expandedCelestialSnapshot: StateFlow<CelestialSnapshot?>`
  driven by combining `expandedSnapshotId` with
  `practicePreferences.celestialAwarenessEnabled` (eager off, cold
  computation on demand off `Dispatchers.Default`). **Cancel viewModelScope
  before db.close()** in tearDown of any test (Stage 7-A flake fix).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRowComposable.kt` —
  **Delete file** (HomeWalkRowCard wholly replaced by dot + expand card).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRow.kt` —
  **Delete file** (replaced by `WalkSnapshot.kt`). Update any references
  in tests.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt` —
  Add `fun dotPositions(strokes, widthPx, verticalSpacingDp, topInsetDp): List<DotPosition(centerXPx, yPx)>` so the parent Canvas can read positions for haptics + lunar/milestone calc. Stage 3-C made dot Y derivation private to the renderer; promote `xOffsetPx` access to a `dotPositions` helper. **Don't change** existing draw lambda — additive only.
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/ActivityIntervalDao.kt` —
  Add `@Query("SELECT activity_type AS type, SUM(end_timestamp - start_timestamp) AS durationMs FROM activity_intervals WHERE walk_id = :walkId GROUP BY activity_type") suspend fun activitySumsFor(walkId: Long): List<ActivityTypeDuration>`.
  Plus `data class ActivityTypeDuration(val type: ActivityType, val durationMs: Long)`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt` —
  Add `open suspend fun activitySumsFor(walkId: Long): Map<ActivityType, Long>`
  (delegates to DAO, materializes a defaulted map — entries for missing
  types collapse to 0 so callers don't NPE).
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStore.kt` —
  Add `fun observeAll(): Flow<Map<String, CachedShare>>` reading `data.map { prefs -> prefs.asMap().filterKeys { it.name.startsWith("share_cache_") }.mapNotNull { (k, v) -> ... } }.distinctUntilChanged()`. Snapshots all per-uuid keys at once — VM consumes from a single combine.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/PilgrimColors.kt` —
  Add four turning-day colors: `turningJade`, `turningGold`, `turningClaret`,
  `turningIndigo`. RGB triples: copy from iOS's Asset Catalog hex values
  (audit pulls them — TODO note: spec author needs to verify exact hex
  tomorrow against iOS xcassets; if unavailable in audit, use approximation
  values listed below and document the divergence). Light/dark variants both.
  Approximate fallback: jade `#5e8b6e`, gold `#c79a3a`, claret `#7a3636`,
  indigo `#3b3d6c` (replace with actual values from iOS xcassets when
  auditing during implementation).
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/WalkFavicon.kt` —
  Add a `materialIcon: ImageVector` field if not already present (Stage 4-D
  added; verify). Map: FLAME → `Icons.Outlined.LocalFireDepartment`,
  LEAF → `Icons.Outlined.Spa`, STAR → `Icons.Outlined.Star`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/weather/WeatherCondition.kt` —
  Verify all 10 conditions have a `materialIcon` (Stage 12-A). Add if missing.
- `app/src/main/res/values/strings.xml` — see Strings section.
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt` —
  Update for the new `WalkSnapshot` shape. **Cancel `vm.viewModelScope`
  before `db.close()` in tearDown** (Stage 7-A flake fix).

## Strings (English)

```xml
<!-- Stage 14: Journal -->
<string name="journal_title">Pilgrim Log</string>

<!-- Empty state -->
<string name="home_empty_begin">Begin</string>
<!-- KEEP existing home_empty_message for fallback if rendering fails -->

<!-- Journey summary cycler (3 modes) -->
<string name="journal_summary_walks_count">%1$s · %2$s</string>          <!-- "5 walks · 3 months" — %1=walks count, %2=months count, both Locale.getDefault() formatted -->
<string name="journal_summary_walks_distance_km">%1$s km walked</string>  <!-- locale-formatted decimal -->
<string name="journal_summary_walks_distance_mi">%1$s mi walked</string>
<string name="journal_summary_walks_distance_m">%1$s m walked</string>
<string name="journal_summary_walks_distance_ft">%1$s ft walked</string>
<string name="journal_summary_talked">%1$s talked</string>                <!-- %1 = formatDuration result, e.g., "1h 12m" -->
<string name="journal_summary_meditated">%1$s meditated</string>
<plurals name="journal_summary_walks_with_talk">
    <item quantity="one">%d walk with talk</item>
    <item quantity="other">%d walks with talk</item>
</plurals>
<plurals name="journal_summary_walks_with_meditation">
    <item quantity="one">%d walk with meditation</item>
    <item quantity="other">%d walks with meditation</item>
</plurals>

<!-- Turning-day banner (Stage 14-C) -->
<string name="turning_equinox_banner">Today, day equals night.</string>
<string name="turning_solstice_banner">Today the sun stands still.</string>

<!-- Expand card -->
<string name="journal_expand_label_distance">distance</string>
<string name="journal_expand_label_duration">duration</string>
<string name="journal_expand_label_pace">pace</string>
<string name="journal_expand_pill_walk">walk</string>
<string name="journal_expand_pill_talk">talk</string>
<string name="journal_expand_pill_meditate">meditate</string>
<string name="journal_expand_view_details">View details &#8594;</string>  <!-- → -->

<!-- Accessibility -->
<string name="journal_dot_a11y_walk_on_date_distance_duration">Walk on %1$s, %2$s, %3$s</string>
<string name="journal_dot_a11y_with_talk">, %1$s talking</string>
<string name="journal_dot_a11y_with_meditate">, %1$s meditating</string>
<string name="journal_summary_a11y_double_tap_to_cycle">Double-tap to change statistic.</string>
<string name="journal_milestone_a11y">Milestone: %1$s</string>            <!-- "100 km" or "62 mi" -->
<string name="journal_turning_banner_a11y">%1$s. %2$s.</string>           <!-- "Today, day equals night. Spring Equinox." -->
```

iOS `WalkDotView.formatDuration` (`%dh %dm` for h>0 else `%dm`) ports as
a Kotlin `formatDuration(seconds: Long): String` helper using
`Locale.US "%dh %dm".format(...)`. Numeric body of cycle stats uses
`Locale.US` for digits; pluralized counts go through Android's
`getQuantityString` (`Locale.getDefault()`).

## Material icon mapping

| iOS SF Symbol | Material `ImageVector` |
|---|---|
| `flame.fill` (favicon) | `Icons.Outlined.LocalFireDepartment` |
| `leaf.fill` (favicon) | `Icons.Outlined.Spa` |
| `star.fill` (favicon) | `Icons.Outlined.Star` |
| `link` (shared marker in expand card) | `Icons.Outlined.Link` |
| `arrow.right` (in "View details →") | use Unicode `→` (U+2192) inline, NOT an icon (matches the iOS `Image(systemName: "arrow.right")` rendering more gracefully + survives RTL flipping if the user's locale is RTL). |
| `seal` (FAB fallback) | `Icons.Outlined.Explore` (existing) — use only when the latest walk's seal hasn't rendered yet. |
| `ladybug` (DEBUG menu icon) | NOT PORTED (Non-goal). |

Scenery shapes are CUSTOM `androidx.compose.ui.graphics.Path` builders
(NOT Material icons): tree, lantern, lanternWindow, butterfly (drawn from
primitives), mountain, moon, torii, footprint, winterTree (bare-branch
tree variant for winter). All path-math constants ported verbatim from iOS
shape files (`/pilgrim-ios/Pilgrim/Views/Scenery/*.swift`).

WeatherCondition glyph mapping is already established by Stage 12-A —
reuse `WeatherCondition.materialIcon`. If the field doesn't exist yet,
audit + add.

Planetary-hour symbols (sun/moon/mercury/venus/mars/jupiter/saturn) and
zodiac symbols are already mapped in Stage 13-Cel; reuse those Unicode
codepoints.

## Implementation order

The 4 sub-stage buckets correspond to internal task batches. Each task is
~2-5 hours of subagent work; bundled tasks (data + helper) are batched per
the Stage 13-XZ Tasks 4+5 precedent. Total: **20 tasks**, sized so the
stage can be split mid-implementation if scope creeps.

### Bucket 14-A — foundation (5 tasks)

1. **`activitySumsFor` DAO + Repository** — Add `@Query` aggregating
   `activity_intervals`, return `Map<ActivityType, Long>`. DAO-level test
   inserts 3 talk + 2 meditate intervals + 1 walk and asserts sums.
   Repository test wraps the DAO (keep signature `open suspend fun` for
   fakes).
2. **`WalkSnapshot` + `JournalUiState` data classes** — Pure data; both
   `@Immutable`. Includes `JourneySummary` and `StatMode` enum.
3. **`WalkDotMath` + `ScrollHapticState`** (batched) — Pure-Kotlin: `dotSize`
   formula (300/7200 sec clamp, 8/22 dp range), `dotColor` selector
   (turning > monthly seasonal), and the haptic-state state-machine. Tests
   cover dot-cross detection at various offsets, `lastTriggeredIndex` dedup
   (don't fire twice for same dot), milestone threshold 25 px, and the
   reduce-motion guard.
4. **`HomeViewModel` rewrite** — Switch to `JournalUiState` flow; add
   `latestSealSpec` + `expandedSnapshotId` + `expandedCelestialSnapshot`.
   Build snapshots via `withContext(Dispatchers.Default)` (CPU work for
   cumulative-distance reduce + per-walk activity-sum lookup). Test:
   tear down `vm.viewModelScope.cancel()` before `db.close()` (Stage 7-A
   flake-fix lesson).
5. **`WalkDot` + `JournalHapticDispatcher` + LazyColumn migration** —
   Replace `Column { ... }` with `LazyColumn` whose items emit zero-height
   anchors; introduce a parent `Canvas(modifier.matchParentSize())`
   reading dot positions from `CalligraphyPath.dotPositions` to draw dots.
   Wire `LazyListState` scroll observer → `ScrollHapticState` →
   `JournalHapticDispatcher`. Robolectric test exercises
   `VibrationEffect.Composition.build()` (Stage 2-F lesson — this is the
   PR's first runtime-validated builder, MUST device-test before merge).

### Bucket 14-B — chrome (5 tasks)

6. **`JournalTopBar` + `JourneySummaryHeader`** — Centered title in a
   `CenterAlignedTopAppBar`; the summary is overlaid on the LazyColumn
   header at `y = 16 dp + turningOffset`. Tap cycles via `rememberSaveable`
   (Stage 5-A lesson — flag whose reset puts user in a stuck state). Stage
   5-A `Modifier.scale(Float)` lambda form for any animated scale (none
   used here, but flag noted for animation-bucket reviewer).
7. **`ExpandCardSheet` + `MiniActivityBar` + `ActivityPills`** (batched) —
   ModalBottomSheet, the 7 internal composables; verbatim layout. Wire
   onTap state from `WalkDot.onTap(snapshot.id)` → VM
   `setExpandedSnapshotId(id)`. `BackHandler` to close. `rememberUpdatedState`
   on the dismiss callback (Stage 4-B lesson). Robolectric snapshot test
   asserting all sub-composables present given a sample snapshot.
8. **`SealRenderer.renderToImageBitmap` helper + `LatestSealThumbnail`** —
   Off-screen Canvas snapshot of the existing `SealRenderer` to an
   `ImageBitmap`, on `Dispatchers.Default`. Cache by `SealSpec` identity
   in the VM (`MutableStateFlow<ImageBitmap?>`). Test: re-rendering the
   same SealSpec twice returns the same bitmap reference.
9. **`GoshuinFAB` swap-in** — Replace inline `FloatingActionButton(Icons.Outlined.Explore)`
   with the new `GoshuinFAB(latestSealBitmap)`. Falls back to the explore
   icon while the bitmap renders (matches iOS `Image(systemName: "seal")`
   placeholder).
10. **`JournalScreen` glue + delete `HomeWalkRowCard`** (batched) — Compose
    the pieces from 6-9 into the new screen layout. Delete
    `HomeWalkRowComposable.kt` and `HomeWalkRow.kt` outright (no shim —
    Stage 14 ships the layout swap). Update `PilgrimNavHost.kt` import if
    the Composable name changes (keep route string `Routes.HOME` to avoid
    deep-link regressions).

### Bucket 14-C — overlays (5 tasks)

11. **`TurningDayService` + `SeasonalMarkerTurnings` extensions** (batched) —
    Kotlin object port. Plus `kanji() / bannerTextRes() / turningColor()
    / isTurning()` extension fns. New `pilgrimColors.turningJade/Gold/
    Claret/Indigo` tokens. Test: known equinox/solstice dates 2024 + 2025
    return the right marker; non-turning dates return null; cross-quarter
    markers return null from `turning(...)` even when calc emits them.
12. **`TurningDayBanner` Composable + integration** — Renders nothing when
    no turning today; renders `text · kanji` row otherwise. Use
    `R.string.turning_*` resources. Robolectric test asserts both branches.
13. **`LunarMarkerCalc` + `LunarMarkerDot`** (batched) — Algorithm port.
    Test fixtures: known new-moon date 2024-01-11 UTC and known full-moon
    date 2024-01-25 UTC; with 4 walks bracketing each, the calc places one
    marker per event, on the side opposite the dot meander. Light + dark
    color variants asserted via `isSystemInDarkTheme` proxy.
14. **`MilestoneCalc` + `MilestoneMarker`** (batched) — `milestoneThresholds()`
    test: first 5 thresholds = `[100k, 500k, 1M, 2M, 3M]`. `computeMilestonePositions`
    test: with cumulative `[50k, 120k, 600k, 1.05M]`, returns 3 markers at
    walks index 1, 2, 3.
15. **`DateDividerCalc` + integration into JournalScreen** — Test: 5 walks
    spanning Apr → May → Jun gets 3 dividers ("Apr", "May", "Jun") with
    each X opposite that walk's dot meander. Verifies `Locale.getDefault()`
    formatting (Stage 6-B lesson — JVM default locale; pin tests via
    `Locale.setDefault(Locale.US)` in `@Before`).

### Bucket 14-D — polish (5 tasks)

16. **`SceneryGenerator` + `SceneryShapes` path builders** (batched) —
    Verbatim FNV-seeded probability port; 7 path builders (each tested
    via `Path.getBounds()` to assert shapes don't escape their declared
    size). Includes `winterTreePath` for the winter `tree` variant. Stage
    3-C path-test pattern.
17. **`SceneryItem` Composable + 7 per-type sub-composables** — Each
    sub-composable has a reduce-motion branch (static fallback). Frame
    throttling via `withFrameNanos`. Robolectric `composeRule.setContent`
    smoke tests assert each type composes without crash; bounds-tests
    cover the path math.
18. **Cascading fade-in + horizontal parallax offset** — Segments and dots
    each get an alpha that animates `0 → 1` driven by `animateFloatAsState`
    keyed on a `hasAppeared: Boolean` flag (set true via `LaunchedEffect(Unit)`
    on first composition). Delays per iOS: `segments[i].delay = 200 ms +
    i * 30 ms`, duration `1200 ms`, easing `EaseOut`; `dots[i].delay =
    300 ms + i * 30 ms`, duration `500 ms`. **Reduce-motion** collapses
    delays + durations to 0 so content snaps in. Parallax: scenery `xOffset
    = (yPx - viewportMidPx) / viewportMidPx * 8 dp`.
19. **`RippleEffect` + newest-walk integration** — Newest-walk dot wraps
    `RippleEffect`. Reduce-motion fallback; 100 ms cadence
    `withFrameNanos`. Robolectric test asserts the static-fallback branch
    renders a single stroked Circle.
20. **`EmptyJournalState`** + reduce-motion sweep + final QA wiring —
    Empty state replaces the existing empty Text. Sweep: every animation
    entry point in 14-A → 14-D consults a single
    `LocalReduceMotion.current` boolean (existing in Stage 5-A). Final QA
    integration test: full JournalScreen with 12 fixture walks renders to
    a Robolectric snapshot without exception, exercises tap → expand
    sheet, tap → "View details" → nav callback fires.

## Tests (high-level)

**Pure-data / pure-Kotlin (no Compose):**

- `WalkDotMathTest` — dotSize at boundary (`durationSec = 300` → 8 dp;
  `7200` → 22 dp; `3600` → 13.69 dp; verify clamp on `<300` and `>7200`).
- `ScrollHapticStateTest` — verbatim Stage 3-C closing-review pattern: dot
  cross at offset `dotY ± 19 px` triggers; `± 21 px` doesn't; reduce-motion
  short-circuits; `lastTriggeredIndex` prevents double-fire.
- `LunarMarkerCalcTest` — synthetic walks bracketing 2024 lunations; one
  marker per event; opposite-side X math verified.
- `MilestoneCalcTest` — threshold list bounds; cumulative-cross detection;
  threshold-already-passed walks don't double-emit.
- `DateDividerCalcTest` — month boundary detection; opposite-side X math;
  locale-pinned `MMM` output ("Apr" not "abr").
- `SceneryGeneratorTest` — 35% chance verified via Monte Carlo (seed range
  64×24-bit UUIDs, count `placement != null`, expect 0.30 ≤ ratio ≤ 0.40).
  Type-distribution test (1000 placements should hit each weight ±2σ).
- `TurningDayServiceTest` — solstice/equinox dates 2023/2024/2025 across
  hemispheres (northern + southern flip); cross-quarter dates → null;
  non-turning dates → null.
- `WalkRepository.activitySumsForTest` — 3 talk intervals + 2 meditate +
  1 walk (mixed durations) → sums match arithmetic; missing types map
  to 0.

**ViewModel:**

- `HomeViewModelJournalTest` — turbine the `journalState` flow. Cases:
  empty → Empty; one finished walk → Loaded with 1 snapshot; cumulative
  distance reduces correctly newest-to-oldest; isShared resolves from
  CachedShareStore for the right uuid. **TearDown**:
  `vm.viewModelScope.cancel()` before `db.close()` (Stage 7-A leak fix).

**Robolectric (require Android runtime):**

- `JournalHapticDispatcherTest` — calls `dispatch(HapticEvent.LightDot(0))`
  and asserts that a real `VibrationEffect.Composition` was BUILT (Stage
  2-F lesson — Fakes hide builder crashes).
- `WalkDotComposableTest` — `composeRule.setContent { WalkDot(snapshot) }
  .onRoot().assertExists()`. Plus a path-extraction unit test on the
  internal `dotPath()` helper if dot drawing migrates to one (Stage 3-C
  pattern).
- `ExpandCardSheetTest` — open with sample snapshot, all sub-composables
  present; tap "View details" → onViewDetails callback fired with
  `snapshot.id`.
- `JournalScreenIntegrationTest` — full screen with 12 fixture walks;
  scroll, tap a dot, verify ExpandCardSheet shows; dismiss, verify
  closed.
- `SceneryItemRobolectricTest` — for each of 7 types,
  `composeRule.setContent { SceneryItem(...) }; onRoot.assertExists()`.

## Verification

**Automated:**

- `./gradlew :app:test` and `:app:testDebugUnitTest` green (all new
  unit + Robolectric tests pass).
- `./gradlew :app:lintDebug` clean — no new warnings introduced.
- Detekt clean.

**Manual (on-device, OnePlus 13 Stage 5-G QA pattern):**

- Fresh-install Empty state shows tail + stone dot + "Begin".
- Seed 12 walks via instrumentation; Journal renders dots, segments,
  scenery, lunar markers (if dates bracket a lunation), at least one
  milestone if cumulative distance reaches 100 km.
- Tap a dot → expand sheet rises; tap "View details →" → WalkSummary
  opens.
- Swipe-to-dismiss the sheet works.
- Scroll triggers haptics on dot center-pass (light/medium for small/large
  dots, heavy for milestone bars). Reduce-motion in Settings → no haptics
  fire.
- Dark-mode flip (`adb shell cmd uimode night yes/no`) re-tints dots,
  scenery, lunar markers, milestone bars.
- Hemisphere flip via Settings → autumn equinox banner appears in southern
  on March 20 (verbatim test).
- Memory pressure test: scroll fast through 50 fixture walks → no
  hitches > 16 ms (per `Layout Inspector` flame chart).
- GoshuinFAB icon shows the latest walk's seal thumbnail after first
  cold start (~200 ms render).
- Long sessions (45-min walk → finish → land on Journal) show the new
  walk at the top with the ripple effect; reduce-motion → static stroked
  ring instead of breathing animation.

## Open questions for review

1. **Turning-day color hex values** — the spec uses placeholders
   (jade `#5e8b6e` etc.). Implementation MUST audit
   `pilgrim-ios/Pilgrim/Resources/Assets.xcassets/turningJade.colorset/Contents.json`
   et al. and copy the exact light + dark hex pairs. Document any
   divergence in the implementation comments.
2. **`renderSealToImageBitmap` perf budget** — first-frame seal render on
   OnePlus 13 was ~190 ms in Stage 4-A QA; cold-start FAB will show
   `Icons.Outlined.Explore` for that duration. If the bitmap render
   blocks the UI more than 250 ms on a low-end device, consider
   pre-warming on `JournalScreen.LaunchedEffect(Unit)` BEFORE the FAB is
   composed. Investigate during 14-B implementation.
3. **`activitySumsFor` correctness during long-running activity intervals
   that span the walk's `endTimestamp`** — Stage 9.5-D guarantees
   intervals are clipped to the walk window, but verify in the DAO test.
   If clipping is post-hoc, the SUM(end - start) will overshoot. Audit
   the existing `ActivityIntervalCoordinator` behavior.

## Documented iOS deviations (in code comments)

These are intentional. Each call site below MUST carry an SPDX-aligned
comment with the rationale linking back to this spec.

1. **CalligraphyPath FNV hash differs from iOS** — Stage 3-C inheritance.
   Already documented in `CalligraphyStrokeSpec.kt`. No action; carries
   forward into `WalkSnapshot` since the renderer reads `uuid` not raw
   bytes.
2. **Talk-duration via DAO aggregate, not denormalized column** — iOS reads
   `walk.talkDuration` from a CoreData computed property. Android reads
   `activitySumsFor(walkId)[ActivityType.TALKING]`. Rationale: avoids
   adding two columns + a migration for fields that are pure arithmetic
   on existing data. Performance: one extra `GROUP BY` per walk per
   journal load — well within the IO-dispatcher budget; cached in the
   VM's snapshot.
3. **Expand card via Material 3 ModalBottomSheet, NOT iOS overlay sheet** —
   Android idiom; matches Stage 13-XZ PromptListSheet precedent. Behavior
   parity (open from below, dismissible, semi-modal) preserved; visual
   detail (ultraThinMaterial → BottomSheetDefaults.containerColor) approximated.
4. **Long-press dot preview omitted** — Audit Gap 13 (vestigial on iOS).
5. **DEBUG seed/clear menu omitted** — Audit Gap 2 (out of scope; instrumentation
   covers QA).
6. **`renderSealToImageBitmap` is Android-specific** — iOS uses
   `SealGenerator.thumbnail(from: input) -> UIImage` (CPU-rendered). Android
   uses an off-screen Compose Canvas snapshot to `ImageBitmap`. Rendering
   cost ~190 ms; cached by SealSpec identity in the VM.
7. **`MoonCalc.SYNODIC_DAYS = 29.530588770576` vs iOS
   `LunarPhase.synodicMonth = 29.53058770576`** — Android value is more
   precise (one trailing digit). Already shipped Stage 5-A. No-op
   difference (period drift < 1 day over 30 lunations). Flagged in code
   comments.
8. **No `id(unitKey)` viewport reset** — iOS uses `.id(unitKey)` on
   `InkScrollView` to nuke the entire view tree on unit toggle (forcing a
   re-render). Compose recomposes naturally on the units StateFlow flip,
   so no manual key needed. iOS comment is for the SwiftUI quirk where
   `.contentTransition(.numericText())` doesn't re-render distance labels
   on unit flip without a viewport key.
