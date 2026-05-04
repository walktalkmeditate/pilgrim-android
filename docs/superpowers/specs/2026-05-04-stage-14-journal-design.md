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
- **Live talk-arc rendering for native walks** — Stage 14 ships WITHOUT a
  live `ActivityIntervalCoordinator`. The only construction site of
  `ActivityInterval` today is `data/pilgrim/builder/PilgrimPackageConverter.kt`
  (the .pilgrim ZIP importer); native walks recorded on Android never write
  TALKING intervals to `activity_intervals`. As a result: talk arcs and
  counts will not render until live ActivityInterval recording lands in a
  future stage (TODO Stage 14.X "wire live ActivityInterval recording from
  WalkViewModel"). Talk shows as 0 in the activity bar / pills /
  journey-summary cycler — visually correct (no talk happened) but
  functionally degraded for users importing from .pilgrim ZIPs that contain
  talk intervals from iOS. Meditation totals are unaffected because
  `Walk.meditationSeconds` is already populated by `WalkMetricsCache` for
  every native walk; Stage 14 reads from that cached column directly.
- **Scenery seasonal/time-of-day sub-effects** — canopy seasonal swap,
  falling leaves, dewdrops, alpenglow, snow flakes, moon clouds + stars +
  rays, torii shimenawa rope + shide are deferred to Stage 14.5. Stage 14
  ships scenery shapes as **static `Path` fills** tinted by the walk's
  seasonal color (no animations, no time/season-driven color shifts beyond
  per-walk tint). Drops the scenery-bucket scope from ~2k LOC to ~400 LOC.
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
is replaced by a **LazyColumn** whose items render the per-walk
`WalkDot` Composable directly (positioned via `Modifier.offset { x =
xPositionPx, y = 0 }` inside a fixed-height row) plus its distance-label
sibling. Each row has a fixed `verticalSpacing = 90 dp` height
(iOS-verbatim; reverts the Stage 3-F-tuned 132 dp; see Documented iOS
deviations note 9). The decorative ribbon path/segments/scenery/lunar
markers/milestone bars/date dividers/turning banner are drawn by a single
`Canvas(Modifier.matchParentSize())` placed BEHIND the LazyColumn (a
`drawBehind` on the parent `Box`). Per-dot tap is the `WalkDot`
composable's own `Modifier.clickable` so accessibility labels + ripple
indication work without a hand-rolled hit-test layer.

**Coordinate math:** the parent Canvas knows row positions in canvas-space
via the formula
```
firstVisibleRowYpx_inCanvas = firstVisibleItemIndex * 90.dp.toPx() + firstVisibleItemScrollOffset_px
```
For the haptic state-machine, viewport-center Y in canvas-space is
`firstVisibleRowYpx_inCanvas + viewportHeightPx / 2`. The state-machine
walks pre-computed dot Y-positions (`List<Float>` indexed by snapshot
order) and triggers when the viewport center crosses any dot ± 20 px (or
± 25 px for milestone bars).

Cards are **gone**. Per-walk content collapses to:
- The dot itself (8-22 dp, color-coded by season + turning-day, favicon
  glyph overlay, activity-duration arcs, optional newest ripple, optional
  shared-ring stroke). Lives inside the LazyColumn row at offset
  `xPositionPx`.
- A small distance label (`pilgrimType.micro`, `fog @ 0.5α`) at the dot's
  Y position offset 32 dp on the side OPPOSITE the dot's X meander. Also
  inside the row, opacity = `dotOpacity(index, total) * 0.7` (iOS
  `InkScrollView.swift:636`).
- A month/year date divider (`pilgrimType.caption`, `fog @ 0.5α`) at the
  first walk-row of each new month, anchored to the OPPOSITE side from
  the dot. Drawn by the behind-Canvas, NOT the row, since it spans the
  same Y as the row but is decorative.

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
2. Reads pause-aware active duration via
   `WalkMetricsMath.computeActiveDurationSeconds(walk, walkRepo.walkEventsFor(walkId))`.
   `walkEventsFor` is a new repo helper delegating to
   `WalkEventDao.getForWalk(walkId)`. iOS `walk.activeDuration` ALREADY
   deducts pause time (per `Walk.swift`); the naive
   `(end - start) / 1000` would silently inflate duration on any walk the
   user paused, breaking pace + activity-bar fractions.
3. Issues activity sums via the new repo helper
   `WalkRepository.activitySumsFor(walkId): Pair<Long, Long>` returning
   `(talkSec, meditateSec)`. **For Stage 14**: `talkSec` is always 0L
   (no live ActivityInterval writer for native walks; see Non-goals);
   `meditateSec` reads `walk.meditationSeconds ?: 0L` (the cached column
   populated by `WalkMetricsCache`). When live ActivityInterval recording
   lands in Stage 14.X this helper switches to a real DAO aggregate,
   leaving the call sites unchanged. Imported .pilgrim ZIPs contribute
   talk intervals via `PilgrimPackageConverter` but the spec accepts
   talk-arc rendering as 0 for those until the helper signature widens.
4. Reduces cumulative distance left-to-right (oldest → newest), assigns to
   the snapshot, then reverses the list so the LazyColumn renders newest
   first.
5. Resolves `isShared` from `shareCache[walk.uuid]?.isExpiredAt(nowMs) ==
   false` (and the cache key existing).
6. Picks `favicon`, `weatherCondition` from the existing `Walk` columns.
7. Pre-computes haptic dot-Y positions via the **dotSize** formula so the
   `ScrollHapticEngine` Android equivalent doesn't need to re-derive on
   every viewport offset emit.

The journey-summary aggregates (`totalTalkSec`, `totalMeditateSec`,
`talkers`, `meditators`, `firstWalkStartMs`) live on the same VM emission
to avoid a second collect on the same flow.

### Latest-seal pipeline

The GoshuinFAB shows a 44 dp thumbnail of the newest finished walk's
goshuin seal. Pipeline:

1. **`HomeViewModel.latestSealSpec: StateFlow<SealSpec?>`** — derived
   from the newest finished walk + the resolved seasonal ink. Built via
   `walk.toSealSpec(distanceMeters, ink, displayDistance, unitLabel)`
   (the actual signature; see `ui/design/seals/SealSpec.kt:91`).
   `ink` resolves through the existing
   `SeasonalInkFlavor.toSeasonalColor(units, hemisphere, LocalDate.now(zone), Intensity.Full)`
   composition (units pref + hemisphere repo + today's date).
2. **`HomeViewModel.latestSealBitmap: StateFlow<ImageBitmap?>`** — emits
   `null` initially. On every `latestSealSpec` emission, the VM launches
   `viewModelScope.launch(Dispatchers.Default) { ... }` that calls
   `EtegamiSealBitmapRenderer.renderToBitmap(spec, ink, sizePx, context)`
   (already shipped Stage 7-C), then converts via `bitmap.asImageBitmap()`
   and updates the StateFlow on the Main dispatcher.
3. **Cache key:** `Pair(SealSpec, sizePx)` — `sizePx` matters because
   thumbnail dp → px depends on the device density.
4. **GoshuinFAB Composable** observes `latestSealBitmap`. When `null`
   (cold start, ~190 ms render), it falls back to
   `Icons.Outlined.Explore` (the existing compass placeholder, matching
   iOS `Image(systemName: "seal")`). On completion, swaps in
   `Image(bitmap = latestSealBitmap)`.

This pipeline reuses `EtegamiSealBitmapRenderer` from `ui/etegami/`
verbatim — no new helper class is introduced.

### iOS-Android type mapping

| iOS | Android |
|---|---|
| `WalkSnapshot: Identifiable` (Swift struct) | `data class WalkSnapshot` (`@Immutable`) |
| `walkSnapshots: [WalkSnapshot]` | `journalState.snapshots: List<WalkSnapshot>` |
| `walks: [Walk]` (CoreStore) | `journalState.walks: List<Walk>` (Room) |
| `loadWalks()` (manual fetch) | `combine(observeAllWalks, ...)` Flow |
| `Calendar.current.dateComponents` | `LocalDate` / `YearMonth` (java.time) |
| `walk.activeDuration` | `WalkMetricsMath.computeActiveDurationSeconds(walk, walkRepository.walkEventsFor(walkId))` (pause-aware; the naive `(end - start) / 1000` would silently inflate paused walks) |
| `walk.talkDuration`, `walk.meditateDuration` (computed) | `walkRepository.activitySumsFor(walkId): Pair<Long, Long>` returning `(talkSec, meditateSec)`. Stage 14 stub: talkSec = 0L always (no live writer); meditateSec = `walk.meditationSeconds ?: 0L` (cached column populated by `WalkMetricsCache`). See Non-goals + TODO Stage 14.X. |
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
| `GoshuinFAB.thumbnail: UIImage?` | `ImageBitmap?` rendered by `EtegamiSealBitmapRenderer.renderToBitmap` (Bitmap → `Bitmap.asImageBitmap()`) at 44 dp |
| `SealGenerator.thumbnail(from: input)` | **Reuse** `EtegamiSealBitmapRenderer.renderToBitmap(spec, ink, sizePx, context): Bitmap` (already shipped Stage 7-C); convert with `Bitmap.asImageBitmap()` for Compose consumption. Drawn on `Dispatchers.Default`, cached by `Pair(SealSpec, sizePx)` in the VM. NO new helper. |
| `WalkFavicon.icon` (SF Symbol name) | `WalkFavicon.materialIcon` (Material `ImageVector`) — already mapped in Stage 4-D |
| `WeatherCondition.icon` (SF Symbol) | `WeatherCondition.iconRes` (`@DrawableRes Int` — already exists from Stage 12-A). Render via `Icon(painterResource(condition.iconRes), ...)` in the expand card; NO new ImageVector field. |
| `Color.parchment`, `.ink`, `.fog`, `.stone`, `.moss`, `.rust`, `.dawn` | `pilgrimColors.*` tokens (already mapped) |
| `SeasonalColorEngine.seasonalColor(named:intensity:on:)` | `SeasonalInkFlavor.X.toBaseColor() + SeasonalColorEngine.applySeasonalShift(...)` (already exists) |
| `TurningDayService.turning(for:hemisphere:)` | new `TurningDayService.turningFor(localDate, hemisphere)` Kotlin object |
| `TurningDayService.turningForToday()` | `turningFor(LocalDate.now(zone), hemisphere)` |
| `SeasonalMarker.color` (asset color) | `SeasonalMarker.turningColor()` extension reading from `PilgrimColors.turningJade/Gold/Claret/Indigo` |
| `SeasonalMarker.kanji` | `SeasonalMarker.kanji: String?` extension (verbatim copy) |
| `SeasonalMarker.bannerText` | `SeasonalMarker.bannerTextRes: Int?` (`R.string.turning_equinox_banner` / `R.string.turning_solstice_banner`) |
| `LunarPhase.current(date:)` | `MoonCalc.moonPhase(instant)` (already exists) — note iOS uses `synodicMonth = 29.53058770576` while Android uses `29.530588770576` (extra `8` digit at fractional position 6; drift < 3 s over 30 lunations; Stage 5-A artifact, documented but acceptable) |
| `SceneryGenerator.scenery(for:)` | `object SceneryGenerator.scenery(snapshot)` (verbatim) |
| `SceneryItemView` (TimelineView per type) | `SceneryItem(type, tintColor, sizePx, walkDate)` Composable |
| `SceneryType` enum + `AnyShape` | `enum class SceneryType` + per-type Compose `Path` builder fns |
| `MilestoneMarkerView` | `@Composable MilestoneMarker(width, distance)` |
| `ToriiGateShape` | `toriiGatePath(rect)` extension fn (verbatim path math) |
| `FootprintShape` | `footprintPath(rect)` extension fn |

### Sub-stage groupings

**14-A foundation** (data + virtualization + dot composable):
WalkSnapshot data class, `activitySumsFor` Stage-14 stub
(returning `(0L, walk.meditationSeconds)`), `walkEventsFor` repo helper,
`HomeViewModel` rewrite, LazyColumn migration with per-row WalkDot
Composable (size formula + favicon overlay + activity arcs + halo +
shared-ring), per-walk distance label, scroll haptics state-machine +
`VibrationEffect.Composition` dispatcher. Reduce-motion guards on the
haptics path (handler-time `Settings.Global` read; see I10).

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
each as a sub-Composable rendering a STATIC `Path` fill tinted by the
walk's seasonal color; sub-effects deferred to Stage 14.5), parallax
horizontal offset by viewport-vertical distance, cascading fade-in
(segment delay = `index * 30 + 200 ms`, duration 1200 ms; dot delay =
`index * 30 + 300 ms`, duration 500 ms), newest-walk ripple (Canvas with
breathing circles + glow at 10 fps), reduce-motion checks on ALL
animation entry points (collapse to static), empty-state tail + stone
dot composable + `R.string.home_empty_begin` ("Begin"). Replaces the
`R.string.home_empty_message` Text.

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
  pilgrimColors): Color` + `dotOpacity(index: Int, total: Int): Float` +
  `labelOpacity(index, total): Float`. Exposed `internal` so a Robolectric
  test exercises the size + color + opacity formulas without composition.

  ```kotlin
  // Verbatim port of iOS InkScrollView.swift:493-497 (dot α newest 1.0 → oldest 0.5)
  fun dotOpacity(index: Int, total: Int): Float =
      if (total <= 1) 1f else 1f - (index.toFloat() / (total - 1)) * 0.5f

  // iOS InkScrollView.swift:636 — distance-label α = dotOpacity * 0.7
  fun labelOpacity(index: Int, total: Int): Float =
      dotOpacity(index, total) * 0.7f
  ```
  (Distinct from segment opacity, which is 0.35 → 0.15 — kept inside the
  Canvas-behind painter.)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticState.kt` —
  port of iOS `ScrollHapticState`: holds `dotPositionsPx: List<Float>`,
  `dotSizesPx: List<Float>`, `milestonePositionsPx: List<Float>`,
  `lastTriggeredDot: Int?`, `lastTriggeredMilestone: Int?`. Method
  `handleScrollOffset(offsetPx, viewportHeightPx)` returns a sealed
  `HapticEvent` (None / LightDot / HeavyDot / Milestone). Threshold: 20 px
  for dots, 25 px for milestones (verbatim iOS); large-dot cutoff:
  `dotSize > 15 px`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcher.kt` —
  `class @Singleton @Inject` wrapping `Vibrator` + `@ApplicationContext context`;
  takes a `HapticEvent` and dispatches the right `VibrationEffect.Composition`
  (Stage 4-D pattern, with `areAllPrimitivesSupported` guard +
  `createOneShot` fallback).

  **Reduce-motion gating is HANDLER-TIME, not Composition-time.** Each
  `dispatch()` call reads
  `Settings.Global.getFloat(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1f) == 0f`
  in-line (microsecond cost; settled at OS level). This is intentionally
  decoupled from `LocalReduceMotion.current` so users can flip
  Quick-Settings reduce-motion mid-scroll and the next haptic respects
  the new setting. `LocalReduceMotion` remains the source of truth for
  Composition-time animation gating elsewhere.

  **50 ms min-interval guard** (Open-Question #3): track a per-instance
  `lastDispatchNs: Long` and skip dispatches within 50 ms of the previous
  one. Defends against multi-finger / scroll-fling haptic flooding.

  Also gates on `soundsEnabled` (existing settings flow).
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt` —
  ADD `open suspend fun activitySumsFor(walkId: Long, walk: Walk): Pair<Long, Long>`.
  **Stage 14 stub:** returns `Pair(0L, walk.meditationSeconds ?: 0L)`. The
  first component (talk seconds) is hard-zeroed because no live
  `ActivityIntervalCoordinator` exists yet — the only construction site
  of `ActivityInterval` today is `data/pilgrim/builder/PilgrimPackageConverter.kt`
  (the .pilgrim ZIP importer); native walks never write talk intervals.
  When live recording lands in Stage 14.X this method switches to a real
  DAO aggregate (`@Query("SELECT activity_type, SUM(end_timestamp - start_timestamp) FROM activity_intervals WHERE walk_id = :walkId GROUP BY activity_type")`)
  and the call sites stay unchanged. Caller passes the already-loaded
  `Walk` so we don't double-fetch from the DB. Add a TODO comment in the
  method body: `// TODO Stage 14.X: wire live ActivityInterval recording from WalkViewModel`.

  ALSO ADD `open suspend fun walkEventsFor(walkId: Long): List<WalkEvent>`
  delegating to `walkEventDao.getForWalk(walkId)` (verified DAO method
  name in `data/dao/WalkEventDao.kt`). Used by `HomeViewModel.buildSnapshots`
  so `WalkMetricsMath.computeActiveDurationSeconds(walk, events)` can run
  pause-aware duration math.

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
  3-stat row, mini activity bar, activity pills, "View details" button.

  **Date format:** the header row's date+time text uses
  ```kotlin
  DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
      .withLocale(Locale.getDefault())
      .withZone(ZoneId.systemDefault())
      .format(Instant.ofEpochMilli(snapshot.startMs))
  ```
  (`FormatStyle.FULL` for date so weekday + full month name render;
  `FormatStyle.SHORT` for time so it's compact next to the favicon row.)

  **"View details" button arrow:** use `Icons.AutoMirrored.Filled.ArrowForward`
  (NOT a Unicode `→`). The Unicode arrow does NOT auto-flip in RTL
  locales; `AutoMirrored.ArrowForward` does, matching iOS
  `Image(systemName: "arrow.right")` RTL behavior.
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
  `@Composable LatestSealThumbnail(latestSealBitmap: ImageBitmap?, sizeDp = 44.dp)`.
  Display-only Composable — when `latestSealBitmap == null` shows
  `Icons.Outlined.Explore` (compass placeholder, matching iOS
  `Image(systemName: "seal")`); else `Image(bitmap = latestSealBitmap, contentScale = Fit)`.

  Bitmap rendering happens IN THE VM (not here) by reusing
  `EtegamiSealBitmapRenderer.renderToBitmap(spec, ink, sizePx, context)` —
  no new helper class. The VM caches the result by
  `Pair(SealSpec, sizePx)` and exposes `latestSealBitmap: StateFlow<ImageBitmap?>`.
  See "Latest-seal pipeline" in Architecture section.
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
  1 = 13` days. Constant `halfCycle = 14.76` verbatim — note iOS uses the
  literal `14.76` here, NOT `synodicMonth / 2.0` (which would equal
  `29.53058770576 / 2 = 14.76529...`); Android matches iOS verbatim with
  the literal. The `< 1.5 day` window absorbs the rounding. Then for each
  event date, locate adjacent dot positions whose start dates bracket it,
  lerp position along Y and X, offset X by ±20 px on the side opposite
  the dot meander.
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
  `fun computeMilestonePositions(snapshots, dotYPositions): List<MilestonePosition(distance, yPx)>`.

  **Algorithm — INTENTIONAL iOS-DIVERGENCE.** iOS `InkScrollView.swift:751-778`
  iterates `snapshots` in display order (newest-first) computing
  `prevCumulative = i > 0 ? snapshots[i-1].cumulativeDistance : 0`, then
  checks `prev < threshold && curr >= threshold`. Because `snapshots` is
  newest-first, `cumulativeDistance` is monotonically *decreasing* with
  `i`, so the check only ever satisfies at `i = 0` (the newest walk
  always has the largest cumulative). The iOS algorithm has a latent bug
  here that the Android port must NOT reproduce.

  **Android iterates oldest-first.** Internally reverse `snapshots` to
  build `oldestFirst: List<WalkSnapshot>` (iOS order is `oldest .. newest`
  before the display reverse). For each threshold in
  `milestoneThresholds()`, walk `oldestFirst` with index `i`:
  `prev = if (i == 0) 0.0 else oldestFirst[i-1].cumulativeDistance`;
  `curr = oldestFirst[i].cumulativeDistance`; if
  `prev < threshold && threshold <= curr`, record the marker at this
  walk's `dotYPositions[displayIndex]` (where `displayIndex` is the
  pre-reverse mapping).

  **Regression test fixture:** 4 walks of 30 km each, cumulative `30 / 60
  / 90 / 120 km`. Expected: 100 km marker on the 4th-oldest walk (the
  newest). NOT all milestones stacked on the newest walk (which the
  verbatim iOS port would produce).
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

  **Seed function — distinct from `CalligraphyStrokeSpec.fnv1aHash`.**
  iOS `SceneryGenerator` mixes:
  - the walk's UUID raw bytes,
  - `startDate.timeIntervalSince1970` (seconds, **not** millis),
  - `distance * 100` (centimeters),
  - `duration` (seconds).

  Android port: `internal fun sceneryFnv1aSeed(uuid: String, startMillis: Long, distanceMeters: Double, durationSeconds: Long): ULong`
  mixing four iOS-equivalent fields with Android-shifted units:
  ```kotlin
  internal fun sceneryFnv1aSeed(
      uuid: String,
      startMillis: Long,
      distanceMeters: Double,
      durationSeconds: Long,
  ): ULong {
      val prime: ULong = 1099511628211UL
      var h: ULong = 14695981039346656037UL
      // Stage 3-C UUID-bytes deviation carries here: Swift hashes raw UUID bytes,
      // Kotlin hashes UTF-16 code units. Acceptable per Stage 3-C precedent.
      uuid.forEach { c -> h = (h xor c.code.toULong()) * prime }
      // iOS `timeIntervalSince1970` is seconds; Android stores millis — divide.
      h = (h xor (startMillis / 1000L).toULong()) * prime
      // iOS `distance * 100` packs cm into the seed; Android matches.
      h = (h xor (distanceMeters * 100.0).toLong().toULong()) * prime
      h = (h xor durationSeconds.toULong()) * prime
      return h
  }
  ```

  **`seededRandom` — SplitMix64 verbatim.** iOS uses Swift's `&*`/`&+`
  (overflow-wrapping arithmetic) and evaluates left-to-right with `*`
  precedence, so the Swift expression `salt &* 6364136223846793005 &+
  seed` reads as `seed + (salt * 6364136223846793005)`:
  ```kotlin
  private fun seededRandom(seed: ULong, salt: ULong): Double {
      var mixed = seed + (salt * 6364136223846793005UL)
      mixed = mixed xor (mixed shr 33)
      mixed *= 0xff51afd7ed558ccdUL
      mixed = mixed xor (mixed shr 33)
      mixed *= 0xc4ceb9fe1a85ec53UL
      mixed = mixed xor (mixed shr 33)
      return (mixed % 10000UL).toDouble() / 10000.0
  }
  ```

  **Stage 14 scenery is STATIC** — `scenery(snapshot)` returns a placement
  + type, but the rendering Composable draws ONLY a `Path.fill` tinted by
  the walk's seasonal color. No `withFrameNanos` animation, no seasonal
  swap (canopy → bare), no time-of-day modifiers. All sub-effects deferred
  to Stage 14.5 (see Non-goals).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItem.kt` —
  `@Composable SceneryItem(type, tintColor, sizePx)` switch-dispatching to
  per-type Composables. **No `walkDate` or `reduceMotion` parameter** —
  scenery is static (Stage 14 scope; sub-effects deferred to Stage 14.5).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/TreeScenery.kt`,
  `LanternScenery.kt`, `ButterflyScenery.kt`, `MoonScenery.kt`,
  `MountainScenery.kt`, `GrassScenery.kt`, `ToriiScenery.kt` — one per type.
  Each draws via `Canvas + Path.fill` ONLY, NO `withFrameNanos`, NO
  time-driven sin/cos. iOS-verbatim path-shape constants (size × 1.08
  echo, size × 0.88 inner, etc.) preserved as constants per file. Tree
  composable picks `treePath` vs `winterTreePath` once at composition
  based on the walk's `LocalDate.month` (no observation; Stage 14.5 will
  handle seasonal canopy swap if revisited).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryShapes.kt` —
  pure path builders: `treePath`, `lanternPath`, `lanternWindowPath`,
  `mountainPath`, `moonPath`, `winterTreePath`, `butterflyPath` (the
  butterfly is built from primitives in `ButterflyScenery.kt`, no Path
  needed). Each takes `(size: Size): Path` so reuse + Robolectric tests
  exercise them directly.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffect.kt` —
  `@Composable RippleEffect(color, dotSizePx)`. Reads
  `LocalReduceMotion.current`. Reduce-motion short-circuit →
  `Canvas(Modifier.size((dotSize + 16.dp))) { drawCircle(color = color, radius = (dotSizePx + 16.dp.toPx()) / 2f, style = Stroke(width = 1.5.dp.toPx()), alpha = 0.15f) }`
  (fallback dimensions: `frame(width: dotSize + 16, height: dotSize + 16)`,
  `lineWidth: 1.5` — verbatim iOS reduce-motion fallback). Else
  `produceState(initial = 0L) + withFrameNanos { now -> if (now - last >=
  100 ms) emit(now); else null }` driving a Canvas that traces 2 expanding
  circles (phase wraps at 1.0, radius `= dotSizePx * 0.5 + phase *
  dotSizePx * 1.2`, opacity `= (1 − phase) * 0.2`) plus a breathing glow
  (`sin(time * 1.2) * 0.5 + 0.5`, glowRadius = `dotSizePx * 1.5`, fill α
  `= 0.04 + breath * 0.04`). Verbatim coefficients.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/LocalReduceMotion.kt` —
  CompositionLocal so 14-A → 14-D animation entry points share a single
  source of truth (I6). `val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }`.
  The provider in `MainActivity` / `PilgrimTheme` reads
  `rememberReducedMotion()` (existing internal helper) and wraps content
  in `CompositionLocalProvider(LocalReduceMotion provides reducedMotion) { content() }`.
  Consumers read `LocalReduceMotion.current` (Composition-time gating).
  See `JournalHapticDispatcher` for the OBSERVER-time gating path
  (handler-time read, not Composition-time).
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
  2-F lesson),
  `app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryActivitySumsTest.kt`
  (verifies `activitySumsFor` Stage-14 stub returns
  `(0L, walk.meditationSeconds ?: 0L)`; covers the cached-meditation
  fallback for missing meditationSeconds → 0L),
  `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStoreTest.kt`
  (round-trip UUID → key → reconstructed UUID assertion + multi-key
  observeAll() verification).

## Files to modify

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` —
  Renamed to `JournalScreen.kt` (or kept as a shim that calls the new
  composable). Body wholly replaced. The tab-route still says
  `Routes.HOME` → keep that string for backward compat with
  `PilgrimNavHost`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt` —
  Rewrite the `uiState` flow to emit `JournalUiState` with `WalkSnapshot`s.
  Add `latestSealSpec: StateFlow<SealSpec?>` (newest finished walk → SealSpec
  via `walk.toSealSpec(distanceMeters, ink, displayDistance, unitLabel)`
  — the actual extension fn signature; see `ui/design/seals/SealSpec.kt:91`).
  Add `latestSealBitmap: StateFlow<ImageBitmap?>` driven by
  `latestSealSpec` × `Pair(SealSpec, sizePx)`-keyed cache —
  `viewModelScope.launch(Dispatchers.Default) { renderToBitmap(...).asImageBitmap() }`
  on each cache miss; emits `null` while rendering.

  Add `expandedSnapshotId: MutableStateFlow<Long?>`. Add
  `_expandedCelestialSnapshot: MutableStateFlow<CelestialSnapshot?>` plus
  a `celestialJob: Job? = null` field. **Lifecycle:** when
  `expandedSnapshotId` flips to a non-null id AND
  `practicePreferences.celestialAwarenessEnabled.value == true`, cancel
  any in-flight `celestialJob`, then
  `celestialJob = viewModelScope.launch(Dispatchers.Default) { _expandedCelestialSnapshot.value = CelestialSnapshotCalc.snapshot(snapshot.startMs, ZoneId.systemDefault(), zodiacSystem) }`.
  Reset to `null` on flip-to-null. Cancel `celestialJob` on next flip.

  **Cancel viewModelScope before db.close()** in tearDown of any test
  (Stage 7-A flake fix).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRowComposable.kt` —
  **Delete file** (HomeWalkRowCard wholly replaced by dot + expand card).
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeUiState.kt` —
  **Delete the `HomeWalkRow` data class** (lives in `HomeUiState.kt`,
  not its own file). Either delete the file outright (if HomeUiState is
  also being replaced by `JournalUiState.kt` — preferred), or rename to
  `JournalUiState.kt` and replace the contents. Update any test references.
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
  Add `fun observeAll(): Flow<Map<String, CachedShare>>` snapshotting all
  per-uuid keys so the VM consumes from a single combine.

  Implementation:
  ```kotlin
  fun observeAll(): Flow<Map<String, CachedShare>> =
      context.cachedShareDataStore.data
          .map { prefs ->
              prefs.asMap().asSequence()
                  .filter { (key, _) -> key.name.startsWith("share_cache_") }
                  .mapNotNull { (key, value) ->
                      val blob = value as? String ?: return@mapNotNull null
                      val cached = decode(blob) ?: return@mapNotNull null
                      val uuid = reconstructUuid(key.name.removePrefix("share_cache_"))
                      uuid to cached
                  }
                  .toMap()
          }
          .distinctUntilChanged()
  ```

  **DataStore key gotcha:** `keyFor(walkUuid)` strips hyphens via
  `walkUuid.replace("-", "")` so the persisted key has NO hyphens. To
  re-emit `Map<canonical-uuid-with-hyphens, CachedShare>` (the shape the
  rest of the app uses), `reconstructUuid(noHyphensKey)` must re-insert
  hyphens at canonical UUID positions: `8-13-18-23` →
  ```kotlin
  private fun reconstructUuid(noHyphens: String): String {
      require(noHyphens.length == 32) { "Expected 32-char UUID-no-hyphens, got ${noHyphens.length}" }
      return "${noHyphens.substring(0, 8)}-${noHyphens.substring(8, 12)}-${noHyphens.substring(12, 16)}-${noHyphens.substring(16, 20)}-${noHyphens.substring(20, 32)}"
  }
  ```
  Round-trip test (round-trip: random UUID → `keyFor` → strip prefix →
  `reconstructUuid` → assertEquals original) lives in
  `CachedShareStoreTest`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt` —
  Add four turning-day colors with light + dark variants. Hex values
  pulled VERBATIM from
  `pilgrim-ios/Pilgrim/Support Files/Assets.xcassets/turning{Jade,Gold,Claret,Indigo}.colorset/Contents.json`
  (sRGB 0.0-1.0 components × 255, rounded):

  ```kotlin
  data class TurningColors(val light: Color, val dark: Color)

  // turningJade: light (0.455, 0.706, 0.584) → #74B495
  //              dark  (0.533, 0.769, 0.627) → #88C4A0
  val turningJade = TurningColors(
      light = Color(0xFF74B495),
      dark  = Color(0xFF88C4A0),
  )

  // turningGold: light (0.788, 0.651, 0.275) → #C9A646
  //              dark  (0.835, 0.710, 0.365) → #D5B55D
  val turningGold = TurningColors(
      light = Color(0xFFC9A646),
      dark  = Color(0xFFD5B55D),
  )

  // turningClaret: light (0.545, 0.267, 0.333) → #8B4455
  //                dark  (0.635, 0.376, 0.439) → #A26070
  val turningClaret = TurningColors(
      light = Color(0xFF8B4455),
      dark  = Color(0xFFA26070),
  )

  // turningIndigo: light (0.137, 0.467, 0.643) → #2377A4
  //                dark  (0.275, 0.569, 0.729) → #4691BA
  val turningIndigo = TurningColors(
      light = Color(0xFF2377A4),
      dark  = Color(0xFF4691BA),
  )
  ```

  Names jade/gold/claret/indigo preserved iOS-canonical. Add four fields
  to `PilgrimColors` data class (`turningJade: Color`, `turningGold:
  Color`, `turningClaret: Color`, `turningIndigo: Color`) and resolve via
  `pilgrimLightColors() / pilgrimDarkColors()` factory functions reading
  from the appropriate variant.
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/WalkFavicon.kt` —
  Add a `materialIcon: ImageVector` field if not already present (Stage 4-D
  added; verify). Map: FLAME → `Icons.Outlined.LocalFireDepartment`,
  LEAF → `Icons.Outlined.Spa`, STAR → `Icons.Outlined.Star`.
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/weather/WeatherCondition.kt` —
  Already has `@DrawableRes val iconRes: Int` per condition (Stage 12-A).
  NO CHANGES NEEDED. Render in expand card via
  `Icon(painterResource(condition.iconRes), contentDescription = ...)`.
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
<string name="journal_expand_view_details">View details</string>  <!-- arrow rendered as Icons.AutoMirrored.Filled.ArrowForward in the Button trailing slot -->

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
| `arrow.right` (in "View details") | `Icons.AutoMirrored.Filled.ArrowForward` — auto-flips in RTL locales matching iOS `Image(systemName: "arrow.right")`. (Unicode `→` does NOT auto-flip; rejected.) |
| `seal` (FAB fallback) | `Icons.Outlined.Explore` (existing) — use only when the latest walk's seal hasn't rendered yet. |
| `ladybug` (DEBUG menu icon) | NOT PORTED (Non-goal). |

Scenery shapes are CUSTOM `androidx.compose.ui.graphics.Path` builders
(NOT Material icons): tree, lantern, lanternWindow, butterfly (drawn from
primitives), mountain, moon, torii, footprint, winterTree (bare-branch
tree variant for winter). All path-math constants ported verbatim from iOS
shape files (`/pilgrim-ios/Pilgrim/Views/Scenery/*.swift`).

WeatherCondition glyph mapping is already established by Stage 12-A —
reuse `WeatherCondition.iconRes` (`@DrawableRes Int`). Render via
`Icon(painterResource(condition.iconRes), ...)`. NO new ImageVector
field needed.

Planetary-hour symbols (sun/moon/mercury/venus/mars/jupiter/saturn) and
zodiac symbols are already mapped in Stage 13-Cel; reuse those Unicode
codepoints.

## Implementation order

The 4 sub-stage buckets correspond to internal task batches. Each task is
~2-5 hours of subagent work; bundled tasks (data + helper) are batched per
the Stage 13-XZ Tasks 4+5 precedent. Total: **20 tasks**, sized so the
stage can be split mid-implementation if scope creeps.

### Bucket 14-A — foundation (5 tasks)

1. **`activitySumsFor` + `walkEventsFor` Repository helpers** — Stage 14
   stub: `activitySumsFor(walkId, walk): Pair<Long, Long>` returns
   `Pair(0L, walk.meditationSeconds ?: 0L)` (no live ActivityInterval
   writer — see Non-goals). `walkEventsFor(walkId): List<WalkEvent>`
   delegates to `walkEventDao.getForWalk(walkId)` (verified DAO method).
   Add a `// TODO Stage 14.X: wire live ActivityInterval recording from WalkViewModel`
   comment in the activitySumsFor body. Tests: meditationSeconds = 600L
   → `(0L, 600L)`; null → `(0L, 0L)`. Both `open suspend fun` so fakes
   can override.
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
8. **VM bitmap cache via `EtegamiSealBitmapRenderer.renderToBitmap` +
   `LatestSealThumbnail`** — REUSE the existing
   `EtegamiSealBitmapRenderer` (Stage 7-C, in `ui/etegami/`); convert
   via `bitmap.asImageBitmap()`. VM caches by `Pair(SealSpec, sizePx)`
   in a `LinkedHashMap` (size 4) wrapped in `MutableStateFlow<ImageBitmap?>`.
   Render runs on `Dispatchers.Default`. `LatestSealThumbnail` is a
   display-only Composable consuming `latestSealBitmap`. Test:
   re-rendering the same `(SealSpec, sizePx)` twice returns from cache
   (no second render coroutine launched).
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
    Verbatim FNV-seeded probability port (`sceneryFnv1aSeed` + SplitMix64
    `seededRandom` — see Files-to-create); 7 path builders (each tested
    via `Path.getBounds()` to assert shapes don't escape their declared
    size). Includes `winterTreePath` for the winter `tree` variant. Stage
    3-C path-test pattern.
17. **`SceneryItem` Composable + 7 per-type sub-composables (STATIC
    FILLS ONLY)** — Each sub-composable draws ONE `Path.fill` call tinted
    by the walk's seasonal color. NO `withFrameNanos`, NO sin/cos, NO
    seasonal swap (canopy → bare done at composition based on month, no
    re-observation). Robolectric `composeRule.setContent` smoke tests
    assert each type composes without crash; bounds-tests cover the path
    math. (Sub-effects deferred to Stage 14.5 per Non-goals.)
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
    `LocalReduceMotion.current` boolean from the new
    `ui/design/LocalReduceMotion.kt` CompositionLocal (provided once in
    `MainActivity`/`PilgrimTheme` via the existing `rememberReducedMotion()`
    helper as the default value source). `JournalHapticDispatcher`
    EXPLICITLY does NOT consume this Local — it reads `Settings.Global`
    at handler-time so Quick-Settings flips mid-scroll take effect on
    the next dispatch (see I10 in Files-to-create). Final QA
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
- `WalkRepositoryActivitySumsTest` — Stage 14 stub: returns
  `Pair(0L, walk.meditationSeconds ?: 0L)`. Tests:
  (1) `meditationSeconds = 600L` → `(0L, 600L)`;
  (2) `meditationSeconds = null` → `(0L, 0L)`;
  (3) talk seconds always 0L regardless of any persisted ActivityIntervals
  (since native walks don't write any). When live recording lands in
  Stage 14.X this test expands to cover the real DAO aggregate.

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

1. **Hemisphere reactivity in dot color** — `dotColor` reads the user's
   hemisphere to pick the seasonal palette. **Decision:** read
   `hemisphereRepository.hemisphere.value` ONCE at snapshot-build time
   (in `HomeViewModel.buildSnapshots`), passing the snapshot a frozen
   `hemisphere: Hemisphere`. No live recomposition on hemisphere flip.
   Rationale: hemisphere is set at first walk + rarely flipped; the cost
   of re-snapshot + re-render on flip > the cost of a cold-restart
   refresh. iOS does the same (computed once when `WalkSnapshot` is
   constructed). RECOMMENDED.
2. **Active-walk in-progress filter race** — the existing
   `walks.filter { it.endTimestamp != null }` correctly excludes
   in-progress walks. The Stage 7-A + 11-A finalize race surfaces in
   THIS filter (the Flow can emit a walk with null endTimestamp briefly
   between INSERT-with-startMs and UPDATE-with-endMs). Stage 14
   inherits the existing pattern's correctness — no new guards needed.
3. **Multi-finger / scroll-fling haptic flooding** — `JournalHapticDispatcher`
   tracks `lastDispatchNs: Long` and skips any dispatch within 50 ms of
   the previous one. Defends against multi-finger gestures + fast-fling
   producing 30+ haptics per second. Documented in Files-to-create.
4. **WalkSnapshot stable identity for ModalBottomSheet open/close** —
   when the underlying snapshots list changes (e.g. a new walk is
   recorded while the sheet is open), the `expandedSnapshotId: Long?`
   should re-resolve to the matching snapshot in the new emission. If
   the snapshot's id is no longer present (deleted), the VM clears
   `_expandedSnapshotId.value = null` (which dismisses the sheet).
   Decision: re-derive the visible snapshot from current snapshots on
   every emission and clear `expandedSnapshotId` if missing.
5. **Scope split — internal mid-stage merge gate** — Stage 14 ships in
   ONE PR (per Stage 13-XZ precedent), but with a deliberate
   "mid-implementation device QA pause" between bucket 14-A
   (foundation: dots + LazyColumn + haptics) and buckets 14-B/C/D
   (chrome + overlays + polish). At end-of-14-A: dogfood on OnePlus 13
   for 24h to catch regressions in the dot/scroll/haptic core BEFORE
   adding 15+ overlay & polish surfaces. If 14-A regresses, fix-and-pause
   instead of stacking more code on top.

## Documented iOS deviations (in code comments)

These are intentional. Each call site below MUST carry an SPDX-aligned
comment with the rationale linking back to this spec.

1. **CalligraphyPath FNV hash differs from iOS** — Stage 3-C inheritance.
   Already documented in `CalligraphyStrokeSpec.kt`. No action; carries
   forward into `WalkSnapshot` since the renderer reads `uuid` not raw
   bytes.
2. **Talk-duration is hard-zero for native walks; meditation reads cached
   `Walk.meditationSeconds`** — iOS reads `walk.talkDuration` and
   `walk.meditateDuration` as CoreData computed properties from
   `activity_intervals`. Android Stage 14 has no live
   `ActivityIntervalCoordinator`; native walks never write talk
   intervals. Rather than stand up the coordinator inside Stage 14, we
   substitute `Walk.meditationSeconds` (already populated by
   `WalkMetricsCache`) for meditation totals and zero-out talk. Imported
   .pilgrim ZIPs from iOS contain talk intervals but Stage 14 does NOT
   surface them — visually correct (no talk happened on this device)
   but functionally degraded for ZIP-imported walks. `activitySumsFor`
   signature is `Pair<Long, Long>` (talkSec, meditateSec) so the call
   sites stay stable when Stage 14.X wires the live coordinator. TODO
   Stage 14.X: wire live ActivityInterval recording from WalkViewModel.
3. **Expand card via Material 3 ModalBottomSheet, NOT iOS overlay sheet** —
   Android idiom; matches Stage 13-XZ PromptListSheet precedent. Behavior
   parity (open from below, dismissible, semi-modal) preserved; visual
   detail (ultraThinMaterial → BottomSheetDefaults.containerColor) approximated.
4. **Long-press dot preview omitted** — Audit Gap 13 (vestigial on iOS).
5. **DEBUG seed/clear menu omitted** — Audit Gap 2 (out of scope; instrumentation
   covers QA).
6. **Reuse existing `EtegamiSealBitmapRenderer` for FAB thumbnail** — iOS
   uses `SealGenerator.thumbnail(from: input) -> UIImage` (CPU-rendered).
   Android reuses `EtegamiSealBitmapRenderer.renderToBitmap` (Stage 7-C,
   in `ui/etegami/`) verbatim, converting the result via
   `Bitmap.asImageBitmap()` for Compose consumption. Rendering cost
   ~190 ms cold; cached by `Pair(SealSpec, sizePx)` in the VM. NO new
   helper class introduced — the etegami renderer was already shipped.
7. **`MoonCalc.SYNODIC_DAYS = 29.530588770576` vs iOS
   `LunarPhase.synodicMonth = 29.53058770576`** — Android value has an
   extra `8` digit at fractional position 6 (`29.530**5**88...` vs
   `29.5305**8**8...`). NOT a "trailing zero" difference — the Android
   port mistakenly inserted a digit during Stage 5-A. Drift < 3 s over
   30 lunations. Acceptable but documented. Flagged in code comments.
8. **`halfCycle = 14.76` literal in `LunarMarkerCalc`** — iOS uses the
   literal `14.76` for the half-synodic-month constant in lunar-event
   detection, NOT `synodicMonth / 2.0` (which would be `14.76529...`).
   Android matches iOS verbatim with the literal so the marker positions
   are byte-identical. The `< 1.5 day` window absorbs the rounding.
9. **`verticalSpacing = 90 dp, topInset = 40 dp` reverts Stage 3-F's
   132-dp tuning** — Stage 3-F bumped row stride to 132 dp because the
   cards justified extra breathing room. Stage 14 deletes the cards;
   the 90-dp iOS-verbatim value is correct for dot-primary layout.
   Re-QA on OnePlus 13 expected to confirm acceptable density.
10. **No `id(unitKey)` viewport reset** — iOS uses `.id(unitKey)` on
    `InkScrollView` to nuke the entire view tree on unit toggle (forcing a
    re-render). Compose recomposes naturally on the units StateFlow flip,
    so no manual key needed. iOS comment is for the SwiftUI quirk where
    `.contentTransition(.numericText())` doesn't re-render distance labels
    on unit flip without a viewport key.
11. **`computeMilestonePositions` walks oldest→newest, NOT verbatim
    iOS newest-first** — iOS `InkScrollView.swift:751-778` iterates
    `snapshots` in display (newest-first) order with
    `prevCumulative = i > 0 ? snapshots[i-1].cumulativeDistance : 0`,
    then checks `prev < threshold && curr >= threshold`. With
    newest-first ordering, `cumulativeDistance` is monotonically
    *decreasing* with `i`, so the check satisfies only at `i = 0` —
    iOS effectively places EVERY milestone on the newest walk. Stage
    14 ports the algorithm with reversed iteration to fix this latent
    iOS bug; regression test with 4 walks of 30 km each (cumul 30/60/90/120)
    asserts the 100 km marker lands on the 4th-oldest walk (newest),
    NOT all milestones stacked there.
