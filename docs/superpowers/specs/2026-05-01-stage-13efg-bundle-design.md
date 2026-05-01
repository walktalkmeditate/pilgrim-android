# Stage 13-EFG bundle — Favicon + Milestone callout + Elevation profile + Details

## Context

Three closely-related but independent layout-only sub-stages bundled into one PR
(per user request). 13-A skeleton left placeholders for sections 12 (favicon),
7 (milestone callout), 4 (elevation profile), 18 (details). Bundle ships them.

iOS reference:
- `pilgrim-ios/Pilgrim/Models/Walk/WalkFavicon.swift` (5 LOC)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/FaviconSelectorView.swift` (50 LOC)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:332-348` (milestoneCallout) + `:421-461` (computeMilestone)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ElevationProfileView.swift` (102 LOC)
- `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift:795-810` (detailsSection)

## Goal

Three new card-style sections on Walk Summary:
- **Favicon selector** — "Mark this walk" with 3 toggle buttons (flame=Transformative, leaf=Peaceful, star=Extraordinary). Persists to existing `Walk.favicon` column. Tap selects, tap again deselects.
- **Milestone text callout** — sparkles icon + dawn-tinted card with text like "Your longest meditation yet" / "Your longest walk yet" / "You've now walked N km total". Renders only when a milestone fires.
- **Elevation profile** — gradient-filled sparkline of altitude with min/max labels (X ft / mountain / Y ft). Renders only when ≥6 altitude samples AND range > 1m.
- **Details (Paused) card** — single row "Paused — H:MM:SS" when `totalPausedMillis > 0`. Hidden otherwise.

## Non-goals (deferred)

- **Celestial line** ("Moon in {sign}, Hour of {planet}, {element} predominates") — Android `CelestialCalculator` is NOT yet ported (only MoonPhase + SunTimes from Stage 6-A landed). Needs zodiac signs, planetary hours, element balance. Defer to a future stage that ports the rest of CelestialCalculator.
- **Celestial milestone** ("You walked on the {seasonal-marker}") — same dependency.
- **Per-section staggered fade delays** — 13-Z polish.

## Architecture

### 13-E: WalkFavicon enum + persistence

New file `data/entity/WalkFavicon.kt`:

```kotlin
enum class WalkFavicon(val rawValue: String, val labelRes: Int, val iconRes: Int) {
    FLAME(rawValue = "flame", labelRes = R.string.summary_favicon_transformative,
        iconRes = R.drawable.ic_walk_favicon_flame),
    LEAF(rawValue = "leaf", labelRes = R.string.summary_favicon_peaceful,
        iconRes = R.drawable.ic_walk_favicon_leaf),
    STAR(rawValue = "star", labelRes = R.string.summary_favicon_extraordinary,
        iconRes = R.drawable.ic_walk_favicon_star),
    ;

    companion object {
        fun fromRawValue(raw: String?): WalkFavicon? =
            raw?.let { entries.firstOrNull { it.rawValue == raw } }
    }
}
```

Use existing Material icons (`Icons.Filled.LocalFireDepartment` / `Icons.Outlined.Spa` / `Icons.Filled.Star`) instead of custom drawables — same precedent as Stage 13-A's TimeBreakdown card. Drop `iconRes`, replace with `imageVector: ImageVector`. Cleanup the spec accordingly.

Add `WalkDao.updateFavicon(walkId, favicon)`:

```kotlin
@Query("UPDATE walks SET favicon = :favicon WHERE id = :walkId")
suspend fun updateFavicon(walkId: Long, favicon: String?)
```

Add `WalkRepository.setFavicon(walkId, favicon)`:

```kotlin
suspend fun setFavicon(walkId: Long, favicon: String?) =
    walkDao.updateFavicon(walkId, favicon)
