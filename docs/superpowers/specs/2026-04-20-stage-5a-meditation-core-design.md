# Stage 5-A — Meditation core UX

**Status:** design
**Stage in port plan:** Phase 5 (Meditation + voice guides) — 5-A (first sub-stage)
**Depends on:** existing domain-layer meditation plumbing (`WalkReducer`, `WalkState.Meditating`, `WalkAction.MeditateStart/MeditateEnd`, `WalkController.startMeditation/endMeditation`, `WalkViewModel.startMeditation/endMeditation`, `replayWalkEventTotals` totalMeditatedMillis).
**iOS reference:** `../pilgrim-ios/Pilgrim/Scenes/ActiveWalk/MeditationView.swift` (950 lines — we MVP a fraction of it).

## Goal

When the walker wants a moment of stillness mid-walk, tapping *Meditate* opens a dedicated meditation surface: a breathing circle paced to a gentle in-and-out rhythm, a running session timer, and a *Done* button. No audio, no rhythm picker, no voice guide, no soundscape — those layer in across sub-stages 5-B through 5-E. The domain already tracks meditation time correctly via `MEDITATION_START`/`MEDITATION_END` events; Stage 5-A only adds the contemplative UI surface and keeps the screen from sleeping during the session.

The walk continues in the background (GPS still sampling, notification still updating, timer still counting total elapsed). Meditation is a *mode within* the walk, not a pause of it — time during meditation is attributed to `totalMeditatedMillis`, not to pause time or active-walking time.

## Non-goals (deferred to later sub-stages)

