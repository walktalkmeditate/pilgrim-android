# Stage 13-Cel — Full Celestial line + extended milestone callouts

## Context

Stage 13-EFG bundle shipped Walk Summary's favicon, milestone callout (partial), elevation profile, and details card. Two iOS-parity gaps remain:

1. **Section 10 — Celestial line** (`WalkSummaryScreen.kt:370` placeholder). iOS shows `"Moon in {sign}, Hour of {planet}, {element} predominates"` between weatherLine (9) and timeBreakdown (11). Android has `MoonCalc` + `SunCalc` + `PlanetaryHourCalc` + `Planet` enum (Stage 6-A); zodiac signs, planetary positions for all 7 planets, lunar/solar ecliptic longitude, element balance, retrograde/ingress, ayanamsa, and seasonal markers are all missing.

2. **Milestone callout extras** — `MilestoneCalloutRow` only handles 4 GoshuinMilestone variants. iOS Walk Summary `computeMilestone()` shows three additional prose templates Android currently can't produce:
   - `"Your longest meditation yet"` (LongestMeditation — Goshuin grid + Walk Summary, but with DIFFERENT detection rules)
   - `"You walked on the {marker.name}"` (SeasonalMarker — Walk Summary only, gated on celestialAwarenessEnabled)
   - `"You've now walked {N} {km|mi} total"` (TotalDistance — Walk Summary only, threshold-crossing on this walk)

Both gaps are bundled per user request to ship the full celestial surface in one PR.

## Goal