```

VM gains `setFavicon(WalkFavicon?)` method that posts to `Dispatchers.IO` and re-fetches the walk to refresh `WalkSummary.walk.favicon`. Pattern matches existing `WalkViewModel.setIntention` (uses bump counter to retrigger flow).

Actually simpler: VM exposes `selectedFavicon: StateFlow<WalkFavicon?>` derived from `state` Loaded summary, and `setFavicon` writes to DAO + bumps a generation counter that triggers re-emission. Or use direct DataStore-style update: write through DAO + immediately update local state.

Cleanest: VM holds `selectedFavicon: MutableStateFlow<WalkFavicon?>(initial = state.walk.favicon)`. `setFavicon(fav)` updates the StateFlow optimistically + DAO write. On re-entry to summary, initial-value re-reads from `state.walk.favicon`.

### 13-E: FaviconSelectorCard composable

```kotlin
@Composable
fun FaviconSelectorCard(
    selected: WalkFavicon?,
    onSelect: (WalkFavicon?) -> Unit,
    modifier: Modifier = Modifier,
)
```

Layout: `Card(parchmentSecondary)`, padding normal:
- Caption "Mark this walk" (caption style, fog)
- Row(spacedBy big) with 3 buttons:
  - 44dp circle: filled stone (selected) or fog@0.15 (unselected) + icon (parchment selected / fog unselected)
  - Label below: micro style (ink selected / fog unselected)
- Tap: if same fav → null (deselect); else → new fav. Calls `onSelect`.
- Animation: `animateColorAsState` for fill + content color (200ms ease-in-out).

Accessibility: `Modifier.semantics { stateDescription = if (selected) "Selected" else "Not selected" }`.

### 13-F (partial): Milestone callout

Existing VM exposes `WalkSummary.milestone: GoshuinMilestone?`. Compute milestone-text label via `GoshuinMilestones.label(milestone)` (existing).

Two-tier label:
- Use existing `label()` for goshuin grid (e.g. "Longest Walk", "First Walk", "5th Walk", "First of Spring")
- For the summary callout, render the iOS-flavored prose: "Your longest walk yet" / "Your longest meditation yet" / "You've now walked N km total"

iOS computeMilestone returns prose strings directly. Android's GoshuinMilestone is structured. Add a `summaryProse(milestone, distanceUnits)` helper that returns the iOS prose given the milestone variant.

Mapping iOS → Android:
- "Your longest walk yet" → `LongestWalk` (existing)
- "Your longest meditation yet" → NOT covered by current GoshuinMilestone. iOS computes meditation-longest separately. Add a new `LongestMeditation` variant? Or compute separately in WalkSummaryViewModel?

Simpler: extend GoshuinMilestone with `LongestMeditation` data object. Keep grid-label "Longest Meditation" too. Update `GoshuinMilestones.detect` to fire if walk.meditationSeconds > all-prior-walks' meditationSeconds AND > 0. Stage 4-D's detection logic for LongestWalk already does the distance equivalent.

For "You've now walked N km total":
- iOS computes after the longest-walk + longest-meditation checks: thresholds [10, 25, 50, 100, 250, 500, 1000] km/mi units.
- Android adds new `TotalDistanceMilestone(unit: Int, isMiles: Boolean)` variant?

Stage scope creep. Decision: **defer LongestMeditation + TotalDistanceMilestone variants.** 13-F-bundle ships callout for the EXISTING GoshuinMilestone variants only:
- FirstWalk → "Your first walk"
- LongestWalk → "Your longest walk yet"
- NthWalk(n) → "Your ${ordinal(n)} walk"
- FirstOfSeason(season) → "Your first walk of ${season}"

The longest-meditation + N-units-total callouts wait for the milestone-detection extension (a separate small stage).

```kotlin
@Composable
fun MilestoneCalloutRow(
    milestone: GoshuinMilestone,
    modifier: Modifier = Modifier,
)
```

Layout: `Row(spacedBy = small, padding-horizontal = normal, padding-vertical = small, dawn@0.1 background, RoundedCornerShape(normal))`:
- Sparkles icon `Icons.Rounded.AutoAwesome`, dawn tint
- Text (caption style, ink, lineLimit=2, minimumScaleFactor=0.7) with the prose

`milestoneSummaryProse(milestone): String` — `@Composable` helper using stringResource.

Caller-side gate: `summary.milestone?.let { MilestoneCalloutRow(it) }`. iOS at section 7 (between duration hero and stats row).

### 13-F (partial): Elevation profile sparkline

```kotlin
@Composable
fun ElevationProfile(
    altitudes: List<Double>,
    units: UnitSystem,
    modifier: Modifier = Modifier,
)
```

Caller-side gate: `if (summary.altitudeSamples.size > 5 && (max - min) > 1m)`. The summary needs `altitudeSamples` exposed — currently NOT on `WalkSummary` (Stage 13-A `ascendMeters` only computed sum, not the raw list). Add to VM.

Layout: `Column(verticalArrangement = spacedBy 4dp)`:
- `Box(fillMaxWidth, height = 48dp)` with `Canvas`:
  - Sample stride: `step = max(1, altitudes.size / canvasWidthInPx)`. Sample by stride to ≤ 1 point per pixel.
  - Two paths: (1) gradient-fill closed path bottom-to-curve-to-bottom, (2) stroke path of curve.
  - Fill: linear-gradient `stone @ 0.3 → stone @ 0.05` top-to-bottom.
  - Stroke: stone @ 0.6, 1.5dp width, round cap + join.
- `Row(spaceBetween)`:
  - Left: `WalkFormat.altitude(min, units)`
  - Center: `Icons.Outlined.Terrain` (caption-sized, fog)
  - Right: `WalkFormat.altitude(max, units)`

iOS `ElevationProfileView` line 41 uses `Int(geo.size.width)` for stride — Compose `Canvas` exposes `size.width` as float. Use `step = max(1, altitudes.size / size.width.toInt())`.

Pure helper extracted for testability:

```kotlin
internal data class ElevationSparklinePoint(val xFraction: Float, val yFraction: Float)

