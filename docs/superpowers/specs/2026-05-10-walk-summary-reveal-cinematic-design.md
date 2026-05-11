# Walk Summary reveal cinematic + count-up — design

**Date:** 2026-05-10
**Parity target:** pilgrim-ios v1.5.0 (`db4196e`)
**Source audit:** `docs/parity/2026-05-10-walk-summary-audit.md` (drift-critical: count-up curve, reveal cinematic)
**Slug:** `walk-summary-reveal-cinematic`

## Problem

Walk Summary screen is the user's first hit of dopamine after a 45–90 minute walk. The iOS reveal sequence (camera zoom-in → 0.8s ceremonial hold → 2.5s zoom-out + 0.6s opacity easeInOut + 30-tick discrete count-up) is the cherished moment of the app. The Android port (Stage 13-B, PR #76) hit structural parity but drifted on three points the parity audit flagged drift-critical or drift-cosmetic:

1. **Initial camera zoom-in is instant** (`view.mapboxMap.setCamera(...)` at `PilgrimMap.kt:179`). iOS uses `cameraDuration = 0.1` (100 ms ease) for a subtle pull-in. Android currently snaps.
2. **Per-section opacity easing is `EaseIn`** (`WalkSummaryRevealAnimations.kt:66`). iOS uses `.easeInOut(duration: 0.6)` (`WalkSummaryView.swift:369`). Different perceptual fade-in shape.
3. **Count-up is frame-driven** (`animateFloatAsState + tween + SmoothStepEasing` at `WalkSummaryScreen.kt:178-191`). iOS schedules 31 explicit `DispatchQueue.asyncAfter` callbacks (i=0..30 inclusive) at ~66.67 ms intervals over a 2 s budget (`WalkSummaryView.swift:380-391`). The discrete-step cadence IS the iOS feel — like an old odometer flipping. A smooth tween loses that texture entirely. Note: 31 emissions but only 30 transitions — the i=0 emission is the snap-to-zero (`target * 0 = 0`).

## Scope

Three code edits + one delight addition + tests:

1. **PilgrimMap.kt:179** — Zoomed-phase camera: replace `setCamera` with `easeTo(duration = 100 ms)`. Reduce-motion path keeps `setCamera` (instant).
2. **WalkSummaryRevealAnimations.kt:66** — swap `EaseIn` → `FastOutSlowInEasing` (Compose's stock cubic-bezier `(0.4, 0, 0.2, 1)`, closest stock match to Apple's `easeInOut`).
3. **WalkSummaryScreen.kt:178-191** — replace `animateFloatAsState` with explicit 30-step emitter using `Animatable` keyed on `loadedWalkId` (hard reset on same-walk re-entry race) + `LaunchedEffect` keyed on `(loadedWalkId, revealPhase, targetDistance)` — `reduceMotion` snapshot via `remember(loadedWalkId)` (NOT a key, to match iOS no-live-toggle parity). Body: 31 iterations of `delay(67ms) + animatable.snapTo(target * SmoothStepEasing.transform(i/30f))`. Reduce-motion: instant snap to target (skip ticks). Generation-cancellation provided for free by Compose's structured cancellation when LaunchedEffect re-keys.
4. **WalkSummaryScreen.kt** (new line near 177) — single firm haptic on Revealed-phase entry (matches Stage 5-B temple-bell vocabulary already in code: `LocalHapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)`). Gated on non-empty route + non-reduce-motion + new entry only (latch on `loadedWalkId`).

## Non-goals