- **Audio anything.** No temple bell, no ambient soundscape, no voice guide. The breathing circle is silent. Temple bell lands in **5-B**, voice guides in **5-C/5-D**, soundscapes in **5-E**.
- **Breath-rhythm picker.** iOS has seven rhythms (Calm 5/7, Equal 4/4, Relaxing 4-7-8, Box 4-4-4-4, Coherent 5/5, Deep-calm 3/6, None). We ship ONE rhythm — **3s inhale, 3s exhale** — a gentle default that doesn't require user choice. A picker arrives when multiple rhythms earn their UI weight (likely 5-D paired with the voice-guide picker sheet).
- **Session milestones / breath count / closing ceremony / particles / voice rings / warmth progression.** All iOS delights — defer. The Android MVP is breathing circle + timer + Done.
- **Long-press on the circle to open options / swipe gestures.** No advanced interaction; tap *Done* to end.
- **Breath-holds** (inhale-hold, exhale-hold). Just in-and-out.
- **Meditation-detection-from-sensors** (iOS's `MeditateDetection.swift` uses phone orientation + stillness to auto-start meditation). Port deferred indefinitely — intentional Android choice to keep meditation user-triggered.
- **Pause-meditation.** No pause; just Done ends the session.

## Experience arc

1. User is on **ActiveWalkScreen**. Walk state is `Active`. The existing *Meditate* TextButton is visible (no change to placement or label).
2. User taps *Meditate*. `WalkViewModel.startMeditation()` dispatches `MeditateStart` through the reducer → `WalkState.Meditating` → `MEDITATION_START` event persisted.
3. Because `ActiveWalkScreen` observes `ui.walkState::class` in a `LaunchedEffect` (current pattern for Finished → onFinished), we add a Meditating branch: nav to `Routes.MEDITATION`.
4. **MeditationScreen** composes:
   - Parchment background, full-screen (no top bar).
   - A centered breathing circle (moss-colored radial gradient, ~320dp canvas with an inner 160dp core) that scales from 0.45 to 1.0 over a gentle 6-second cycle (3s inhale → 3s exhale).
   - Below the circle, the session timer in `pilgrimType.statValue` counting `mm:ss` since the screen appeared.
   - At the bottom, a pill-outlined *Done* button in `pilgrimColors.fog`.
5. User sits with the breath for however long they want. Screen stays awake via `FLAG_KEEP_SCREEN_ON`.
6. User taps *Done*. `WalkViewModel.endMeditation()` dispatches → `WalkState.Active` → `MEDITATION_END` event persisted. MeditationScreen observes the state transition away from `Meditating` and pops back to `ActiveWalk`.
7. If the user **backgrounds the app** during meditation, then returns: the `HomeScreen` resume-check already routes `isInProgress == true` walks to `ActiveWalk`. From `ActiveWalk`, the state-observing `LaunchedEffect` sees state is `Meditating` and forward-navs to `MeditationScreen`. Restoration is one extra hop but correct.
8. If the user **ends the walk entirely** (e.g., via the ongoing notification's finish action, or via `ActiveWalkScreen`'s Finish button — though they'd have to pop back to ActiveWalk first), `WalkReducer.reduceMeditating` handles `WalkAction.Finish` and transitions directly to `Finished`, folding meditation time into the accumulator. MeditationScreen observes `Finished` state and pops (ActiveWalkScreen then pops itself to summary).

### The one delight: the breathing circle

A single moss-colored radial-gradient Circle, rendered via Compose's `Canvas`, that **breathes**. The scale animates between 0.45 (exhale) and 1.0 (inhale) via `rememberInfiniteTransition + animateFloat`, with a gentle `tween(3000, easing = FastOutSlowInEasing)` on each half-cycle. The gradient itself is subtle: moss at 50% opacity in the core fading to moss at 0% at the outer edge; a secondary inner core (160dp) at moss 70% gives depth. The effect reads as "soft glow of presence" — not a digital-looking animation, not a lock-screen pulse, but a *held breath of light*.

No rippling ring, no milestone flashes, no particles, no warmth overlay. Later sub-stages can layer on.

## Architecture

### `MeditationScreen.kt` (new composable)

```kotlin
@Composable
fun MeditationScreen(
    onEnded: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
)
```

- Observes `viewModel.uiState.walkState`. When state transitions **away from `Meditating`** (to `Active` via user's Done tap, or to `Finished` via external finish), fires `onEnded()`. Mirrors ActiveWalkScreen's Finished→onFinished pattern.
- Hosts a `SessionClock`-like counter — simpler than the iOS one: a `LaunchedEffect(Unit) { while (true) { delay(1.second); elapsed += 1 } }` pattern, or `producedState` over a 1-Hz tick. We'll use `remember { mutableStateOf(0) } + LaunchedEffect(Unit) { while(isActive) { delay(1000); elapsed++ } }`. Clock starts at 0 on first composition, NOT at `WalkState.Meditating.meditationStartedAt`, because the user doesn't care that the state transition happened 200ms before the screen composed.
- Applies `FLAG_KEEP_SCREEN_ON` via `DisposableEffect` at screen entry; clears on disposal.
- Renders the breathing circle, timer label, and Done button.
- *Done* calls `viewModel.endMeditation()`. The state transition then fires `onEnded()` via the observer.
- A `BackHandler` during meditation: intercept and treat it like Done, so hardware back doesn't pop to ActiveWalk with the controller still in `Meditating`. iOS doesn't have this problem (no back button); Android does.

Pattern cribbed from:
- `ActiveWalkScreen.kt`'s `LaunchedEffect(ui.walkState::class)` observer for state transitions.
- `ActiveWalkScreen.kt`'s `BackHandler(enabled = …)` usage for intercepting nav-back mid-critical-state.
- `SealRevealOverlay.kt`'s `DisposableEffect` cleanup idiom (Stage 4-B) — though `DisposableEffect` is overkill for a simple window-flag; we can use `DisposableEffect(Unit) { window.addFlags(KEEP_SCREEN_ON); onDispose { window.clearFlags(KEEP_SCREEN_ON) } }`.

### `BreathingCircle.kt` (new, internal to the `ui.meditation` package)

Pure visual composable. Decoupled from `WalkViewModel` so it can be unit-rendered in tests + composable-preview'd without dependencies. Accepts no state beyond its own animated scale.

```kotlin
@Composable
internal fun BreathingCircle(modifier: Modifier = Modifier)
```

Implementation sketch:
```kotlin
val transition = rememberInfiniteTransition(label = "breath")
val scale by transition.animateFloat(
    initialValue = SCALE_EXHALED,
    targetValue = SCALE_INHALED,
    animationSpec = infiniteRepeatable(
        animation = tween(HALF_CYCLE_MS, easing = FastOutSlowInEasing),
        repeatMode = RepeatMode.Reverse,
    ),
    label = "breathScale",
)
Canvas(modifier = modifier.size(CIRCLE_SIZE_DP.dp)) {
    val radius = size.minDimension / 2f * scale
    // Outer radial gradient (moss @ 50% fading to 0%)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                moss.copy(alpha = 0.5f),
                moss.copy(alpha = 0.15f),
                moss.copy(alpha = 0f),
            ),
            center = Offset(size.width / 2, size.height / 2),
            radius = radius,
        ),
        radius = radius,
        center = Offset(size.width / 2, size.height / 2),
    )
    // Inner core (moss @ 70% fading to @ 30%)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                moss.copy(alpha = 0.7f),
                moss.copy(alpha = 0.3f),
            ),
            center = Offset(size.width / 2, size.height / 2),
            radius = radius * 0.5f,
        ),
        radius = radius * 0.5f,
        center = Offset(size.width / 2, size.height / 2),
    )
}
```

`moss` is resolved via `pilgrimColors.moss` at the call site and passed as a param (keeps `BreathingCircle` theme-agnostic and pure for test).

Constants:
```kotlin
private const val SCALE_EXHALED = 0.45f
private const val SCALE_INHALED = 1.0f
private const val HALF_CYCLE_MS = 3_000 // 3s inhale, 3s exhale → 6s full cycle
private val CIRCLE_SIZE_DP = 320
```

### Navigation

- `Routes.MEDITATION = "meditation"` added to `PilgrimNavHost.Routes`.
- `composable(Routes.MEDITATION) { MeditationScreen(onEnded = { navController.popBackStack() }) }` — pop on state transition away from Meditating.
- `ActiveWalkScreen` gets a new `onEnterMeditation: () -> Unit` parameter; its state-class observer forks:
  - `WalkState.Finished` → `onFinished(walkId)` (existing)
  - `WalkState.Meditating` → `onEnterMeditation()` (new)
- `PilgrimNavHost` wires `onEnterMeditation = { navController.navigate(Routes.MEDITATION) { launchSingleTop = true } }`.

### `pilgrimColors.moss`

Already exists in the Stage 3-E palette (used for the calligraphy ink flavor and for the ripple rings in iOS ported forms). No theme changes needed — just read `pilgrimColors.moss` at the `MeditationScreen` level and pass it to `BreathingCircle`.

### Strings

- `R.string.meditation_done` = "Done"
- Existing `R.string.walk_action_meditate` ("Meditate") already on the ActiveWalk button — no change.
- Existing `R.string.walk_action_end_meditation` is currently shown on ActiveWalk when state is Meditating; with 5-A, the user never sees the ActiveWalk controls while Meditating (they're on MeditationScreen instead). The string stays for the case where the state restoration races and the user sees ActiveWalk for a frame — harmless fallback.

### FLAG_KEEP_SCREEN_ON

```kotlin
val context = LocalContext.current
val activity = context as? Activity ?: return
DisposableEffect(activity) {
    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    onDispose {
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
```

Caveats:
- `LocalContext.current` may not be an Activity in preview / test contexts. Guard with `as?` cast and early-return on null — the flag is a nice-to-have for tests.
- `DisposableEffect(activity)` keys on the activity so configuration changes re-apply (unlikely during a meditation session, but correct).

## Testing

### `BreathingCircleTest.kt` (Robolectric compose-rule smoke)

- Composes `BreathingCircle(moss = Color.Green)` inside a size-bounded Box; asserts `onRoot().assertExists()`. No visual verification (Canvas is a Robolectric stub per Stage 3-C lesson).

### `MeditationScreenTest.kt` (Robolectric compose-rule smoke)

- Internal `MeditationScreenContent(elapsedSeconds: Int, onDone: () -> Unit)` extracted for testability, same pattern as Stage 4-C's `GoshuinScreenContent`.
- Tests: renders, shows timer (e.g., "0:07" for elapsed=7), Done button click fires callback. Animation timing NOT asserted (Compose animations under Robolectric return initial values deterministically — we rely on state tests + manual QA).

### `WalkViewModelTest.kt` extension

- Existing tests already cover `startMeditation` / `endMeditation` dispatching. No new VM tests needed for 5-A; the feature is UI + nav wiring on top of existing domain.

### What's NOT tested

- FLAG_KEEP_SCREEN_ON application (verified manually on-device — Robolectric's Activity is stubbed).
- Nav auto-forward from ActiveWalk → Meditation on state transition (UI nav tests exist in the project but require instrumentation; manual on-device QA is authoritative).
- 10+ minute meditation session reliability (device QA phase per port plan).

## What's on the commit

- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/meditation/MeditationScreen.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/meditation/BreathingCircle.kt`
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/meditation/MeditationScreenTest.kt`
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/meditation/BreathingCircleTest.kt`
- Modified: `ActiveWalkScreen.kt` (new `onEnterMeditation` param; state-class observer gains Meditating branch)
- Modified: `PilgrimNavHost.kt` (new `Routes.MEDITATION`, composable, wire ActiveWalk param)
- Modified: `res/values/strings.xml` (one new string: `meditation_done`)

## Risks and mitigations

- **State transition loops:** ActiveWalk → nav to Meditation → Meditation state-transition-away observer fires immediately because the initial state *is* Meditating … wait, actually the observer fires on state CHANGES, not on initial state. `LaunchedEffect(ui.walkState::class)` keys on the class; first composition reads Meditating and runs the block. The Meditating case in MeditationScreen's observer does *nothing* on Meditating (only fires onEnded on non-Meditating). Verify the `when` branch logic carefully so the initial state doesn't fire `onEnded` prematurely.
- **Double-pop race:** User taps Done → `endMeditation()` → state→Active → MeditationScreen observes transition → `onEnded()` → `popBackStack()`. But `WalkViewModel.endMeditation` is a `suspend` fun wrapped in a coroutine launch in the VM. The state transition may arrive several frames after the tap. During that window, the Done button is tappable again. Mitigation: disable the Done button after first tap using a `remember { mutableStateOf(false) }` guard (`didTapDone`). Button `enabled = !didTapDone`.
- **`LocalContext` not an Activity (Composable preview, test):** guarded with `as?` cast; the keep-screen-on flag simply doesn't apply. No crash.
- **Back gesture mid-meditation:** `BackHandler` in MeditationScreen treats back like Done (calls `endMeditation()`). Without this, back would pop to ActiveWalk with the controller still in `Meditating`, and ActiveWalk's observer would immediately bounce back to Meditation — oscillation bug.
- **Walk finishes mid-meditation (external):** state transitions Meditating → Finished. MeditationScreen's observer fires `onEnded()` → popBackStack. ActiveWalk's observer then fires `onFinished(walkId)` → nav to summary. Two-hop but correct.
- **Meditation started in a prior session, user opens app fresh:** resume-check on HomeScreen routes to ActiveWalk; ActiveWalk's observer sees `Meditating` and forward-navs to MeditationScreen. One extra hop. Acceptable.

## Success criteria

- Tap *Meditate* → MeditationScreen appears with breathing circle + timer; the walk's GPS tracking continues (underlying service + notification unaffected).
- Screen stays awake (`FLAG_KEEP_SCREEN_ON`) throughout the session.
- Tap *Done* → pops back to ActiveWalk with the walk now in `Active` state, active-walking timer resumes, `totalMeditatedMillis` in the subsequent walk-finish summary includes this session's duration.
- Back press during meditation behaves as Done (no oscillation to ActiveWalk and back).
- Screen rotation mid-session doesn't reset the breathing animation or session timer.
- Background the app → return: lands back on MeditationScreen via the ActiveWalk forward-nav.
- On-device manual QA (per port plan's Stage 5 verification): 10+ minute session plays without animation glitches or state-flip regressions.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` green.

## Open questions (answered)

- *Rhythm picker now or later?* Later. Ship one rhythm (3s/3s) so the MVP has zero UI weight beyond the breathing circle. Picker naturally pairs with 5-D voice-guide selection when a sheet is already warranted.
- *Audio now or later?* Later, across sub-stages 5-B through 5-E. Silent meditation ships a testable core.
- *Auto-detect meditation from motion sensors?* Not porting. iOS's `MeditateDetection.swift` uses phone-orientation + stillness to auto-suggest meditation mode; we choose user-triggered-only on Android for simplicity and agency.
- *Dedicated VM vs reuse WalkViewModel?* Reuse `WalkViewModel`. It already holds `startMeditation`/`endMeditation` and `uiState.walkState`. Adding a separate `MeditationViewModel` would fork state (two VMs both observing the same `WalkController`) for zero benefit.
- *Where does the session timer start counting?* From first screen composition (`LaunchedEffect(Unit)` starting at 0), not from `WalkState.Meditating.meditationStartedAt`. The user's mental model is "I tapped Meditate a moment ago" — not an accounting concern. The accounting truth — `totalMeditatedMillis` — comes from the reducer, unaffected by what the UI shows.
- *Screen background color?* `pilgrimColors.parchment` (matches the rest of the app; the breathing circle's moss gradient reads beautifully against warm parchment). Dark mode: `parchment` is near-black; moss is a lighter green; still reads.
- *Timer format?* `"m:ss"` with monospaced digits. Simple, uncomplicated.
