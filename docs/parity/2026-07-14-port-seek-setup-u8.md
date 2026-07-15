# Port spec — U8: Walk-mode plumbing + seek setup ritual

> **iOS anchor:** pilgrim-ios main @ `c1745e8` (verified 2026-07-14).
> **iOS files:** `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel.swift` (SeekSetupStage machine + accuracy seam),
> `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Seek.swift` (GPS-lock hold, seed/chain call, seek-marker event),
> `Pilgrim/Scenes/ActiveWalk/SeekSetupFlowModifier.swift`, `SeekDurationView.swift`, `SeekGatewayView.swift`,
> `Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift` (intention requirement + greeting substitution),
> `Pilgrim/Scenes/Root/MainCoordinatorView.swift` (`startWalk(mode:)`), `Pilgrim/Models/Walk/WalkMode.swift`,
> `UnitTests/Seek/SeekSetupFlowTests.swift`.
> **Android files:** `domain/WalkMode.kt`, `domain/WalkAction.kt`, `domain/WalkAccumulator.kt`, `domain/WalkState.kt`,
> `domain/WalkReducer.kt`, `walk/WalkController.kt`, `walk/WalkControllerImpl.kt`, `walk/UiWalkController.kt`,
> `walk/WalkActionPublisher.kt`, `service/WalkTrackingService.kt`, `walk/seek/SeekSessionStore.kt`,
> `ui/seek/SeekSetupViewModel.kt`, `ui/seek/SeekDurationSheet.kt`, `ui/seek/SeekGatewayOverlay.kt`,
> `ui/seek/SeekSetupModule.kt`, `ui/walk/ActiveWalkScreen.kt`, `ui/walk/GreetingOverlay.kt`,
> `ui/walk/IntentionSettingSheet.kt`, `ui/path/WalkStartScreen.kt`, `ui/navigation/PilgrimNavHost.kt`.

Scope note (plan R4/R5): end-state at `c1745e8` = iOS U6 (`SeekSetupStage` +
flow modifier + duration + gateway) **plus** `1f727ce` (gateway celestial line
+ seek weather voice). The `WalkMode.Seek` availability flip is **out of
scope** (U13); everything here ships dark behind `WalkMode.isAvailable`.

---

## B1. The stage machine — `SeekSetupStage`

**iOS** (`ActiveWalkViewModel.swift:666-679@c1745e8`):

```swift
enum SeekSetupStage: Equatable {
    case verifyingAccuracy
    case durationQuestion
    case intention
    case transition
    case ready
    case cancelled(SeekSetupCancelReason)
}

enum SeekSetupCancelReason: Equatable {
    case userDismissed
    case accuracyDeclined
    case gpsTimeout
}
```

Construction (`ActiveWalkViewModel.swift:132-133@c1745e8`):

```swift
self.seekSetupStage = mode == .seek ? .verifyingAccuracy : .ready
self.seekShowsSafetyCaption = mode == .seek && !UserPreferences.seekSafetyShown.value
```

Transitions, ALL guarded no-ops from wrong stages
(`ActiveWalkViewModel.swift:715-769@c1745e8`):

```swift
func beginSeekSetup() {
    guard mode == .seek, seekSetupStage == .verifyingAccuracy else { return }
    if UserPreferences.celestialAwarenessEnabled.value { ... seekTint = SeekSky.tint(...) }
    if seekAccuracy.hasFullAccuracy { seekSetupStage = .durationQuestion; return }
    seekAccuracy.requestTemporaryFullAccuracy { [weak self] granted in
        guard let self, self.seekSetupStage == .verifyingAccuracy else { return }
        self.seekSetupStage = granted ? .durationQuestion : .cancelled(.accuracyDeclined)
    }
}

func advanceSeekSetup(durationMinutes: Int) {
    guard mode == .seek, seekSetupStage == .durationQuestion else { return }
    seekDurationMinutes = durationMinutes
    UserPreferences.seekLastDurationMinutes.value = durationMinutes
    UserPreferences.seekSafetyShown.value = true
    seekSetupStage = .intention
}

func advanceSeekSetupIntentionSet() {
    guard mode == .seek, seekSetupStage == .intention else { return }
    seekSetupStage = .transition
}

func advanceSeekSetupTransitionComplete() {
    guard mode == .seek, seekSetupStage == .transition else { return }
    seekSetupStage = .ready
}

func cancelSeekSetup() {
    guard mode == .seek, seekSetupStage != .ready else { return }
    if case .cancelled = seekSetupStage { return }
    seekSetupStage = .cancelled(.userDismissed)
}
```

