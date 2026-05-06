# Stage 14-BCD bundle — ExpandCardSheet + Overlays + Polish

## Context

Stage 14-A merged via PR #86 (commits `cd3fe58 ← b3df1a2`) and shipped the
foundation of the iOS-parity Journal: `WalkSnapshot` + `JournalUiState` +
`JourneySummary` data, the `WalkDot` Composable (halo + 3D core gradient +
specular highlight + activity arcs + animated ripple + favicon overlay +
shared-walk ring), `WalkDotColor` (turning override on equinox/solstice
→ jade/gold/claret/indigo, else moss), `ScrollHapticState` +
`JournalHapticDispatcher`, the four turning colors (light + dark) in
`PilgrimColors`, the seal-bitmap pipeline via `EtegamiSealBitmapRenderer`,
the Goshuin FAB chrome (64dp parchmentTertiary disc + stone-stroke ring +
52dp seal clipped, ink = stone), `JourneySummaryHeader` (3-state cyclable),
inline distance labels (±32 dp / +14 dp Y per `InkScrollView.swift:627-637`),
opposite-side month markers, the calligraphy thread blur (0.6 dp on API 31+),
the 7 scenery types as static fills (20-36 dp range), `LocalReduceMotion` +
theme-wide `LocalRippleConfiguration null` + `LocalIndication NoIndication`
(transparent on tap globally), `SealRevealStore`, plus Settings + About
animation polish.

What is still missing relative to iOS v1.5.0 (`db4196e`): the **expand-card
sheet** that appears on dot tap (currently a tap routes straight to Walk
Summary — iOS shows a rich on-tap card first), the **overlays** that frame
the journal (turning-day banner, lunar markers at full/new moons, milestone
bars at 100k/500k/Nx1M cumulative crossings, plus the date-divider helper
extracted to a testable pure function), the two **path-shape builders**
(`ToriiGateShape`, `FootprintShape`) that the milestone marker and
expand-card header consume, and the **polish layer** (cascading fade-in on
first composition, the empty-state tail + stone dot + "Begin" copy
replacing the current empty Text).

These nine remaining tasks bundle into one PR because they all live above
the Stage 14-A foundation, share the same `JournalScreen.kt` integration
seam, and have no external blockers. Splitting Bucket 14-B (chrome) from
14-C (overlays) would require shipping 14-B without the milestone bar that
14-C builds — incoherent for review. Splitting 14-D (polish) is a fade-in
flag and an empty-state Composable; not enough surface to warrant a
separate PR cycle.

## Goal

Close the iOS-v1.5.0 Journal parity gap in a single PR by shipping the
nine remaining bucket-14-B/C/D internal tasks: rich expand-card sheet on
dot tap, turning-day banner above the canvas, lunar markers between dots,
milestone bars at cumulative thresholds, extracted date-divider helper,
two reusable path-shape builders, cascading fade-in for segments + dots +
scenery, empty-state tail composable, and final on-device QA verification.

## Non-goals

- **Scenery animated sub-effects** — canopy seasonal swap, falling leaves,
  dewdrops, alpenglow, snow flakes, moon clouds + stars + rays, torii
  shimenawa rope + shide. All deferred to Stage 14.5 per Stage 14-A
  precedent. Bundle ships scenery as static fills (already the case).
- **Live talk-arc rendering for native walks** — Stage 14.X work; no live
  `ActivityIntervalCoordinator` exists yet, so `activitySumsFor` returns
  `(0L, walk.meditationSeconds)`. Talk shows zero in the mini-bar +
  pills; the spec accepts this.
- **LazyColumn re-virtualization** — Stage 14-A kept the `Column +
  verticalScroll` from the original migration. With <100 walks the
  eager-render cost is acceptable. Promotion to LazyColumn deferred until
  scroll perf measurably regresses.
- **Turning kanji i18n** — English source-only banner + verbatim
  `春分/夏至/秋分/冬至` Kanji. No locale-aware substitution.
