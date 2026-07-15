# Port spec — U9: Seek orchestrator, senses routing, options sheet

> **Plan:** `docs/plans/2026-07-14-001-feat-seek-mode-journal-scenery-plan.md` (U9) · **Requirements:** R4 (iOS Seek U7), R5 (`85373c1` pre-walk options surfacing, `ece26a7` tangible reroll)
> **iOS pin:** `pilgrim-ios` @ `c1745e8` (HEAD of `main`, verified 2026-07-14). All quotes cite `file:line@c1745e8`.
> **iOS files:** `Pilgrim/Scenes/ActiveWalk/ActiveWalkViewModel+Seek.swift` (engine boot, event routing, arrival persistence, seekAnew, reveal whisper, fog feed, `SeekSenses`), `Pilgrim/Scenes/ActiveWalk/WalkOptionsSheet.swift` (seek section, 85373c1 end-state), `Pilgrim/Scenes/ActiveWalk/ActiveWalkView.swift:272-277` (sheet call site), `Pilgrim/Models/LS.swift:141-183` (strings), `UnitTests/Seek/ActiveWalkSeekTests.swift`.
> **Android files:** `walk/seek/SeekOrchestrator.kt` (new), `di/SeekModule.kt` (new), `ui/walk/SeekWalkViewModel.kt` (new), `ui/walk/WalkOptionsSheet.kt`, `ui/walk/ActiveWalkScreen.kt`, `PilgrimApp.kt`, plus upstream signal additions: `location/LocationSource.kt` + `FusedLocationSource.kt` (`rawLocationFlow`), `data/whisper/WhisperPlayer.kt` (`isAnyChannelPlaying`, `isAvailable`), `audio/VoiceRecorder.kt` (`isRecording`), `audio/voiceguide/VoiceGuideOrchestrator.kt` (talk-recording wire), `audio/seek/SeekSoundPlayer.kt` (`SeekSoundPlaying` interface), `walk/seek/SeekSessionStore.kt` (+`intention`), `ui/seek/SeekSetupViewModel.kt`, `domain/seek/SeekEngine.kt` (comment fix), `res/values/strings.xml`.
> **Builds on:** U3 engine spec (`…port-seek-engine-u3.md` B15 scope-confinement contract), U5 audio spec (`…port-seek-audio-u5.md` §2.3 gate table, §6 non-goals), U6 fog spec (dispatcher note), U7 crescent spec (dispatcher note), U8 setup spec (B7 `SeekSessionStore` handoff).

---

## B1. Ownership shape — iOS view model → Android app-scoped singleton

**iOS** hangs the whole seek session off `ActiveWalkViewModel` (constructed per walk): the engine boots inside the VM at GPS lock (`ActiveWalkViewModel+Seek.swift:72-119`), events route through `handleSeekEvent` (`:132-157`), and teardown rides `vm.stop()` → `teardownSeek()` (`:121-126`).

**Android:** the walk state machine spans two processes and the walk screen's `WalkViewModel` is NavBackStackEntry-scoped, so the session owner is an app-scoped `@Singleton SeekOrchestrator` (plan U9), instantiated eagerly from `PilgrimApp.onCreate` via the `Provider<T>` pattern and started explicitly (`VoiceGuideOrchestrator` precedent — visible, cancellable subscription; no `init { launch }`).

**Process topology (D1):** `PilgrimApp.onCreate` early-returns in the `:tracker` process, so the orchestrator — like `MeditationBellObserver`, `VoiceGuideOrchestrator`, and `WalkFinalizationObserver` — lives in the **UI (main) process** and observes `WalkController.state`, which Hilt resolves per process (`di/WalkModule.kt:41-51`) to `UiWalkController`'s Room-derived StateFlow there. The engine rides the same process (U3 spec B15: engine state is confined to the orchestrator's scope). Every sense the orchestrator drives (SeekSoundPlayer focus session, SeekHaptics, WhisperPlayer, the map renderers, SeekSessionStore, the options sheet) is a UI-process singleton, so this is the only topology that doesn't require a cross-process seek-state channel. Consequences, accepted and documented:

- The engine's GPS feed is a UI-process FLP subscription (B2). Background delivery while the screen is off is entitled at the **UID level** by the `:tracker` FGS with `foregroundServiceType="location"` — Android's background-location carve-out is per-app, not per-process (the manifest's own comment: "our foreground service with foregroundServiceType=location covers the entire walk lifecycle"). The screen-off sonar is a standing item on the Phase 14 pocket QA (R12).
- A UI-process death mid-walk (OEM o-kill) ends seek guidance but never recording — the walk continues in `:tracker`, its SEEK_MODE/SEEK_ARRIVAL events and waypoints persist, and the restored walk never reboots the engine. This mirrors iOS exactly (the chain lives only in the VM there, and is never persisted — plan scope: "No live-seek resume after process death").

## B2. Location feed — `rawLocationFlow` (D2)

`FusedLocationSource.locationFlow()` drops accuracy > 20 m at the source (`FusedLocationSource.kt:186,201` — `DESIRED_ACCURACY_METERS = 20f`). The engine's arrival debounce and stillness detector accept fixes up to **50 m** (`SeekEngineTuning.ARRIVAL_ACCURACY_METERS`, `SeekStillnessDetector.ACCURACY_GATE_METERS`) — feeding the engine the walk pipeline's gated flow would starve arrival/stillness in degraded GPS (20–50 m fixes never arrive). iOS has no such gate on the engine's feed: `seekLocationFixes` mirrors `$currentLocation` raw (`ActiveWalkViewModel+Seek.swift:292-307`).

**Android:** `LocationSource` gains `fun rawLocationFlow(): Flow<LocationPoint> = locationFlow()` (interface default so every existing fake stays source-compatible); `FusedLocationSource` overrides it with a truly unfiltered variant of the same callback flow (no 20 m gate, no first-sample anchor special-case — the engine applies its own gates). The walk pipeline keeps its 20 m gate untouched. The Room-derived route feed was rejected as an engine source: samples are already 20 m-gated at the tracker AND `RouteDataSample` carries no bearing, which would permanently disable alignment (B9 of the U3 spec).

## B3. Boot key + restore-path filter

**iOS** boots the engine from the GPS lock during setup (`ActiveWalkViewModel+Seek.swift:72-119`); a restored walk constructs a fresh VM that never runs `bindSeekLifecycle`'s transition hook, so no reboot.

**Android:** U8 split setup (chain generation → `SeekSessionStore.pending`) from engine boot. The orchestrator boots **only** on:

```
WalkState.Active  &&  walk.mode == Seek  &&  engine == null  &&  sessionStore.pending.value != null
```

and **consumes** the pending session at boot (`sessionStore.clear()`), which makes the filter inherently restore-safe (D3):

- Cold start / UI-process restart into a live seek walk: `SeekSessionStore` is in-memory and fresh → `pending == null` → no boot, no fog, no sound; the walk keeps recording (pinned by test).
- Terminal transitions clear any un-consumed session (`WalkLifecycleObserver` U8 clear, kept as-is; the orchestrator additionally clears at its own teardown — both idempotent).
- The UI process's Room-derived state can briefly read `mode == Wander` before the SEEK_MODE event row lands (`UiWalkController.buildActiveState` comment); the boot condition simply fires on the next emission once the row arrives.

No separate first-emission latch is needed — the pending-session key subsumes it (the first emission after app start is `Idle`, and any restored in-progress emission fails the `pending != null` arm).

**Conflated-terminal defense (Android-only):** StateFlow conflation can elide walk A's terminal emission between two walks under contention — the exact race class `WalkLifecycleObserver` documents. A live engine observing a different walk's in-progress state is therefore treated as a stale session and torn down (keeping the store's pending session, which belongs to the NEW walk) before the boot check runs, so arrivals can never persist to the old walk. iOS has no analogue (a fresh VM per walk makes the race unrepresentable); pinned by test.

`WalkState.Paused`/`Meditating`/`Active` transitions are NOT the orchestrator's concern — the engine's own `walkStates` collector implements suspension (U3 spec B13). Teardown fires on `Finished` and `Idle` while an engine exists (iOS `teardownSeek` from `vm.stop()`).

## B4. Engine boot (iOS `startSeekEngine` → `bootEngine`)

**iOS** (`ActiveWalkViewModel+Seek.swift:87-118`):

```swift
let engine = SeekEngine(chain: chain)
let sound = seekSenses.makeSoundPlayer()
sound.prepare()
seekSound = sound
engine.bind(
    locations: seekLocationFixes,
    stepCounts: builder.stepsPublisher.compactMap { $0 }.eraseToAnyPublisher(),
    builderStatus: builder.statusPublisher,
    powerTier: sessionGuard?.powerTierPublisher
        ?? Just(WalkSessionGuard.PowerTier.normal).eraseToAnyPublisher()
)
engine.events
    .sink { [weak self] event in self?.handleSeekEvent(event) }
    .store(in: &seekCancellables)
engine.$chain
    .combineLatest(engine.$activeIndex, engine.$phase, engine.$distanceToActiveMeters)
    .sink { [weak self] chain, activeIndex, phase, distance in
        self?.updateSeekFog(chain: chain, activeIndex: activeIndex, phase: phase, distance: distance)
    }
    .store(in: &seekCancellables)
seekEngine = engine
```

**Android claims, one-for-one (with the U8 chain-source difference — the chain arrives pre-generated in the pending session, D3):**

- Consume the pending session; capture `sessionWalkId` (from the Active accumulator), `sessionIntention` (session), `tintHex = session.tint?.fogHex` (iOS `seekTint?.fogHex`, `ActiveWalkViewModel+Seek.swift:281`), reset `previousActiveBucket`.
- Construct the engine with the orchestrator's own scope/clock, `locations = rawLocationFlow().onEach { latestFix = it }` (SecurityException defensively caught-and-logged — unreachable behind U8's accuracy gate), `walkStates` = the observed controller StateFlow (replays current `Active`; a no-op for an unsuspended engine), `powerTiers = SeekPowerTierSource.tiers` (cold callbackFlow — the engine collects it once per session in `start()` and cancels it in `stop()`, so the receiver never leaks). No step flow (U3 spec D3).
- `senses.soundPlayer.prepare()` before events can arrive (iOS order: sound armed before `engine.bind`).
- Events collector + fog combine (`chain × activeIndex × phase × distanceToActiveMeters`) launched on the orchestrator scope BEFORE `engine.start()` — nothing is missed; iOS's main-confined synchronous sinks map to the single-threaded scope (B10).
- The engine's initial `COMPLETE` phase for an empty chain is impossible here (U2's generator never returns an empty chain), but harmless: the fog combine would emit an all-halo state and the engine is inert.