**Android claims:** `SeekSetupStage` sealed class + `SeekSetupCancelReason`
enum inside `ui/seek/SeekSetupViewModel.kt`, one-for-one:
`VerifyingAccuracy → DurationQuestion → Intention → Transition → Ready |
Cancelled(UserDismissed | AccuracyDeclined | GpsTimeout)`. Invariants pinned:

- Wander is born `Ready` and none of the advance methods engage
  (`SeekSetupFlowTests.swift:45-73`).
- Duration cannot be set before accuracy resolves
  (`SeekSetupFlowTests.swift:102-107`).
- Duration-before-intention ordering: `advanceIntentionSet` /
  `advanceTransitionComplete` no-op from `DurationQuestion`
  (`SeekSetupFlowTests.swift:77-100`).
- `cancel` after `Ready` is ignored; user-cancel never overwrites an earlier
  cancel reason (`SeekSetupFlowTests.swift:191-217`).
- Repeat `begin` after a cancel does not re-run the accuracy request
  (`SeekSetupFlowTests.swift:180-187`).
- Safety caption captured ONCE at setup start (iOS captures at VM init,
  `ActiveWalkViewModel.swift:69-71` — "so the caption doesn't vanish mid-flow
  when seekSafetyShown flips during this same setup").
- `advanceSeekSetup` persists BOTH `seekLastDurationMinutes` AND
  `seekSafetyShown = true` ("safety caption gone after first Begin",
  `SeekSetupFlowTests.swift:138-148`).

**D1 (VM shape divergence):** iOS puts the machine on `ActiveWalkViewModel`
(constructed per walk with `mode:`). Android's `WalkViewModel` is
NavBackStackEntry-scoped and mode arrives via nav argument, so the machine
lives on a dedicated `@HiltViewModel SeekSetupViewModel`; iOS's
`init(mode:)` + `beginSeekSetup()` pair collapses into a single
`beginSetup(mode)` entry (idempotent `begun` latch) that sets
`VerifyingAccuracy`, captures the safety-caption flag + celestial tint, and
runs the accuracy gate. All other transitions keep iOS's exact guards.

## B2. Accuracy gate — `SeekAccuracyProviding`

**iOS** (`ActiveWalkViewModel.swift:681-706@c1745e8`):

```swift
/// Seek hard-gates on full accuracy: approximate fixes (1-3 km) make
/// 80-120 m clearing regions physically undetectable — there is no
/// degrade path.
protocol SeekAccuracyProviding {
    var hasFullAccuracy: Bool { get }
    func requestTemporaryFullAccuracy(completion: @escaping (Bool) -> Void)
}
```

Production: `CLLocationManager().accuracyAuthorization == .fullAccuracy` +
`requestTemporaryFullAccuracyAuthorization(withPurposeKey: "SeekMode")`.

**Android mapping (D2):** iOS "approximate location" ↔ Android
coarse-only grant. Full accuracy ↔ `ACCESS_FINE_LOCATION` granted.
Seam: `fun interface SeekAccuracyChecking { fun hasPreciseLocation(): Boolean }`
(production: `PermissionChecks.isFineLocationGranted`). The *request* half of
iOS's protocol cannot live in a VM on Android (permission launchers are
Activity-scoped): the VM emits a one-shot `accuracyUpgradeRequests` event,
`ActiveWalkScreen` launches the `ACCESS_FINE_LOCATION` request, and the
result returns through `onAccuracyResult(granted)` — which carries iOS's
exact completion-handler guard (`stage == VerifyingAccuracy`). Declined →
`Cancelled(AccuracyDeclined)` → alert → home. Hard gate, no degraded seek
(same rationale comment ported).

## B3. Presentation owner — `SeekSetupFlowModifier`

**iOS** (`SeekSetupFlowModifier.swift:8-96@c1745e8`): one ViewModifier owns
every seek-setup presentation — duration sheet, gateway overlay, both alerts:

- Duration sheet presented while `stage == .durationQuestion`; a swipe-down
  *while still on the question* is a cancel ("the Begin-driven dismissal has
  already advanced the stage by the time SwiftUI writes false back",
  `:17-29`).
- `.intention` → present the intention sheet after **0.35 s**
  ("house sheet-swap spacing", `:76-81`).