internal fun computeElevationSparklinePoints(
    altitudes: List<Double>,
    targetWidthBuckets: Int,
): List<ElevationSparklinePoint>
```

Lives in `ui/walk/summary/ElevationProfile.kt` private file. Tests cover empty, monotonic-ascent, monotonic-descent, mixed.

### 13-G: Details (Paused) card

```kotlin
@Composable
fun WalkSummaryDetailsCard(
    pausedMillis: Long,
    modifier: Modifier = Modifier,
)
```

Caller-side gate: `if (summary.totalPausedMillis > 0L)`.

Layout: `Card(parchmentSecondary, padding-normal)`:
- `Row(SpaceBetween, body style)`:
  - Left: "Paused" (fog)
  - Right: `WalkFormat.duration(pausedMillis)` (ink)

Single row. iOS line 797-808 verbatim port.

## VM additions

```kotlin
data class WalkSummary(
    // existing fields…
    val altitudeSamples: List<AltitudeSample> = emptyList(),
)
```

Populate from already-hoisted `altitudeSamples` local in `buildState`.

`WalkSummaryViewModel`:

```kotlin
private val _selectedFavicon = MutableStateFlow<WalkFavicon?>(null)
val selectedFavicon: StateFlow<WalkFavicon?> = _selectedFavicon.asStateFlow()

// In buildState() after fetching walk:
_selectedFavicon.value = WalkFavicon.fromRawValue(walk.favicon)