- **Theme-flip mid-reveal recovery.** Real edge per robust-explorer, but rare (theme flip during a 4-second reveal window is unusual). Punt to a follow-up issue.
- **MapKit-mimicking custom interpolator** on `easeTo`. Mapbox default cubic for a 100ms zoom is fine; custom curve is over-tuning.
- **Per-tick count-up haptic.** Slot-machine effect, breaks the contemplative tone.
- **Breathing pulse during 0.8s hold.** The stillness IS the moment.
- **Earlier head-start** (count-up starting at Zoomed instead of Revealed). Breaks cause→effect grammar; iOS gets it right by waiting.
- **Live reduce-motion toggle support.** Match iOS — snapshot at launch via `remember(loadedWalkId) { reduceMotion }`; mid-reveal toggle does not re-apply. (Compose's `LaunchedEffect` would otherwise re-key on the live `reduceMotion` value and replay the animation — drop it from the keys to honor parity.)
- **Replacing `Modifier.alpha(value)` with `Modifier.graphicsLayer { alpha = ... }` for the 7 section reveals.** Stage 5-A perf cliff applies but is OUT OF SCOPE — separate PR worth doing alone, would balloon this one.

## Approach

### Delta 1 — camera ease (`PilgrimMap.kt:177-184`)

```kotlin
RevealPhase.Zoomed -> {
    val first = points.firstOrNull() ?: return@LaunchedEffect
    val target = CameraOptions.Builder()
        .center(Point.fromLngLat(first.longitude, first.latitude))
        .zoom(REVEAL_ZOOM)
        .build()
    if (reduceMotion) {
        view.mapboxMap.setCamera(target)
    } else {
        view.mapboxMap.easeTo(
            target,
            MapAnimationOptions.Builder().duration(REVEAL_ZOOM_PLANT_MS).build(),
        )
    }
}
```

New constant in `RevealAnimation.kt`: `internal const val REVEAL_ZOOM_PLANT_MS = 100L` (matches iOS `cameraDuration = 0.1`).

**Cancellation contract:** Mapbox SDK v11.11.0 (`gradle/libs.versions.toml:27` — `mapbox = "11.11.0"`) `easeTo` is fire-and-forget — no in-flight cancellation token. The Zoomed→Revealed transition supersedes the 100 ms ease via Mapbox's last-write-wins camera contract: the subsequent `easeTo(2500ms)` in the Revealed branch interrupts and replaces the in-flight 100 ms ease at the current intermediate camera position. iOS MapKit behaves identically (`cameraDuration` is a hint, not a hard commitment).

### Delta 2 — opacity easing (`WalkSummaryRevealAnimations.kt:61-69`)

```kotlin
import androidx.compose.animation.core.FastOutSlowInEasing
// ...
animationSpec = if (reduceMotion) {
    tween(0)
} else {
    tween(durationMs, delayMillis = delayMs, easing = FastOutSlowInEasing)
}
```

Drop the `EaseIn` import.

### Delta 3 — 30-step count-up emitter (`WalkSummaryScreen.kt:178-194`)

`targetDistance` already bound at the existing site (`WalkSummaryScreen.kt:178-179`):
```kotlin
val targetDistance =
    (state as? WalkSummaryUiState.Loaded)?.summary?.distanceMeters?.toFloat() ?: 0f
```
(`state` comes from `viewModel.state.collectAsStateWithLifecycle()` at `WalkSummaryScreen.kt:142` — pre-existing binding.)

Snapshot `reduceMotion` at LaunchedEffect entry (do NOT include in keys — iOS does not honor live toggles for this animation; matches Non-goals):

```kotlin
val countUp = remember(loadedWalkId) { Animatable(0f) }
val reduceMotionSnapshot = remember(loadedWalkId) { reduceMotion }
LaunchedEffect(loadedWalkId, revealPhase, targetDistance) {
    if (revealPhase != RevealPhase.Revealed) {
        countUp.snapTo(0f)
        return@LaunchedEffect
    }
    if (reduceMotionSnapshot || targetDistance == 0f) {
        countUp.snapTo(targetDistance)
        return@LaunchedEffect
    }
    // iOS for i in 0...steps — 31 emissions, i=0 writes 0, i=30 writes target.
    // 30 delays of 67ms = 2010ms (close enough to iOS 2.0s budget; integer-rounded up
    // from 66.67ms to preserve the 67ms iOS interval rather than truncate to 66ms).
    val intervalMs = COUNT_UP_INTERVAL_MS
    for (i in 0..COUNT_UP_STEPS) {
        val progress = i.toFloat() / COUNT_UP_STEPS
        countUp.snapTo(targetDistance * SmoothStepEasing.transform(progress))
        if (i < COUNT_UP_STEPS) delay(intervalMs)
    }
}
val animatedDistanceMeters = countUp.value
```

New constants in `RevealAnimation.kt`:
- `internal const val COUNT_UP_STEPS = 30` — number of progress increments; loop fires 31 emissions (i=0..30 inclusive) matching iOS `for i in 0...steps`.
- `internal const val COUNT_UP_INTERVAL_MS = 67L` — per-tick delay; 30 delays × 67 ms = 2010 ms total (iOS uses Double `2.0 / 30 = 66.67 ms` with no truncation; Android pins to 67 to mirror the human-visible rhythm without integer-truncation loss).

`Animatable` chosen over `produceState` for stable Compose lifecycle semantics + clean integration with `snapTo`. Same-walk re-entry race fix: `remember(loadedWalkId)` rebuilds the Animatable on every fresh entry; LaunchedEffect re-key cancels any in-flight loop. The final `snapTo(target * 1.0)` at i=30 lands on the EXACT target value (iOS has the same guarantee).

### Delta 4 — single haptic on Revealed (`WalkSummaryScreen.kt`, new ~line 177)

`state` comes from the existing `viewModel.state.collectAsStateWithLifecycle()` binding at `WalkSummaryScreen.kt:142`. `reduceMotionSnapshot` reused from Delta 3 (single snapshot at `loadedWalkId` boundary so haptic + count-up share parity semantics).

```kotlin
val haptic = LocalHapticFeedback.current
LaunchedEffect(loadedWalkId, revealPhase) {
    if (revealPhase != RevealPhase.Revealed) return@LaunchedEffect
    if (reduceMotionSnapshot) return@LaunchedEffect
    val loaded = state as? WalkSummaryUiState.Loaded ?: return@LaunchedEffect
    if (loaded.summary.routePoints.isEmpty()) return@LaunchedEffect
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
}
```

`LongPress` strength matches the temple-bell strike pattern from Stage 5-B (firm but not jarring).

**Once-per-visit contract:** the phase machine is monotonic Hidden→Zoomed→Revealed within a single walk-summary visit (LaunchedEffect at `WalkSummaryScreen.kt:167` drives the sequence linearly with no oscillation). `LaunchedEffect(loadedWalkId, revealPhase)` re-keys on each transition but only the Hidden→Revealed and Zoomed→Revealed transitions pass the `phase != Revealed` guard. Within a visit, phase reaches Revealed exactly once.

- **Back-nav:** Compose Navigation recreates `WalkSummaryScreen`'s composable instance on each NavBackStackEntry creation (verified: pop + push to the same destination route allocates a new entry; `popUpTo` + re-navigate also creates fresh entry). Stage 13-B lesson 10 confirms this for the count-up Animatable; same contract applies to the haptic LaunchedEffect since both rely on `remember`'s NavBackStackEntry scope. A fresh visit fires a fresh haptic.
- **Rotation:** `revealPhase` lives in `var revealPhase by remember(loadedWalkId) { mutableStateOf(RevealPhase.Hidden) }` at `WalkSummaryScreen.kt:156` — `remember` is preserved across config changes via `androidx.compose.runtime.saveable.SaveableStateRegistry` when nested in a NavBackStackEntry-scoped composition. `loadedWalkId` is also stable across rotation. LaunchedEffect therefore does not re-key on config change. No second haptic.
- **Process death:** state is rebuilt from scratch on restoration; behaves like a fresh visit (matches iOS).

## Files affected

- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt` (1 branch edit)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryRevealAnimations.kt` (1 import + 1 line swap)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt` (count-up rewrite + haptic LaunchedEffect)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/RevealAnimation.kt` (3 new constants: `REVEAL_ZOOM_PLANT_MS`, `COUNT_UP_STEPS`, `COUNT_UP_INTERVAL_MS`)
- `app/src/test/java/.../WalkSummaryRevealAnimationsTest.kt` (extend with Tests 1–3 below — pure-fn easing/interval assertions)
- New: `app/src/test/java/.../WalkSummaryCountUpTest.kt` (Robolectric + Turbine for the 30-step emitter — Tests 4–9 below: emission count, cancellation, reset, reduce-motion bypass, empty-route haptic suppression)

## Tests

All tests are deterministic-time (Turbine + `runTest` virtual time) and assert against the `Animatable.value` stream or against pure constants.

1. `countUpStartsAtZero_whenRevealed` — first emission is exactly `0f` (i=0, `SmoothStepEasing.transform(0f) * target == 0f`).
2. `countUpAtStep30_landsExactlyOnTarget` — final emission equals `targetDistance` exactly (`SmoothStepEasing.transform(1f) * target == target`; no precision drift).
3. `countUpInterval_is67ms` — `COUNT_UP_INTERVAL_MS == 67L` (catches future drift; locks the iOS rhythm).
4. `countUpEmits31Values_over2010ms` — collecting Animatable.value with `advanceTimeBy` of 67 ms intervals yields 31 distinct emissions (i=0..30); total elapsed = 2010 ms (replaces "visible odometer feel" with a measurable gate).
5. `countUpResetsToZero_whenSameWalkReentered` — set walkId, advance to mid-animation, simulate re-entry (re-trigger `remember(loadedWalkId)`), assert Animatable resets to 0 and re-runs.
6. `countUpCancelled_whenWalkIdChanges` — Walk A mid-count-up → switch to Walk B, assert Walk B starts at 0 with Walk B's target (no leak from A's Animatable).
7. `countUpSnapsInstantly_underReduceMotion` — `reduceMotion=true`, exactly 1 emission (target value), 0 delays consumed.
8. `revealPhase_skipsZoomed_whenRoutePointsEmpty` — empty route → Hidden→Revealed directly, no 100 ms `easeTo` + no 800 ms hold (lock the existing contract).
9. `revealPhase_skipsCinematic_underReduceMotion` — `reduceMotion=true`, no delays consumed, phase = Revealed immediately.