- `.transition` → `SeekGatewayView(line: viewModel.seekTint?.gatewayLine)`
  overlay (`:45-52`).
- `.cancelled(.accuracyDeclined)` → alert **"Precise Location Needed"** /
  `LS.seekAccuracyDeclined`; `.cancelled(.gpsTimeout)` → alert
  **"Still Reaching for the Sky"** / `LS.seekGPSTimeout`; both OK buttons →
  `onCancelled()` (home). `.cancelled(.userDismissed)` → `onCancelled()`
  directly (`:83-91`).
- `onAppear` + 0.5 s → `beginSeekSetup()`, seek only (`:66-71`).

**Android:** the equivalent wiring lands inline in `ActiveWalkScreen`
(the screen already hosts every other sheet with the existing
`SHEET_HANDOFF_DELAY_MS = 300L` idiom — used for the 0.35 s intention
handoff; divergence D6). Alerts are `AlertDialog`s. `LaunchedEffect` on the
nav-arg mode fires `beginSetup` after 0.5 s on a pre-walk surface only
(recovery/in-progress compositions skip setup — a restored seek walk must
not re-run the ritual).

## B4. Duration sheet — `SeekDurationView`

**iOS** (`SeekDurationView.swift:5-115@c1745e8`):

```swift
static let presetMinutes = [30, 60, 120, 180]

static func preselectedMinutes(lastUsed: Int) -> Int {
    presetMinutes.min(by: { abs($0 - lastUsed) < abs($1 - lastUsed) }) ?? 60
}
```

- Title `LS.seekDurationTitle` = "How long do you have?".
- Preset labels: "30 minutes" / "1 hour" / "2 hours" / "3 hours".
- Tapping a preset row ALSO writes `seekLastDurationMinutes` immediately
  (`SeekDurationView.swift:76-79`).
- Safety caption (`LS.seekSafetyCaption`, fog color) shown only while
  `showsSafetyCaption` (first seek).
- Bottom row: Cancel (fog) + Begin (stone); Begin → `onContinue(selected)`.
- Presentation: `.medium/.large` detents, drag indicator, parchment 0.95.

**Android:** `ui/seek/SeekDurationSheet.kt` `ModalBottomSheet` (parchment,
house style), same presets/labels/caption/buttons. `preselectedMinutes`
ported verbatim as `internal fun` (Kotlin `minByOrNull` = Swift `min(by:)`
strict-`<`, both return the FIRST of tied candidates → **45 snaps to 30**,
`SeekSetupFlowTests.swift:130-134` pins 50→60, 500→180, 0→30).
`onDismissRequest` → `onCancel` (matches the swipe-down-cancels rule; the
Begin path flips the stage before the sheet leaves, and the host only
renders the sheet while `stage == DurationQuestion`).

## B5. Gateway overlay — `SeekGatewayView`

**iOS** (`SeekGatewayView.swift:8-104@c1745e8`) full-screen parchment ZStack:

- Mist: 260 pt fog circle, blur 42, opacity 0 → 0.5 + scale 0.85 → 1.0 over
  1.4 s easeInOut.
- Quote: `line ?? LS["Seek.Quote.1"]` ("What you seek\nis seeking you"),
  displayMedium, fog color; fade-in 1.6 s easeIn starting at 0.8 s.
- t=2.0 s: **`HapticPattern.seekBreathIn.fire()`** + ring one (220 pt,
  stone 0.6, 1.5 pt stroke) scale 0.25 → 1.7 / opacity 0.5 → 0 over 1.8 s
  easeOut. **Rings are silent** — no sonar audio in the gateway.
- t=3.1 s: ring two, opacity 0.4 → 0, same expansion. **No second haptic.**
- t=4.9 s: everything fades out over 1.2 s (mist scale drifts to 1.06).
- **t=6.2 s: `onComplete()`**.
- Reduce Motion (`:59-65`): text-only — quote fades in 0.4 s, out at 2.2 s,
  **`onComplete()` at 2.6 s**. No mist, no rings, no haptic.

**Android:** `ui/seek/SeekGatewayOverlay.kt` — full-screen Box, same
geometry/timeline via `Animatable` + one `LaunchedEffect` timeline;
`LocalReduceMotion` for the 2.6 s text-only path; `onBreathHaptic` callback
at t=2.0 s wired to `SeekHaptics.breathIn()` (U5) through the setup VM.
Celestial override line = `SeekTint.gatewayLineRes` (U7); default line =
first entry of `R.array.path_quotes_seek` (the Android home of iOS
`Seek.Quote.1` — same value "What you seek\nis seeking you").
`rememberUpdatedState(onComplete)` per the Stage 4-B delayed-callback rule.

