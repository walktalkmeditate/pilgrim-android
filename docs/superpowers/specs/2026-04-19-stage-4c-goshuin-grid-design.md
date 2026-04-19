# Stage 4-C — Goshuin collection grid

**Status:** design
**Stage in port plan:** Phase 4 (Goshuin / generative seals) — 4-C
**Depends on:** Stage 4-A (SealRenderer), Stage 4-B (SealRevealOverlay integrated into WalkSummary)
**iOS reference:** `../pilgrim-ios/Pilgrim/Scenes/Goshuin/GoshuinView.swift`, `GoshuinPageView.swift`, `GoshuinFAB.swift`

## Goal

A browsable "book" of all seals the user has earned. Tap a seal → jump to that walk's summary. Empty state says "your goshuin will fill as you walk." The screen should feel like opening a physical stamp-collection book — contemplative, unhurried, visibly *accumulating* as practice deepens.

## Non-goals (YAGNI — defer to later stages)

- **Filter bar** by walk favicon/category. Android doesn't have favicons on walks yet — no data to filter on. Revisit when walk-type tagging lands.
- **Share renderer** (iOS `GoshuinShareRenderer`). No share infrastructure exists on Android yet (Phase 8). Adding one-off bitmap rendering for this surface now would bake in a pattern we'll redo.
- **Milestone rings** (dawn-color ring around N-th seal). Milestone *detection* is Stage 4-D territory. The grid cell's frame is designed to accommodate a ring overlay when 4-D lands — zero rework.
- **HorizontalPager "book" metaphor** with page indicators. iOS uses a paged TabView (6 seals/page). On Android, `LazyVerticalGrid` is the idiom — endless scroll through generative artifacts feels more like *inspecting a collection* than forced-6-at-a-time paging. The one thing the pager bought iOS (per-page patina tinting) we port as a whole-screen patina instead.
- **Seal-thumbnail FAB in the app bar** (iOS `GoshuinFAB` shows the latest seal). Requires bitmap-baking the seal or wrapping `SealRenderer` in a clipped preview — additional rendering plumbing. A plain button on Home works; the FAB is a polish pass we can revisit.
- **Walk favicon column on `Walk` entity.** No UI uses it yet.
- **Pre-computed `distanceMeters` column on `Walk`.** HomeViewModel already does an N+1 `locationSamplesFor` fetch to compute distance per row. At current walk counts this isn't a problem. If device QA at 100+ walks flags jank, that's a targeted schema/migration stage of its own — not ridden on top of a UI stage.

## Experience arc

1. **Home** has a new button below *Start a walk*: **"View goshuin"**. Enabled whenever at least one finished walk exists; disabled (greyed, but still visible) when the collection is empty — tapping shows the empty state anyway so the user can preview what it'll look like.
2. **Goshuin screen** opens. Top: small title "Goshuin" in `displayMedium`, `pilgrimColors.ink`. Body: 2-column `LazyVerticalGrid` of seals, most-recent-first.
3. Each **grid cell** is a square parchment tile (~170dp side on a typical phone) containing a `SealRenderer` at ~140dp, framed by a thin circular ink outline (`pilgrimColors.ink.copy(alpha = 0.04f)`, 1dp stroke) to read as "pressed stamp on paper." Beneath the seal: a one-line date label (`"Apr 19"` style, via `HomeFormat.relativeDate` or a simpler short-date formatter).
4. Tapping a cell navigates to `Routes.walkSummary(walkId)`. No stamp-reveal replay — the reveal overlay is scoped to the walk-finish flow; re-tapping an old seal is navigation, not reveal. (Consistent with iOS, which `dismiss()`es the goshuin view then nav-pushes to the walk detail — no second reveal animation.)
5. **Back** returns to Home with the grid's scroll position preserved (Compose `rememberLazyGridState` + NavBackStackEntry's saved-state-handle survive back-stack pop).
6. **Empty state** (no finished walks): a faded-seal placeholder centered on the screen (`SealRenderer` rendered with a placeholder spec + `ink.copy(alpha = 0.10f)`) + caption "Your goshuin will fill as you walk." in `pilgrimColors.fog`.

### The one delight: parchment patina