**Scope/threading (D4):** the orchestrator's Hilt scope is `CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))` — a single-threaded view of Default. That satisfies the U3 engine contract ("build the scope on a single-threaded dispatcher, call `seekAnew`/`stop` from it": both are dispatched via `scope.launch`) AND the U6/U7 dispatcher note ("U9's orchestrator computes the fog state on `Dispatchers.Default`"). The renderers stay main-thread-only: fog/pulse cross to the UI as StateFlows collected by composition, and `SeekFogRenderer.apply` runs inside `PilgrimMap`'s `AndroidView.update` on main.

## B5. Event routing (iOS `handleSeekEvent` → `handleSeekEvent`)

**iOS** (`ActiveWalkViewModel+Seek.swift:132-157`):

```swift
case .pulse(let aligned, let distanceMeters):
    let closeness = SeekEngine.closeness(forDistanceMeters: distanceMeters)
    seekPulse = SeekPulseVisual(token: seekPulse.token + 1, aligned: aligned, closeness: closeness)
    seekSound?.playPing(aligned: aligned, closeness: closeness)
    fireSeekHaptic(aligned ? .seekAligned(closeness: closeness) : .seekTick(closeness: closeness))
case .arrived:
    // The persistence commit happens before any ritual effect so an
    // interruption mid-ritual can never lose the arrival.
    recordSeekArrival()
    fireSeekHaptic(.seekArrival)
case .stillnessBegan:
    fireSeekHaptic(.seekBreathIn)
case .revealedNext:
    seekSound?.playBowl()
    scheduleSeekRevealWhisper()
case .seekComplete:
    seekSound?.playBowl()
    scheduleSeekSoundRelease()
```

**Android claims:**

- **Pulse:** `closeness = SeekEngine.closeness(distanceMeters)` (Double); `SeekPulseVisual(token = previous + 1, aligned, closeness)` — Double for the visual, `.toFloat()` for `playPing` (review B2 conversion contract). Tokens therefore start at 1 and increase monotonically per session — the renderer initializes `lastHandledPulseToken = 0` and would swallow a token-0 first pulse. The tick/aligned haptic is NOT fired here: U5 moved the audio-coupled haptic into `SeekSoundPlayer.playPing` (U5 spec §3.3 — fires on play or policy-skip, never on platform-reject), so one `playPing` call carries both senses.
- **Arrived:** `recordSeekArrival()` is awaited BEFORE `senses.arrivalHaptic()` — persist-before-ritual ported verbatim (B6). A persistence failure logs and skips the ritual for that arrival (the defensive collector catch, B10); it can never crash the walk.
- **StillnessBegan:** `senses.breathInHaptic()`.
- **RevealedNext:** `soundPlayer.playBowl()` then `scheduleRevealWhisper()` (B7). The fog reveal itself needs no orchestration — the engine's phase/activeIndex StateFlows re-fire the fog combine.
- **SeekComplete:** `soundPlayer.playCompletionBowl()` — U5 moved iOS's VM-owned `scheduleSeekSoundRelease` (generation-guarded stop 4.5 s after the bowl, `ActiveWalkViewModel+Seek.swift:159-172`) into the player, so one call covers bowl + release. The engine already stopped itself; the final fog emission (all halos, phase COMPLETE) persists on the map until walk end, and `enginePhase` stays COMPLETE for the options sheet's disabled row.
- **Haptics foreground gate dropped (D5):** iOS's `fireSeekHaptic` routes through `seekSenses.isAppActive()` (`:264-267`) because iOS discards background CoreHaptics. Android's `Vibrator` works screen-off and pocket walking is the primary use case — plan Key Decision; `SeekSenses` carries no `isAppActive`.

## B6. Arrival persistence (iOS `recordSeekArrival` → `recordSeekArrival`)

**iOS** (`ActiveWalkViewModel+Seek.swift:179-190`):