## B6. GPS-lock hold + chain generation

**iOS** (`ActiveWalkViewModel+Seek.swift:36-119@c1745e8` +
`ActiveWalkViewModel.swift:713, 759-769@c1745e8`):

```swift
static let seekGPSLockTimeoutSeconds: TimeInterval = 20
static let seekChainFixAccuracyMeters = 50.0

func bindSeekLifecycle() {
    $seekSetupStage.removeDuplicates()
        .sink { ... guard stage == .transition else { return }
                self?.beginSeekGPSLock() }
}

func beginSeekGPSLock() {
    guard mode == .seek, seekEngine == nil else { return }
    seekGeneration += 1; let generation = seekGeneration
    $currentLocation.compactMap { $0 }
        .filter { $0.horizontalAccuracy >= 0 && $0.horizontalAccuracy <= Self.seekChainFixAccuracyMeters }
        .prefix(1)
        .sink { ... guard self.seekGeneration == generation else { return }
                self.startSeekEngine(from: sample) }
    DispatchQueue.main.asyncAfter(deadline: .now() + Self.seekGPSLockTimeoutSeconds) {
        guard self.seekGeneration == generation else { return }
        self.failSeekSetupGPSLock()
    }
}

func startSeekEngine(from sample: TempRouteDataSample) {
    guard mode == .seek, seekEngine == nil else { return }
    seekGeneration += 1
    var rng = SeekSeededGenerator(seed: SeekSeed.make(intention: intention, fix: sample))
    let chain = SeekChainGenerator.generate(
        durationMinutes: seekDurationMinutes ?? UserPreferences.seekLastDurationMinutes.value,
        start: start, using: &rng)
    ...
}

/// U7 GPS-lock hold: only the breath transition may time out. Once the
/// stage reached `.ready` the walk may already be recording, so a late
/// timeout stays silent and the engine simply starts on the first
/// accurate fix (the fix subscription stays armed). A real timeout bumps
/// the generation so the armed first-fix subscription becomes a no-op —
/// a late accurate fix must never boot the engine into a cancelled walk.
func failSeekSetupGPSLock() {
    guard mode == .seek, seekEngine == nil, seekSetupStage == .transition else { return }
    seekGeneration += 1
    seekSetupStage = .cancelled(.gpsTimeout)
}
```

**Android claims:**

- The lock starts WITH the transition (entering `Transition` launches it —
  usually the chain is ready before the walker opens their eyes).
- Fix filter: `horizontalAccuracyMeters != null && >= 0 && <= 50` — first
  qualifying fix only. Android's `LocationPoint.horizontalAccuracyMeters`
  is nullable; `null` = unknown = not qualifying (iOS's negative-invalid
  convention maps to null-or-negative here).
- **D3 (location feed):** iOS taps the walk screen's already-running
  CoreLocation pipeline (`$currentLocation`). Android has no pipeline before
  the FGS starts, so the setup VM collects `LocationSource.locationFlow()`
  directly for the lock (fine permission is guaranteed by the B2 gate;
  `SecurityException` is caught and treated as "no fix" — the timeout
  resolves it).
- No engine in U8 (iOS boots `SeekEngine` here; Android's engine boot is
  U9): the first qualifying fix instead **generates the chain** —
  `SeekSeed.make(intention = capturedIntention, momentEpochMillis =
  clock.now(), fix = fix)` → `SeekSeededGenerator` →
  `SeekChainGenerator.generate(durationMinutes ?:
  seekPreferences.lastDurationMinutes.value, start, rng)` — and publishes it
  to `SeekSessionStore` (B7).
- Timeout 20 s, **transition-only**: fires → still `Transition` and no chain
  → generation bump (arm-kill) + `Cancelled(GpsTimeout)` + alert. Stage
  already `Ready` → silent, the fix collection stays armed and a late fix
  locks the chain quietly. Chain-lock itself bumps the generation so a
  later timeout tick is a no-op (mirrors iOS's `startSeekEngine`
  generation bump).