The screen's background is layered: base `pilgrimColors.parchment`, overlaid with a translucent `pilgrimColors.dawn` wash whose alpha scales with the user's total finished-walk count. Four breakpoints (ported from iOS):

| Walk count | Dawn overlay alpha |
|------------|-------------------|
| 0 – 10     | 0.00 (no tint)    |
| 11 – 30    | 0.03              |
| 31 – 70    | 0.07              |
| 71+        | 0.12              |

This is a quiet, wabi-sabi reward: the book visibly *ages* as practice deepens. The overlay is a `Box` modifier at the Scaffold level — one line, ~zero runtime cost. iOS applies it per-page inside the TabView; on Android's endless scroll we apply it once to the whole screen (cleaner).

## Architecture

### New types

**`GoshuinSeal` (UI model)** — packages the walk id, pre-built `SealSpec` (with `ink = Color.Transparent` placeholder, resolved in the @Composable layer), the walk's `LocalDate` (for seasonal-ink resolution), and a pre-formatted short-date label for under-the-seal display. Pattern matches `HomeWalkRow`.

```kotlin
data class GoshuinSeal(
    val walkId: Long,
    val sealSpec: SealSpec,        // ink = Color.Transparent placeholder
    val walkDate: LocalDate,       // for SeasonalColorEngine.applySeasonalShift
    val shortDateLabel: String,    // "Apr 19", "Mar 3", etc.
)
```

**`GoshuinUiState`** — three-state load, mirroring `WalkSummaryUiState` and `HomeUiState`.

```kotlin
sealed class GoshuinUiState {
    data object Loading : GoshuinUiState()
    data object Empty : GoshuinUiState()
    data class Loaded(val seals: List<GoshuinSeal>, val totalCount: Int) : GoshuinUiState()
}
```

`totalCount` feeds the patina breakpoint lookup. It equals `seals.size` today; it's a separate field so a future filter (Stage 4-D) can render a subset without dimming the patina — the patina tracks *lifetime* practice, not current view.

### `GoshuinViewModel` (Hilt-injected)

- Injects `WalkRepository` + `HemisphereRepository` (for the per-cell seasonal tint — VM only proxies the Hemisphere StateFlow, composable does the HSV math).
- Observes `repository.observeAllWalks()`, filters `endTimestamp != null`, maps each finished walk to a `GoshuinSeal`.
- Distance per walk computed via `walkDistanceMeters(samples)` — same N+1 pattern as `HomeViewModel.mapToRow`. Acceptable for current scale; a future optimization stage can cache.
- `SealSpec` constructed via `Walk.toSealSpec(distanceMeters, ink = Color.Transparent, displayDistance, unitLabel)` (already exists from 4-A; takes pre-computed distance since 4-B).
- `stateIn(scope = viewModelScope, started = WhileSubscribed(5_000), initialValue = Loading)` — matches `HomeViewModel` lesson. Stops the Room collector when the user is on another screen for more than 5s; unit tests without subscribers don't hang (Stage 2-E / 3-C lessons).
- Sorts finished walks by `endTimestamp DESC` (most recent first). Ties broken by `id DESC` for stability.

### `GoshuinScreen`

```kotlin
@Composable
fun GoshuinScreen(
    onBack: () -> Unit,
    onSealTap: (Long) -> Unit,
    viewModel: GoshuinViewModel = hiltViewModel(),
)
```

Structure:

```
Scaffold (pilgrimColors.parchment background)
  └── Box (fillMaxSize)
        ├── Box (fillMaxSize).background(dawn.copy(alpha = patinaFor(totalCount)))  // patina overlay
        └── Column
              ├── Header row: "Goshuin" title + back IconButton
              └── when (uiState):
                    Loading -> CircularProgressIndicator (16dp, stone)
                    Empty   -> GoshuinEmptyState (centered faded seal + caption)
                    Loaded  -> LazyVerticalGrid (2 cols)
                                 items(seals) { seal ->
                                   GoshuinSealCell(seal, hemisphere, onClick = { onSealTap(seal.walkId) })
                                 }
```

Per-cell seasonal tint (mirrors `WalkSummaryScreen.specForReveal`):