```swift
/// The ordinal counts arrivals already persisted this walk rather than
/// echoing the engine's clearing index: after "Seek anew" from inside an
/// unrevealed clearing, the replacement clearing replays the same index,
/// which would duplicate labels and inflate the unknowns-found count.
private func recordSeekArrival() {
    builder.addWorkoutEvent(TempWalkEvent(uuid: nil, eventType: .seekArrival, timestamp: Date()))
    let ordinal = waypoints.filter(SeekPersistence.isArrivalWaypoint).count + 1
    addWaypoint(
        label: SeekPersistence.arrivalWaypointLabel(clearingOrdinal: ordinal),
        icon: SeekPersistence.arrivalWaypointIcon
    )
}
```

**Android (D6):** iOS's in-memory builder writes are synchronous; Android's UI→tracker intent path (`WalkController.recordWaypoint`) is fire-and-forget and cannot be awaited, which would break persist-before-ritual. The orchestrator therefore writes Room **directly** — the same `WalkRepository.recordEvent` / `WalkRepository.addWaypoint` calls the tracker's own effect handler and `recordWaypoint` ride (`WalkControllerImpl.kt:502`, `:234-243`), from the UI process (precedent: `UiWalkController.recoverStaleWalks` finalizes rows from the UI process; Room multi-instance invalidation keeps both processes coherent):

- `recordEvent(WalkEvent(walkId = sessionWalkId, timestamp = clock.now(), eventType = SEEK_ARRIVAL))`.
- Ordinal = `SeekPersistence.arrivalOrdinal(repository.waypointsFor(walkId).map { it.icon })` — counted from **persisted icons**, never the engine index (reroll replays indices; iOS comment ported).
- `addWaypoint(Waypoint(walkId, clock.now(), lat/lon, label = SeekPersistence.arrivalWaypointLabel(resources, ordinal), icon = ARRIVAL_WAYPOINT_ICON))`. Position = the orchestrator's `latestFix` (the engine's own feed — three ≤ 50 m fixes inside the region guarantee one), falling back to the accumulator's `lastLocation`; both absent (unreachable) → the waypoint is skipped with a log, the event still lands.

Both writes complete before the arrival haptic fires.

## B7. Reveal whisper (iOS `scheduleSeekRevealWhisper` → `scheduleRevealWhisper`)

**iOS** (`ActiveWalkViewModel+Seek.swift:244-260`):

```swift
private func scheduleSeekRevealWhisper() {
    guard UserPreferences.soundsEnabled.value else { return }
    seekGeneration += 1
    let generation = seekGeneration
    DispatchQueue.main.asyncAfter(deadline: .now() + seekSenses.revealWhisperDelay) { [weak self] in
        guard let self, self.seekGeneration == generation else { return }
        guard let whisper = self.seekSenses.pickRevealWhisper() else { return }
        self.seekSenses.playWhisper(whisper)
    }
}

static func randomDownloadedRevealWhisper() -> WhisperDefinition? {
    let whispers = WhisperManifestService.shared.manifest?.whispers ?? []
    return whispers
        .filter { $0.retiredAt == nil && WhisperPlayer.shared.isAvailable($0) }
        .randomElement()
}
```

**Android claims, one-for-one:** master-sounds guard at schedule time; `whisperGeneration` bumped so a superseding reveal, completion, or teardown cancels the pending play (`revealWhisperDelay = 2.5` s, `ActiveWalkViewModel+Seek.swift:26`); after the delay, one whisper from `pickRevealWhisper()` — production default filters `manifest.value?.whispers` on `isActive` (≙ `retiredAt == nil`) AND `whisperPlayer.isAvailable(it)` (locally downloaded), `randomOrNull()`; none available → bowl-only, the ritual proceeds. `playWhisper` default = `whisperPlayer.play(definition)` (the main channel — same as iOS `WhisperPlayer.shared.play`). The disk-touching availability filter runs on the orchestrator's Default-backed scope, never Main.

**Upstream addition:** `WhisperPlayer.isAvailable(definition)` — cached-file check (`filesDir/whispers/<audioFileName>.aac` exists non-empty, the exact `ensureCached` target) — did not exist on Android; iOS's `WhisperPlayer.shared.isAvailable` is quoted above as its contract.

## B8. Seek anew (iOS `seekAnewRequested` → `seekAnewRequested`)

**iOS** (`ActiveWalkViewModel+Seek.swift:192-210`):

```swift
/// R17 "Seek anew": regenerates the remainder of the chain from the
/// walker's current position. Uncapped by design. A reroll re-asks —
/// the same intention, a new moment — so it is seeded like the
/// original generation.
func seekAnewRequested() {
    guard let engine = seekEngine else { return }
    let point: SeekPoint
    if let sample = currentLocation {
        point = SeekPoint(latitude: sample.latitude, longitude: sample.longitude)
    } else if let last = routeCoordinates.last {
        point = SeekPoint(latitude: last.latitude, longitude: last.longitude)
    } else {
        return
    }
    engine.seekAnew(currentLocation: point, seed: SeekSeed.make(intention: intention, fix: currentLocation))
}
```