- User-cancel during transition kills the armed collection (Android
  explicit `Job.cancel`; iOS reaches the same end via `teardownSeek()`'s
  generation bump on the coordinator's cancel path).

## B7. Handoff seam for U9 — `SeekSessionStore` (Android-only shape)

iOS hands the generated chain straight to `SeekEngine` inside the same VM.
Android defers the engine to U9's orchestrator, so U8 publishes the setup's
output through a dumb `@Singleton` holder (`walk/seek/SeekSessionStore.kt`):

```kotlin
data class SeekPendingSession(
    val chain: SeekChain,
    val durationMinutes: Int,
    val tint: SeekTint?,
    val seededAtEpochMillis: Long,
)
```

Contract: **set** when the GPS lock generates the chain (transition or the
silent late-fix path); **cleared** on setup cancel, on a fresh
`beginSetup`, when the setup VM dies with no walk in progress
(pre-walk back-out), and on every walk terminal transition
(`WalkLifecycleObserver`'s Finished/Idle branches — U9's orchestrator may
take this over). No engine imports; the store knows `SeekChain`/`SeekTint`
and nothing else. U9 reads `pending.value` when a seek walk starts. UI
process only (the orchestrator plan pins it to `PilgrimApp`).

## B8. Celestial tint + gateway line (U7 consumption)

**iOS** (`ActiveWalkViewModel.swift:717-724@c1745e8`): computed ONCE in
`beginSeekSetup`, gated on `celestialAwarenessEnabled`:

```swift
seekTint = SeekSky.tint(
    marker: CelestialCalculator.snapshot(for: now, system: system).seasonalMarker,
    lunarPhase: CelestialCalculator.lunarPhaseName(for: now))
```

**Android:** `SeekSky.tint(marker, isFullMoon)` (U7, already shipped) called
once in `beginSetup` when `practicePreferences.celestialAwarenessEnabled
.value`; `isFullMoon = MoonCalc.moonPhase(now).name == "Full Moon"` (U7 spec
D7). **Adopted per U7-spec recommendation:** the marker is
**hemisphere-corrected** (`turningMarkerForEpochMillis(now)
.forHemisphere(hemisphereRepository.hemisphere.value)`) — iOS passes the raw
northern-named marker, but every other Android turning surface
(PR #169/#170 precedent: `HomeScreen`, `WalkDotColor`,
`ActiveWalkScreen.activeTurning`) corrects for the southern hemisphere; a
June seek in Sydney tints winter-blue, not summer-gold. Conscious, flagged
divergence (D4). The tint rides `SeekPendingSession.tint` (U9 feeds
`SeekFogState.tintHex`) and `gatewayLineRes` overrides the gateway line.

## B9. Mode plumbing — `startWalk(mode:)` end-to-end

**iOS** (`MainCoordinatorView.swift:51-59@c1745e8`):

```swift
func startWalk(mode: WalkMode = .wander) {
    ...
    let vm = ActiveWalkViewModel(mode: mode)
```

`WalkMode` (`WalkMode.swift:3-24@c1745e8`): `case wander, together, seek`;
`isAvailable: self == .wander || self == .seek` (**the flip is U13 — Android
keeps `isAvailable = this == Wander`**).

**Android mapping (D5 — the load-bearing divergence):** iOS carries mode as
a VM constructor argument; Android's walk state machine spans two processes,
so mode travels:

1. `WalkStartScreen` → nav arg: route becomes
   `active_walk?mode={mode}` (default `Wander`), `Routes.activeWalk(mode)`.
2. `ActiveWalkScreen(mode)` → `WalkViewModel.startWalk(intention, mode)` →
   `WalkController.startWalk(intention, mode)`.
3. UI process: `UiWalkController` → `WalkActionPublisher.start(intention,
   mode)` → intent extra `EXTRA_WALK_MODE` (enum name string) →
   `WalkTrackingService.startTracking` parses (unknown → Wander, forward
   compat) → tracker `WalkControllerImpl.startWalk(intention, mode)`.
4. Reducer: `WalkAction.Start(walkId, at, mode)` → the accumulator carries
   it — **`WalkAccumulator.mode: WalkMode`** rides every in-progress +
   Finished state (the "how mode rides WalkState" answer; readable by the
   service for U10, the orchestrator for U9, and the options sheet).
5. Persistence: walks stay ordinary Room rows. On seek start the reducer's
   Start transition emits **one `WalkEffect.PersistEvent(SEEK_MODE, at)`**
   (U4's declared call-site: iOS writes it in `startRecording()` →
   `writeSeekMarkerEventIfNeeded()`, `ActiveWalkViewModel.swift:297-304` +
   `ActiveWalkViewModel+Seek.swift:174-177@c1745e8`:
   `guard mode == .seek else { return }` + one `TempWalkEvent(.seekMode)`).
   Android's "recording start" IS `startWalk` (there is no waiting→ready→
   recording ladder), so the reducer emits it exactly once per started seek
   walk, never for wander, never on restore.