```kotlin
@Composable
private fun GoshuinSealCell(seal: GoshuinSeal, hemisphere: Hemisphere, onClick: () -> Unit) {
    val baseInk = pilgrimColors.rust  // same base as WalkSummaryScreen uses
    val tintedSpec = remember(seal.sealSpec, baseInk, seal.walkDate, hemisphere) {
        val tintedInk = SeasonalColorEngine.applySeasonalShift(
            base = baseInk,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = seal.walkDate,
            hemisphere = hemisphere,
        )
        seal.sealSpec.copy(ink = tintedInk)
    }
    // Cell layout: inked-circle frame + SealRenderer + short-date caption
    Column(Modifier.clickable { onClick() }.padding(PilgrimSpacing.small)) {
        Box(contentAlignment = Alignment.Center) {
            // thin ink-outline circle (the "stamp on paper" frame)
            Box(Modifier.size(CELL_SEAL_SIZE).drawBehind {
                drawCircle(color = inkFrameColor, radius = size.minDimension / 2f, style = Stroke(1.dp.toPx()))
            })
            SealRenderer(spec = tintedSpec, modifier = Modifier.size(CELL_SEAL_SIZE - 8.dp))
        }
        Text(seal.shortDateLabel, style = pilgrimType.caption, color = pilgrimColors.fog)
    }
}
```

### Navigation

- Add `Routes.GOSHUIN = "goshuin"` to `PilgrimNavHost.Routes`.
- Add `composable(Routes.GOSHUIN)` with `onBack = { navController.popBackStack() }`, `onSealTap = { id -> navController.navigate(Routes.walkSummary(id)) }`.
- `HomeScreen` gets a new `onEnterGoshuin: () -> Unit` param. Button below *Start a walk*, labeled "View goshuin" (new string resource).
- `PilgrimNavHost` wires the button to `navController.navigate(Routes.GOSHUIN)`.

### Strings (English-only, matches project policy)

- `R.string.home_action_view_goshuin` = "View goshuin"
- `R.string.goshuin_title` = "Goshuin"
- `R.string.goshuin_empty_caption` = "Your goshuin will fill as you walk"
- `R.string.goshuin_back_content_description` = "Back"

### Constants

```kotlin
private val CELL_SEAL_SIZE = 140.dp         // on-seal canvas size; tile is slightly larger
private val CELL_SEAL_TILE = 170.dp         // outer tile for the cell (seal + date label)
private const val PATINA_TIER_1_COUNT = 11  // below → alpha 0
private const val PATINA_TIER_2_COUNT = 31  // 11..30 → alpha 0.03
private const val PATINA_TIER_3_COUNT = 71  // 31..70 → alpha 0.07, 71+ → alpha 0.12
private const val PATINA_ALPHA_TIER_1 = 0.03f
private const val PATINA_ALPHA_TIER_2 = 0.07f
private const val PATINA_ALPHA_TIER_3 = 0.12f
private const val SEAL_FRAME_ALPHA = 0.04f  // ink outline around each cell seal
```

## Testing

### `GoshuinViewModelTest` (JUnit 4 + Turbine)

