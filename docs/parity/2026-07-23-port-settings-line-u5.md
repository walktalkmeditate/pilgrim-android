# Settings Line Swap (U5) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U5) · **Requirements:** R3
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (HEAD of `main`, 2026-07-21). All Swift quotes cite `file@9a418e4` unless a historical commit is named.
> **Authority:** SHIPPED Swift over plan docs. The shipped end-state is the second of two iOS commits: `2133af5` ("the Settings line stops being frozen") did the swap with an inline body-pass resolve, and `b131de7` ("refuse an empty catalog…") converted it to a resolve-once-and-hold `@State`. Android ports the `b131de7`/`9a418e4` end-state, not the intermediate.
> **U2/U3 companions:** `docs/parity/2026-07-23-port-route-catalog-u2.md` (phrasing, D2 unit-parameter seam, D8 stability), `docs/parity/2026-07-23-port-route-catalog-service-u3.md` (§ "U5/U6 consumption notes" — hot `catalog: StateFlow`, null-until-content lookups).
> **Android files:** modify `ui/settings/{PracticeSummaryHeader,SettingsViewModel,SettingsScreen}.kt`; delete `data/collective/PilgrimageProgress.kt` + its test; tests `PracticeSummaryHeaderDailyLineTest.kt` + `SettingsViewModelTest.kt` extension.

## E1. Render conditions — nothing until BOTH catalog and total exist

**iOS** (`Pilgrim/Scenes/Settings/PracticeSummaryHeader.swift@9a418e4`):

```swift
if let stats = counterService.stats, stats.totalWalks > 0 {
    VStack(spacing: 4) {
        if let dailyLine {
            ...
            Text(dailyLine)
```

```swift
private func refreshDailyLine() {
    dailyLine = routeCatalogService.dailyLine(for: Date(), collectiveKm: counterService.stats?.totalDistanceKm)
}
```

The nil-handling chain: `counterService.stats` is nil until a counter fetch has ever landed, so `collectiveKm` arrives nil; the U2 model's `dailyLine` starts `guard let collectiveKm else { return nil }`; and the service passthrough is `catalog?.dailyLine(...)` — nil until the initial load lands. Either gap → `dailyLine == nil` → the `if let dailyLine` renders **nothing**. The swap commit (`2133af5`) states the intent:

> The line renders only when the catalog has loaded and the collective total is known. That total is nil until a fetch has ever succeeded, and coercing it to zero would tell a never-online pilgrim the path is beginning while the collective is several hundred kilometres along. Nothing is a truer answer than a fabricated number.

Note the double gate: the whole collective `VStack` (daily line + walks·distance line + streak flame) already only renders when `stats != nil && stats.totalWalks > 0` — so on a fresh offline install the *entire block* is absent, not just the daily line. The daily line's own nil additionally covers "stats present but catalog still loading" (stats line renders, daily line absent) and "catalog resolves no entry" (empty catalog).

**Android:** the existing gate `if (stats != null && stats.totalWalks > 0)` in `PracticeSummaryHeader.kt` already matches the outer condition — unchanged. Inside it, the line derives from the U2/U3 chain: `routeCatalog.dailyLine(nowEpochMillis(), stats.totalDistanceKm, distanceUnits)` where `routeCatalog` is the service's published catalog (`EMPTY` pre-load → `entry()` null → line null, U3 C3) and `stats.totalDistanceKm` is already kilometres (`CollectiveStats` mirrors the iOS wire model — no meters→km conversion exists to perform). `null` renders nothing. The stats-null degraded state never fabricates a zero: the outer gate makes the km argument unreachable when stats are null, exactly as on iOS, and the model-side `null → null` (U2 B3 branch 1) is what a caller outside the gate would hit.

## E2. Position in the stack

**iOS:** inside the collective `VStack(spacing: 4)`, the daily line is the FIRST element, directly above the `collectiveStatsLine` ("N walks · X km") and the streak flame; the block sits below the tappable per-user stat line, `.padding(.top, 4)`.

**Android:** the daily-line `Text` replaces the `PilgrimageProgress` `Text` in the same slot — first child of the inner `Column(verticalArrangement = spacedBy(4.dp), modifier = padding(top = 4.dp))`, above `collectiveStatsLine(stats, distanceUnits)` and `StreakFlame`. Position is inherited from the deleted line, matching iOS one-for-one.

## E3. Truncation contract — two lines, 0.7 scale floor

**iOS** (`PracticeSummaryHeader.swift@9a418e4`):

```swift
if let dailyLine {
    // Two lines where the sibling below takes one: the route
    // name is curator-editable after ship, so a hard single
    // line would truncate a longer one at accessibility sizes.
    Text(dailyLine)
        .font(Constants.Typography.caption.italic())
        .foregroundColor(.stone)
        .multilineTextAlignment(.center)
        .minimumScaleFactor(0.7)
        .lineLimit(2)
}
```

`2133af5`: "Two lines where the sibling takes one. … At default text size it still renders as one. The line it replaced had no limits at all."