6. Restore/derivation: both controllers re-derive mode from Room —
   `events.any { it.eventType == SEEK_MODE } → Seek` — in
   `WalkControllerImpl.restoreActiveWalk` and `UiWalkController.
   buildActiveState`/`buildFinishedAccumulatorState`. (Brief first-frame
   `Wander` in the UI process until the event row lands in the combine is
   acceptable: no mode consumer renders in that window.)

`decideStartAction` / `decideStateAction` are mode-independent by
construction (they consume state classes + flags, not payloads); the
seek × {fresh, cached-Finished, restored-Active} cross-product is pinned by
tests to prove the cached-`:tracker` second-walk gates hold unchanged.

## B10. Required intention for seek

**iOS** (`ActiveWalkView.swift:283-297@c1745e8`):

```swift
IntentionSettingView(
    historyStore: intentionHistory,
    allowsSkip: viewModel.mode != .seek,
    onSet: { intention in
        viewModel.intention = intention
        showIntention = false
        viewModel.advanceSeekSetupIntentionSet()
    },
    onDismiss: { showIntention = false }
)
...
.interactiveDismissDisabled(viewModel.mode == .seek)
```

`allowsSkip: false` hides the Cancel button
(`IntentionSettingView.swift:319-326@c1745e8`).

Also (`ActiveWalkView.swift:392-398@c1745e8`): the wander-only
auto-intention prompt is suppressed for seek — "Seek drives the intention
step from its own stage machine — the wander-only auto-present would
double-fire the sheet."

**Android:** `IntentionSettingSheet` gains `allowsSkip: Boolean = true`.
`false` = hide Cancel + block every dismissal path (`onDismissRequest`
no-op, `confirmValueChange` rejects `Hidden`, `ModalBottomSheetProperties
(shouldDismissOnBackPress = false)`) — the Android reading of
`interactiveDismissDisabled`. The seek save handler additionally calls
`SeekSetupViewModel.advanceIntentionSet(text)` (guarded no-op for wander,
same as iOS). `shouldAutoPromptIntention` callers gate on mode != Seek.

## B11. Seek weather greeting

**iOS** (`ActiveWalkView.swift:691-693@c1745e8`):

```swift
if viewModel.mode == .seek {
    greeting = SeekVoice.greeting(for: condition)
}
```

**Android:** `WeatherGreetingOverlay` gains `isSeek: Boolean = false` and
resolves `SeekVoice.greetingRes(condition)` (U7, strings already shipped)
instead of the wander table. The caller passes
`navWalkState.modeOrNull() == WalkMode.Seek` — the *accumulator's* mode, not
the nav arg, so a recovered/deep-linked seek walk still speaks seek.

## B12. Strings

New (one commented block in `values/strings.xml`; `seek_greeting_*` and
`seek_tint_line_*` shipped with U7):

| resource id | value | iOS source |
|---|---|---|
| `seek_duration_title` | `How long do you have?` | `seek.duration.title` |
| `seek_duration_30min` | `30 minutes` | `seek.duration.30min` |
| `seek_duration_1hour` | `1 hour` | `seek.duration.1hour` |
| `seek_duration_2hours` | `2 hours` | `seek.duration.2hours` |
| `seek_duration_3hours` | `3 hours` | `seek.duration.3hours` |
| `seek_safety_caption` | `Never trespass, and let your own judgment walk above the pulse. Any clearing may be released — seek anew.` | `seek.safety.caption` |
| `seek_begin` | `Begin` | `seek.begin` |
| `seek_setup_cancel` | `Cancel` | literal `"Cancel"` (`SeekDurationView.swift:103`) |
| `seek_accuracy_declined_title` | `Precise Location Needed` | literal (`SeekSetupFlowModifier.swift:53`) |
| `seek_accuracy_declined_body` | `Seeking needs your precise location to sense the clearings. Wander is always open.` | `seek.accuracy.declined` |
| `seek_gps_timeout_title` | `Still Reaching for the Sky` | literal (`SeekSetupFlowModifier.swift:58`) |
| `seek_gps_timeout_body` | `The sky hasn't answered yet. Try again under more open sky — Wander is always open.` | `seek.gps.timeout` |
| `seek_alert_ok` | `OK` | literal `"OK"` |