// New public method:
fun setFavicon(favicon: WalkFavicon?) {
    val current = _selectedFavicon.value
    val newValue = if (favicon == current) null else favicon
    _selectedFavicon.value = newValue  // optimistic
    viewModelScope.launch(Dispatchers.IO) {
        try {
            repository.setFavicon(walkId, newValue?.rawValue)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "setFavicon failed for walk $walkId", t)
            // Revert optimistic update
            _selectedFavicon.value = current
        }
    }
}
```

Match iOS toggle semantics: tap selected → deselect.

## Section ordering after EFG

Wire into existing `WalkSummaryScreen.kt` Loaded branch:
- Section 4 (between Reliquary/Intention and JourneyQuote) — `ElevationProfile` (gated on samples ≥ 6 AND range > 1m). Lives INSIDE the Stage 13-B `AnimatedVisibility` reveal wrapper.
- Section 7 (between DurationHero and StatsRow) — `MilestoneCalloutRow` (gated on milestone != null). INSIDE reveal wrapper.
- Section 12 (between TimeBreakdown and ActivityTimeline) — `FaviconSelectorCard`. INSIDE reveal wrapper.
- Section 18 (between VoiceRecordings/AI-prompts and LightReading) — `WalkSummaryDetailsCard` (gated on pausedMillis > 0). OUTSIDE reveal wrapper (sits after voice recordings).

## Strings

```xml
<!-- 13-E favicon -->
<string name="summary_favicon_caption">Mark this walk</string>
<string name="summary_favicon_transformative">Transformative</string>
<string name="summary_favicon_peaceful">Peaceful</string>
<string name="summary_favicon_extraordinary">Extraordinary</string>

<!-- 13-F milestone callout prose -->
<string name="summary_milestone_first_walk">Your first walk</string>
<string name="summary_milestone_longest_walk">Your longest walk yet</string>
<string name="summary_milestone_nth_walk">Your %1$s walk</string>
<string name="summary_milestone_first_of_spring">Your first walk of Spring</string>
<string name="summary_milestone_first_of_summer">Your first walk of Summer</string>
<string name="summary_milestone_first_of_autumn">Your first walk of Autumn</string>
<string name="summary_milestone_first_of_winter">Your first walk of Winter</string>

<!-- 13-G details -->
<string name="summary_details_paused">Paused</string>
```

## Files

### Create

| Path | Purpose |
|---|---|
| `app/src/main/java/.../data/entity/WalkFavicon.kt` | Enum |
| `app/src/main/java/.../ui/walk/summary/FaviconSelectorCard.kt` | 13-E composable |
| `app/src/main/java/.../ui/walk/summary/MilestoneCalloutRow.kt` | 13-F composable + prose helper |
| `app/src/main/java/.../ui/walk/summary/ElevationProfile.kt` | 13-F composable + `computeElevationSparklinePoints` pure |
| `app/src/main/java/.../ui/walk/summary/WalkSummaryDetailsCard.kt` | 13-G composable |
| `app/src/test/java/.../ui/walk/summary/ElevationProfileTest.kt` | 4 cases |
| `app/src/test/java/.../ui/walk/summary/FaviconSelectorCardTest.kt` | 2 Robolectric cases |
| `app/src/test/java/.../ui/walk/summary/MilestoneCalloutRowTest.kt` | 4 Robolectric cases |
| `app/src/test/java/.../ui/walk/summary/WalkSummaryDetailsCardTest.kt` | 1 Robolectric case |

### Modify

| Path | Change |
|---|---|
| `app/src/main/java/.../data/dao/WalkDao.kt` | Add `updateFavicon` |
| `app/src/main/java/.../data/WalkRepository.kt` | Add `setFavicon` |
| `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt` | Add `altitudeSamples` to WalkSummary; add `selectedFavicon` StateFlow + `setFavicon` method |
| `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt` | Wire 4 sections (4/7/12/18) |
| `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt` | Add 2 tests (favicon set/toggle, altitudeSamples populated) |
| `app/src/main/res/values/strings.xml` | 12 new strings |

## Tests

```kotlin
// ElevationProfileTest
@Test fun emptyAltitudes_returnsEmpty()
@Test fun monotonicAscent_yieldsAscendingPath()  // higher altitude → lower y
@Test fun mixedAltitudes_normalizesAcrossRange()
@Test fun stride_caps_buckets_at_target_width()