**Android:** `SeekOrchestrator.seekAnewRequested()` dispatches onto the orchestrator scope (engine confinement, D4). Live engine → `engine.seekAnew(point, SeekSeed.make(sessionIntention, clock.now(), latestFix))` — position from `latestFix`, falling back to the accumulator's `lastLocation` (≙ iOS `routeCoordinates.last`), neither → no-op. The engine emits the immediate stale-distance pulse itself (ece26a7, U3 spec B14) — one ping, one haptic, one ring the moment the new clearing exists. `sessionIntention` is captured at boot from the pending session (D7) — the same string iOS reads from `vm.intention`.

**Pre-departure reroll (D8):** on iOS the engine exists from the gateway's GPS lock, so 85373c1's pre-walk sheet reroll drives the live engine. On Android the engine boots at walk start (U8/U9 split), so a pre-departure "Seek anew" (setup Ready, walk not yet started) has no engine. The orchestrator instead regenerates the **pending session's** chain: `pending.chain.regeneratingRemainder(fromActiveIndex = 0, current = lastKnownLocation(), budget = max(chain.budgetMeters, REROLL_MIN_BUDGET_METERS), rng = SeekSeededGenerator(SeekSeed.make(pending.intention, now, fix)))` → `sessionStore.set(copy(chain =…))`. No immediate pulse is possible (no engine, no armed sonar channel) — the sheet dismissal is the feedback; flagged as a conscious divergence.

To seed both reroll paths like iOS, `SeekPendingSession` gains `intention: String? = null` (U8's `SeekSetupViewModel.lockChain` populates it from the captured intention).

## B9. Fog-state feed (iOS `updateSeekFog` → `updateFog`)

**iOS** (`ActiveWalkViewModel+Seek.swift:269-290`):

```swift
private func updateSeekFog(chain: SeekChain, activeIndex: Int, phase: SeekEnginePhase, distance: Double?) {
    let state = SeekFogModel.fogState(
        chain: chain, activeIndex: activeIndex, phase: phase,
        distanceToActiveMeters: distance,
        previousActiveBucket: previousActiveFogBucket,
        tintHex: seekTint?.fogHex,
        walkerPosition: currentLocation.map { SeekPoint(latitude: $0.latitude, longitude: $0.longitude) }
    )
    previousActiveFogBucket = state.activeFogBucket
    if state != seekFogState { seekFogState = state }
}
```

**Android claims, one-for-one:** the fog combine (B4) calls `SeekFogModel.fogState` with `previousActiveBucket` fed back from the previous state's `activeFogBucket` (hysteresis reference), `tintHex` fixed per walk from the pending session's tint, `walkerPosition = latestFix`. Distinct-value write into `_fogState: MutableStateFlow<SeekFogState?>` (≙ `if state != seekFogState`). The same collector mirrors `phase` into `_enginePhase` (options-sheet + U10 seam). Computation runs on the orchestrator's Default-backed thread (U6 dispatcher note); `null` at teardown returns wander behavior (renderer's `null == null` fast path).

**Screen feed:** `ActiveWalkScreen` collects `SeekWalkViewModel.fogState`/`pulse` — direct hot-singleton passthroughs of the orchestrator flows (Stage 5-G rule: no `WhileSubscribed` between a singleton and a consumer that must not see stale values) — and passes them to the previously-unfed `PilgrimMap(seekFog =, seekPulse =)` parameters. Teardown's `apply(null, NONE)` pass also resets the renderer's `lastHandledPulseToken` to 0, so the next session's token 1 fires.

## B10. Defensive collects + REVEALING

- Every long-lived collector body (walk-state observer, events collector, fog combine) wraps its work in `try/catch` re-throwing `CancellationException` — one throwing routee must not silently kill seek routing for the process lifetime (Stage 5-D; pinned by the throwing-whisper test).
- **REVEALING (D9):** iOS never produces `SeekEnginePhase.revealing` — repo-wide, the only references are the enum case (`SeekGlance.swift:28`), the engine's phase-guard `case .revealing, .complete: break` (`SeekEngine.swift:273`), and defensive fog/wisp tests. The orchestrator does NOT park the visual phase there either (iOS's `updateSeekFog` passes the engine phase straight through). Android matches: never produced; the stale "(U7)" comment on `SeekEngine.kt:41-45` is corrected to say the value is an iOS-parity reserve handled defensively by the fog/crescent models.

## B11. Options sheet — seek section (85373c1 end-state)

**iOS** (`WalkOptionsSheet.swift:73-137@c1745e8`):