## Risks

- **`FastOutSlowInEasing` ≠ Apple `.easeInOut` exactly.** Material's curve is `(0.4, 0, 0.2, 1)` (asymmetric — fast-out, slow-in). Apple's `.easeInOut` is undocumented but visually closer to a symmetric cubic `(0.42, 0, 0.58, 1)`. Material's asymmetric curve is the closest stock match and indistinguishable to the eye over 600 ms in practice; flag in PR body. Promote to custom `CubicBezierEasing(0.42f, 0f, 0.58f, 1f)` (symmetric) only if device QA flags the asymmetric tail as visibly different.
- **31 recompositions × WalkStatsRow over 2 s.** `WalkStatsRow` (`WalkSummaryScreen.kt:~470`) reads `animatedDistanceMeters` from a single Text node (formatted distance string). Sibling Text nodes for duration + pace do NOT read the animated value, so they do not recompose with each tick. Net: ~31 recomposes on one Text node over 2 s — well below jank threshold (~5 ms per Text recompose). No mitigation in this PR; revisit only if device QA shows frame drops.
- **Haptic on muted/silent profiles.** Android haptic is independent of audio mute state (correct behavior); user gets the tap regardless of ringer setting. Matches iOS UIImpactFeedbackGenerator semantics.
- **Animatable.snapTo (vs animateTo) on every tick.** Intentional — `snapTo` is instant and avoids any frame-interpolation between ticks (which would defeat the discretization). Matches iOS's per-tick assignment.
- **2010 ms total count-up duration vs iOS 2000 ms budget.** 10 ms drift over a 2-second window is sub-perceptible. iOS uses Double arithmetic (`2.0 / 30 = 66.67 ms`) with no truncation; Android rounds up to 67 ms to preserve the iOS rhythm rather than truncate to 66 ms (which would yield 1980 ms total, +20 ms drift in the other direction). Pick the slower-by-10ms option because the final-tick payoff frame is what matters perceptually.

