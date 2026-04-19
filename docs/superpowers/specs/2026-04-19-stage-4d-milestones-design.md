# Stage 4-D — Goshuin milestone celebrations

**Status:** design
**Stage in port plan:** Phase 4 (Goshuin / generative seals) — 4-D
**Depends on:** Stage 4-A (`SealRenderer`), Stage 4-B (`SealRevealOverlay` + WalkSummary integration), Stage 4-C (Goshuin grid)
**iOS reference:** `../pilgrim-ios/Pilgrim/Scenes/Goshuin/GoshuinMilestones.swift`, `../pilgrim-ios/Pilgrim/Scenes/Goshuin/GoshuinPageView.swift:41-58`, `../pilgrim-ios/UnitTests/GoshuinMilestonesTests.swift`

## Goal

Mark walks that crossed a meaningful threshold — first walk ever, every 10th walk, the longest distance, the first walk of a season — with a quiet visual halo + label on the goshuin grid, and a slightly more present celebration during the post-finish seal reveal (an extra haptic pulse + a marginally longer hold on the reveal overlay so the user has time to *feel* the moment).

Pilgrim's aesthetic is wabi-sabi — the milestone is a footnote on the artifact, not a confetti popup. The label *is* the recognition. No "achievement unlocked!" chrome.

## Non-goals (deferred per scope discipline)

- **`longestMeditation` milestone (5th iOS type).** iOS computes per-walk meditation duration from a stored `Walk.meditateDuration`. Android currently derives it via `replayWalkEventTotals(events, closeAt)` per walk, which is one Room query per walk. Detecting `longestMeditation` across the user's entire history would N-multiply the existing N+1 sample query cost. **Defer** until a focused stage adds a `Walk.meditatedMillis` cached column (likely paired with the Stage 4-C `distanceMeters` cache that's already on the backlog).
- **Temple bell sound.** The original port-plan blurb mentions "temple bell + sound", but Android has no audio-bell infrastructure yet — Phase 5 introduces lazy-downloaded bells/voice-guides via the same `pilgrimapp.org/*` manifest iOS uses. Bundling a one-off `.ogg` in `res/raw/` for Stage 4-D would pre-bake an audio-session pattern that Phase 5 will rip out. **Defer.** The 2-pulse haptic carries the audible-celebration weight in the meantime.
- **`VibrationEffect.Composition` primitives** (`PRIMITIVE_THUD`, `PRIMITIVE_TICK`). Min SDK is 28; Composition is API 30+. A runtime tier'd haptic provider with `areAllPrimitivesSupported` checks + a custom `Vibrator` injection + `VIBRATE` permission is ~80 LOC of infrastructure for one feature. Compose's `LocalHapticFeedback.performHapticFeedback(LongPress)` × 2 with a 100ms delay gives the 2-pulse celebration in 5 LOC, works on every supported SDK, and honors the system haptic-disabled setting automatically.
- **Breathing halo animation.** A 4-second sin-wave alpha pulse on milestone halos (proposed by one design probe) is poetic but introduces per-frame `graphicsLayer` work for every visible milestone cell, which is risky at scroll without on-device QA. iOS uses a static ring. We match — and revisit if device QA in a polish stage requests breathing.
- **`Walk.favicon`-based filtering**, share renderer, achievement counts, sort-by-milestone — all out of scope.

## Experience arc

### On the goshuin grid

Each cell that hits one or more milestones gets:
1. A **dawn-color halo ring** — `pilgrimColors.dawn.copy(alpha = 0.5f)`, 2dp stroke, drawn concentrically *outside* the existing thin ink-outline frame (the frame stays at radius `CELL_FRAME_SIZE/2` ≈ 74dp; the halo sits at ~78dp, so the two are visually distinct rings).
2. A **milestone label** that *replaces* the short-date caption. The label uses the same `pilgrimType.caption` as the date — no new typography. Examples: "First Walk", "10th Walk", "Longest Walk", "First of Spring".