**Android:** `maxLines = 2`, `textAlign = TextAlign.Center`, `style = pilgrimType.caption.copy(fontStyle = FontStyle.Italic)`, `color = pilgrimColors.stone`, and the codebase's established `minimumScaleFactor` analogue (WelcomeScreen precedent):

```kotlin
autoSize = TextAutoSize.StepBased(
    minFontSize = pilgrimType.caption.fontSize * 0.7f,
    maxFontSize = pilgrimType.caption.fontSize,
    stepSize = 0.5.sp,
)
```

Out of U5 scope: at `9a418e4` the three sibling lines (season label, per-user stat line, walks·distance line) each carry `.minimumScaleFactor(0.7).lineLimit(1)` — those modifiers **predate the frozen-line swap** (already present at the previous parity target `c1745e8`) and Android's header shipped without them through the v1.7.0 sweep. Pre-existing accepted divergence; U5 changes only the line it swaps.

## E4. Invalidation — resolve once and hold; the four triggers; no midnight timer

**iOS** (`PracticeSummaryHeader.swift@9a418e4`):

```swift
/// Resolved from the modifiers below rather than in the body, which
/// re-evaluates on every stat-cycling tap and every defaults change.
/// Phrasing costs an entry lookup, an ICU calendar call and a measurement
/// formatter — the same cost `CollectiveTrailSection` refuses to pay per
/// frame.
@State private var dailyLine: String?
```

```swift
.onAppear(perform: refreshDailyLine)
.onChange(of: counterService.stats?.totalDistanceKm) { _, _ in refreshDailyLine() }
.onChange(of: routeCatalogService.catalog?.version) { _, _ in refreshDailyLine() }
.onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
    isImperial = UserPreferences.distanceMeasurementType.safeValue == .miles
    // The line carries a raw distance on its sub-one-percent horizon
    // branch, and `CustomMeasurementFormatting` reads the preference
    // when it formats rather than publishing a change. Re-resolving
    // here is what makes a unit toggle reach the cached string.
    refreshDailyLine()
}
```

`2133af5` on why the observer stays: "The unit-preference state and its notification observer stay. The formatter reads the preference at call time and produces a correct string, but nothing invalidates the view — removing the observer would leave the line stale until something else redrew Settings."

**Midnight:** iOS has **no midnight/day-change trigger**. `Date()` is read fresh inside `refreshDailyLine()`, so the UTC-day re-resolution rides the four triggers above: every Settings appearance re-resolves for the current day (`.onAppear`), and a stats/catalog/defaults change after midnight lands the new day's entry. A device parked on the Settings screen across UTC midnight keeps yesterday's line until the next trigger — shipped iOS behavior, ported as-is.

**Android:** the Compose analogue of "@State resolved from triggers" is `remember` keyed on exactly the values the resolve reads:

```kotlin
val dailyLine = remember(routeCatalog, stats.totalDistanceKm, distanceUnits) {
    routeCatalog.dailyLine(nowEpochMillis(), stats.totalDistanceKm, distanceUnits)
}
```

Trigger mapping, one-for-one:

| iOS trigger | Android mechanism |
|---|---|
| `.onAppear` | `remember` computes fresh each time the header enters composition (tab re-entry, nav return) |
| `.onChange(stats?.totalDistanceKm)` | `stats.totalDistanceKm` remember key (the minimum field the resolve reads — Stage 4-A lesson) |
| `.onChange(catalog?.version)` | `routeCatalog` remember key (`CollectiveRouteCatalog.equals` is `(version, entries)`; a published sync is a new, unequal object) |
| `UserDefaults.didChangeNotification` → re-read `isImperial` + refresh | `distanceUnits` remember key — the DataStore-backed `StateFlow<UnitSystem>` **is** the change publisher, so no separate observer exists to port (D3) |

Stat-cycling taps and milestone recompositions leave every key unchanged → the cached string is reused, honoring the resolve-once rationale quoted above. No day ticker, matching iOS.

## E5. CollectiveMilestone extraction — no Android work

**iOS:** `2133af5` moved `CollectiveMilestone` out of the deleted `PilgrimageProgress.swift` into `Pilgrim/Models/Collective/CollectiveMilestone.swift`, **untouched** ("extracting it before the deletion keeps that deletion readable as a deletion rather than a rewrite"), and added `UnitTests/CollectiveMilestoneTests.swift`.

**Android:** `data/collective/CollectiveMilestone.kt` already exists as its own file with the verbatim sacred-numbers copy (Stage 11-B/12-C). The extraction has no Android counterpart; nothing moves.

## E6. The frozen table dies

**iOS:** `2133af5` deleted `Pilgrim/Models/Collective/PilgrimageProgress.swift` (the hardcoded seven-route table + threshold walk) and the `stats.pilgrimageProgress` computed property on `CollectiveCounterService.CollectiveStats`:

> The header rendered a hardcoded route table that was both wrong and stuck. It listed the Kumano Kodo at 40km against a real 39, the Camino de Santiago at 800 against 764, and a "Via Francigena stage" that appears in no dataset … It picked the largest route the collective had surpassed, so it climbed once and then sat — at the current total it would have shown one sentence for roughly five months.

**Android deletions, one-for-one:**

- `app/src/main/java/org/walktalkmeditate/pilgrim/data/collective/PilgrimageProgress.kt` — deleted (the port of the deleted Swift file, including its Android-only "the the Moon" article patch, which dies with it).
- `app/src/test/java/org/walktalkmeditate/pilgrim/data/collective/PilgrimageProgressTest.kt` — deleted (iOS's table had no dedicated tests; Android's did, and they pin deleted behavior).
- `PracticeSummaryHeader.kt`: the `Text(PilgrimageProgress.from(stats.totalDistanceKm).message)` element + import — replaced by the daily line (E2/E3).
- `CollectiveStats` never had a `pilgrimageProgress` property on Android — the iOS property removal has no counterpart.

## Divergences (conscious) and resolved ambiguities

| # | Divergence | Reason |
|---|---|---|
| D1 | Catalog reaches the view as `SettingsViewModel.routeCatalog: StateFlow<CollectiveRouteCatalog>` (constructor-injected service, direct hot passthrough) → collected in `SettingsScreen` → passed to the header as a required parameter; iOS reads the `.shared` singleton via `@ObservedObject` inside the view. | House MVVM (no singleton reads in composables); direct hot-Singleton passthrough per the Stage 5-G lesson; required (not defaulted) parameter so a missing caller is a compile error, per the U2 D2 precedent. |
| D2 | `nowEpochMillis: () -> Long = System::currentTimeMillis` parameter on the header; iOS hardcodes `Date()` inside `refreshDailyLine()`. | The selection is date-keyed, so an uninjectable clock makes every render assertion flaky at UTC midnight (house determinism rule). Default preserves production behavior; only tests override. |
| D3 | No `UserDefaults.didChangeNotification` analogue. | iOS needs the observer because `CustomMeasurementFormatting` reads a preference global that publishes no change. Android's unit preference is already a `StateFlow<UnitSystem>` parameter (U2 D2 made the phrasing take `units` explicitly), so the `distanceUnits` remember key is the observer. Same observable behavior: a unit toggle re-resolves the cached string without leaving the screen. |
| D4 | `remember(keys)` replaces `@State` + four explicit trigger modifiers. | Identical resolve points (E4 table) and identical caching between them; Compose has no `onChange` modifier family to mirror literally. |
| D5 | The catalog crosses into a `LazyColumn` item as an unannotated, hence Compose-unstable, type (U2 D8). | Accepted at this seam: the header's enclosing `item` reads only state whose changes must recompose the header anyway (stats, units, milestone, catalog), so skippability buys nothing; the expensive part (phrasing) is guarded by `remember`, which uses `equals`, not stability. No `@Immutable` wrapper until a real jank surface appears (YAGNI, Stage 4-D lesson). |
| D6 | Sibling truncation modifiers (`minimumScaleFactor(0.7)` / `lineLimit(1)` on season, stat, walks·distance lines) not added. | Pre-date the swap (present at `c1745e8`); Android shipped without them through the v1.7.0 sweep. Out of U5's line-swap scope (E3). |

## Test parity map

iOS ships **no view tests** for `PracticeSummaryHeader` (its `2133af5` test additions cover only the extracted `CollectiveMilestone`, already ported in Stage 12-C). The render conditions live in view-body logic iOS leaves untested; Android pins them (Android-only, per plan U5 scenarios):

| Scenario (plan U5) | Android test |
|---|---|
| catalog + stats present → day's entry renders; matches U2 phrasing for same inputs | `PracticeSummaryHeaderDailyLineTest.dailyLineRendersTheDaysEntryAgainstTheCollectiveTotal` (hard-pinned string at a fixed epoch) |
| stats null (fresh install offline) → nothing renders — not the beginning line | `PracticeSummaryHeaderDailyLineTest.statsUnknownRendersNothingNotTheBeginningLine` |
| stats present, catalog not yet loaded → walks·distance line renders, daily line absent; catalog arriving re-resolves in place | `PracticeSummaryHeaderDailyLineTest.lineAppearsWhenTheCatalogLoads` |
| unit toggle updates the line without leaving the screen | `PracticeSummaryHeaderDailyLineTest.unitToggleUpdatesTheLineWithoutLeavingTheScreen` (sub-one-percent horizon branch — the only unit-bearing `dailyLine` branch, U2 B3.8) |
| UTC day change re-resolves | `PracticeSummaryHeaderDailyLineTest.reentryReResolvesForTheCurrentUtcDay` (screen re-entry after a day flip — iOS's `.onAppear` trigger) |
| VM exposes the catalog | `SettingsViewModelTest."routeCatalog StateFlow proxies the catalog service"` |