## Acceptance criteria

- [ ] Camera zooms in over 100 ms (not instant) when `revealPhase = Zoomed` and `reduceMotion = false`.
- [ ] Section opacity uses `FastOutSlowInEasing` at 600 ms instead of `EaseIn`.
- [ ] Distance count-up emits 31 discrete `Animatable.value` writes over 2010 ms (verified by Test 4); final value lands on exact `targetDistance`.
- [ ] Same-walk re-entry mid-count-up resets Animatable to 0 and re-runs from start (proven by Test 5).
- [ ] Single haptic fires once on Revealed-phase entry per walk visit (not on subsequent recomposes).
- [ ] Empty-route walks suppress haptic + skip Zoomed phase (proven by Test 8).
- [ ] Reduce-motion path: count-up snaps to target, camera uses `setCamera`, haptic suppressed.
- [ ] All existing tests pass.
- [ ] 9 new tests added (Tests 1–9 above — all introduced by this PR; previous spec wording incorrectly tagged 7–9 as "locking existing contracts" but Tests 7–9 exercise behavior introduced or modified by this change).
- [ ] Device-tested on OnePlus 13: cinematic plays correctly on a fresh walk; back-nav + re-open works without count-up artifacts.

## Open questions

- None for Phase 3 plan. All deltas have concrete code blocks; constants are named; lifecycle keys are specified.