When a walk hits **multiple** milestones simultaneously (e.g., walk #10 is also the longest), only one label is shown. **Precedence order**, ranked by significance:
1. `FirstWalk`
2. `LongestWalk`
3. `NthWalk(N)`
4. `FirstOfSeason(season)`

(iOS does `Set<Milestone>.first` which depends on Swift's hash-based iteration order — non-deterministic across processes. Android fixes this by explicit precedence.)

### On walk-finish (SealRevealOverlay)

When the walk being shown is itself a milestone walk:
1. The reveal overlay holds for **3.0s** instead of 2.5s — an extra beat to let the moment land.
2. At the Pressing → Revealed transition, the overlay fires **two `LongPress` haptics** ~120ms apart (instead of one). The body reads it as a "double tap" — distinct from a non-milestone reveal.

When the walk is *not* a milestone, behavior is unchanged from Stage 4-B.

The `SealRevealOverlay` receives one new parameter: `isMilestone: Boolean = false`. The `WalkSummaryViewModel` computes the milestone status as part of `WalkSummary` and `WalkSummaryScreen` passes it through.

## Architecture

### `GoshuinMilestone` — sealed class

```kotlin
sealed class GoshuinMilestone {
    data object FirstWalk : GoshuinMilestone()
    data class NthWalk(val n: Int) : GoshuinMilestone()
    data object LongestWalk : GoshuinMilestone()
    data class FirstOfSeason(val season: Season) : GoshuinMilestone()
}

enum class Season { Spring, Summer, Autumn, Winter }
```

`Season` is a Kotlin enum (locale-stable); the user-facing label is resolved at the call site via `R.string.season_spring`, etc., so adding a new translation doesn't require touching detection code.

### `GoshuinMilestones` — pure detector

```kotlin
object GoshuinMilestones {
    /**
     * Detect the highest-precedence milestone for the walk at
     * [walkIndex] (0-based, most-recent-first) within the [allFinished]
     * snapshot. Returns null when no milestone applies.
     *
     * Pure function; no Android dependencies; trivially testable.
     */
    fun detect(
        walkIndex: Int,
        walk: WalkMilestoneInput,
        allFinished: List<WalkMilestoneInput>,
        hemisphere: Hemisphere,
    ): GoshuinMilestone?

    /** Stable English label per CLAUDE.md "English-only baseline" rule.
     *  Localization scaffold (string resources) is Stage 10. */
    fun label(milestone: GoshuinMilestone): String

    /** Pure month → Season helper (mirrors iOS SealTimeHelpers.season). */
    fun seasonFor(timestamp: Long, hemisphere: Hemisphere): Season
}
```

`WalkMilestoneInput` is a small DTO — `walkId: Long`, `uuid: String`, `startTimestamp: Long`, `distanceMeters: Double` — so the detector doesn't depend on the full `Walk` Room entity (testable from unit tests without instantiating Room).

The `walkNumber` for `NthWalk` and `FirstWalk` checks is computed as `allFinished.size - walkIndex` (index 0 = most recent = highest walkNumber). This matches iOS's semantics (iOS passes `walkIndex + 1` from the page-view loop, where index 0 is the OLDEST). Same effective value, computed once here for clarity.

### Detection placement: shared pure function, called from two VMs

- `GoshuinViewModel.mapToSeal(walk, allFinished, distanceMap, hemisphere)` — passes the full `allFinished` list and a precomputed `Map<walkId, distanceMeters>` so each per-walk `detect()` call is O(N) without re-loading samples.
- `WalkSummaryViewModel.buildState()` — loads `repository.allWalks()`, filters finished, computes the same distance map for milestone detection of the current walk only.

Both VMs call `GoshuinMilestones.detect(...)`. No drift risk.

### Updated data classes

```kotlin
// GoshuinSeal.kt: one new field
data class GoshuinSeal(
    val walkId: Long,
    val sealSpec: SealSpec,
    val walkDate: LocalDate,
    val shortDateLabel: String,
    val milestone: GoshuinMilestone? = null,  // NEW
)

// WalkSummary.kt: one new field
data class WalkSummary(
    val walk: Walk,
    val totalElapsedMillis: Long,
    val activeWalkingMillis: Long,
    val totalPausedMillis: Long,
    val totalMeditatedMillis: Long,
    val distanceMeters: Double,
    val paceSecondsPerKm: Double?,
    val waypointCount: Int,
    val routePoints: List<LocationPoint>,
    val sealSpec: SealSpec,
    val milestone: GoshuinMilestone? = null,  // NEW
)
```

Both remain `@Immutable` (the data class on `GoshuinSeal` is annotated; `WalkSummary` was not previously annotated but only contained primitives and stable types — adding `GoshuinMilestone?` keeps it stable since the sealed class hierarchy contains only stable types).

### `GoshuinSealCell` updates

Add the halo and swap the label:

```kotlin
Box(modifier = Modifier.size(CELL_FRAME_SIZE), contentAlignment = Alignment.Center) {
    // existing inked frame ring (light stamp on paper)
    Box(...)

    // NEW: milestone halo, drawn outside the inked frame
    if (seal.milestone != null) {
        Box(
            modifier = Modifier.size(CELL_HALO_SIZE).drawBehind {
                drawCircle(
                    color = haloColor,
                    radius = size.minDimension / 2f,
                    style = Stroke(width = HALO_STROKE_DP.toPx()),
                )
            },
        )
    }

    SealRenderer(spec = tintedSpec, modifier = Modifier.size(CELL_SEAL_SIZE))
}
Spacer(Modifier.height(PilgrimSpacing.small))
Text(
    text = seal.milestone?.let { GoshuinMilestones.label(it) } ?: seal.shortDateLabel,
    style = pilgrimType.caption,
    color = if (seal.milestone != null) pilgrimColors.dawn else pilgrimColors.fog,
)
```

Constants added: `CELL_HALO_SIZE = 156.dp` (~8dp outside the 148dp frame), `HALO_STROKE_DP = 2.dp`.

The label color shifts from `fog` to `dawn` for milestone cells — same warm tone as the halo, reads as "this label is special."

### `SealRevealOverlay` updates

Add an `isMilestone: Boolean = false` parameter. Behavioral changes:

```kotlin
const val HOLD_DURATION_MS = 2500L
const val MILESTONE_HOLD_BONUS_MS = 500L
const val MILESTONE_PULSE_GAP_MS = 120L

LaunchedEffect(Unit) {
    if (phase == SealRevealPhase.Hidden) phase = SealRevealPhase.Pressing
    delay(PRESS_DURATION_MS.toLong())
    if (phase != SealRevealPhase.Pressing) return@LaunchedEffect
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    if (isMilestone) {
        delay(MILESTONE_PULSE_GAP_MS)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }
    phase = SealRevealPhase.Revealed
    val hold = HOLD_DURATION_MS + if (isMilestone) MILESTONE_HOLD_BONUS_MS else 0L
    delay(hold)
    if (phase == SealRevealPhase.Revealed) {
        phase = SealRevealPhase.Dismissing
    }
}
```

The 2nd haptic fires *between* the existing reveal haptic and the spring-to-Revealed phase change. Total added latency: 120ms — imperceptible to the visual flow, distinctly perceptible to the body.

`WalkSummaryScreen` resolves `isMilestone = loaded.summary.milestone != null` and passes it through.

### Strings

```xml
<string name="goshuin_milestone_first">First Walk</string>
<string name="goshuin_milestone_longest">Longest Walk</string>
<string name="goshuin_milestone_nth">%1$s Walk</string>      <!-- e.g. "10th Walk" -->
<string name="goshuin_milestone_first_of_spring">First of Spring</string>
<string name="goshuin_milestone_first_of_summer">First of Summer</string>
<string name="goshuin_milestone_first_of_autumn">First of Autumn</string>
<string name="goshuin_milestone_first_of_winter">First of Winter</string>
```

The ordinal "10th" / "21st" / "100th" suffix is generated in Kotlin via a pure helper inside `GoshuinMilestones` (matches iOS's `ordinal(n)` private helper); the `%1$s` placeholder receives the formatted ordinal. Locale-sensitive ordinals (e.g., French "10e") wait for Stage 10's localization.

## Testing

### `GoshuinMilestonesTest` (plain JUnit, pure functions)

Port iOS's 4 tests + add Android-specific cases:

- `firstWalk_isMilestone` — single-walk list, walkIndex 0 → `FirstWalk`
- `everyTenth_isMilestone` — 10-walk list with walkIndex (size - 10) → `NthWalk(10)`; same for 20, 30
- `nonMilestone_isEmpty` — 7-walk list, middle walk → null
- `secondWalk_notFirstWalk` — 2-walk list, walkIndex 1 (older) → null
- `longestWalk_marksTheMaxDistance` — 3 walks with distances [1000, 5000, 2000], detect on walk #2 → `LongestWalk`
- `longestWalk_tiebreakerLatestWins` — two walks with identical max distance, detect picks the most recent
- `firstOfSeason_marksFirstSpringWalkOfYear` — walks across spring + summer; first spring walk → `FirstOfSeason(Spring)`
- `firstOfSeason_doesNotMarkSecondInSameSeasonYear` — second spring-2026 walk → not first-of-season
- `firstOfSeason_marksAcrossYears` — first walk of spring-2027 even though spring-2026 happened → `FirstOfSeason(Spring)`
- `precedence_firstWalkOverridesNthWalk` — 1st walk that's also the (currently unique) longest → `FirstWalk`, not `LongestWalk`
- `precedence_longestOverridesNth` — 10th walk that's also the longest → `LongestWalk`, not `NthWalk(10)`
- `seasonFor_northernHemisphereMonths` — Mar/Jun/Sep/Dec timestamps map to the four seasons (parameterized by month)
- `seasonFor_southernHemisphereFlips` — same months, hemisphere = Southern → flipped seasons
- `ordinal_handlesTeens` — 11th, 12th, 13th use "th"; 21st, 22nd, 23rd use st/nd/rd
- `label_eachMilestoneType` — exhaustive label() coverage

### `GoshuinViewModelTest` extension

Add 2 cases:
- `Loaded_marksFirstWalkMilestone` — single finished walk → seal.milestone == FirstWalk
- `Loaded_marksLongestAcrossWalks` — three walks, middle one with max distance → that walk's seal.milestone == LongestWalk

### `WalkSummaryViewModelTest` extension

Add 1 case:
- `summary_carriesMilestoneForCurrentWalk` — finish first walk, observe summary → milestone == FirstWalk

### `GoshuinScreenTest` extension

Add 1 case:
- `Loaded_milestoneCellShowsMilestoneLabel` — seed a seal with `milestone = FirstWalk`, assert "First Walk" text is displayed (the date caption is replaced)

### `SealRevealOverlayTest` extension

Add 1 case:
- `overlay_rendersWithMilestoneFlag` — pass `isMilestone = true`, assert composition succeeds. Animation timing isn't asserted (hard in compose-test; manual on-device QA verifies).

Total new tests: ~16 across 5 files. Detection covers the bulk; VM/UI tests verify wiring.

## What's on the commit

- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestone.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestones.kt`
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestonesTest.kt`
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinSeal.kt` (add `milestone: GoshuinMilestone?`)
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt` (build distance map + call detector)
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreen.kt` (halo ring + milestone-label swap + dawn label color)
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt` (add milestone to `WalkSummary`)
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt` (pass `isMilestone` to overlay)
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRevealOverlay.kt` (`isMilestone` param + 2-pulse + bonus hold)
- Modified: `app/src/main/res/values/strings.xml` (7 new strings)
- Modified: existing tests get the extension cases above

## Success criteria

- On a device with one finished walk: open goshuin → that walk's cell shows a dawn halo + "First Walk" label. Finishing a brand-new walk (the 2nd ever) shows no halo, label is the date.
- On a device with 10 finished walks: walk #1 shows "First Walk", walk #10 shows "10th Walk". Walks 2-9 show date labels (unless they're the longest or first-of-season). The longest-distance walk among any of them shows "Longest Walk" UNLESS it's already #1 or #10.
- Finishing the 10th walk: the SealRevealOverlay holds for 3.0s instead of 2.5s, and the body feels two haptic pulses 120ms apart at the reveal moment.
- `./gradlew :app:testDebugUnitTest` green; ~16 new test cases all passing.
- No new lint warnings.

## Open questions (answered)

- *Defer `longestMeditation`?* Yes — needs a Walk-row meditation cache to avoid N×M event reads per detection. Will land paired with the Stage 4-C distance cache when one of them gets prioritized.
- *Bell sound now or later?* Later. Phase 5 owns the audio-asset-download infra.
- *Custom `MilestoneHapticProvider` with API 30+ tiering?* No — overengineered. Compose's `LocalHapticFeedback × 2 + delay` covers the celebration with zero new infra and respects system haptic settings.
- *Breathing halo animation?* No — static ring matches iOS, ships clean. Add as a polish-stage if device QA requests.
- *Multiple milestones on one cell?* Show one label by explicit precedence (FirstWalk > LongestWalk > NthWalk > FirstOfSeason). iOS's non-deterministic Set ordering is a bug we don't replicate.
- *Where does detection live?* Pure shared function, called from `GoshuinViewModel` (for grid) and `WalkSummaryViewModel` (for reveal). No drift, no duplication.