- **Long-press dot preview** — vestigial on iOS (Audit Gap 13). Tap-only.
- **DEBUG seed/clear menu** — Audit Gap 2 (out of scope).
- **Hemisphere-flip recomposition of the lunar/milestone overlays** —
  hemisphere is read once at snapshot-build time (Stage 14-A pattern,
  Open-Question #1 of the original spec). Flipping mid-session does not
  re-derive the overlays until the next cold start; matches iOS.
- **Parity beyond v1.5.0 (`db4196e`)** — out of scope per `CLAUDE.md`
  parity-frozen rule.

## Architecture

### Layout shape (post-Stage 14-A)

The Journal already renders as:

```
Box(parchment fill)
  Column
    Sticky "Pilgrim Log" Text (16dp outer + 8dp/16dp inner padding,
                               matching SettingsScreen)
    Box (content area)
      when (journalState):
        Loading -> CircularProgressIndicator
        Empty   -> centered home_empty_message Text  ← Bucket 14-D replaces
        Loaded  -> Column.verticalScroll
                     JourneySummaryHeader
                     BoxWithConstraints (canvas)
                       CalligraphyPath (blurred)
                       forEach snapshot:
                         [SceneryItem]
                         WalkDot (offset to xPx, yPx)
                         distance label (Text at offset)
                         month label (Text at offset, first walk per month)
  GoshuinFAB (BottomEnd, 64dp parchmentTertiary disc)
```

This bundle adds, in canvas-Z order:

1. **TurningDayBanner** above `JourneySummaryHeader` (32-40dp row when
   today is an equinox/solstice; 0dp height otherwise so layout collapses).
2. **Lunar markers** drawn as 10×10 dp Box.offset siblings inside the
   canvas Box, derived from the existing `dotPositions` list.
3. **Milestone bars** drawn as full-width Row siblings inside the canvas
   Box, derived from cumulative-distance crossings.
4. **DateDividerCalc** — a pure helper extracting the inline `showMonth`
   logic in `HomeScreen.kt:355-380` so it's unit-testable. UI behavior
   unchanged.
5. **ExpandCardSheet** — a Material 3 `ModalBottomSheet` shown when the VM's
   already-shipped `expandedSnapshotId: StateFlow<Long?>` is non-null.
   Replaces the current `onTap = { onEnterWalkSummary(snap.id) }` flow on
   `WalkDot`.
6. **Cascading fade-in** — `Modifier.graphicsLayer { alpha = ... }` lambda
   form (Stage 5-A lesson) on the calligraphy + per-dot + per-scenery
   Composables, driven by a `hasAppeared` flag flipped via
   `LaunchedEffect(Unit)` once on first composition.
7. **EmptyJournalState** Composable replacing the empty-state Text branch.

The dot tap flow rewires from
`onTap = { onEnterWalkSummary(snap.id) }` to
`onTap = { homeViewModel.setExpandedSnapshotId(snap.id) }`. The expand
card's `Button("View details →")` is the new sole entry point to
`onEnterWalkSummary`.

### iOS-Android type mapping

The original Stage 14 spec maps the foundation primitives. Bundle-specific
mappings:

| iOS | Android |
|---|---|
| `expandCard: some View` (`InkScrollView.swift:300-403`) | `@Composable ExpandCardSheet(snapshot, celestial?, seasonColor, onViewDetails, onDismissRequest)` rendering `ModalBottomSheet` |
| `FootprintShape()` (Path) | `fun footprintPath(size: Size): Path` |
| `ToriiGateShape()` (Path) | `fun toriiGatePath(size: Size): Path` |
| `Image(systemName: "arrow.right")` | `Icons.AutoMirrored.Filled.ArrowForward` (auto-flips RTL) |
| `Image(systemName: "link")` | `Icons.Outlined.Link` |
| `WalkDotView.formatDuration(snapshot.duration)` | `WalkFormat.formatDuration(seconds)` (existing) |
| `Self.expandDateFormatter.string(from: ...)` (`.full + .short`) | `DateTimeFormatter.ofLocalizedDateTime(FULL, SHORT).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault())` |
| `LunarMarkerDot` (`InkScrollView+LunarMarkers.swift:15-29`) | `@Composable LunarMarkerDot(isFullMoon: Boolean)` 10×10 dp Box, `isSystemInDarkTheme()` gated colors |
| `LunarMarker` struct | `data class LunarMarker(idTag, xPx, yPx, illumination, isWaxing)` `@Immutable` |
| `findLunarEvents(from:to:)` + `refinePeak(...)` | `internal fun findLunarEvents(start, end): List<LunarEvent>` + `refinePeak(near, isFullMoon)` |
| `interpolatePosition(for:positions:)` | `internal fun interpolatePosition(targetMs, snapshots, dotPositions): Triple<DotPosition, DotPosition, Double>?` |
| `MilestoneMarkerView` (`MilestoneMarkerView.swift`) | `@Composable MilestoneMarker(widthPx, distanceM, units)` Row of `Spacer(0.5dp height)` + `Canvas(toriiGatePath)` + `Text` + trailing hairline; constrained to `widthPx * 0.7f` |
| `computeMilestonePositions(positions:)` (`InkScrollView.swift:751-778`) | `fun computeMilestonePositions(snapshots, dotPositions): List<MilestonePosition>` (oldest-first iteration — see Documented iOS deviations) |
| `milestoneThresholds()` | `fun milestoneThresholds(): List<Double>` |
| `dateLabels(positions:)` (`InkScrollView.swift:658-704`) | `fun computeDateDividers(snapshots, dotPositions, viewportWidthPx, locale, zone): List<DateDivider>` |
| `TurningDayService.turning(for:hemisphere:)` | `fun turningMarkerFor(walkStartMs): SeasonalMarker?` (Stage 14-A `WalkDotColor.kt`); reused for banner + extension to receive `LocalDate` overload via the same body |
| `SeasonalMarker.kanji` (asset string) | `fun SeasonalMarker.kanji(): String?` extension (verbatim 春分/夏至/秋分/冬至) |
| `SeasonalMarker.bannerText` (asset string) | `@StringRes fun SeasonalMarker.bannerTextRes(): Int?` |

### Sub-stage groupings

Tasks numbered 1-9 globally. The original Stage 14 spec used a separate
"Bucket task N" label per bucket (Task 7 in Bucket 14-B vs Task 7 in
Bucket 14-D were different things). This bundle renumbers to a single
sequence so "Task N" is unambiguous across Sub-stage groupings,
Files-to-create, Implementation order, and Tests sections.

- **Bucket 14-B chrome — Task 1** (1 task): ExpandCardSheet + the two
  embedded primitives MiniActivityBar and ActivityPills. Tap rewiring +
  Walk Summary nav handoff.
- **Bucket 14-C overlays — Tasks 2-6** (5 tasks): TurningDayBanner +
  SeasonalMarker.kanji/bannerTextRes extensions, LunarMarkerCalc +
  LunarMarkerDot, MilestoneCalc + MilestoneMarker, DateDividerCalc
  extraction, FootprintShape + ToriiGateShape path builders.
- **Bucket 14-D polish — Tasks 7-9** (3 tasks): Cascading fade-in,
  EmptyJournalState replacement, final integration + on-device QA.

## Files to create

### Bucket 14-B chrome — Task 1 ExpandCardSheet

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt`
  — `@Composable ExpandCardSheet(snapshot: WalkSnapshot, celestial: CelestialSnapshot?,
  seasonColor: Color, units: DistanceUnits, isShared: Boolean,
  onViewDetails: (Long) -> Unit, onDismissRequest: () -> Unit)`. Renders a
  Material 3 `ModalBottomSheet(onDismissRequest = onDismissRequest,
  sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true))`.
  Body in iOS verbatim order (`InkScrollView.swift:312-385`):
  1. **Header row** — `FootprintShape` 12×18 dp filled `seasonColor.copy(alpha=0.3f)`,
     favicon `Icon(walkFavicon.icon, tint = seasonColor)` if present,
     date+time `Text` (style = `pilgrimType.annotation`, color = `pilgrimColors.ink`),
     `Spacer(Modifier.weight(1f))`, conditional shared-link `Icon(Icons.Outlined.Link,
     size = 10dp, tint = pilgrimColors.stone, alpha = 0.5f)`,
     conditional planetary-hour + moon-sign Unicode `Text("${planet.symbol}${moonSign.symbol}",
     style = TextStyle(fontSize = 10.sp), color = pilgrimColors.fog)` —
     ONLY when `celestial != null`, weather glyph
     `Icon(painterResource(condition.iconRes), size = caption-equivalent, tint = pilgrimColors.fog)`
     if `snapshot.weatherCondition != null`.
  2. **Divider** — 1 dp height, `seasonColor.copy(alpha=0.15f)`.
  3. **3-stat Row** — `expandStat("distance", value)`, `Spacer.weight(1f)`,
     `expandStat("duration", value)`, `Spacer.weight(1f)`,
     `expandStat("pace", value)`. Each `expandStat` is a `Column(spacing=2dp)`
     of value (`pilgrimType.statValue`, `ink`) + label (`pilgrimType.micro`, `fog`).
  4. **MiniActivityBar** (sub-Composable below).
  5. **ActivityPills** (sub-Composable below).
  6. **"View details" Button** — full-width capsule (`RoundedCornerShape(percent=50)`),
     `pilgrimColors.stone.copy(alpha=0.8f)` background, `pilgrimColors.parchment` content;
     trailing icon `Icons.AutoMirrored.Filled.ArrowForward`. On click: capture
     `id = snapshot.id`, call `onDismissRequest()`, then `onViewDetails(id)`.
     `BackHandler(enabled = true) { onDismissRequest() }` invoked at top of body.
  7. `rememberUpdatedState(onDismissRequest)` (Stage 4-B lesson) for the
     dismiss callback used inside the Button onClick lambda — guards against
     stale-closure if the parent recomposes mid-tap.

  Padding: 16dp inside the sheet content; outer `paddingHorizontal =
  PilgrimSpacing.normal`; `paddingBottom = 8dp`.
  Date format:
  ```kotlin
  remember(snapshot.startMs) {
      DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
          .withLocale(Locale.getDefault())
          .withZone(ZoneId.systemDefault())
          .format(Instant.ofEpochMilli(snapshot.startMs))
  }
  ```
  (Stage 6-B lesson — never the no-Locale ofPattern overload.)

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt`
  — `@Composable MiniActivityBar(snapshot, modifier = Modifier)`. Computes
  `val totalLong = max(1L, snapshot.durationSec.toLong())` (Stage 14-A
  `WalkSnapshot.durationSec` is `Double`),
  `walkFrac = snapshot.walkOnlyDurationSec.toFloat() / totalLong.toFloat()`,
  `talkFrac = snapshot.talkDurationSec.toFloat() / totalLong.toFloat()`,
  `meditateFrac = snapshot.meditateDurationSec.toFloat() / totalLong.toFloat()`. Renders
  ```
  Row(Modifier.height(6.dp).clip(CapsuleShape).fillMaxWidth(), spacing = 1dp)
    if (walkFrac > 0.01f) Box(weight=walkFrac, RoundedCornerShape(2dp), pilgrimColors.moss.copy(0.5f))
    if (talkFrac > 0.01f) Box(weight=talkFrac, RoundedCornerShape(2dp), pilgrimColors.rust.copy(0.6f))
    if (meditateFrac > 0.01f) Box(weight=meditateFrac, RoundedCornerShape(2dp), pilgrimColors.dawn.copy(0.6f))
  ```
  Hide-below-1% rule verbatim from `InkScrollView.swift:425-441`.

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt`
  — `@Composable ActivityPills(snapshot, modifier = Modifier)`. Renders
  `Row(spacing = 8.dp)` containing:
  - Always: `pill(color = moss, durationSec = walkOnlyDurationSec, label = R.string.journal_expand_pill_walk)`.
  - If `snapshot.hasTalk`: `pill(rust, talkDurationSec, R.string.journal_expand_pill_talk)`.
  - If `snapshot.hasMeditate`: `pill(dawn, meditateDurationSec, R.string.journal_expand_pill_meditate)`.
  - Trailing `Spacer.weight(1f)`.

  Each `pill` = `Row(spacing = 3dp)` with `Box(5×5 dp, CircleShape, color)` +
  `Text("${WalkFormat.formatDuration(seconds)} ${stringResource(label)}",
  style = pilgrimType.micro, color = pilgrimColors.fog)`.

### Bucket 14-C overlays — Task 2 TurningDayBanner

- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurnings.kt`
  — Kotlin extension functions on `SeasonalMarker`:
  ```kotlin
  fun SeasonalMarker.isTurning(): Boolean = when (this) {
      SeasonalMarker.SpringEquinox, SeasonalMarker.SummerSolstice,
      SeasonalMarker.AutumnEquinox, SeasonalMarker.WinterSolstice -> true
      else -> false
  }

  fun SeasonalMarker.kanji(): String? = when (this) {
      SeasonalMarker.SpringEquinox  -> "春分"
      SeasonalMarker.SummerSolstice -> "夏至"
      SeasonalMarker.AutumnEquinox  -> "秋分"
      SeasonalMarker.WinterSolstice -> "冬至"
      else -> null
  }

  @StringRes
  fun SeasonalMarker.bannerTextRes(): Int? = when (this) {
      SeasonalMarker.SpringEquinox, SeasonalMarker.AutumnEquinox ->
          R.string.turning_equinox_banner
      SeasonalMarker.SummerSolstice, SeasonalMarker.WinterSolstice ->
          R.string.turning_solstice_banner
      else -> null
  }
  ```

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt`
  — `@Composable TurningDayBanner(marker: SeasonalMarker?, modifier = Modifier)`.
  When `marker == null || !marker.isTurning()`: emits a zero-height Box
  (so the parent's offset math collapses cleanly). Else emits a centered
  `Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp),
  horizontalArrangement = Arrangement.Center, spacing = 8.dp)`:
  ```
  Text(stringResource(marker.bannerTextRes()!!), style = pilgrimType.caption,
       color = pilgrimColors.fog)
  Text("·", style = pilgrimType.caption, color = pilgrimColors.fog.copy(0.5f))
  Text(marker.kanji()!!, style = pilgrimType.caption, color = pilgrimColors.fog)
  ```
  No animation — banner is static.

  Caller (`HomeScreen.kt`) computes `currentTurning =
  walkDotBaseColor`-style helper (see Files-to-modify): `remember {
  turningMarkerForToday(clock = Clock.systemDefaultZone()) }`. The
  TurningDayBanner is placed BEFORE `JourneySummaryHeader` in the
  scrollable Column.

### Bucket 14-C overlays — Task 3 LunarMarkerCalc + LunarMarkerDot

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt`
  — pure-Kotlin, no Compose imports.
  ```kotlin
  @Immutable
  data class LunarMarker(
      val idTag: String,
      val xPx: Float,
      val yPx: Float,
      val illumination: Double,
      val isWaxing: Boolean,
  )

  fun computeLunarMarkers(
      snapshots: List<WalkSnapshot>,        // newest-first per VM emission
      dotPositions: List<DotPosition>,      // CalligraphyPath.dotPositions output, parallel to snapshots
      viewportWidthPx: Float,
  ): List<LunarMarker>
  ```
  Algorithm (verbatim port of `InkScrollView+LunarMarkers.swift:40-65`):
  1. `if (snapshots.size < 2) return emptyList()`.
  2. `earliestMs = snapshots.minOf { it.startMs }`,
     `latestMs = snapshots.maxOf { it.startMs }`.
  3. `events = findLunarEvents(earliestMs, latestMs)`.
  4. For each event, locate `(posA, posB, fraction)` via `interpolatePosition`.
     Y = `posA.yPx + fraction * (posB.yPx - posA.yPx)`.
     midX = `posA.centerXPx + fraction * (posB.centerXPx - posA.centerXPx)`.
     `markerX = if (midX > viewportWidthPx / 2f) midX - 20f else midX + 20f`.
  5. Append `LunarMarker(idTag = "lunar-$index", ...)` (use `idTag` instead
     of `id` to avoid Compose `Identifiable`-by-id collision with snapshot ids).

  ```kotlin
  internal data class LunarEvent(
      val instantMs: Long,
      val illumination: Double,
      val isWaxing: Boolean,
  )

  internal fun findLunarEvents(startMs: Long, endMs: Long): List<LunarEvent> {
      // halfCycle = 14.76 (verbatim iOS literal — NOT MoonCalc.SYNODIC_DAYS / 2).
      // Use Instant.ofEpochMilli; advance via Instant.plus(Duration.ofDays(...)).
      // Outer loop: while (checkMs <= endMs).
      // Compute MoonPhase via MoonCalc.moonPhase(checkMs) — already public.
      // isNearNew = phase.age < 1.5 || phase.age > 28.0
      // isNearFull = abs(phase.age - 14.76) < 1.5
      // If either: refinePeak(checkMs, isNearFull); record event;
      //   advance by (Int(halfCycle) - 1) = 13 days.
      // Else: advance by 1 day.
  }

  internal fun refinePeak(nearMs: Long, isFullMoon: Boolean): Long {
      // 12 candidate offsets: -36 .. +36 in 6-hour steps.
      // score = isFullMoon ? phase.illumination : 1 - phase.illumination.
      // Return ms with max score.
  }

  internal fun interpolatePosition(
      targetMs: Long,
      snapshots: List<WalkSnapshot>,
      dotPositions: List<DotPosition>,
  ): Triple<DotPosition, DotPosition, Double>?
  ```

  iOS `interpolatePosition` (verbatim port): walk `0..(snapshots.size - 2)`,
  let `dateA = snapshots[i].startMs`, `dateB = snapshots[i+1].startMs`,
  `earlier = min(dateA, dateB)`, `later = max(dateA, dateB)`. If
  `target in earlier..later`:
  `totalInterval = (later - earlier).toDouble()`,
  `fraction = if (totalInterval > 0) (target - earlier).toDouble() / totalInterval else 0.5`,
  `adjusted = if (dateA < dateB) fraction else 1.0 - fraction`,
  return `Triple(dotPositions[i], dotPositions[i+1], adjusted)`.

  **Edge case — identical `startMs`:** when `dateA == dateB`,
  `totalInterval = 0` triggers the `else 0.5` branch. The marker lands
  at the midpoint between the two dot positions, which are themselves
  visually adjacent (same vertical-spacing stride; meander-x may
  differ by FNV hash). Acceptable visual outcome — the lunar marker
  reads as nestled between the simultaneous walks rather than picking
  one. Verbatim iOS behavior; documented for the test fixture rather
  than guarded against.

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt`
  — `@Composable LunarMarkerDot(isFullMoon: Boolean, modifier = Modifier)`.
  10×10 dp Box. Color (verbatim iOS RGBs):
  - dark theme: `Color(red=0.85f, green=0.82f, blue=0.72f)`
  - light theme: `Color(red=0.55f, green=0.58f, blue=0.65f)`

  Full moon: `Box(Modifier.size(10.dp).clip(CircleShape).background(moonColor.copy(
  alpha = if (isSystemInDarkTheme()) 0.6f else 0.4f)))`.
  New moon: `Box(Modifier.size(10.dp).border(width = 1.dp,
  color = moonColor.copy(alpha = if (isSystemInDarkTheme()) 0.7f else 0.5f),
  shape = CircleShape))`.

### Bucket 14-C overlays — Task 4 MilestoneCalc + MilestoneMarker

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt`
  — pure-Kotlin.
  ```kotlin
  fun milestoneThresholds(): List<Double> {
      // Returns [100k, 500k, 1M, 2M, 3M, ..., 100M]. Verbatim iOS:
      // first three discrete thresholds, then 1M-step from 2M to 100M.
      return buildList {
          add(100_000.0); add(500_000.0); add(1_000_000.0)
          var next = 2_000_000.0
          while (next <= 100_000_000.0) { add(next); next += 1_000_000.0 }
      }
  }

  @Immutable
  data class MilestonePosition(val distanceM: Double, val yPx: Float)

  fun computeMilestonePositions(
      snapshots: List<WalkSnapshot>,        // newest-first per VM emission
      dotPositions: List<DotPosition>,      // parallel to snapshots
  ): List<MilestonePosition>
  ```

  **Algorithm — INTENTIONAL iOS divergence (Stage 14-A `Documented iOS
  deviations` #11; carried verbatim into this bundle).** iOS iterates
  `snapshots` in display order (newest-first) computing
  `prevCumulative = i > 0 ? snapshots[i-1].cumulativeDistance : 0`. Because
  snapshots are newest-first, cumulative distance is *decreasing* with `i`,
  so the iOS check `prev < threshold && curr >= threshold` only ever
  satisfies at `i = 0` — every milestone collapses onto the newest walk
  (latent iOS bug).

  Android iterates **oldest-first**. Internally:
  ```kotlin
  if (snapshots.size < 2) return emptyList()
  val oldestFirst = snapshots.asReversed()
  val oldestPositions = dotPositions.asReversed()
  val results = mutableListOf<MilestonePosition>()
  for (threshold in milestoneThresholds()) {
      if (threshold > (oldestFirst.last().cumulativeDistanceM)) break
      for (i in oldestFirst.indices) {
          val prev = if (i == 0) 0.0 else oldestFirst[i - 1].cumulativeDistanceM
          val curr = oldestFirst[i].cumulativeDistanceM
          if (prev < threshold && threshold <= curr) {
              if (i < oldestPositions.size) {
                  results += MilestonePosition(threshold, oldestPositions[i].yPx)
              }
              break
          }
      }
  }
  return results
  ```

  **Regression-test fixture** (`MilestoneCalcTest`): 4 walks of 30 km each,
  cumulative `30 / 60 / 90 / 120 km` (oldest-first). Expected:
  `[MilestonePosition(100_000.0, dotPositions[3 in display-order].yPx)]` —
  the 100 km marker lands on the 4th-oldest walk (which is the newest).
  NOT all milestones stacked on the newest walk (which the verbatim iOS
  port would produce).

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt`
  — `@Composable MilestoneMarker(distanceM: Double, units: DistanceUnits,
  modifier = Modifier)`. Renders inside a `Row(Modifier.fillMaxWidth(0.7f),
  spacing = 6.dp, verticalAlignment = CenterVertically)`:
  ```
  Spacer(Modifier.weight(1f).height(0.5dp).background(pilgrimColors.fog.copy(0.15f)))
  Canvas(Modifier.size(width=16dp, height=14dp)) {
      drawPath(toriiGatePath(size), pilgrimColors.stone.copy(alpha=0.25f))
  }
  Text(distanceText(distanceM, units), style = pilgrimType.micro,
       color = pilgrimColors.fog.copy(alpha=0.4f))
  Spacer(Modifier.weight(1f).height(0.5dp).background(pilgrimColors.fog.copy(0.15f)))
  ```
  Where `distanceText` returns
  `String.format(Locale.US, if (units == miles) "%d mi" else "%d km", ...)`
  with the integer division `(distanceM / 1609.344).toInt()` for miles or
  `(distanceM / 1000).toInt()` for km. `Locale.US` is intentional for the
  numeric body (Stage 5-A locale convention).

  Accessibility: `Modifier.semantics { contentDescription =
  context.getString(R.string.journal_milestone_a11y, "%d km".format(...)) }`.

### Bucket 14-C overlays — Task 5 DateDividerCalc

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt`
  — promotes the inline `showMonth` logic (`HomeScreen.kt:355-380`) to a
  pure helper. UI behavior unchanged.
  ```kotlin
  @Immutable
  data class DateDivider(
      val idTag: Int,           // unique per-divider id (the snapshot index)
      val text: String,         // localized "MMM" output ("Apr", "May", ...)
      val xPx: Float,           // OPPOSITE side of the dot meander
      val yPx: Float,
  )

  fun computeDateDividers(
      snapshots: List<WalkSnapshot>,        // newest-first
      dotPositions: List<DotPosition>,      // parallel
      viewportWidthPx: Float,
      monthMarginPx: Float,                 // current 36 dp converted
      locale: Locale,
      zone: ZoneId,
  ): List<DateDivider>
  ```
  Body (verbatim from current `HomeScreen.kt` showMonth + monthFormatter):
  iterate snapshots; track `lastYearMonth: YearMonth?`; emit a divider on
  every transition. `xPx = if (dotPositions[i].centerXPx > viewportWidthPx / 2f)
  monthMarginPx else viewportWidthPx - monthMarginPx` (opposite side). `yPx =
  dotPositions[i].yPx`. Format via
  `DateTimeFormatter.ofPattern("MMM", locale).withZone(zone)`.

  Caller in `HomeScreen.kt` replaces the inline `showMonth + monthText +
  monthXPx` calculation with `computeDateDividers(...)` followed by an
  iteration to emit the labels. Behavior is identical (this is a pure
  extraction).

### Bucket 14-C overlays — Task 6 path shape builders

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt`
  — `fun toriiGatePath(size: Size): Path`. Verbatim port of iOS
  `ToriiGateShape.swift` math. Two horizontal beams (top + lintel) +
  two vertical pillars. Used by `MilestoneMarker` and (for future
  parity) the existing `ToriiScenery` if it migrates to share the helper.
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt`
  — `fun footprintPath(size: Size): Path`. Verbatim port of iOS
  `FootprintShape.swift` math. An asymmetric oval-with-toes outline. Used
  by `ExpandCardSheet` header row.

  Both are pure path builders — no Compose Composable wrappers; consumers
  call `Canvas(size) { drawPath(toriiGatePath(size), ...) }` directly.

### Bucket 14-D polish — Task 7 cascading fade-in

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeIn.kt`
  — pure helper file:
  ```kotlin
  @Composable
  fun rememberJournalFadeIn(reduceMotion: Boolean): JournalFadeInState {
      val hasAppeared = rememberSaveable { mutableStateOf(false) }
      LaunchedEffect(Unit) { hasAppeared.value = true }
      return remember(reduceMotion, hasAppeared.value) {
          JournalFadeInState(hasAppeared.value, reduceMotion)
      }
  }

  @Stable
  class JournalFadeInState(
      private val hasAppeared: Boolean,
      private val reduceMotion: Boolean,
  ) {
      // Per iOS InkScrollView.swift:
      //   segments[i].delay = 200 ms + i * 30 ms, duration 1200 ms, EaseOut
      //   dots[i].delay = 300 ms + i * 30 ms, duration 500 ms, EaseOut
      fun segmentAlpha(index: Int): Float = TODO("animateFloatAsState")
      fun dotAlpha(index: Int): Float = TODO("animateFloatAsState")
      fun sceneryAlpha(index: Int): Float = TODO("animateFloatAsState")  // shares dot timing
  }
  ```
  Implementation: each `*Alpha(index)` returns the result of an
  `animateFloatAsState(targetValue = if (hasAppeared) 1f else 0f, animationSpec
  = if (reduceMotion) snap() else tween(durationMillis = 1200/500, delayMillis
  = perTask, easing = FastOutSlowInEasing))`. `rememberSaveable` for
  `hasAppeared` (Stage 5-A lesson) so rotation doesn't replay the fade.
  `Modifier.graphicsLayer { alpha = state.dotAlpha(index) }` lambda form
  at consumer sites (Stage 5-A graphicsLayer lesson).

  **Reduce-motion gate**: when `LocalReduceMotion.current == true`, the
  state returns `1f` immediately for every index (no animation, content
  snaps in).

### Bucket 14-D polish — Task 8 EmptyJournalState

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt`
  — `@Composable EmptyJournalState(modifier = Modifier)`. Drawn in place
  of the current `Text(stringResource(R.string.home_empty_message))` empty
  branch in `HomeScreen.kt`. Layout:
  - `Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center)` containing
  - `Column(spacing = PilgrimSpacing.small, horizontalAlignment = CenterHorizontally)`:
    - A single tapered `CalligraphyPath` stroke at empty-state mode — call
      `CalligraphyPath(strokes = emptyList(), modifier = Modifier.fillMaxWidth().height(120.dp), emptyMode = true)`
      (see Files-to-modify for the additive `emptyMode = false`
      parameter on `CalligraphyPath`). `Modifier.size(...)` takes a `Dp`
      not a width fn; `fillMaxWidth().height(N.dp)` is the correct
      Compose chain. The empty-state path is a 120-dp-tall single
      stroke with half-width 1 dp top, tapering to 0.2 dp bottom and back
      to 1 dp (verbatim iOS `InkScrollView.swift:714`).
    - `Box(Modifier.size(14.dp).clip(CircleShape).background(pilgrimColors.stone))`
      filled stone-color circle.
    - `Text(stringResource(R.string.home_empty_begin), style =
      pilgrimType.caption, color = pilgrimColors.fog)` "Begin".

### Tests

- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheetTest.kt`
  (Robolectric — Compose smoke-test + onClick of "View details" → callback
  fires with `snapshot.id`)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt`
  (Robolectric — null marker ⇒ zero-height; SpringEquinox ⇒ banner text +
  kanji visible)
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurningsTest.kt`
  (pure: kanji() + bannerTextRes() + isTurning() across 8 markers)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ShapePathTest.kt`
  (Robolectric — `toriiGatePath(Size(16f,14f)).getBounds()` ≤ size;
  `footprintPath(Size(12f,18f)).getBounds()` ≤ size; Stage 3-C path-test
  pattern)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeInTest.kt`
  (Robolectric — reduceMotion ⇒ alpha = 1f; default ⇒ alpha animates from
  0f to 1f after first composition)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalStateTest.kt`
  (Robolectric — `composeRule.setContent { EmptyJournalState() };
  onNodeWithText("Begin").assertExists()`)
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt`
  (Robolectric — 4 fixture walks ≥ 30 km each; tap 2nd dot ⇒ ExpandCardSheet
  opens with that walk's data; tap "View details" ⇒ `onEnterWalkSummary`
  fires with the right id; assert MilestoneMarker for 100 km present)

## Files to modify

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` —
  - Above `JourneySummaryHeader`, insert `TurningDayBanner(currentTurning)`
    where `currentTurning = remember { turningMarkerForToday(...) }` reuses
    the same `SunCalc` + `SeasonalMarkerCalc` chain that `WalkDotColor.kt`
    already calls (extract a tiny `internal fun turningMarkerForLocalDate(
    localDate: LocalDate, zone: ZoneId): SeasonalMarker?` in a new
    `SeasonalMarkerTurnings.kt` so both callers share it).
  - Replace the inline `dotTap → onEnterWalkSummary(snap.id)` with
    `dotTap → homeViewModel.setExpandedSnapshotId(snap.id)`.
  - Below the canvas Box (still inside the scrolling Column), observe
    `homeViewModel.expandedSnapshotId` + `expandedCelestialSnapshot`. When
    `expandedSnapshotId != null` and the matching snapshot exists in the
    current `s.snapshots` list, render `ExpandCardSheet(snapshot,
    celestial, seasonColor, units, isShared, onViewDetails =
    onEnterWalkSummary, onDismissRequest = { homeViewModel.setExpandedSnapshotId(null) })`.
    Stale-id guard (Stage 14 spec Open-Q #4): if the id is not present in
    the latest emission (e.g. walk deleted), call
    `homeViewModel.setExpandedSnapshotId(null)` from a `LaunchedEffect`.
  - Wrap the canvas-Box scenery + dot Composables in
    `Modifier.graphicsLayer { alpha = fadeInState.dotAlpha(index) }` lambda
    form. Wrap `CalligraphyPath` in `Modifier.graphicsLayer { alpha =
    fadeInState.segmentAlpha(0) }` (whole-path single value; iOS
    per-segment math is an approximation we collapse here because the
    Android `CalligraphyPath` draws all segments in one Canvas pass).
  - Add `LunarMarker`s + `MilestoneMarker`s as Box.offset siblings inside
    the canvas Box, keyed by `idTag` / `distanceM`. Lunar markers use
    `LunarMarkerDot(isFullMoon = marker.illumination > 0.5)`; milestones
    use `MilestoneMarker(marker.distanceM, units)` positioned at
    `IntOffset(0, (marker.yPx).toInt())` with `Modifier.fillMaxWidth()`.
  - Replace inline `showMonth` + monthText + monthXPx logic with
    `computeDateDividers(...)` and a follow-up loop emitting Text labels.
    UI behavior unchanged.
  - Replace `Text(stringResource(R.string.home_empty_message), ...)` with
    `EmptyJournalState()` in the `JournalUiState.Empty` branch.

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt`
  — additive `emptyMode: Boolean = false` parameter. When `true`, draws
  the iOS empty-state tapered single stroke (120 dp tall, half-width 1 dp
  top, 0.2 dp bottom, 1 dp tail). Existing call sites unaffected by the
  default value. Document via comment block linking back to `InkScrollView.swift:714`.

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotColor.kt`
  — extract the `turningMarkerFor(walkStartMs)` private helper into the
  new `SeasonalMarkerTurnings.kt` so the banner caller and dot-color
  caller share a single source. Existing public function unchanged.

- `app/src/main/res/values/strings.xml` — see Strings section.

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt`
  — no new fields needed (`expandedSnapshotId` + `expandedCelestialSnapshot`
  + `setExpandedSnapshotId` already shipped Stage 14-A). Add a single
  `LaunchedEffect`-friendly clear method `fun clearExpandedIfMissing(currentIds:
  Set<Long>)` that drops the value if absent. Implementation: 1-line guard
  in `setExpandedSnapshotId`, no new MutableStateFlow.

## Strings (English)

```xml
<!-- Stage 14-BCD bundle additions. Most existing 14-A strings stay. -->

<!-- Empty state -->
<string name="home_empty_begin">Begin</string>
<!-- KEEP existing home_empty_message for accessibility fallback. -->

<!-- Turning-day banner (14-C) -->
<string name="turning_equinox_banner">Today, day equals night.</string>
<string name="turning_solstice_banner">Today the sun stands still.</string>

<!-- Expand-card labels (14-B) -->
<string name="journal_expand_label_distance">distance</string>
<string name="journal_expand_label_duration">duration</string>
<string name="journal_expand_label_pace">pace</string>
<string name="journal_expand_pill_walk">walk</string>
<string name="journal_expand_pill_talk">talk</string>
<string name="journal_expand_pill_meditate">meditate</string>
<string name="journal_expand_view_details">View details</string>
<string name="journal_expand_dismiss_a11y">Close walk details</string>

<!-- Milestone marker (14-C) -->
<plurals name="journal_milestone_km">
    <item quantity="one">%d km</item>
    <item quantity="other">%d km</item>
</plurals>
<plurals name="journal_milestone_mi">
    <item quantity="one">%d mi</item>
    <item quantity="other">%d mi</item>
</plurals>
<string name="journal_milestone_a11y">Milestone: %1$s</string>

<!-- Lunar marker (14-C) -->
<string name="journal_lunar_full_a11y">Full moon</string>
<string name="journal_lunar_new_a11y">New moon</string>
```

`MilestoneMarker.distanceText` ignores the plurals at the visible-text
layer (the integer body always uses `Locale.US "%d km|mi"` for digit
consistency, Stage 5-A locale lesson) and uses the plurals only in the
content-description for screen readers, where pluralization should follow
locale rules.

## Material icon mapping

| iOS SF Symbol | Material `ImageVector` |
|---|---|
| `link` (shared marker in expand card) | `Icons.Outlined.Link` |
| `arrow.right` (in "View details") | `Icons.AutoMirrored.Filled.ArrowForward` — auto-flips RTL matching iOS `Image(systemName: "arrow.right")` |
| `flame.fill` / `leaf.fill` / `star.fill` (favicon) | already mapped via `WalkFavicon.icon` (Stage 4-D); reused by ExpandCardSheet header |
| Planetary-hour symbols (sun/moon/mercury/venus/mars/jupiter/saturn) | already mapped Stage 13-Cel as Unicode codepoints (`Planet.symbol`) — reused |
| Zodiac symbols | already mapped Stage 13-Cel as Unicode (`ZodiacSign.symbol`) — reused |

The Torii gate and footprint glyphs are NOT Material icons; they are
custom Compose `Path` builders. Reasoning: Material's library does not
ship a torii or footprint icon, and the iOS originals are `SwiftUI Shape`
structs (not SF Symbols). Following the Stage 14-A scenery precedent
(`SceneryShapes.kt`).

## Implementation order

Tasks run roughly in dependency order so each can be reviewed individually.
Tasks 5 and 6 are blocking for Tasks 1 and 4 respectively; everything
else parallelizes.

1. **Task 6 — path shape builders** (`ToriiGateShape.kt`, `FootprintShape.kt`).
   No dependencies. Pure path math + Robolectric `getBounds()` tests.
   ~1.5 h. Blocks Tasks 1 + 4.
2. **Task 5 — DateDividerCalc extraction**. No dependencies. Pure refactor of
   inline `HomeScreen.kt` logic; behavior unchanged. Tests via `Locale.setDefault(
   Locale.US)` in `@Before`. ~1.5 h.
3. **Task 2 — TurningDayBanner + SeasonalMarker.kanji/bannerTextRes**.
   Depends only on the `SeasonalMarkerTurnings.kt` shared helper extraction.
   ~2 h.
4. **Task 4 — MilestoneCalc + MilestoneMarker**. Depends on Task 6
   (`toriiGatePath`). ~3 h. Includes the regression-test fixture (4 walks
   × 30 km).
5. **Task 3 — LunarMarkerCalc + LunarMarkerDot**. Independent of Tasks 1+4
   but composes them in `JournalScreenIntegrationTest`. `MoonCalc` already
   exists. ~3 h. Tests use synthetic walks bracketing a known 2024 lunation.
6. **Task 1 — ExpandCardSheet + MiniActivityBar + ActivityPills**. Depends
   on Task 6 (`footprintPath`). ~5 h. Includes ModalBottomSheet rewiring
   in `HomeScreen.kt` (replace direct `onEnterWalkSummary` callback with
   VM `expandedSnapshotId` flow + sheet observer).
7. **Task 7 — cascading fade-in primitives**. Independent. ~2 h.
   `JournalFadeInState` reused by Task 9 wiring.
8. **Task 8 — EmptyJournalState + CalligraphyPath emptyMode**. Depends on
   the `CalligraphyPath` additive parameter. ~2 h.
9. **Task 9 — Final integration + on-device QA**. Wires all overlays
   into `HomeScreen.kt` in their final positions, runs the verification
   commands, executes the on-device QA checklist on OnePlus 13. ~2 h.

Total: ~22 h subagent work (~3 days for an autopilot Stage). Single PR.

## Tests (high-level)

**Pure-data / pure-Kotlin (no Compose):**

- `LunarMarkerCalcTest` — synthetic walks bracketing 2024-01-25 (full moon)
  + 2024-02-09 (new moon) UTC; assert one marker per event with correct
  `isFullMoon` from illumination > 0.5; opposite-side X math (markerX <
  midX when midX > viewportWidth/2). Edge: empty snapshots, single
  snapshot ⇒ `emptyList()`.
- `MilestoneCalcTest` —
  1. `milestoneThresholds()` first 5 elements = `[100k, 500k, 1M, 2M, 3M]`;
     last = 100M; size = 102.
  2. **Regression fixture** — 4 walks of 30 km each (cumulative 30/60/90/120
     km). Returns 1 marker at threshold 100 km on the **4th-oldest walk**
     (newest by display order). NOT all stacked on the newest.
  3. Cumulative `[50k, 120k, 600k, 1.05M]` returns 3 markers at walks
     index 1, 2, 3 (display-order; oldest-first iteration internally).
  4. `snapshots.size < 2` returns `emptyList()`.
- `DateDividerCalcTest` — 5 snapshots Apr 5 / Apr 18 / May 2 / Jun 11 / Jun 25
  (newest-first), `Locale.setDefault(Locale.US)`. Returns 3 dividers
  ("Apr", "May", "Jun") at indices 4, 2, 1 (display oldest = Jun 25 case
  reverses; sort it out from the inline impl). Assert opposite-side X
  math.
- `SeasonalMarkerTurningsTest` — all 8 markers; only the 4 cardinal ones
  return non-null `kanji()` and `bannerTextRes()`; cross-quarter all
  null; `isTurning()` correct.

**Robolectric (Compose runtime):**

- `ExpandCardSheetTest` — render with sample snapshot; assert footprint
  glyph + favicon + date Text + 3 stat values + 3 pills (when
  hasTalk + hasMeditate) + "View details" button present. Tap "View
  details" ⇒ `onViewDetails` callback fired with `snapshot.id`,
  `onDismissRequest` fired before. Verify the planetary/moon glyph row
  hides when `celestial == null`.
- `TurningDayBannerTest` — `null` ⇒ zero-height (use
  `Modifier.fillMaxWidth().testTag("banner")` and assert
  `getBoundsInRoot().height == 0.dp`); `SpringEquinox` ⇒ banner Text +
  kanji "春分" both visible.
- `MilestoneMarkerComposableTest` — render at 100 km in km units ⇒
  `onNodeWithText("100 km").assertExists()`; in mile units ⇒
  `"62 mi"`. Path bounds via Modifier.testTag.
- `LunarMarkerDotTest` — `isFullMoon = true` renders a filled circle;
  `false` renders a stroked outline. Light/dark theme color flip via
  `MaterialTheme(... isSystemInDarkTheme proxy ...)`.
- `JournalFadeInTest` — `LocalReduceMotion provides true` ⇒
  `state.dotAlpha(0) == 1f` immediately. Default ⇒ first frame `0f`,
  `composeRule.mainClock.advanceTimeBy(1500)` ⇒ `1f`.
- `EmptyJournalStateTest` — `Begin` text node exists; the stone-circle
  Box has size 14 dp.
- `JournalScreenIntegrationTest` — fixture: 4 walks of 30 km each.
  Asserts: tap dot 1 ⇒ ExpandCardSheet opens with snapshot 1's data; tap
  "View details" ⇒ `onEnterWalkSummary(snap1.id)` fires; sheet dismissed.
  100 km MilestoneMarker present in tree.

**Verification of Stage 14-A test cancellation discipline:** any new VM
test (none expected — VM surface unchanged) MUST `vm.viewModelScope.cancel()`
before `db.close()` (Stage 7-A flake fix, repeated through 7-A → 13-Cel
→ 14-A).

## Verification

**Automated:**

```bash
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
```

All three must be green. No new lint warnings.

**On-device QA checklist (OnePlus 13, Stage 5-G pattern):**

1. Fresh install (no walks) → JournalScreen shows EmptyJournalState
   (tapered tail + 14 dp stone circle + "Begin" caption).
2. Seed 4 walks of 30 km each via instrumentation OR manual debug
   import → MilestoneMarker visible at 100 km on the newest walk's row.
3. Seed walks bracketing 2024-01-25 (full moon) → at least one filled
   silver disc visible interpolated between dots; `adb shell cmd uimode
   night yes` re-tints the disc to the warmer dark-theme color.
4. Tap any dot → ExpandCardSheet rises from bottom; FootprintShape +
   favicon + date + 3 stats + activity bar + 3 pills + "View details"
   all visible. Tap-outside dismisses. `adb shell input keyevent BACK`
   dismisses (BackHandler).
5. Tap "View details" → ExpandCardSheet animates closed THEN navigates
   to WalkSummary for the right walkId.
6. Test on a known turning-day date (set `adb shell date YYYYMMDDHHMM`
   to a March 20 noon UTC, restart Pilgrim) → TurningDayBanner appears
   above JourneySummaryHeader with "Today, day equals night. · 春分".
7. Cold-start with 12 fixture walks → cascading fade-in visible (segments
   appear over ~1.2 s, dots over ~0.5 s). With Settings → Accessibility
   → Reduce Animations on, content snaps in immediately.
8. Settings → Accessibility → Reduce Animations on, scroll the journal —
   no haptics fire (existing Stage 14-A `JournalHapticDispatcher` reads
   `Settings.Global` at handler-time).
9. Rotate device while ExpandCardSheet open → sheet stays open; cycler
   header preserves its StatMode (`rememberSaveable`, Stage 5-A).
10. Memory pressure test: navigate Journal → WalkSummary → back → Journal
    20 times, no leaks reported by Android Studio profiler.

## Documented iOS deviations (in code comments)

This bundle introduces NO new iOS deviations. It carries forward two
already-documented ones from Stage 14-A:

1. **MilestoneCalc oldest-first iteration** — `MilestoneCalc.kt` body MUST
   carry a comment block citing Stage 14-A Documented iOS deviations #11
   + linking to this spec. The iOS algorithm in
   `InkScrollView.swift:751-778` has a latent bug (every milestone
   collapses onto the newest walk because cumulative distance is
   monotonically decreasing in newest-first iteration). Android iterates
   oldest-first to fix it; the regression-test fixture (4 walks × 30 km
   ⇒ 100 km marker on the 4th-oldest) is the ground truth.
2. **`halfCycle = 14.76` literal in LunarMarkerCalc** — comment block
   citing Stage 14-A Documented iOS deviations #8. iOS uses the literal
   `14.76` for the half-synodic-month constant in lunar-event detection,
   NOT `MoonCalc.SYNODIC_DAYS / 2.0` (which would be `14.76529...`).
   Android matches iOS verbatim. The `< 1.5 day` window absorbs the
   rounding.

No new deviations are introduced.

## Open questions for review

1. **ExpandCardSheet visibility of celestial header row when
   `celestialAwarenessEnabled = false`** — iOS shows the planetary-hour +
   moon-sign symbols ONLY when the user opts in (`celestialAwarenessEnabled
   = true`). Stage 14-A already gates the VM-side `_expandedCelestialSnapshot`
   computation on the same pref, so when the pref is OFF, `celestial`
   passed to ExpandCardSheet will always be `null` and the row collapses
   automatically. **Decision:** rely on the VM-side gating; no additional
   composable-side check. Matches iOS — verified at `InkScrollView.swift:337-346`.
2. **MilestoneMarker behavior on the day-of-crossing vs always at first
   crossing walk** — iOS records the marker at the first walk whose
   cumulative distance crosses the threshold (the same walk every time
   the journal is rendered, regardless of when the user views it).
   **Decision:** Android matches — `computeMilestonePositions` returns
   the marker at the first cumulative-cross walk in oldest-first order.
   Re-deriving on every emission is correct.
3. **Date format in ExpandCardSheet under non-default locale (Arabic /
   Persian / Hindi)** — `FormatStyle.FULL + Locale.getDefault()` will use
   non-ASCII digits for those locales. iOS does the same via
   `DateFormatter.dateStyle = .full`. **Decision:** match iOS — locale
   default. The Stage 5-A "always Locale.US for numeric body" rule
   applies to user-visible STAT values (distance/duration/pace digits),
   not to date strings, where locale-correct rendering is desired.
4. **Stale-id guard cadence** — when a walk is deleted while
   ExpandCardSheet is open, the new VM emission won't contain that id.
   `LaunchedEffect(s.snapshots, expandedId) { if (expandedId != null &&
   s.snapshots.none { it.id == expandedId }) homeViewModel.setExpandedSnapshotId(null) }`
   in `HomeScreen.kt` body. **Decision:** apply this guard in the screen,
   not the VM, because the screen is where the snapshots list and
   expanded id meet. Matches Stage 14 spec Open-Q #4.
5. **Cascading fade-in alpha source for `CalligraphyPath`** — Android
   draws the entire path in one Canvas pass, so per-segment opacity isn't
   directly addressable. `JournalFadeInState.segmentAlpha(0)` returns a
   single whole-path alpha. **Decision:** accept the visual approximation
   — by the time all per-dot fades complete (~`300 + n*30 + 500` ms for
   `n` snapshots), the path-level fade (1200 ms from 200 ms delay) will
   also be complete. The cascading effect on Android comes from the
   per-dot + per-scenery fades, not from per-segment.

## Self-review

- Placeholder scan: no TBD / TODO / "implement later" markers in the spec
  (the `// TODO Stage 14.X` carries forward from Stage 14-A in unchanged
  code). The `JournalFadeInState.*Alpha` body uses `TODO(...)` ONLY in
  illustrative pseudo-code blocks; the actual implementation guidance
  immediately below the snippet specifies `animateFloatAsState` + the
  reduce-motion branch.
- Type consistency: `WalkSnapshot` uses `durationSec: Double` (the shipped
  Stage 14-A type) with `talkDurationSec / meditateDurationSec` as `Long`
  and the computed `walkOnlyDurationSec: Long` derived from
  `durationSec.toLong() - talkSec - meditateSec`. `MiniActivityBar`
  fractions cast `total = max(1L, snapshot.durationSec.toLong())` before
  dividing. `cumulativeDistanceM: Double`; `DotPosition` carries
  `centerXPx + yPx` per `CalligraphyPath.dotPositions` shipped helper;
  all referenced consistently.
- Coverage: the 9 internal tasks each map to a "Files to create" or
  "Files to modify" entry plus a test entry. ExpandCardSheet (Task 7) +
  MiniActivityBar + ActivityPills are bundled per Bucket 14-B in the
  original spec.
- Scope: ~22 h subagent work, single PR, ~1.5K-2K LOC including tests
  (the heaviest task is ExpandCardSheet at ~5 h). Within Stage 13-XZ
  precedent for a bundled stage.
