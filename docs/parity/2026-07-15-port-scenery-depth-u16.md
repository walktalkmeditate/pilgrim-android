# Port spec — Scenery depth, age, and touch: parallax, age fade, gate/cairn haptics, placement audit (U16)

- **iOS anchor:** `pilgrim-ios` @ `c1745e88494d7677c4be8770ab6ceed1a61f3f6f` (c1745e8)
- **Source commits:** `680faa2` ("depth, age, and touch for the scroll's
  landscape" — parallax table, age fade, gateDot/cairnDot haptics),
  `6e80a91` **remainder** (the seeking-gate age-fade *exemption*; the
  moss/fill half landed in U15), `3f9d3db` ("scenery lands beside its own
  dot at every scroll depth" — the placement bug class this unit audits),
  `b1f7638` (the haptic sensory-feedback slots). This spec ports the
  **end-state** at c1745e8. Note: c1745e8's `2f5c116` refactor moved
  `sceneryForDot` out of `InkScrollView.swift` into
  `InkScrollView+Scenery.swift` — U15's handoff pointer
  (`InkScrollView.swift:659-661`) resolves to
  `InkScrollView+Scenery.swift:38-40@c1745e8` at the pin.
- **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` § U16
- **Depends on:** U14 model (`SceneryPlacement.gateKind`/`stones`,
  `WalkSnapshot.threshold`/`foundPlaces`/`isSeek` —
  `docs/parity/2026-07-15-port-scenery-model-u14.md`), U15 renderers
  (`Cairn`/`Drift` composables, `gateKind` visible at the HomeScreen
  scenery-alpha site — `docs/parity/2026-07-15-port-scenery-renderers-u15.md`).

## 1. Parallax — per-type depth of field

Swift table (`Pilgrim/Models/SceneryGenerator.swift:38-53@c1745e8`):

```swift
/// Parallax drift in points at the viewport edge — depth of field for
/// the scroll. Sky and horizon barely move; things at the walker's
/// feet move most, and drift rides the air nearest of all.
var parallaxWeight: CGFloat {
    switch self {
    case .mountain: 3
    case .moon: 4
    case .torii: 6
    case .tree: 8
    case .lantern: 9
    case .cairn: 9
    case .grass: 12
    case .butterfly: 14
    case .drift: 16
    }
}
```

Application (`Pilgrim/Scenes/Home/InkScrollView+Scenery.swift:37,52-57@c1745e8`):

```swift
let parallax = placement.type.parallaxWeight
...
.visualEffect { content, proxy in
    let frame = proxy.frame(in: .global)
    let screenMid = viewportHeight / 2
    let distFromCenter = (frame.midY - screenMid) / screenMid
    return content.offset(x: distFromCenter * parallax)
}
```

Pinned semantics:

- **Horizontal** drift only; `xOffset = ((midY − H/2) / (H/2)) · weight`
  where `midY` is the item's center in viewport space. Item at viewport
  center → 0; at the viewport's bottom edge → `+weight` pt (drifts right);
  top edge → `−weight`. No clamp — items beyond the edges keep scaling
  linearly (they are culled on iOS and invisible on Android anyway).
- Weight units are points (→ dp on Android), converted to px at apply time.
- **No reduce-motion gate.** iOS applies `.visualEffect` unconditionally —
  parallax is scroll-driven, not autonomous, so Reduce Motion leaves it
  alone (the only reduce-motion gates in the scroll are the haptic engine,
  `ScrollHapticEngine.swift:35@c1745e8`, and the scenery TimelineViews,
  `SceneryItemView.swift:19,49,...@c1745e8`). Android matches: no
  `LocalReduceMotion` check on the parallax term.

Android mapping: `SceneryType.parallaxWeightDp` table in
`SceneryGenerator.kt` (dp-typed Float, values verbatim), pure
`sceneryParallaxXPx(sceneryCenterYPx, scrollOffsetPx, viewportHeightPx,
weightPx)` in a new `ui/home/scenery/SceneryLayout.kt`, applied as
`translationX` inside the existing scenery `graphicsLayer` lambda
(HomeScreen.kt scenery box) — `scrollState.value` /
`scrollState.viewportSize` are read **inside** the lambda (draw-phase
snapshot reads; Stage 5-A house rule), so scrolling re-executes only layer
placement, never composition.

**Coordinate-space divergence (deliberate):** iOS measures the item's
viewport-space `midY` from its real global frame. Android computes it as
`sceneryCenterY − (scrollOffset + viewportH/2)` in journal-canvas space —
the same approximation the shipped haptic engine uses (HomeScreen.kt
"viewportCenter in journal-canvas space": `centerPx = offsetPx + vH/2`),
which ignores the header stack (~title spacer + turning banner + journey
summary) above the canvas. The error is a small constant phase shift of
the zero-drift line (≲ a quarter viewport-half → ≲ 4 dp at drift's
weight 16); parallax is perceived as relative motion so the bias is
invisible, and the neutral line stays consistent with where the haptic
engine considers "center". Chosen over per-item `onGloballyPositioned`
plumbing.

## 2. Age fade — scenery dims with its walk; the seeking gate refuses

Dot fade (`InkScrollView.swift:572-576@c1745e8`, already on Android as
`WalkDotMath.dotOpacity`):

```swift
private func dotOpacity(index: Int, total: Int) -> Double {
    guard total > 1 else { return 1.0 }
    let normalized = Double(index) / Double(total - 1)
    return 1.0 - normalized * 0.5
}
```

Scenery multiplies it, with the 6e80a91 exemption
(`InkScrollView+Scenery.swift:38-40,50@c1745e8`):

```swift
// Seeking gates refuse the age fade — old stone grows older,
// not fainter. Everything else dims with its walk.
let sceneryOpacity = placement.gateKind == .seeking ? 1.0 : opacity
...
.opacity(sceneryOpacity)
```

Pinned: newest walk's scenery 1.0 → oldest 0.5, **exactly the dot's own
opacity** (`dotsLayer` passes the same `opacity` to both dot and scenery,
`InkScrollView.swift:136-138@c1745e8`); a seeking-gate torii renders at
1.0 always while its dot still fades. Practice gates fade (only
`.seeking` is exempt).

Android: pure `sceneryAgeAlpha(gateKind, dotFade)` in `SceneryLayout.kt`;
HomeScreen multiplies it into the existing fade-in cascade at the scenery
graphicsLayer: `alpha = perSceneryAlpha * sceneryAgeAlpha(...)`. (iOS
composes the same product: the `.opacity(sceneryOpacity)` modifier under
`dotContent`'s appearance-fade `.opacity(hasAppeared ? 1 : 0)`,
`InkScrollView.swift:147-151@c1745e8`; `perSceneryAlpha` is the Android
appearance-fade analogue.)

## 3. Haptics — gates and cairns speak their own touch

Vocabulary (`Pilgrim/Scenes/Home/ScrollHapticEngine.swift:3-20@c1745e8`):

```swift
enum HapticEvent: Equatable {
    case none
    case lightDot(Int)
    case heavyDot(Int)
    case milestone(Int)
    /// Crossing a threshold walk's torii — the milestone thump.
    case gateDot(Int)
    /// Crossing a seek walk's cairn — the soft double of a found place.
    case cairnDot(Int)
}

/// What a dot means under the thumb: gates and cairns speak their own
/// touch; everything else falls back to size.
enum DotHapticKind {
    case plain
    case gate
    case cairn
}
```

Kind consult BEFORE the size fallback
(`ScrollHapticEngine.swift:49-68@c1745e8`):

```swift
let threshold: CGFloat = 20
for (index, dotY) in dotPositions.enumerated() {
    guard abs(viewCenter - dotY) < threshold else { continue }
    guard lastTriggeredIndex != index else { continue }
    lastTriggeredIndex = index
    if index < dotKinds.count {
        switch dotKinds[index] {
        case .gate: return .gateDot(index)
        case .cairn: return .cairnDot(index)
        case .plain: break
        }
    }
    let isLarge = index < dotSizes.count && dotSizes[index] > 15
    return isLarge ? .heavyDot(index) : .lightDot(index)
}
```

Feedback mapping (`InkScrollView.swift:50-58@c1745e8`): `gateDot` shares
the **milestone slot** — `.impact(weight: .heavy, intensity: 0.8)`
("a torii speaks the milestone thump regardless of size",
`ScrollHapticEngineTests.swift:22@c1745e8`); `cairnDot` fires
`.sensoryFeedback(.success)` — the soft success double.

Kind wiring (`InkScrollView.swift:692-696@c1745e8`):

```swift
hapticState.dotKinds = snapshots.map { snap in
    if snap.threshold != nil { return .gate }
    if snap.isSeek && snap.foundPlaces > 0 { return .cairn }
    return .plain
}
```

(The same decision order as `SceneryGenerator.scenery(for:)` — iOS
duplicates it here rather than deriving from the placement; ported
verbatim, including the duplication.)

Android mapping:

- `HapticEvent.GateDot` / `HapticEvent.CairnDot` in `HapticEvent.kt`;
  `DotHapticKind { Plain, Gate, Cairn }` in `ScrollHapticState.kt`.
- `ScrollHapticState` gains `dotKinds: List<DotHapticKind> = emptyList()`
  (constructor param, matching its existing immutable-lists shape),
  consulted after the retrigger guard and before the size fallback;
  `dotKinds.getOrNull(index)` null/`Plain` → size vocabulary (the iOS
  `index < dotKinds.count` missing-array fallback).
- `JournalHapticDispatcher` primitive mapping: `GateDot` reuses the
  **exact Milestone composition** (`PRIMITIVE_CLICK` 1.0 +
  `PRIMITIVE_LOW_TICK` 0.7 @30 ms — Android's established
  heavy-0.8-impact analogue, tuned in Stage 14-A) since iOS routes both
  through one sensory-feedback slot. `CairnDot` = the success double:
  `PRIMITIVE_CLICK` 0.55 + `PRIMITIVE_CLICK` 0.7 @120 ms — two soft
  rising taps, the dispatcher-vocabulary analogue of
  `UINotificationFeedbackGenerator` success (same rising-double contour
  as SeekHaptics' iOS-derived pairs; distinct from the milestone thump).
  Pre-R / no-primitive fallbacks follow the file's `fallback(amplitude)`
  pattern (gate 1.0 — same as milestone; cairn 0.7).
- Throttle: the 50 ms dot throttle narrows to `LightDot`/`HeavyDot`.
  Milestone already bypassed as "rare + important" (kaijutsu PR #86);
  gates (first walk + every 10th + seeking) and cairns (seek with
  arrivals) are the same class, and iOS fires every event unthrottled.
  Plain-dot fling-flood defense is unchanged.
- Reduce-motion: iOS gates in the engine
  (`ScrollHapticEngine.swift:35@c1745e8`); Android's established seam is
  the dispatcher's per-call `Settings.Global` read
  (`JournalHapticDispatcher.dispatch`), which runs before any event
  branch — the new kinds are covered with no change. Verified by test.

**Pre-existing Android divergences kept (unchanged by U16):**
`ScrollHapticState` re-arms `lastTriggeredDot` when the center exits the
±20 window (iOS never re-arms until a *different* dot fires) — Stage
14-A closing-review pattern; within-window semantics agree, so iOS's
`testSameDot_doesNotRetrigger` (200→205) pins identically. Android
checks milestones before dots (iOS dots first); production wiring passes
`milestonePositionsPx = emptyList()` so the order is currently inert.

## 4. Placement audit — the 3f9d3db bug class

iOS's bug: scenery was `.position(sceneryPosition)`-ed in content-space
coordinates while hosted **inside** `WalkDotView`'s own positioned ~50 pt
frame, so the y-coordinate applied twice (≈2× the dot's y) — scenery
landed beside the wrong dot near the top and below the viewport
everywhere deeper. The fix (`3f9d3db`, comment preserved at
`InkScrollView+Scenery.swift:27-33@c1745e8`):

```swift
// Dot-relative placement. The scenery is hosted inside WalkDotView's
// ZStack, which is framed to a ~50pt box and then positioned at the
// dot — so content-space `.position` coordinates get applied twice
// (box position + local position ≈ 2× the dot's y), landing scenery
// beside the wrong dot near the top of the scroll and below the
// viewport everywhere deeper. An offset from the dot's center is
// correct in any host geometry.
.offset(x: xOffset + placement.offset, y: -4)
```

**Android verdict: verified correct — the bug class does not reproduce.**
HomeScreen hosts scenery as a **sibling** of `WalkDot` directly inside
the single canvas `Box` (not nested inside the dot composable), and both
are placed once, in the same canvas coordinate space, via
`Modifier.offset { IntOffset(...) }` from the shared `xPx`/`yPx` dot
center (HomeScreen.kt scenery block). There is no second positioned frame
for coordinates to apply through. The displacement from the dot is the
iOS-verbatim `±(40 dp + size/2) + jitter` horizontally and `−4 dp`
vertically.

Regression guard: the inline center math is extracted to pure
`sceneryCenterPx(dotXPx, dotYPx, scenerySizePx, side, jitterPx,
clearancePx, liftPx)` in `SceneryLayout.kt` (behavior-identical), and
`SceneryLayoutTest` pins the 3f9d3db invariant — the scenery→dot
displacement is **constant across scroll depths** (equal for dots at row
0 and row 89), never proportional to the dot's y.

## 5. Deep-journal culling — verified state

iOS bounds live scenery via AF52 visible-window culling
(`InkScrollView.swift:126-156,813-815@c1745e8` — dots + their scenery
TimelineViews exist only within ±1 viewport of the visible rect).
**Android has no journal windowing** — a deliberate Stage 14 deferral
(HomeScreen.kt: "`Column.verticalScroll` rather than LazyColumn for now:
bucket 14-D will revisit virtualization. Typical user has < 100 walks, so
the eager-render cost is acceptable"). U16 does not add virtualization;
it verifies the new types keep the eager render bounded:

- Composition stays N-proportional: placements are `remember(snap)`-ed,
  and per-frame work is draw-phase only (scenery clocks are ~~the shared
  `sceneryTimeSeconds()` 300 s infinite transition~~ the
  `sceneryTimeSeconds()` 300 s infinite-transition idiom; parallax/alpha
  are graphicsLayer-lambda reads). **Corrected 2026-07-15 (P15 review):**
  "shared" overstated it. Each `sceneryTimeSeconds()` call creates its
  OWN `rememberInfiniteTransition` instance — roughly one clock per
  animating scenery item, not one process-wide clock. The clocks are
  phase-locked by construction (identical 0→300 s linear spec started at
  composition) but each is an independent animation subscription
  invalidating its own Canvas per frame, so a deep journal runs ~one
  subscription per animated item, offscreen included. A true
  shared-clock optimization (hoist one transition to the journal and
  pass the `State<Float>` down) is a known candidate but is DEFERRED
  pending the R12 deep-journal device-QA pass — measure the real cost
  before buying the plumbing.
- Cairn subscribes to **no** clock (U15: deliberately static); drift
  joins the same per-item clock idiom as the seven pre-existing types —
  no new per-frame recomposition class.
- Pinned by test: a 90-walk journal's worth of scenery (forced gates,
  cairns, drift among the lottery) composes in one pass
  (`JournalScreenIntegrationTest.deep_journal_scenery_composes_at_scale`).
- Real scroll-perf remains a Phase 15 device-QA item (plan R12 milestone).

## 6. Deliberate non-goals / divergences

- **Parallax coordinate space** — journal-canvas approximation instead of
  iOS's global-frame read (§ 1); consistent with the shipped haptic
  engine's convention.
- **Parallax under reduce-motion stays live** — iOS-verbatim (§ 1). The
  plan's U16 test-scenario line ("frozen parallax animation") predates
  this Swift verification; parallax is scroll-driven, so freezing it
  would be an iOS divergence, not a match. Scroll haptics are fully
  suppressed under reduce-motion (existing dispatcher gate, § 3).
- **Gate/cairn primitive shapes** are Android-vocabulary analogues (the
  file's Stage 14-A CLICK family), not waveform transcriptions of
  UIKit's private impact/notification curves — same policy as the
  existing light/heavy/milestone mapping.
- **Throttle narrowing** (§ 3) is Android-only defense-in-depth carried
  forward; iOS has no throttle at all.
- **No virtualization** (§ 5) — pre-existing 14-D deferral, unchanged.
- **`--demo-journal-stress` force-scenery hook**
  (`SceneryGenerator.swift:115-122@c1745e8`, added by 3f9d3db) stays
  unported — U14 § 7 decision, demo-seeder family.
- **iOS `JournalScrollDiagnostics` screenshot test** (3f9d3db's guard) has
  no Android screenshot harness; the placement invariant is pinned as a
  pure-geometry unit test instead (§ 4).

## 7. Test parity map

| iOS test (`UnitTests/ScrollHapticEngineTests.swift@c1745e8`) | Android (`ScrollHapticStateTest`) |
|---|---|
| `testGateCrossing_firesTheGateEvent` | `gate dot fires the gate event regardless of size` |
| `testCairnCrossing_firesTheCairnEvent` | `cairn dot fires the cairn event` |
| `testPlainDots_keepTheSizeVocabulary` | `plain dots keep the size vocabulary` |
| `testSameDot_doesNotRetrigger` | `same dot with a kind does not retrigger inside the window` |
| `testMissingKinds_fallBackToSize` | `missing kinds fall back to size` |

iOS has no unit tests for parallax/age-fade/placement (SwiftUI modifier
chains); Android pins them through pure functions (Stage 3-C rule):

| Pinned behavior | Android test |
|---|---|
| Weight table verbatim incl. Cairn 9 / Drift 16 | `SceneryGeneratorTest.parallax weight table is pinned per type` |
| Center → 0, edges → ±weight, linear beyond, zero-viewport safe | `SceneryLayoutTest` parallax cases |
| Age alpha = dot fade; seeking → 1.0; practice/null fade | `SceneryLayoutTest` age cases |
| Dot-relative displacement constant across depths (3f9d3db) | `SceneryLayoutTest` placement cases |
| Gate/cairn compositions build on the real Vibrator; reduce-motion suppresses the new kinds | `JournalHapticDispatcherTest` |
| 90-walk scenery (gates+cairns+drift) composes in one pass | `JournalScreenIntegrationTest` |