// FaviconSelectorCardTest (Robolectric)
@Test fun rendersAllThreeButtons()
@Test fun tapsSameButtonTwice_deselects()  // verify onSelect called with same THEN null

// MilestoneCalloutRowTest (Robolectric)
@Test fun firstWalk_rendersCorrectProse()
@Test fun longestWalk_rendersCorrectProse()
@Test fun nthWalk_includesOrdinal()
@Test fun firstOfSeason_includesSeasonName()

// WalkSummaryDetailsCardTest (Robolectric)
@Test fun rendersPausedDuration()

// WalkSummaryViewModelTest (extend)
@Test fun setFavicon_persistsAndUpdatesFlow()
@Test fun altitudeSamples_populatedFromRepo()
```

## Behavioral details

- **Reveal-wrap:** sections 4 + 7 + 12 fade in with the Stage 13-B AnimatedVisibility group. Section 18 (Details) sits OUTSIDE the wrapper (matches Light Reading + Etegami pattern).
- **Favicon optimistic update:** UI flips immediately on tap; DAO write happens off-Main; failure reverts. iOS does not have an explicit revert path because CoreData writes are synchronous within the same managed-object-context — Android's Room.suspend can fail (DB closed during nav transition, etc.) — the revert is defensive.
- **Empty paused:** when totalPausedMillis is 0, the Details card is absent. Doesn't render header alone.
- **Empty milestone:** when no milestone, callout absent.
- **Empty elevation:** when fewer than 6 altitude samples OR range ≤ 1m, profile absent.
- **Favicon icons:** Material `Icons.Filled.LocalFireDepartment` / `Icons.Outlined.Spa` / `Icons.Filled.Star` — match iOS SFSymbol intent without bundling custom drawables.

## Risks + mitigations

- **`@Query UPDATE walks SET favicon = ...` bypasses entity `init` invariants.** Stage 7-B memory: "raw SQL writes don't re-materialize." Walk entity has no `init` invariants today (data class with default values), so this is safe. Document inline in the DAO method.

- **VM `selectedFavicon` StateFlow vs. WalkSummary.walk.favicon:** two sources of truth. Optimistic update keeps `_selectedFavicon` ahead of DAO write. On re-entry to summary, fresh buildState reads walk.favicon from DAO + sets _selectedFavicon. No drift.

- **Elevation sparkline allocation:** `computeElevationSparklinePoints` creates a new list per recompose. `remember(altitudes, canvasWidth)` caches; allocations bounded.

- **Material icons availability:** `Icons.Outlined.Terrain` (mountain icon for elevation profile) — verify in extended icons pack.

## Success criteria

- Open finished walk summary → favicon card shows 3 buttons; tap one → animates to selected; tap same → deselects; tap another → switches.
- Re-open summary after favicon set → previously-selected fav restored.
- Walk with `LongestWalk` milestone (existing detector) → callout reads "Your longest walk yet"
- Walk with `FirstWalk` / `NthWalk` / `FirstOfSeason` → callout shows the matching prose
- iOS divergence DOCUMENTED (not fixed in this bundle): iOS computeMilestone ALSO covers "Your longest meditation yet" + "You've now walked N km total". Android's GoshuinMilestone (Stage 4-D) doesn't have `LongestMeditation` or `TotalDistanceMilestone` variants. Adding them ripples through the goshuin grid + halo + reveal overlay surface — beyond the layout-only scope of this bundle. Defer to a focused follow-up stage that extends `GoshuinMilestones.detect` + adds matching label/halo/reveal handling. The visible gap: Android summary won't show those two callout variants for walks that would qualify on iOS.

- Walk with altitude variation > 1m → elevation profile renders with min/max labels.
- Walk with paused time → Details card shows below voice recordings.
- Stage 13-A/B/C/D regressions all pass.

`./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest` clean.
