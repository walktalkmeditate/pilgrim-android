# Walk Summary Collective Trail (U6) — iOS → Android Port Spec

> **Plan:** `docs/plans/2026-07-23-001-feat-ios-v190-parity-port-plan.md` (U6) · **Requirements:** R3
> **iOS pin:** `pilgrim-ios` @ `9a418e4` (HEAD of `main`, 2026-07-21). All Swift quotes cite `file@9a418e4` unless a historical commit is named.
> **Authority:** SHIPPED Swift over plan docs and commit messages. The shipped end-state is `9a418e4` after four commits (`ce7a750` feature → `8c83c36` perf pass → `b131de7` empty-catalog refusal → `8b33711` comment compression); the parent-resolve shape quoted below is the final one.
> **Companions:** `2026-07-23-port-route-catalog-u2.md` (B4 contribution phrasing, B5 unit seam), `2026-07-23-port-route-catalog-service-u3.md` (hot `catalog: StateFlow`, `EMPTY` pre-load), `2026-07-23-port-contribution-ledger-u4.md` (L6 read surface — this spec implements its "U6 consumption contract"), `2026-07-23-port-settings-line-u5.md` (the sibling surface that DOES need the collective total).
> **Android files:** create `ui/walk/summary/CollectiveTrailSection.kt`; modify `ui/walk/WalkSummaryScreen.kt` + `ui/walk/WalkSummaryViewModel.kt`; test `ui/walk/summary/CollectiveTrailSectionTest.kt` + `WalkSummaryViewModelTest.kt` extension.

## T1. The section — a bare if-let, no background fill

**iOS** (`Pilgrim/Scenes/WalkSummary/CollectiveTrailSection.swift@9a418e4`):

```swift
/// This walk's distance against the day's collective route, and a sentence naming
/// who else has walked it. No background fill, so it doesn't read as a second milestone.
struct CollectiveTrailSection: View {

    /// Already phrased, and resolved by the owning summary rather than here: this
    /// body is a bare `if let`, so the view is `EmptyView` on exactly the frames
    /// where it would need to resolve, and no lifecycle callback would fire.
    let contributionLine: String?
    /// A past-tense fact from `CollectiveContributionLog`, not the live preference.
    let wasContributed: Bool
    let revealPhase: WalkSummaryView.RevealPhase

    var body: some View {
        if let line = Self.renderedLine(wasContributed: wasContributed, contributionLine: contributionLine) {
            trail(line)
        }
    }
```

```swift
private func trail(_ line: String) -> some View {
    HStack(alignment: .top, spacing: Constants.UI.Padding.small) {
        Image(systemName: "signpost.right")
            .font(Constants.Typography.caption)
            .foregroundColor(.stone)
        Text(line)
            .font(Constants.Typography.caption)
            .foregroundColor(.stone)
            // A walk distance plus a company sentence runs to ~110 characters.
            .minimumScaleFactor(0.5)
            .lineLimit(4)
            .fixedSize(horizontal: false, vertical: true)
    }
    .padding(.horizontal, Constants.UI.Padding.normal)
    ...
}
```

Contrast with the personal milestone directly above it (`WalkSummaryView.milestoneCallout@9a418e4`): sparkles icon in `.dawn`, `.background(Color.dawn.opacity(0.1))`, `.cornerRadius(...)`. The trail carries **no background, no corner radius, stone (not ink) text** — visually subordinate by construction. `ce7a750`: "it doesn't read as a second milestone."

The empty state is structural, not visual — `ce7a750`: "An empty body contributes no element, so the surrounding stack inserts no gap — the same mechanism the weather and celestial lines already rely on."