Gateway default line: reuses `R.array.path_quotes_seek[0]` = iOS
`LS["Seek.Quote.1"]` (same fragility-by-position as iOS; noted).

## Divergence table

| # | iOS | Android | Why |
|---|---|---|---|
| D1 | Stage machine on `ActiveWalkViewModel(mode:)`; `init` + `beginSeekSetup()` split | Dedicated `SeekSetupViewModel`; `beginSetup(mode)` collapses init+begin behind a `begun` latch | Hilt VMs are argless; mode arrives via nav arg |
| D2 | `requestTemporaryFullAccuracyAuthorization` (per-session accuracy) | `ACCESS_FINE_LOCATION` upgrade request via Activity launcher; VM event + `onAccuracyResult` | No Android analogue of temporary full accuracy; coarse-only grant is the "approximate" state |
| D3 | GPS-lock taps the walk pipeline's `$currentLocation` | Setup VM collects `LocationSource.locationFlow()` itself | Android has no location pipeline before the FGS starts |
| D4 | Raw (northern-named) seasonal marker feeds `SeekSky.tint` | Hemisphere-corrected marker (`forHemisphere`) | House precedent PR #169/#170; U7 spec recommends it; flagged consciously |
| D5 | Mode = VM constructor arg; `seekMode` event written in `startRecording()` | Mode = nav arg → intent extra → `WalkAction.Start(mode)` → `WalkAccumulator.mode`; SEEK_MODE event emitted by the reducer's Start transition | Two-process state machine; Android's start IS recording start |
| D6 | 0.35 s sheet-swap delay constant | Existing `SHEET_HANDOFF_DELAY_MS = 300L` | House constant already used for every other sheet handoff on this screen; 50 ms delta invisible |
| D7 | Chain consumed by `SeekEngine` in the same VM | Chain published to `SeekSessionStore` for U9 | Engine boot is U9 on Android |
| D8 | Gateway haptic via `HapticPattern.seekBreathIn.fire()` in the view | `SeekHaptics.breathIn()` behind a `SeekBreathHaptic` seam on the setup VM | Composables can't reach Hilt singletons; keeps VM JVM-testable |
| D9 | `SeekLocationAccuracyProvider` purpose key `"SeekMode"` | n/a | Info.plist purpose keys have no Android analogue |

## Test map (iOS `SeekSetupFlowTests.swift` → Android)

| iOS test | Android home |
|---|---|
| `testWanderIsReadyImmediately` / `testWanderSeekStagesNeverEngage` / `testWanderDurationAdvanceDoesNotTouchPreferences` | `SeekSetupViewModelTest` wander block |
| `testSeekRequiresDurationThenIntention` / `testSeekDurationCannotBeSetBeforeAccuracyResolves` | full ladder + guarded-advance tests |
| `testDurationSelectionPersistsToPreference` / `testPreselectionReadsLastUsedPreference` / `testPreselectionSnapsUnknownValueToClosestPreset` | persistence + snap tests (45 → 30 pinned) |
| `testFirstSeekShowsSafetyCaptionSecondDoesNot` / `testWanderNeverShowsSafetyCaption` | safety-caption tests |
| `testFullAccuracySkipsTemporaryRequest` / `testReducedAccuracyGrantedProceedsToDuration` / `testReducedAccuracyDeclinedCancels` / `testCancelledSeekDoesNotResumeOnRepeatBegin` | accuracy-gate tests |
| `testUserCancelFromDurationQuestion` / `testUserCancelDoesNotOverwriteAccuracyDecline` / `testCancelAfterReadyIsIgnored` | cancel tests |
| `testGPSLockTimeoutHookExists` | superseded by real timeout tests: timeout-in-transition → cancelled; fix-after-ready → silent chain lock |
| `testSeekVoice_coversEveryConditionInSeekLanguage` / `testSeekSky_turningsOutrankTheMoon` | shipped with U7 (`SeekSkyLightTest`) |
| — (Android-only) | reducer mode-carriage + SEEK_MODE-effect tests; controller SEEK_MODE persist-once + restore-mode tests; `decideStartAction` seek cross-product; publisher `EXTRA_WALK_MODE` Robolectric intent test |