Match iOS Walk Summary celestial line + extended callout prose verbatim. Port the missing celestial calculator surface as pure Kotlin. Add `LongestMeditation` to `GoshuinMilestone` (also surfaces on the goshuin grid). Compute SeasonalMarker + TotalDistance + Walk-Summary-specific LongestMeditation/LongestWalk callouts independently at Walk Summary VM scope (NOT added to GoshuinMilestone — they're not seal-collection-worthy).

## Non-goals

- **`isIngress` / retrograde annotations on the inline row** — iOS only uses these for the `ContextFormatter` AI-prompt context, NOT the Walk Summary celestial line. Skip from row UI; populate the data fields on `PlanetaryPosition` so future surfaces can read them.
- **`sunriseAzimuth`** — used by iOS for a directional marker UI element not currently planned for Android. Skip the function entirely (no data field needed).
- **`SeasonalMarker` Goshuin variant** — iOS doesn't have one either; the seasonal marker only appears in the Walk Summary callout prose, never on the Goshuin grid as a halo.
- **`TotalDistance` Goshuin variant** — same. Walk Summary callout only.
- **iOS multi-fire `Set<Milestone>` semantics** — Android keeps single-highest-precedence model for the Goshuin grid (Stage 4-D documented choice). The Walk Summary callout is a separate single-string priority chain (matches iOS `computeMilestone`).
- **"2-pulse haptic + extra hold" reveal-overlay milestone behavior** — KDoc claim aspirational; iOS `SealRevealView` does NOT differentiate haptics by milestone variant. Existing Android behavior preserved unchanged.
- **Per-walk-latitude season detection for `FirstOfSeason`** — Android uses device-level hemisphere by design (Stage 4-D documented divergence).
- **Sidereal zodiac UI consumer (Settings toggle)** — `practice.ZodiacSystem` preference + Settings toggle are already shipped (Stage 6-A's `MoonCalc.kt:24` has a TODO referencing it). This stage CONSUMES the pref via `practicePreferences.zodiacSystem` so the celestial line shows zodiac positions in the user's chosen system, but does NOT add new UI for the toggle.

## Architecture

### Two parallel milestone surfaces, distinct rules

iOS has TWO milestone surfaces with different semantics AND different detection rules:

1. **Goshuin grid** (`GoshuinPageView` halo + label) — `GoshuinMilestones.detect` returns `Set<Milestone>` with 5 cases including `longestMeditation`. Detection: walk is the global max among all walks with `meditateDuration > 0`. NO requirement that prior walks had meditation.

2. **Walk Summary callout** (`MilestoneCalloutRow`) — `computeMilestone() -> String?` returns at most ONE prose string. Priority chain (verbatim iOS `WalkSummaryView.swift:421-461`):
   1. `SeasonalMarker` — only if `celestialAwarenessEnabled` AND sun longitude within ±1.5° of cardinal/cross-quarter angle
   2. `LongestMeditation` — only if `walk.meditateDuration > longestPast && longestPast > 0` (strict improvement over a NON-ZERO prior)
   3. `LongestWalk` — only if `walk.distance > longestPast && longestPast > 0` (strict improvement over a NON-ZERO prior)
   4. `TotalDistance` — threshold crossed on this walk
   5. `null` (no callout)
   
   NOTE: There is NO fallthrough to FirstWalk / FirstOfSeason / NthWalk on iOS Walk Summary callout. Those only appear via the Goshuin path. Walk Summary shows `null` if none of the 4 chain entries fire.

Android mirrors this:

1. **`GoshuinMilestone` sealed class** — extend with `LongestMeditation` (single new case). Goshuin grid shows halo + label; precedence preserved (Stage 4-D order kept; `LongestMeditation` inserted between `LongestWalk` and `NthWalk`).
2. **New `WalkSummaryCalloutProse` helper** — pure function returning `String?`. Computes the iOS 4-step priority chain. Consumed by `MilestoneCalloutRow` (signature changes to `prose: String`).

This keeps the goshuin grid clean (collection of seal-worthy milestones) and lets the Walk Summary callout enrich the prose with non-seal variants (SeasonalMarker, TotalDistance) without polluting `GoshuinMilestone`.

### Celestial calculator extension — modular file split, NOT umbrella object

Stage 6-A used separate `internal object`s per concern: `MoonCalc`, `SunCalc`, `PlanetaryHourCalc`. Continue that pattern. New objects:

- **`PlanetCalc`** — heliocentric + geocentric ecliptic longitude for Mercury/Venus/Mars/Jupiter/Saturn. Plus `lunarLongitude(T)` (full ecliptic longitude in degrees, distinct from `MoonCalc.moonPhase` which is age/illumination only). Plus `solarLongitude(T)` (already partially present in `SunCalc` for sunrise calc — verify and extract if needed).
- **`SeasonalMarkerCalc`** — `seasonalMarker(sunLongitude) -> SeasonalMarker?` with ±1.5° threshold from cardinal/cross-quarter angles.
- **`CelestialSnapshotCalc`** — top-level `snapshot(date, system) -> CelestialSnapshot` composing PlanetCalc + PlanetaryHourCalc + element balance.

New types in `core/celestial/`:
- `ZodiacSign.kt` — enum (12 signs) + nested `Element` + `Modality` + `name` + `symbol`
- `ZodiacPosition.kt` — `data class ZodiacPosition(sign: ZodiacSign, degree: Double)`
- `PlanetaryPosition.kt` — `data class PlanetaryPosition(planet: Planet, longitude: Double, tropical: ZodiacPosition, sidereal: ZodiacPosition, isRetrograde: Boolean, isIngress: Boolean)`
- `ElementBalance.kt` — `data class ElementBalance(counts: Map<Element, Int>, dominant: Element?)` (nil-on-tie)
- `SeasonalMarker.kt` — enum (8 cases) + `name: String` ("Spring Equinox" / "Imbolc" / etc.)
- `CelestialSnapshot.kt` — `data class CelestialSnapshot(positions: List<PlanetaryPosition>, planetaryHour: PlanetaryHour, elementBalance: ElementBalance, system: ZodiacSystem, seasonalMarker: SeasonalMarker?)` + `position(for: Planet)` helper

Existing `Planet` enum (`core/celestial/Planet.kt`) is already the full 7-Planet enum. Add `name: String` ("Sun" / "Moon" / "Mercury" / etc.) and `symbol: String` (Unicode glyph). Audit existing callers (`LightReadingPresenterTest:60-66` uses `Planet.Sun → "Sunday"` mapping for weekday rendering — check there's no name-collision; the new `name` field returns "Sun", weekday name lives elsewhere).

Existing `practice.ZodiacSystem` enum (Tropical | Sidereal) is consumed via `practicePreferences.zodiacSystem` — `CelestialSnapshotCalc.snapshot(date, system)` accepts the system; the VM passes the live preference value.

### Walk Summary VM additions

`WalkSummaryViewModel.WalkSummary`:
- Add `celestialSnapshot: CelestialSnapshot?` — computed in `buildState()` ALWAYS (cheap, time-only). The pref-gating happens at the live-combine seam below.
- Add `walkSummaryCalloutProse: String?` — computed in `buildState()` from priority chain. Reads:
  - `walk.meditationSeconds ?: 0L` for current walk's meditation total (already persisted on `Walk` entity; no replay needed)
  - `repository.allWalks().asSequence().filter { it.endTimestamp != null && it.id != walk.id }.sortedByDescending { it.endTimestamp ?: 0L }.take(100).toList()` for past-walk comparison + cumulative distance sum (matches iOS `fetchLimit=100` ordered DESC by end time; documented "cumulative over recent activity" semantic, not "lifetime total" — iOS power-user behavior). Explicit sort is required because `allWalks()` does not guarantee end-time DESC order.
  - For LongestMeditation/LongestWalk: max of those past walks' meditation/distance respectively, then `current > pastMax && pastMax > 0` rule
  - For TotalDistance: `pastSum + walk.distance`, threshold list `[10, 25, 50, 100, 250, 500, 1000]`, current unit from `unitsPreferences.distanceUnits.value`, prose with `String.format(Locale.US, "%d", threshold)` for ASCII digits across CI locales (Stage 5-A memory pattern)

The existing `milestone: GoshuinMilestone?` field stays for Goshuin grid use.

New live-combine flow in VM (mirrors `lightReadingDisplay` pattern):

```kotlin
val celestialSnapshotDisplay: StateFlow<CelestialSnapshot?> =
    combine(state, practicePreferences.celestialAwarenessEnabled) { s, enabled ->
        if (s is WalkSummaryUiState.Loaded && enabled) s.summary.celestialSnapshot else null
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), initialValue = null)

val walkSummaryCalloutProseDisplay: StateFlow<String?> =
    combine(state, practicePreferences.celestialAwarenessEnabled) { s, enabled ->
        if (s !is WalkSummaryUiState.Loaded) return@combine null
        // Recompute the priority chain with the LIVE pref value. The
        // costly past-walks query already happened in buildState() and
        // is captured on s.summary.calloutInputs (see WalkSummary
        // additions below) — we only re-derive the prose, not refetch.
        WalkSummaryCalloutProse.compute(
            inputs = s.summary.calloutInputs,
            celestialEnabled = enabled,
            context = context,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), initialValue = null)
```

Both flows mirror the existing `lightReadingDisplay` precedent (`WhileSubscribed(5s)`). Toggling the pref while Summary is open immediately re-derives prose without re-querying past walks; SeasonalMarker branch enters/leaves the priority chain based on live pref value.

`WalkSummary` adds `calloutInputs: WalkSummaryCalloutInputs` capturing the immutable past-walks-list + this-walk's distance/meditation already computed in `buildState()`. Pure data; safe to hold across pref toggles.

### MilestoneCalloutRow signature change

Before: `fun MilestoneCalloutRow(milestone: GoshuinMilestone, ...)` — switch on enum.

After: `fun MilestoneCalloutRow(prose: String, ...)` — render as-is. Composable signature is non-nullable `String`; the screen call site does `walkSummaryCalloutProseDisplay.value?.let { MilestoneCalloutRow(prose = it) }` so the composable is omitted entirely when the callout chain returns null (matches iOS view-builder `if let`).

The `milestoneSummaryProse` switch helper deletes; prose for the existing 4 GoshuinMilestone variants moves to the new `WalkSummaryCalloutProse.kt` so all prose lives in one place. Existing 4 variants no longer surface on Walk Summary at all (matches iOS — they only appear on the Goshuin grid). Confirmed sole caller is `WalkSummaryScreen.kt` + `MilestoneCalloutRowTest.kt`; no other consumers.

### LongestMeditation detection — TWO rules

**Goshuin grid** (`GoshuinMilestones.detect`):
- Filter walks where `meditateDurationMillis > 0`
- Find max by `meditateDurationMillis`
- If current walk is the max → fires `LongestMeditation`
- NO requirement that prior walks had meditation (matches iOS Set semantics)

**Walk Summary callout** (`WalkSummaryCalloutProse`):
- Past walks max meditation: `pastMax = pastWalks.maxOf { it.meditationSeconds ?: 0L }`
- Fires only if `currentWalk.meditationSeconds > pastMax && pastMax > 0` (matches iOS strict-improvement-over-nonzero rule)

Same pattern for **LongestWalk** in the callout: `current.distance > pastMax && pastMax > 0`. The Goshuin grid `LongestWalk` keeps existing rule (max wins, no nonzero gate).

Goshuin precedence preserved from Stage 4-D, with `LongestMeditation` inserted between `LongestWalk` and `NthWalk`:
1. `FirstWalk`
2. `LongestWalk`
3. **`LongestMeditation` ← NEW**
4. `NthWalk`
5. `FirstOfSeason`

`WalkMilestoneInput` adds `meditateDurationMillis: Long` field. All Stage 4-D test fixtures explicitly extended (`= 0L` default acceptable).

### SeasonalMarker callout (Walk Summary only)

Computed in VM via `SeasonalMarkerCalc.seasonalMarker(SunCalc.solarLongitude(T(walk.startTimestamp)))`. Gated on `celestialAwarenessEnabled` preference (only included in priority chain when pref is ON). Prose: `"You walked on the {marker.name}"` for ALL 8 markers — including cross-quarter ("You walked on the Imbolc"). iOS uses uniform "the" prefix (`WalkSummaryView.swift:426`); awkward for cross-quarter but matches verbatim.

### TotalDistance callout (Walk Summary only)

Detection (port from iOS verbatim, with documented past-walk cap):
- All distance fields are meters (Stage 4-D convention)
- `unitFactor` = `1_000.0` for km, `1_609.344` for mi (UCUM exact mile)
- Sum 100-most-recent finished walks' distances + this walk's distance = `totalDistance` (meters)
- Convert via `unitFactor` based on `unitsPreferences.distanceUnits.value`
- Thresholds: `[10, 25, 50, 100, 250, 500, 1000]`
- `pastUnits = floor((totalDistance - thisWalk.distanceMeters) / unitFactor)` — for first walk ever, this is 0; first 10-km walk fires "10 km total" (matches iOS)
- `totalUnits = floor(totalDistance / unitFactor)`
- For each threshold m (iterated lowest-first): if `totalUnits >= m && pastUnits < m`, fire prose and return (single threshold per callout)
- Prose via `String.format(Locale.US, "%d", m)` interpolated into `R.string.summary_milestone_total_distance_km` or `_mi`

Cap rationale: iOS's `fetchLimit=100` makes `totalDistance` semantically "cumulative over recent activity," not "lifetime total." A user with 950 km lifetime distance whose recent 100 walks sum to 80 km will get a 100-km callout much later than expected. Acceptable for iOS parity; documented in code comment so future maintainers don't "fix" it.

### Walk Summary callout prose priority

Mirror iOS `computeMilestone()` exactly (NO fallthrough to GoshuinMilestone variants):

```
1. SeasonalMarker (only if celestialAwarenessEnabled is true)
2. LongestMeditation (with strict-improvement-over-nonzero rule)
3. LongestWalk     (with strict-improvement-over-nonzero rule)
4. TotalDistance   (threshold crossed on this walk)
5. null  ← FirstWalk / FirstOfSeason / NthWalk are NOT shown on the Walk Summary callout
```

This is a behavior CHANGE from current Android: the Walk Summary callout will stop showing FirstWalk / FirstOfSeason / NthWalk prose. Those still appear on the Goshuin grid. Matches iOS.

## Files to create

| File | Purpose |
|------|---------|
| `app/src/main/java/.../core/celestial/ZodiacSign.kt` | Enum (12 signs) + nested `Element` + `Modality` + `name` + `symbol` |
| `app/src/main/java/.../core/celestial/ZodiacPosition.kt` | data class with sign + degree |
| `app/src/main/java/.../core/celestial/PlanetaryPosition.kt` | data class with longitude + tropical + sidereal + retrograde + ingress |
| `app/src/main/java/.../core/celestial/ElementBalance.kt` | data class with counts map + nullable dominant |
| `app/src/main/java/.../core/celestial/SeasonalMarker.kt` | Enum (8 cases) + `name` |
| `app/src/main/java/.../core/celestial/CelestialSnapshot.kt` | Top-level data class + `position(for: Planet)` |
| `app/src/main/java/.../core/celestial/PlanetCalc.kt` | `lunarLongitude`, `mercuryLongitude`, `venusLongitude`, `marsLongitude`, `jupiterLongitude`, `saturnLongitude`, `isRetrograde`, `zodiacPosition`, `isIngress` (defensive normalize), `ayanamsa`, `geocentricForInnerPlanet` (private), `geocentricForOuterPlanet` (private). For `solarLongitude(T)`, callers import directly from `SunCalc` (already exists from Stage 6-A) — DO NOT duplicate. |
| `app/src/main/java/.../core/celestial/SeasonalMarkerCalc.kt` | `seasonalMarker(sunLongitude) -> SeasonalMarker?` |
| `app/src/main/java/.../core/celestial/CelestialSnapshotCalc.kt` | `snapshot(date, system) -> CelestialSnapshot` + `elementBalance(positions, system)` |
| `app/src/main/java/.../ui/walk/summary/WalkSummaryCalloutProse.kt` | Pure helper: `WalkSummaryCalloutProse.compute(inputs: WalkSummaryCalloutInputs, celestialEnabled: Boolean, context: Context): String?` + `data class WalkSummaryCalloutInputs(currentDistanceMeters, currentMeditationSeconds, pastWalksMaxDistance, pastWalksMaxMeditation, pastWalksDistanceSum, units, sunLongitudeDegrees)` capturing all immutable inputs needed for re-derivation on pref toggle. Context for `getString`; matches Stage 6-B `LightReadingPresenter` precedent |
| `app/src/main/java/.../ui/walk/summary/CelestialLineRow.kt` | Composable row rendering "Moon in X" / "Hour of Y" / "Z predominates" caption fog-tinted |
| `app/src/test/java/.../core/celestial/PlanetCalcTest.kt` | Tests for planetary longitudes (one per planet at known reference dates), retrograde, zodiacPosition, isIngress (defensive negative-lon case), ayanamsa |
| `app/src/test/java/.../core/celestial/SeasonalMarkerCalcTest.kt` | Tests for cardinal/cross-quarter detection at threshold edges |
| `app/src/test/java/.../core/celestial/CelestialSnapshotCalcTest.kt` | End-to-end snapshot tests at known reference dates (verbatim port of iOS `CelestialCalculatorTests.swift` cases that match our scope; ~12-15 tests) |
| `app/src/test/java/.../ui/walk/summary/WalkSummaryCalloutProseTest.kt` | Priority chain tests: 4 priority levels each fires; null fallthrough; LongestMeditation strict-improvement-over-nonzero; TotalDistance threshold-crossing |
| `app/src/test/java/.../ui/walk/summary/CelestialLineRowTest.kt` | Composable rendering tests (3 conditional Texts, fog tint, gated by null snapshot) |

## Files to modify

| File | Change |
|------|--------|
| `app/src/main/java/.../core/celestial/Planet.kt` | Add `name: String` + `symbol: String` properties to enum constants |
| `app/src/main/java/.../core/celestial/SunCalc.kt` | Verify `solarLongitude(T)` extractable; expose if needed for SeasonalMarkerCalc |
| `app/src/main/java/.../core/celestial/MoonCalc.kt` | Remove TODO referencing zodiacSystem (now consumed via CelestialSnapshotCalc) |
| `app/src/main/java/.../ui/goshuin/GoshuinMilestone.kt` | Add `data object LongestMeditation` case |
| `app/src/main/java/.../ui/goshuin/GoshuinMilestones.kt` | Add `LongestMeditation` detection. Extend `WalkMilestoneInput` with `meditateDurationMillis: Long = 0L`. Update precedence (insert between LongestWalk and NthWalk; preserve Stage 4-D order otherwise) |
| `app/src/main/java/.../ui/walk/summary/MilestoneCalloutRow.kt` | Change signature: `prose: String` instead of `milestone: GoshuinMilestone`. Drop `milestoneSummaryProse` helper |
| `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` | Add `celestialSnapshot: CelestialSnapshot?` + `calloutInputs: WalkSummaryCalloutInputs` to `WalkSummary`. Compute both in `buildState()`. Add `celestialSnapshotDisplay` + `walkSummaryCalloutProseDisplay` flows (both `WhileSubscribed(SUBSCRIBER_GRACE_MS)`, mirroring `lightReadingDisplay` precedent). Build `WalkMilestoneInput` with `meditateDurationMillis = (walk.meditationSeconds ?: 0L) * 1000L` |
| `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` | Replace section 10 placeholder with `CelestialLineRow(snapshot = celestialSnapshotDisplay)`. Wire `MilestoneCalloutRow(prose = walkSummaryCalloutProseDisplay)` instead of `(milestone = ...)`. Drop `summary.milestone` reading at this site |
| `app/src/main/java/.../ui/goshuin/GoshuinViewModel.kt` | Pass `meditateDurationMillis = walk.meditationSeconds * 1000L` into `WalkMilestoneInput` |
| `app/src/main/res/values/strings.xml` | Add 14 strings (3 celestial-line + 1 longest meditation + 8 seasonal markers + 2 total distance) |
| `app/src/test/java/.../ui/goshuin/GoshuinMilestonesTest.kt` | Add `LongestMeditation` detection tests (5 tests minimum). Audit ALL existing `WalkMilestoneInput(...)` constructions and add `meditateDurationMillis = 0L` (default makes this transparent but tests should be explicit for new tests). Verify Stage 4-D's 26 existing test cases still pass with default 0L |
| `app/src/test/java/.../ui/walk/summary/MilestoneCalloutRowTest.kt` | Update tests for new `prose: String` signature. Drop variant-specific tests (covered by WalkSummaryCalloutProseTest now) |
| `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` | Add tests: `celestialSnapshot` populated; `celestialSnapshotDisplay` flips on pref toggle; `walkSummaryCalloutProseDisplay` shows SeasonalMarker only when pref ON; LongestMeditation strict-improvement rule; TotalDistance threshold-crossing |

## Strings (English) — 14 total

```xml
<!-- Stage 13-Cel celestial line -->
<string name="summary_celestial_moon_in">Moon in %1$s</string>
<string name="summary_celestial_hour_of">Hour of %1$s</string>
<string name="summary_celestial_predominates">%1$s predominates</string>

<!-- Stage 13-Cel milestone prose extras -->
<string name="summary_milestone_longest_meditation">Your longest meditation yet</string>
<string name="summary_milestone_seasonal_spring_equinox">You walked on the Spring Equinox</string>
<string name="summary_milestone_seasonal_summer_solstice">You walked on the Summer Solstice</string>
<string name="summary_milestone_seasonal_autumn_equinox">You walked on the Autumn Equinox</string>
<string name="summary_milestone_seasonal_winter_solstice">You walked on the Winter Solstice</string>
<string name="summary_milestone_seasonal_imbolc">You walked on the Imbolc</string>
<string name="summary_milestone_seasonal_beltane">You walked on the Beltane</string>
<string name="summary_milestone_seasonal_lughnasadh">You walked on the Lughnasadh</string>
<string name="summary_milestone_seasonal_samhain">You walked on the Samhain</string>
<string name="summary_milestone_total_distance_km">You\'ve now walked %1$s km total</string>
<string name="summary_milestone_total_distance_mi">You\'ve now walked %1$s mi total</string>
```

(Element names "Fire" / "Earth" / "Air" / "Water" + zodiac names + planet names + seasonal-marker names are inline data on the enums; not in `strings.xml`. Matches iOS hardcoding pattern. Localization deferred until Android adds non-English locales.)

`%1$s` for total-distance numbers; call site does `String.format(Locale.US, "%d", units)` for ASCII-digit formatting (Stage 5-A locale safety pattern).

## Implementation order

Tasks split for parallel TDD where possible. Numbers reference task slots in the implementation plan.

1. **Spec** (this file) + **plan** (next file).
2. **`ZodiacSign` + `ZodiacPosition` + `Element` + `Modality`** — pure data, fully independent.
3. **`Planet` extension** — add `name` + `symbol` properties. Audit `LightReadingPresenter` weekday-mapping to ensure no name-collision.
4. **`SeasonalMarker` + `CelestialSnapshot` + `PlanetaryPosition` + `ElementBalance`** — data classes only. No logic.
5. **`PlanetCalc`** — `solarLongitude`, `lunarLongitude`, 5 planetary longitudes, `isRetrograde`, `zodiacPosition`, `isIngress` (with defensive `((lon % 30 + 30) % 30)` normalize), `ayanamsa`, geocentric helpers. Tests against iOS test cases (verbatim where possible).
6. **`SeasonalMarkerCalc`** — `seasonalMarker(sunLongitude)`. Tests at threshold edges.
7. **`CelestialSnapshotCalc`** — `snapshot(date, system)` + `elementBalance(positions)`. End-to-end test for known date matching iOS expected output. Consumes `practice.ZodiacSystem` enum.
8. **`GoshuinMilestone.LongestMeditation` case + `WalkMilestoneInput.meditateDurationMillis` field** — data only.
9. **`GoshuinMilestones.detect` LongestMeditation logic + precedence update** — pure function update with tests. Audit existing 26 test fixtures (default `= 0L` keeps them passing; explicit value for new test cases).
10. **`GoshuinViewModel` plumbing** — pass `walk.meditationSeconds * 1000L` through. Verify regression tests still pass.
11. **`WalkSummaryCalloutProse` helper** — pure function with priority chain. Takes `Context` for `getString` calls. Tests for: SeasonalMarker fires only when celestialEnabled, LongestMeditation strict-improvement-over-nonzero, LongestWalk strict-improvement-over-nonzero, TotalDistance threshold-cross, null fallthrough (NO FirstWalk/NthWalk/FirstOfSeason — these intentionally drop on Walk Summary callout).
12. **`MilestoneCalloutRow` signature change** — accept `prose: String`. Update WalkSummaryScreen call site + test.
13. **`CelestialLineRow` composable** — render 3 conditional Texts gated on snapshot null + per-component nullability (zodiac, dominant). Tests.
14. **`WalkSummaryViewModel` integration** — populate `celestialSnapshot` (always, in buildState) + `walkSummaryCalloutProse`. Add `celestialSnapshotDisplay` and `walkSummaryCalloutProseDisplay` live-combine flows for pref-toggle reactivity. Tests.
15. **`WalkSummaryScreen` section 10 wiring** — replace placeholder with CelestialLineRow + MilestoneCalloutRow signature change + read from new display flows.
16. **`strings.xml`** — add 14 strings.

## Tests (high-level)

**Celestial calculator** (Task 5 + 6 + 7):
- PlanetCalc: ~15 tests covering all 7 planets at reference dates, retrograde at known periods, zodiacPosition wraparound, isIngress at boundaries (positive + defensive negative), ayanamsa at multiple T values
- SeasonalMarkerCalc: 4 cardinal + 4 cross-quarter detection tests + nil-outside-window test = 9
- CelestialSnapshotCalc: ~10 end-to-end tests (3 reference dates × tropical+sidereal + element tie/winner cases)

**Milestones** (Task 9):
- LongestMeditation: 5 tests (current wins / current doesn't win / no walks have meditation / single walk default / tie case)
- Existing 26 GoshuinMilestonesTest cases: pass with default `meditateDurationMillis = 0L` — explicit audit step

**WalkSummaryCalloutProse** (Task 11):
- 6 tests: SeasonalMarker fires (pref ON), SeasonalMarker null (pref OFF), LongestMeditation fires, LongestMeditation requires nonzero past, LongestWalk fires, TotalDistance fires at threshold-cross, null fallthrough (when none apply — confirms NO Goshuin fallback)

**CelestialLineRow** (Task 13):
- snapshot null → no node; complete snapshot → 3 Texts; element tied (dominant null) → only 2 Texts; zodiac unavailable (defensive only) → only 2 Texts

**WalkSummaryViewModel** (Task 14):
- `celestialSnapshot` always populated in buildState
- `celestialSnapshotDisplay` flips with pref toggle
- `walkSummaryCalloutProseDisplay` reflects pref toggle (SeasonalMarker on/off)
- Each priority chain entry covered by an integration test

## Verification

- `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` clean.
- Manual on-device walkthrough:
  - Toggle `celestialAwarenessEnabled` in Settings; celestial line + SeasonalMarker callout (if applicable) appear/disappear immediately on the open Summary
  - Set device clock to 2026-06-21 ≈ summer solstice; SeasonalMarker callout fires
  - Walk with > previous max meditation and prior walks have meditation > 0; LongestMeditation callout fires
  - Walk hitting 10/25/50 km cumulative-over-recent-activity threshold; TotalDistance callout fires
  - Toggle Tropical ↔ Sidereal in Settings (if UI exposes it); zodiac sign in celestial line shifts
- Goshuin grid renders LongestMeditation halo + label for the qualifying walk

## Documented iOS deviations (in code comments)

- Android uses real `SunTimes` for planetary hour day/night split (Stage 6-A); iOS uses fixed 6am/6pm. Carry-over.
- Android's `FirstOfSeason` uses device hemisphere; iOS uses walk's GPS latitude. Stage 4-D documented.
- Android's GOSHUIN milestone detection returns single highest-precedence; iOS returns Set with non-deterministic ordering. Stage 4-D documented.
- Android's WALK SUMMARY callout returns single string with explicit priority chain; matches iOS `computeMilestone()`.
- Android keeps existing 2-pulse haptic for any milestone seal reveal; iOS uses single medium impact. Pre-existing Stage 4-B Android-only enhancement.
- TotalDistance "100 most recent" cap: iOS quirk preserved for parity; semantic = "cumulative over recent activity," NOT "lifetime total." Documented in code comment.
- Cross-quarter prose uses "the {marker.name}" verbatim from iOS even though awkward ("You walked on the Imbolc"). Match iOS exactly.
- `isIngress` uses defensive normalized longitude (`((lon % 30 + 30) % 30) < 1.0 || > 29.0`); iOS uses raw `truncatingRemainder` which mishandles negative longitudes (unreachable in iOS production flow but defensive on Android).