**Android:** `internal @Composable fun CollectiveTrailSection(contributionLine, wasContributed, revealPhase, reduceMotion, modifier)` in `ui/walk/CollectiveTrailSection.kt`. Body is the same bare gate: `collectiveTrailRenderedLine(...) ?: return` — an early return emits no node, so the enclosing `spacedBy` Column inserts no gap (same mechanism the screen's ElevationProfile self-gate relies on). The row: `Icon(Icons.Rounded.Signpost, tint = stone, 16.dp)` + caption `Text` in `pilgrimColors.stone`, `verticalAlignment = Alignment.Top`, `Arrangement.spacedBy(PilgrimSpacing.small, Alignment.CenterHorizontally)`, `fillMaxWidth().padding(horizontal = PilgrimSpacing.normal)`. No background, no shape — subordinate to `MilestoneCalloutRow`'s dawn-tinted pill exactly as on iOS. No new model class exists (both inputs are primitives), so the plan's `@Immutable` rule has nothing to annotate.

## T2. The render gate — a pure function, and no collective total

**iOS** (`CollectiveTrailSection.swift@9a418e4`):

```swift
/// The render gate as a pure function, so it is testable without a view. No
/// collective total involved — unlike the Settings line, this one still holds
/// on day twelve of a Camino with no signal.
static func renderedLine(wasContributed: Bool, contributionLine: String?) -> String? {
    wasContributed ? contributionLine : nil
}
```

The no-total property is pinned by test (`CollectiveTrailSectionTests.swift@9a418e4`, `testGate_isIndependentOfTheCollectiveTotal`):

> The plan left open whether an unknown collective total should suppress both surfaces. It suppresses only one: the Settings line states the collective's progress and cannot invent it, while this line states the walk's own distance against a fixed route length and never needs a total at all. That asymmetry is what lets a walk that ended on day twelve with no signal still say something true.

And the catalog-gap half (`testGate_catalogNotYetLoaded_rendersNothing`):

> The catalog is nil for the first frames of every summary — the load is detached — and stays nil if the artifact failed to decode. Half a line is worse than none.

**Android:** top-level `internal fun collectiveTrailRenderedLine(wasContributed: Boolean, contributionLine: String?): String? = if (wasContributed) contributionLine else null` beside the composable (house analogue of the Swift static; precedent: `targetAlpha` in `WalkSummaryRevealAnimations.kt`). The catalog gap arrives as null the same way: the U3 service publishes `CollectiveRouteCatalog.EMPTY` pre-load, `EMPTY.entry()` selects nothing, `contributionLine(...)` returns null, the gate passes null through. `contributionLine(walkKm, units)` on a resolved entry is non-null by construction (U2 B4) — the only null sources are "not contributed" and "no entry".

## T3. Parent resolve — ledger fact + walk-start anchor + catalog call

**iOS** (`Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift@9a418e4`):

```swift
@State private var walkWasContributed = false
/// Resolved on appear and whenever the catalog lands, never in a body.
/// `CollectiveTrailSection` re-renders with this view roughly thirty times
/// during the distance count-up, and phrasing the line allocates a
/// measurement formatter and an ICU calendar lookup on every pass.
@State private var collectiveContributionLine: String?
private let contributionLog = CollectiveContributionLog()
```

```swift
.onAppear {
    ...
    milestone = computeMilestone()
    walkWasContributed = walk.uuid.map { contributionLog.wasContributed(walkUUID: $0.uuidString) } ?? false
    resolveCollectiveContributionLine(from: CollectiveRouteCatalogService.shared.catalog)
```

```swift
// Subscribed rather than observed: a summary opened before the
// detached catalog load lands still fills its trail line, without
// handing this whole view a second invalidation source.
.onReceive(CollectiveRouteCatalogService.shared.$catalog) { catalog in
    resolveCollectiveContributionLine(from: catalog)
}
```

```swift
/// Phrasing is skipped outright for a walk that was never sent to the
/// collective — the section would discard the line anyway, and most walks
/// in a journal predate the feature.
///
/// The catalog arrives as a parameter rather than being read back off the
/// service, because `@Published` emits in `willSet`: a subscriber that
/// re-reads the property mid-publish sees the value being replaced, not the
/// one that just landed.
private func resolveCollectiveContributionLine(from catalog: CollectiveRouteCatalog?) {
    guard walkWasContributed else {
        collectiveContributionLine = nil
        return
    }
    // The walk's own start date, never `Date()`. This screen opens for any
    // walk in the journal, so anchoring to today would silently re-route a
    // walk from last month every time it is reopened.
    collectiveContributionLine = catalog?.contributionLine(
        for: walk.startDate,
        walkKm: walk.distance / 1000
    )
}
```

Anchor semantics pinned by test (`CollectiveTrailSectionDateAnchorTests@9a418e4`): the parity fixture resolves 2026-10-07 → `camino-primitivo` and 2026-10-12 → `around-earth`; the line for the walk's day contains "Camino Primitivo" and differs from the reopened day's; `00:00:00Z` and `23:59:59Z` of the walk day agree ("Midnight and one second to midnight UTC straddle the local-day boundary everywhere on earth, so agreeing across both means the walk's own UTC day is what decides, whatever the pilgrim's time zone"). A midnight-spanning walk anchors to its **start** date because `walk.startDate` is what the resolve passes (U4 spec L6).

**Android** (`WalkSummaryViewModel`):

- `_walkWasContributed: MutableStateFlow<Boolean>(false)` seeded in `buildState()` via `contributionLedger.wasContributed(walk.uuid)` — the same load-time read as iOS's `.onAppear`, from the same U4 store; a ledger read failure degrades to `false` (no line), never a broken summary. Declared before `state` (the `Eagerly` field-init ordering rule the VM already documents on `_showSealReveal`). Android's `walks.uuid` column is non-null, so iOS's `walk.uuid.map { ... } ?? false` nil-arm has no counterpart (U4 D-note).
- `collectiveContributionLine: StateFlow<String?>` = `combine(state, _walkWasContributed, routeCatalogService.catalog, distanceUnits)`; body returns null unless Loaded && contributed (the iOS guard — phrasing skipped outright), else `catalog.contributionLine(walk.startTimestamp, summary.distanceMeters / 1000.0, units)`. The `catalog` key is the `.onReceive` analogue: a summary opened before the detached load lands re-resolves when the catalog publishes. The combine parameter IS the emitted value (no read-back), so iOS's willSet-tearing note is satisfied by construction.
- Distance: `WalkSummary.distanceMeters` is the live event-replay haversine — the same number the stats row beside the line displays. iOS's `walk.distance` is likewise the summary's displayed distance; Android's *cached column* (`Walk.distanceMeters`) is written asynchronously post-finish and can be null in exactly the just-finished window this section debuts in, so the live value is the faithful source. Converted to km at the call site — `walkKm` is kilometres on every platform (U2 B4).
- No clock anywhere in the resolve: the anchor is the walk row's `startTimestamp`, so the U5 `nowEpochMillis` seam is not needed (plan U5→U6 note confirmed).

## T4. Placement — beneath the personal milestone, above the stats row

**iOS** (`WalkSummaryView.swift@9a418e4`, body):

```swift
durationHero
if let milestone {
    milestoneCallout(milestone)
}
CollectiveTrailSection(
    contributionLine: collectiveContributionLine,
    wasContributed: walkWasContributed,
    revealPhase: revealPhase
)
statsRow
```

`ce7a750`: "It arrives a beat after the personal milestone, so the screen reads as the fact, then your arc, then the larger one."

**Android:** inserted in `WalkSummaryScreen` as section **7b** — after the section-7 `MilestoneCalloutRow` block, before the section-8 `WalkStatsRow`, inside the `spacedBy(PilgrimSpacing.normal)` Column (the "3b" Seek-story precedent for slotting without renumbering). Rendered unconditionally like iOS; the bare gate inside the section means a non-contributed walk emits no node and no gap.

## T5. Reveal — a second beat, 0.55s behind the milestone's 0.3s

**iOS** (`CollectiveTrailSection.swift@9a418e4`):

```swift
@Environment(\.accessibilityReduceMotion) private var reduceMotion

/// A beat behind the personal milestone's 0.3s, so the two land as two thoughts.
private static let revealDelay: TimeInterval = 0.55
```

```swift
.opacity(revealPhase == .revealed ? 1 : 0)
.animation(reduceMotion ? nil : .easeIn(duration: 0.8).delay(Self.revealDelay), value: revealPhase)
```

Reference beats at the pin: milestone `.easeIn(duration: 0.8).delay(0.3)`, stats row `.easeIn(duration: 0.6).delay(0.2)`.

**Android:** the section applies its own alpha (mirroring iOS's section-owned delay constant): `Modifier.alpha(rememberRevealAlpha(revealPhase, durationMs = 800, delayMs = 550, reduceMotion))`, constants `COLLECTIVE_TRAIL_REVEAL_DURATION_MS = 800` / `COLLECTIVE_TRAIL_REVEAL_DELAY_MS = 550` owned by `CollectiveTrailSection.kt` (iOS: `Self.revealDelay`). `rememberRevealAlpha` is the established per-section reveal mechanism (13-XZ) and already collapses to a 0ms tween under reduce-motion — on iOS this section is the only summary section that checks `reduceMotion` in its reveal; on Android every section already does, so the trail simply inherits the stricter house behavior. Under reduce-motion the screen also jumps straight to `Revealed`, so the line appears immediately, matching iOS's nil-animation snap.

## T6. Truncation contract — 4 lines, 0.5 scale floor, 130-char curator budget

**iOS:** `.minimumScaleFactor(0.5)` + `.lineLimit(4)` + the inline note "A walk distance plus a company sentence runs to ~110 characters." `ce7a750`: "Its own file, and its own text budget. … The budget is looser than the milestone's because this is the longest text the feature produces and a curator can lengthen it without a release." The budget is enforced against the SHIPPED artifact, not the fixture (`testLine_longestPhrasingStaysWithinTheRenderBudget@9a418e4`):

> Measured against the bundled artifact rather than the fixture above. Company sentences are curator-editable after ship — which is the entire reason this exists — so measuring a frozen transcription of them means a 300-character sentence could reach a pilgrim's screen with this still green.

```swift
XCTAssertLessThanOrEqual(longest.count, 130, "Re-tune lineLimit and minimumScaleFactor before letting this grow")
```

**Android:** `maxLines = 4` + the house `minimumScaleFactor` analogue (U5/WelcomeScreen precedent):

```kotlin
autoSize = TextAutoSize.StepBased(
    minFontSize = pilgrimType.caption.fontSize * 0.5f,
    maxFontSize = pilgrimType.caption.fontSize,
    stepSize = 0.5.sp,
)
```

The budget test measures the **bundled bootstrap asset** (Android's shipped artifact, `assets/collective/collective-routes-bootstrap.json`) at `walkKm = 12.34`, ceiling 130 — one-for-one. iOS's `.fixedSize(horizontal: false, vertical: true)` (wrap, take needed height) is Compose `Text`'s default and ports as a no-op.

## T7. Accessibility

**iOS:** no explicit accessibility modifiers on the section — the SF Symbol is decorative alongside a full-sentence `Text`, and `reduceMotion` (T5) is the section's one explicit accessibility behavior.

**Android:** `Icon(contentDescription = null)` — decorative, per the sibling convention (`MilestoneCalloutRow`, weather line); the line itself is plain `Text`, readable by TalkBack as-is. Reduce-motion handled in T5. Nothing else to port.

## Divergences (conscious) and resolved ambiguities

| # | Divergence | Reason |
|---|---|---|
| D1 | Parent resolve lives in the VM as a combine-derived `StateFlow<String?>` (`collectiveContributionLine`), not `@State` + `.onAppear`/`.onReceive` modifiers. | House MVVM. Identical resolve points: walk load (`buildState` ≙ `.onAppear`), catalog publish (`catalog` combine key ≙ `.onReceive($catalog)`). Same caching-between-resolves property: `stateIn` holds the string; the count-up recompositions never re-phrase (the iOS `@State` rationale). Display-only, so `WhileSubscribed` per the VM's display-flow convention — the Stage 5-G hot-passthrough rule applies only to nav-driving flows. |
| D2 | `distanceUnits` joins the combine; iOS re-resolves the line on appear + catalog publish only (its formatter reads the unit preference at phrase time; `WalkSummaryView` has no `UserDefaults.didChangeNotification` observer, unlike the Settings header). | U2 D2 made phrasing take `units` explicitly, so the preference must arrive as data. Behaviorally never staler than iOS (which refreshes at latest on re-appear via `.onAppear`), occasionally fresher — the horizon sub-branch is the only phrasing that renders a unit at all. |
| D3 | `walkKm` from `WalkSummary.distanceMeters` (live event-replay), not the Room walk row's cached `distanceMeters` column. | The cached column is written asynchronously after finish and races the summary open (documented `WalkMetricsCache` race); the live value is what the adjacent stats row displays, matching iOS where `walk.distance` backs both. Converted `/1000.0` at the call site. |
| D4 | `walkWasContributed` derived reactively from the ledger (`ContributionLedger.contributedFlow`); iOS re-reads on every `.onAppear`. | Originally seeded once in `buildState` on the claim that the ledger records strictly before any summary can open — untrue on Android, where the ledger write is a late step in `WalkFinalizationObserver`'s async finalize chain and races the auto-opened summary's load, blanking the line for that whole visit. Deriving the fact reactively makes the line appear the moment the write lands. Semantics stay past-tense: opting out later never erases the line, because the ledger itself is append-only for that walk. |
| D5 | `internal` composable + top-level `collectiveTrailRenderedLine` function instead of a `View` struct with a static method. | House style; `RevealPhase`/`rememberRevealAlpha` are `internal` (module-visible) like every reveal consumer (`PilgrimMap` precedent). The gate stays a pure function testable without a view — the iOS property that mattered. |
| D6 | `Icons.Rounded.Signpost` at 16.dp for `Image(systemName: "signpost.right").font(caption)`. | Closest Material glyph to the SF Symbol; fixed 16.dp is the established caption-scale icon size on this screen (milestone sparkles, weather icon). |
| D7 | Reveal easing `FastOutSlowInEasing` via `rememberRevealAlpha` vs iOS `.easeIn`. | Pre-existing whole-screen divergence (13-XZ reveal stagger); the trail inherits it rather than introducing a second animation mechanism. Duration 800ms / delay 550ms preserved exactly. |
| D8 | Android applies reduce-motion to every summary section; iOS only this one. | Inherited house behavior (stricter, never less accessible); the iOS-specific `reduceMotion ? nil :` branch is subsumed by `rememberRevealAlpha`'s `tween(0)` collapse plus the screen's straight-to-`Revealed` fast path. |

## Test parity map

iOS `UnitTests/CollectiveTrailSectionTests.swift@9a418e4`, one-for-one, plus the plan's U6 scenarios. The third iOS class (`CollectiveContributionLogTests`) was ported in U4 (`ContributionLedgerTest`) and is not re-ported here.

| iOS test | Android test |
|---|---|
| `testGate_walkWasNotContributed_rendersNothing` | `CollectiveTrailSectionTest.gate - a walk that was not contributed renders nothing` |
| `testGate_contributedWithALoadedCatalog_rendersTheLineUnchanged` | `CollectiveTrailSectionTest.gate - contributed with a loaded catalog renders the line unchanged` |
| `testGate_catalogNotYetLoaded_rendersNothing` | `CollectiveTrailSectionTest.gate - catalog not yet loaded renders nothing` (Android's "not loaded" is the service's pre-load `EMPTY`, U3 C-note) |
| `testGate_emptyCatalog_rendersNothing` | `CollectiveTrailSectionTest.gate - empty catalog renders nothing` |
| `testGate_isIndependentOfTheCollectiveTotal` | `CollectiveTrailSectionTest.gate - is independent of the collective total` (asserts the trail line non-null AND `dailyLine(collectiveKm = null)` null — the asymmetry both specs pin) |
| `testLine_resolvesTheWalksOwnUtcDayNotAnother` | `CollectiveTrailSectionTest.line resolves the walks own UTC day not another` (same fixture pins: 10-07 → `camino-primitivo`, 10-12 → `around-earth`, "Camino Primitivo" contained, lines differ) |
| `testLine_holdsAcrossTheWholeUtcDayOfTheWalk` | `CollectiveTrailSectionTest.line holds across the whole UTC day of the walk` |
| `testLine_longestPhrasingStaysWithinTheRenderBudget` | `CollectiveTrailSectionTest.line longest phrasing stays within the render budget` (bundled bootstrap asset, `walkKm = 12.34`, ≤ 130) |
| — (iOS AE6 note: milestone+trail together deliberately not asserted — `computeMilestone()` is view-private on iOS) | `WalkSummaryViewModelTest."milestone callout and the collective line render together"` — genuinely assertable on Android because both are VM flows (`walkSummaryCalloutProseDisplay` + `collectiveContributionLine`) |

Android-only (plan U6 scenarios, VM plumbing iOS has no VM for):

| Plan scenario | Android test |
|---|---|
| contributed → gate true, resolved against the walk's start-day entry | `WalkSummaryViewModelTest."collective line resolves a contributed walk against its start days entry"` |
| non-contributed → nothing | `WalkSummaryViewModelTest."collective line is null for a walk that never contributed"` |
| contributed but catalog unloaded → nothing (no partial line) | `WalkSummaryViewModelTest."collective line stays null when the catalog never loads"` |
| reopened weeks later resolves walk-date entry, not today's | fixture-level date pins (section test) + VM line asserted equal to the walk-date resolve and unequal to another day's |
| midnight-spanning walk uses the start day | `WalkSummaryViewModelTest."midnight-spanning walk anchors the collective line to its start day"` |
| horizon entries produce a line | `CollectiveTrailSectionTest.horizon entries produce a line` (+ the VM start-day test doubles on a horizon date) |
| section renders / suppresses in composition | `CollectiveTrailSectionTest` compose cases (gate-null emits no node; revealed line displayed) — Android pins the view wiring iOS leaves untested |