```swift
// The seek is already alive on the ready screen — the engine boots at
// the gateway, fog and crescent are on the map — so its controls appear
// as soon as the engine exists (self-gated via isSeekActive), letting
// the walker check the sonar or reroll before stepping off.
// Wander pre-walk renders nothing here.
seekSection
...
/// Seek-only controls (R11 sonar mirror + R17 reroll). Present for the
/// whole seek — after `seekComplete` the section stays visible with the
/// reroll row disabled (Traces-row precedent), so the layout holds still
/// and the quiet control confirms the seeking is complete.
@ViewBuilder
private var seekSection: some View {
    if isSeekActive {
        VStack(alignment: .leading, spacing: 4) {
            Text(LS.seekSectionTitle) ...
            sonarToggleRow
            if sonarEnabled { sonarVolumeRow }
            optionRow(
                icon: "arrow.triangle.2.circlepath",
                title: LS.seekAnewTitle,
                subtitle: isSeekComplete ? LS.seekAnewCompleteSubtitle : nil
            ) { onSeekAnew?() }
            .disabled(isSeekComplete)
            .opacity(isSeekComplete ? 0.4 : 1.0)
        }
    }
}
```

Call site (`ActiveWalkView.swift:272-277`): `isSeekActive: viewModel.seekEngine != nil`, `isSeekComplete: viewModel.isSeekComplete`, `onSeekAnew: { showOptions = false; viewModel.seekAnewRequested() }`.

**Android claims:**

- Section placed after the intention/waypoint rows, before Traces (iOS order), self-gated on an active seek session. Gate: `enginePhase != null || pendingSession != null` — iOS's `seekEngine != nil` covers pre-departure because its engine boots at the gateway; Android's pre-departure session is the pending chain (D8), so the gate ORs both. Wander renders nothing.
- Sonar toggle row (`seek_sonar_title` = "Sonar") + volume slider row (`seek_sonar_volume_title` = "Sonar Volume") shown only while the toggle is on (iOS `if sonarEnabled`). Both **live-mirror** `SeekPreferencesRepository` — collected StateFlows down, setter callbacks up (D10: iOS snapshots into `@State` at sheet init + writes on change; Android's repository StateFlows keep the sheet live against external writes, e.g. U13's settings screen). The slider uses the house local-drag pattern (write once on `onValueChangeFinished` — SoundSettings `VolumeRow` precedent).
- "Seek Anew" row (`seek_anew_title` = "Seek Anew"): tap → `onDismiss()` + `seekAnewRequested()` (iOS dismisses then rerolls). After `seekComplete` the row stays visible, disabled, subtitle `seek_anew_complete_subtitle` = "The seeking is complete"; disabled styling rides the house `OptionRow(enabled=)` convention (fog-tinted icon/title — same treatment the whisper/stone rows use; iOS uses `.opacity(0.4)`).
- Strings verbatim from `LS.swift:141-183`: section "Seek", toggle "Sonar", slider "Sonar Volume", row "Seek Anew", subtitle "The seeking is complete". `LS.seekSonarSettingsCaption` is U13's (settings screen).
- iOS's `.contextMenu`-free plain rows port to the existing `OptionRow`; the toggle is an M3 `Switch` (iOS `Toggle` tinted stone).

## B12. Orphaned strings (review C3) — deleted

`seek_event_seek_mode` ("Seek") / `seek_event_arrival` ("Clearing reached") were added for iOS `EventType.description` parity. At `c1745e8` that `description` (`WalkEvent.swift:66-81` → `SeekPersistence.seekModeEventName`/`seekArrivalEventName`, `SeekPersistence.swift:32-42`) has **no live display surface** — repo-wide, event types are only used for filtering (`HomeViewModel.swift:142`, `SeekSummarySection.swift:183,229`) and wire mapping (`PilgrimPackageConverter.swift:84` uses `workoutEventTypeString`, not `description`). Android has zero consumers. Both strings are deleted; if a future surface renders event names, they return with it.

## B13. Share map (review B6) — parity, unchanged

iOS's share payload passes **all** waypoints unfiltered — arrival waypoints included, icon `"sun.haze"` and all (`WalkShareViewModel.swift:282-293`); halo rendering of clearings on the shared page is deferred to the worker repo on both platforms (plan scope: "No share-worker clearing halos (iOS deferred these to the worker repo too)"). Android's `SharePayloadBuilder` already does exactly the same (`SharePayloadBuilder.kt:150-151`). No change; documented as parity.

## B14. Upstream signal additions (U5 spec §2.3 gate table)