- Empty repository → `uiState` emits `Empty`.
- Repository with only in-progress (no endTimestamp) walks → emits `Empty` (filter excludes).
- Two finished walks → emits `Loaded` with 2 seals, ordered by end_timestamp desc.
- `seals[i].sealSpec.ink == Color.Transparent` (VM doesn't resolve seasonal tint).
- `totalCount == seals.size` in current stage.
- Hemisphere proxied from `HemisphereRepository` (simple passthrough check).
- `WhileSubscribed(5000)` behavior: test exercises `stateIn` subscription lifecycle.

### `GoshuinScreenTest` (Robolectric compose-rule smoke)

- Composes `GoshuinScreen` with a fake `GoshuinViewModel` in `Empty` state — asserts the empty caption is displayed.
- Composes in `Loaded` state with one seal — asserts the date label is displayed (seal Canvas draw is a stub per Robolectric constraint, not asserted).
- Tap on a seal row fires the `onSealTap(walkId)` callback.

### `PatinaAlphaTest` (plain JUnit, pure function)

- `patinaAlphaFor(0) == 0f`
- `patinaAlphaFor(10) == 0f`
- `patinaAlphaFor(11) == 0.03f`
- `patinaAlphaFor(30) == 0.03f`
- `patinaAlphaFor(31) == 0.07f`
- `patinaAlphaFor(70) == 0.07f`
- `patinaAlphaFor(71) == 0.12f`
- `patinaAlphaFor(500) == 0.12f`

### Existing `HomeScreen` smoke test (if present) — extend to verify the new button exists and fires its callback.

## Risks and mitigations

- **Seal render cost at scale.** `SealRenderer` inside a `LazyVerticalGrid` cell: geometry is `remember`-cached on `(uuid, startMillis, distanceMeters)` so scroll-back doesn't re-hash. Off-screen cells are unmounted; re-scroll-in re-composes and re-runs `remember`. For 100 seals, first render ≤ 4 visible cells per frame = negligible. Device QA (Stage 4-E or equivalent) is the proving ground; current numbers on 4-B's reveal overlay suggest budget.
- **Font allocation per cell.** `SealRenderer` calls `ResourcesCompat.getFont(...)` inside `remember {}`. Font *handles* are cached by `ResourcesCompat` — repeated calls hit the in-memory cache. Per-cell `remember` means each mounted cell holds its own Typeface reference; they share the same underlying `Typeface` object. Not a leak.
- **Hemisphere flip mid-scroll.** `HemisphereRepository` emits rarely (user crosses equator). The `remember(... hemisphere)` key invalidates every mounted cell, recomputing the HSV shift. This is batched via Compose's snapshot system; one frame of work for all visible cells. Acceptable.
- **N+1 distance query.** `locationSamplesFor` called per finished walk in `GoshuinViewModel.mapToSeal`. Current pattern precedent (HomeViewModel) — not a regression. Future cache is tracked separately, not coupled to this stage.
- **Back-nav race.** User: Home → Goshuin → Summary → back (Goshuin) → back (Home). Each screen's VM re-subscribes to its Room Flow; `WhileSubscribed(5000)` keeps the most recent value fresh during quick back/forward navigation.

## What's on the commit

- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreen.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinUiState.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinSeal.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinPatina.kt` (pure `patinaAlphaFor(count): Float` helper + constants)
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModelTest.kt`
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreenTest.kt`
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinPatinaTest.kt`
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` (new `onEnterGoshuin` param + button)
- Modified: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` (new `Routes.GOSHUIN`, composable, and wiring from Home)
- Modified: `app/src/main/res/values/strings.xml` (4 new strings)

## Success criteria

- On a device with N≥1 finished walks: Home shows *View goshuin* button; tapping opens a 2-col grid with seals most-recent-first; seal tiles render with date caption; tapping a seal navigates to that walk's summary; back returns with scroll position preserved.
- On a device with zero finished walks: *View goshuin* button still navigates to the Goshuin screen, which shows the empty state with a faded seal placeholder + "Your goshuin will fill as you walk."
- Patina breakpoints observably tint the background at 11, 31, and 71 walks (manually verifiable by seeding a test DB).
- `./gradlew :app:testDebugUnitTest` green.
- No new lint warnings.

## Open questions (answered)

- *Paged book vs. endless grid?* Endless grid. The iOS pager metaphor is seductive but doesn't port cleanly to Compose idioms and costs us a nested LazyVerticalGrid inside a HorizontalPager. Android users expect `LazyVerticalGrid` for a collection view.
- *Where does the entry point live?* Below the Start-a-walk button on Home. A top-bar action or a FAB are polish-pass decisions; a plain button ships the feature.
- *Does tapping a seal replay the reveal animation?* No. Reveal is scoped to the walk-finish moment. Tapping a collection cell is navigation.
- *Room schema change for a cached distance column?* Not in this stage. The HomeViewModel N+1 pattern is already in production — this stage mirrors it. If device QA flags it, that's a focused schema migration of its own.
- *Filter bar / favicon support?* Deferred — no favicon concept exists on Android yet.
- *Milestone rings on cells?* Deferred to Stage 4-D. Cell layout already accommodates a ring overlay.