| Gate axis | iOS source | Android binding (this unit) |
|---|---|---|
| whisper playing | `AudioPriorityQueue.shared.isPlayingWhisper` | **New** `WhisperPlayer.isAnyChannelPlaying: StateFlow<Boolean>` — true while either the main (`playPlayer`) or preview channel holds a player; updated at every player-lifecycle point (start, stop, completion, error). The existing `isPlaying` (preview-only, drives the placement sheet's stop button) is untouched. |
| voice-guide prompt playing | `VoiceGuidePlayer.shared.isPlaying` | `VoiceGuidePlayer.state.value is State.Playing` (existing). |
| talk recording active | `coordinator.currentMode ∈ {recordingOnly, recordAndPlay}` | **New** `VoiceRecorder.isRecording: StateFlow<Boolean>` — recorder-level singleton flow (set on successful `start()`, cleared in the shared `finalizeSession` teardown that both user-stop and focus-loss interruption route through). Solves the `VoiceGuideOrchestrator.kt:70-74` gap once: exposed as a `@TalkRecordingActive StateFlow<Boolean>` binding consumed by BOTH the seek ping gate and `VoiceGuideOrchestrator`'s previously-hardcoded `isRecordingVoice = false` (its "wire it here" note). |

`SeekSoundPlayer` + `SeekPingGate` get their Hilt wiring in `di/SeekModule.kt` (U5 spec §6 deferred this to U9). `SeekSoundPlayer` implements a new `SeekSoundPlaying` interface (≙ iOS's protocol, `ActiveWalkViewModel+Seek.swift:8-15`, plus `playCompletionBowl`) so orchestrator tests can spy the ritual audio without touching the audio session — same seam iOS built for the same reason.

## Divergence table

| # | iOS @c1745e8 | Android U9 | Why |
|---|---|---|---|
| D1 | Session owner = per-walk `ActiveWalkViewModel`; engine in the app's only process | `@Singleton SeekOrchestrator`, UI process only (PilgrimApp eager Provider; `:tracker` early-returns); observes the process-local `WalkController.state` (UiWalkController, Room-derived) | Two-process split; every sense is a UI-process singleton; MeditationBellObserver/VoiceGuideOrchestrator topology mirrored exactly |
| D2 | Engine feed mirrors `$currentLocation` raw | New `LocationSource.rawLocationFlow()` (unfiltered) collected in the UI process; UID-level FGS-location entitlement covers screen-off | `locationFlow()`'s 20 m gate starves the engine's 50 m arrival/stillness gates; route samples carry no bearing |
| D3 | Engine boots at GPS lock inside setup; chain never leaves the VM | Boot at walk start on (Active + mode Seek + pending session), consuming `SeekSessionStore.pending` | U8/U9 split; consumption makes the restore filter structural — an in-memory store is empty after process death, so restored walks can never reboot (plan Key Decision) |
| D4 | Engine main-confined; sinks fire synchronously on main | Single-threaded `Dispatchers.Default.limitedParallelism(1)` scope owns engine + routing + fog math; UI crossings are StateFlows | U3 B15 confinement contract + U6/U7 "fog on Default" dispatcher note; keeps Mapbox writes main-only |
| D5 | `fireSeekHaptic` gated on `isAppActive()`; tick/aligned fired by the VM per pulse | No foreground gate anywhere; tick/aligned ride `playPing`'s coupled path (U5 §3.3) | Plan Key Decision (background CoreHaptics is an iOS platform limit; Android pocket walks are the primary case); U5 moved the audio-haptic coupling into the player |
| D6 | Arrival persists via synchronous in-memory `builder.addWorkoutEvent` + `addWaypoint` | Direct awaited `WalkRepository.recordEvent` + `addWaypoint` from the orchestrator (same rows the tracker's own paths write) | The UI→tracker intent path is fire-and-forget — unawaitable persist-before-ritual; Room multi-instance invalidation keeps processes coherent (UiWalkController precedent) |
| D7 | Reroll seed reads `vm.intention` | `sessionIntention` captured at boot from `SeekPendingSession.intention` (new field, set by U8's `lockChain`) | The orchestrator has no VM; the pending session is the intention's natural carrier (it already seeded the original chain) |
| D8 | Pre-departure "Seek anew" drives the live engine (boots at gateway) and pulses | Pre-departure reroll regenerates the pending session's chain (`regeneratingRemainder(0, lastKnownLocation, …)`), silently — no engine, no pulse; in-walk reroll pulses (ece26a7) | Engine boots at walk start on Android; a visible-but-dead row would be worse than a silent regenerate; sheet dismissal is the feedback |
| D9 | `SeekEnginePhase.revealing` reserved, never produced | Same — never produced; stale `SeekEngine.kt` comment corrected | Match shipped iOS behavior; fog/crescent models already handle it defensively |
| D10 | Sheet snapshots sonar prefs into `@State` at init; writes on change | Live StateFlow mirror of `SeekPreferencesRepository` + setter callbacks | Repository StateFlows are the Android source of truth; keeps the sheet coherent with U13's settings mirror |
| D11 | `randomDownloadedRevealWhisper` via `WhisperManifestService.shared` + `WhisperPlayer.shared.isAvailable` | `manifest.value?.whispers` filtered on `isActive` + new `WhisperPlayer.isAvailable` (cache-file check) | Same contract; Android needed the availability surface added |
| D12 | `seek.event.seek_mode` / `seek.event.arrival` exist behind `CustomStringConvertible` | Android strings deleted | No live display surface on either platform (B12) |
| D13 | — | `VoiceGuideOrchestrator.isRecordingVoice` wired to `@TalkRecordingActive` | Solve-once with the ping gate's recorder signal (its own TODO note) |

## Test parity map (`UnitTests/Seek/ActiveWalkSeekTests.swift@c1745e8` → Android)

| iOS test | Android home |
|---|---|
| `testEngineStartsOnFirstAccurateFix_ignoresPoorFixes` (`:130-146`) | U8 `SeekSetupViewModelTest` (chain lock — Android boots at walk start); orchestrator boot pinned by `boots the engine on a pending seek session and consumes it` |
| `testGPSLockFailure_*` / `testGPSTimeout_*` (`:148-184`) | U8 `SeekSetupViewModelTest` (setup owns the lock on Android) |
| `testArrival_recordsExactlyOneWaypointAndOneArrivalEvent` (`:188-200`) | `SeekOrchestratorTest.two clearing walk drives the full sensory contract in order` (event + waypoint + label + icon asserted against real Room) |
| `testSeekMarker_writtenOnceAtRecordingStart` / `testWander_writesZeroEventsAtRecordingStart` (`:204-223`) | U8 reducer/controller SEEK_MODE tests (shipped) |
| `testSeekAnew_whileArrived_recordsSequentialOrdinals` (`:227-253`) | `seek anew keeps ordinal continuity via persisted icons` |
| `testSeekAnew_regeneratesRemainder_prefixStable` (`:255-273`) | engine-level (U3 `SeekEngineTest`); orchestrator asserts reroll never touches recorded arrivals |
| `testPulseEvent_incrementsTokenAndPlaysPing` (`:277-293`) | `pulse events advance the token from one and play the ping` |
| `testRevealedNext_playsBowlThenWhisperAfterDelay` (`:297-308`) | `revealed next plays the bowl then the whisper after the delay` |
| `testRevealedNext_zeroDownloadedWhispers_bowlOnly` (`:310-319`) | `no downloaded whisper leaves the reveal bowl-only` |
| `testSeekComplete_playsBowlWithoutWhisper` (`:321-330`) | `seek complete plays the completion bowl without a whisper` |
| `testSeekComplete_releasesSoundOnceBowlHasRung` (`:332-341`) | U5 `SeekSoundPlayerTest` (release lives in `playCompletionBowl`) |
| `testRevealedNext_soundsDisabled_suppressesWhisperAndPing` (`:347-356`) | `master sounds off suppresses the reveal whisper` |
| `testStop_stopsEngineAndSound_cancelsPendingWhisper_noEventsAfter` (`:360-378`) | `walk end tears down the engine, sound, fog, and pending whisper` |
| `testDeinit_releasesViewModelAndEngine` (`:380-391`) | no analogue (structured concurrency; teardown-silence test covers it — U3 spec D6 precedent) |
| `testWander_neverGrowsSeekState` (`:395-409`) | `wander walk lifecycle produces zero seek side effects` |
| — (Android-only) | `active seek walk without a pending session never boots` (restore path); `a throwing whisper routee does not kill event routing`; `arrival fog dissolves and reveal leaves a halo through the real renderer` (review B7 end-to-end against `SeekFogRenderer` + fake style); pre-departure pending reroll; `VoiceRecorder.isRecording` lifecycle; `WhisperPlayer.isAnyChannelPlaying` / `isAvailable`; options-sheet section rendering (`WalkOptionsSheetTest`) |

## U10 seam (glance — out of scope here, noted)

iOS computes the Live Activity glance in the VM (`currentSeekGlance()`, `ActiveWalkViewModel+Seek.swift:219-236`) from engine phase + distance + bearing-to-clearing + course/speed. On Android the notification renders in the **`:tracker`** process (`WalkNotificationFactory`), which cannot read the UI-process orchestrator directly. U9 exposes the inputs on the orchestrator (UI process): `enginePhase: StateFlow<SeekEnginePhase?>`, the live engine's `distanceToActiveMeters`/`chain`/`activeIndex` (internal), and `latestFix` (course + speed). U10 must add the glance model (pure, shared) plus a UI→tracker transport for glance updates — the `WalkActionPublisher` intent channel is the established precedent — throttled to glance-state changes per the plan's Samsung-suppression rule.

## Device QA additions (Phase 14 consolidated pass)

1. Screen-off pocket seek: sonar ping + tick haptic keep arriving with the UI backgrounded ≥ 10 min (UID-level FGS location entitlement, D1/D2).
2. Options sheet pre-departure (seek Ready, walk not started): section visible, sonar toggle + volume live, Seek Anew regenerates quietly.
3. In-walk Seek Anew: sheet dismisses, immediate ping + haptic + ring (ece26a7).
4. Arrival: three-tap haptic AFTER the waypoint appears in the summary data (kill-app-right-after-arrival test: arrival survives).
5. Reveal: bowl, then one downloaded whisper ~2.5 s later; airplane-mode fresh install → bowl only.
6. Completion: bowl rings fully, sonar goes quiet, options row reads "The seeking is complete" disabled.
7. Force-stop the app mid-seek, relaunch into the live walk: walk recording, NO fog/crescent/sonar (restore filter), summary still shows persisted arrivals.
