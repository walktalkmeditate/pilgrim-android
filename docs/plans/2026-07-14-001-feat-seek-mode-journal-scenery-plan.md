---
title: "feat: Seek Mode + Journal Scenery (iOS c1745e8 parity)"
type: feat
status: active
date: 2026-07-14
origin: docs/brainstorms/2026-07-14-ios-main-parity-retarget-requirements.md
---

# feat: Seek Mode + Journal Scenery (iOS c1745e8 parity)

## Summary

Port iOS Seek Mode (v1.8.0) and the 1.8.1 journal ink-scroll scenery to Android as 17 dependency-ordered units in five delivery phases (A–E) plus a release step, each ported from the end-state Swift at `c1745e8` behind a per-unit `/ios-parity port` spec. The port is smaller than the iOS build was: Android already has the walk-event write path, a Room migration chain (v8), the dark-shipped `WalkMode.Seek` selector, and the complete base scenery layer — the work is the seek engine/senses/experience stack, then the data-driven scenery delta on top of it.

---

## Problem Frame

Android froze parity at iOS v1.6.0 while iOS shipped Seek Mode (v1.8.0) and journal scenery (1.8.1, in Apple review). The origin doc re-pins the anchor to `c1745e8` and defines both ports; this plan is the HOW (see origin: `docs/brainstorms/2026-07-14-ios-main-parity-retarget-requirements.md`).

---

## Requirements

Traced to origin R-IDs:

- R1 (anchor re-pin) → U1
- R2 (bounded fold-in) → process rule, not a unit; noted in Operational Notes
- R3 (gate waiver + live-probe carve-out) → U1 (waiver), Phase 14 QA milestone (probe)
- R4 (end-state port, U-unit boundaries, plan docs cross-checked against shipped code) → all units
- R5 (v1.8.0 seek follow-ups in scope, SHAs enumerated in origin) → U7, U9, U11, U12
- R6 (Live Activity → foreground-service notification content) → U10
- R7 (reuse iOS audio assets) → U5
- R8 (scenery end-state) → U14–U16
- R9 (scenery blocked on seek persistence) → enforced via unit dependencies (U14 depends on U4, U12)
- R10 (per-stage `/ios-parity port` spec before implementation) → execution note on every port unit
- R11 (unit tests + platform-object builder Robolectric rule) → test scenarios per unit; explicit on U5, U10
- R12 (consolidated device QA per feature + mid-phase smoke + carved-out live probes) → QA milestones after U7, U13, U16
- R13 (single v1.2.0 release) → U17

**Origin acceptance examples:** AE1 (covers R2 — fold-in routing), AE2 (covers R9 — scenery blocked on persistence).

---

## Scope Boundaries

- No new runtime permissions: stillness voting ships displacement-primary (iOS's own Motion-&-Fitness-denied fallback); no activity-recognition permission.
- No live-seek resume after process death: the walk restores, its events/waypoints persist, but the chain/fog/engine do not reboot (mirrors iOS — the chain is never persisted).
- No screenshot/demo seeder infrastructure: iOS's seek demo walk, `--demo-journal-stress` seeder, and XCUI scroll diagnostics have no Android counterpart; Robolectric tests pin the same rules instead.
- No share-worker clearing halos (iOS deferred these to the worker repo too).
- No Glance-widget seek content: R6 maps the Live Activity to the foreground-service notification only.
- Anything iOS ships after `c1745e8`, except deltas folded in via the origin's bounded R2 rule.

### Deferred to Follow-Up Work

- Second stillness-vote signal (step-delta) if device QA shows displacement-only voting too eager/slow: future iteration.
- Worker-repo share-render of clearing halos: separate repo, unscheduled.

---

## Context & Research

### Relevant Code and Patterns

- Walk reducer core: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/` (`WalkState`, `WalkAction`, `WalkEffect`, `WalkReducer`); controller `walk/WalkControllerImpl.kt`; UI→service path `walk/UiWalkController.kt` → `walk/WalkActionPublisher.kt` → `service/WalkTrackingService.kt`.
- `domain/WalkMode.kt` — `Seek` already exists with `isAvailable = this == Wander`; selector UI + strings shipped dark in `ui/path/WalkStartScreen.kt`.
- Walk events: `data/entity/WalkEvent.kt`, `domain/WalkEventType.kt`, `WalkEffect.PersistEvent` → `data/WalkRepository.recordEvent` — write path exists.
- Room: `data/PilgrimDatabase.kt` version 8, `exportSchema` on, manual `MIGRATION_2_3`..`MIGRATION_7_8`; tests `app/src/test/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabaseMigrationTest.kt`.
- Proximity precedent: `data/proximity/ProximityDetectionService.kt` (haversine, dedup, location binding).
- Earcon players: `audio/BellPlayer.kt`, `data/cairn/StonePlayer.kt` (per-consumer `AudioFocusRequest`, manual `MediaPlayer()` build); assets in `app/src/main/res/raw/`.
- App-scoped orchestrators: eager `Provider<T>` instantiation in `PilgrimApp.kt` (MeditationBellObserver, VoiceGuideOrchestrator, WalkFinalizationObserver).
- Map: `ui/walk/PilgrimMap.kt` (custom puck bitmap, polyline managers, layer-ordering comment in `loadStyle`, fade-in timeout), `ui/walk/IncrementalRoute.kt`, `ui/walk/ProximityPins.kt`.
- Notification: `service/WalkNotificationFactory.kt` (`walkNotificationText`, `addWalkActionsForState`, both `internal` for testing).
- Summary cards: `ui/walk/summary/` one-file-per-card; narrative card pattern `ui/walk/LightReadingPresenter.kt` + `ui/walk/WalkLightReadingCard.kt`.
- Goshuin: `ui/goshuin/GoshuinMilestone.kt` sealed class + pure `GoshuinMilestones.detect()`; seal pipeline `ui/design/seals/`.
- Journal: `ui/home/WalkSnapshot.kt` (`@Immutable`), `ui/home/dot/WalkDot.kt`, `ui/home/expand/ExpandCardSheet.kt`; scenery layer complete in `ui/home/scenery/` (generator + 7 item types + shapes + `SceneryGeneratorTest`); scroll haptics `ui/home/scroll/` (`HapticEvent`, `ScrollHapticState`, `JournalHapticDispatcher`).
- Celestial math: `core/celestial/` has sunrise/sunset/solar-noon (`SunCalc`) and lunar illumination (`MoonCalc`); **instantaneous solar elevation is new work** — port iOS `CelestialCalculator.solarElevationDegrees` (consumed by U7 and U11).
- Prefs family pattern: `data/practice/PracticePreferencesRepository.kt` + DataStore impl + Hilt module.

### Institutional Learnings

(Memory topic files; `docs/solutions/` is empty — capture Phase 14/15 learnings there after landing.)

- Audio: per-consumer `AudioFocusRequest`; manual `MediaPlayer()` construction (`setAudioAttributes` before `prepare`); `setWillPauseWhenDucked(false)` does NOT auto-duck; haptics gate on audio `start()` success.
- Map: `AndroidView.update` rebuild blocks need structural-snapshot equality gates, reset on style reload; retro-audit existing blocks when adding new state; `loadStyle`-gated UI needs timeout fallback; dark mode reads `LocalPilgrimDarkTheme`, never `isSystemInDarkTheme()`; heavy geometry math off Main.
- Service/state: cached-`:tracker`-process startup snapshots look like fresh transitions — `decideStartAction` gates must survive seek mode; restore paths write state after first emission (observers need restore-path filters, not just first-emission skip); finalize side-effects live in app-scoped observers, never `viewModelScope`.
- Notification: throttle `notify()` to display-rounding buckets (per-second updates trip Samsung suppression).
- Room: nested `withTransaction` is not a savepoint; audit `PilgrimPackageConverter` + share payload for every new persisted vocabulary; migration tests hand-build raw SQLite (see `PilgrimDatabaseMigrationTest` KDoc).
- Compose: `@Immutable` cascades transitively; `viewModelScope.launch` defaults to Main (hop at repository seam); `WhileSubscribed(5s)` wrong for `.value` readers and nav-driving flows.
- Review budget: ~4 adversarial cycles for stages mixing platform-object lifecycle + new state machines; device QA catches what desk review cannot (Stage 2-F, 5-G precedents).

### External References

- None needed — reference implementation is the pinned iOS tree; Mapbox Android 11.11.0 style API (runtime sources/layers, data-driven expressions) is established SDK surface.

---

## Key Technical Decisions

- **End-state port with Android-honest naming**: iOS files named "Wisp" implement the shipped crescent — Android names everything `SeekCrescent*` from the start. Port specs quote the wisp-named Swift but must not reproduce the dead wisp design.
- **No chain persistence / no live-seek resume** (mirrors iOS): seek-ness persists as `SEEK_MODE`/`SEEK_ARRIVAL` walk events + arrival waypoints; the chain lives only in the engine. `SeekOrchestrator` applies a restore-path filter so restored walks never reboot the engine.
- **Persistence = enum extension first, migration only if needed**: `WalkEventType` gains `SEEK_MODE`/`SEEK_ARRIVAL`, plus an `UNKNOWN` fallback protecting v1.2.0+ readers against *future* vocabulary. The shipped v1.1.x converter maps unknown strings to `PAUSED` (an in-place downgrade would skew pause replay) — accepted, since Play blocks in-place downgrades. Arrival waypoints need a reserved marker equivalent to iOS's `"sun.haze"` icon — whether that is an existing `Waypoint` field or a `MIGRATION_8_9` column is decided at implementation after reading the entity; either way the `PilgrimPackageConverter` and share-payload surfaces are audited in the same unit (Stage 12 lesson).
- **Audio = BellPlayer clone**: dedicated seek sound player owning its own `AudioFocusRequest` (GAIN_TRANSIENT_MAY_DUCK), manual `MediaPlayer()` build, iOS assets copied verbatim (R7). Ping suppression remaps iOS's sources to Android's: whisper playback, voice-guide playback, and an active talk recording all suppress pings.
- **Haptics fire regardless of foreground state** — deliberate, re-justified divergence: iOS discards background CoreHaptics; Android's primary use case is screen-off pocket walking, and `Vibrator` works there. Seek guidance stays tactile with the screen off.
- **Stillness voting ships displacement-primary**: iOS's documented Motion-&-Fitness-denied fallback (window ×1.5, displacement <15 m, ≥15 m veto) is the parity anchor; no new permissions (user-confirmed).
- **Fog/crescent/pulse are runtime style layers** — new surface for Android (`PilgrimMap` only used annotation managers so far): per-clearing `CircleLayer`+`GeoJsonSource`, crescent `SymbolLayer` with a pre-rendered arc bitmap, one-shot pulse `CircleLayer`. Equality gates keyed on whole fog state, deferred-update queue behind render pause, and the iOS self-heal `layerExists` probe all port with it.
- **Notification glance throttled to state change**: rebuild the seek line only when the glance (bucket/direction/completion) changes or a 15 s floor elapses — the bucket IS the display rounding, satisfying the Samsung-suppression rule.
- **Accuracy gate → fine-location upgrade flow**: iOS's temporary-full-accuracy request maps to detecting coarse-only grant and requesting `ACCESS_FINE_LOCATION` upgrade; declined → alert → home (hard gate, no degraded seek).
- **Scenery stays deterministic across the data-driven change**: gate/cairn decisions branch *before* the 35% lottery, and `drift` occupies the retired random-torii probability band — every pre-existing walk **that stays in the lottery** keeps its exact rolled scenery, while threshold and cairn walks intentionally change retroactively, matching iOS 1.8.1.
- **Ship flip is the last Phase 14 commit** (`WalkMode.Seek` → available), after the Phase 14 consolidated device QA; scenery lands after it; the single v1.2.0 release gates on both QA passes.

---

## Open Questions

### Resolved During Planning

- Mapbox Android SDK fog/pulse-ring feasibility: 11.11.0's style API supports runtime GeoJSON sources, circle/symbol layers, and zoom-interpolated expressions — equivalent surface to what iOS uses. Per-unit spec verifies exact property names.
- `.aac` earcons vs OGG transcode: copy verbatim per R7 (`MediaPlayer` plays AAC/M4A); transcode only if device QA surfaces a playback issue.
- Split-pin (Phase 15 anchor): user pulled Phase 15 into this plan at `c1745e8`; origin's open question marked resolved.
- "First production migration" concern from origin: false — DB is at v8 with a tested migration chain; seek persistence follows the established pattern.

### Deferred to Implementation

- Exact arrival-waypoint marker mechanism (existing `Waypoint` field vs new column + `MIGRATION_8_9`): decided after reading the entity; both paths are patterned.
- Android equivalent of the iOS off-screen coordinate clamp trap (`point(for:)` → (−1,−1)): verify `pixelForCoordinate` behavior when porting crescent viewport release.
- Whether a usable step-count signal exists for a second stillness vote without new permissions: check during U3; displacement-only is the committed baseline.
- Whether Android's scenery composition already places items relative to the dot composable (iOS's 3f9d3db placement bug may not reproduce): verified during U16's parity check.

---

## High-Level Technical Design

> *This illustrates the intended approach and is directional guidance for review, not implementation specification. The implementing agent should treat it as context, not code to reproduce.*

```mermaid
flowchart LR
    WSS[WalkStartScreen<br/>mode = Seek] --> SETUP[Seek setup flow<br/>accuracy -> duration -> intention -> gateway]
    SETUP --> SVC[WalkTrackingService<br/>+ WalkControllerImpl<br/>mode-aware start]
    SVC -->|state + locations| ORCH[SeekOrchestrator<br/>@Singleton, restore-path filter]
    ORCH -->|GPS lock| GEN[SeekSeed + SeekChainGenerator<br/>deterministic chain]
    GEN --> ENG[SeekEngine<br/>pulse clock / alignment /<br/>arrival debounce / stillness]
    ENG -->|events| ORCH
    ORCH --> AUDIO[SeekSoundPlayer<br/>ping / bowl]
    ORCH --> HAPT[Seek haptics<br/>tick / aligned / arrival / breath]
    ORCH --> FOG[SeekFogRenderer + SeekCrescent<br/>runtime style layers on PilgrimMap]
    ORCH --> GLANCE[SeekGlanceModel] --> NOTIF[WalkNotificationFactory<br/>seek line]
    ORCH -->|SEEK_MODE / SEEK_ARRIVAL events,<br/>arrival waypoints| ROOM[(Room)]
    ROOM --> SUM[SeekSummarySection]
    ROOM --> GOSH[Goshuin seeking seals]
    ROOM --> SCEN[Journal scenery<br/>gates / cairns via snapshots]
```

---

## Implementation Units

### U1. Anchor re-pin + gate waiver

**Goal:** Retarget the frozen parity anchor to `c1745e8` everywhere it is recorded, and close the old v1.7.0 gate with the dated waiver + live-probe carve-out.

**Requirements:** R1, R3

**Dependencies:** None

**Files:**
- Modify: `CLAUDE.md` (parity-scope section + stale "single baseline schema" claim + phasing note)
- Modify: `docs/parity/2026-05-15-parity-ledger.md` (Gate summary waiver, dated 2026-07-14)
- Modify: `docs/parity/gate.md` (one-line pointer to the ledger waiver)
- Modify (out-of-repo): project memory `parity_scope_v1_5_0.md`; `~/.claude/skills/ios-parity/` pinned anchor (SKILL.md description + `ios-pin.sh`)

**Approach:**
- Waiver text: `unverified` re-capture rows accepted as-is; PR #47 phone-call LOSS/LOSS_TRANSIENT probe + interruption residuals explicitly carved out to the Phase 14 device QA pass.
- Sweep only operational references; historical plan/matrix docs that name old targets as facts stay untouched.

**Test scenarios:**
- Test expectation: none — docs/config only.

**Verification:**
- `grep -r "v1.6.0\|v1.5.0\|db4196e\|fcd22553"` across CLAUDE.md, memory, and the ios-parity skill shows only historical-document hits.

---

### U2. Seek chain model, seed, and generator

**Goal:** Pure-Kotlin deterministic clearing-chain generation: seeded from intention + moment + GPS fix + entropy, one-way outbound geometry, reroll support.

**Requirements:** R4 (iOS U1 + `SeekSeed` + 52ff1a5 one-way budget)

**Dependencies:** None

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekChain.kt` (SeekPoint, SeekClearing, SeekChain, `regeneratingRemainder`)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekChainGenerator.kt` (SeekTuning constants, count bands, budget math, outbound placement, constraint scoring)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekSeed.kt` (SHA-256 digest → UInt64 → SplitMix64 generator)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/seek/SeekChainGeneratorTest.kt`, `SeekSeedTest.kt`

**Approach:**
- Shipped geometry only (one-way outbound, 52ff1a5) — the iOS plan doc's out-and-back/loop shapes are stale; the port spec quotes shipped `SeekChainGenerator.swift`.
- Count bands: <45 min → 1, <90 → 1–2, else 2–3 (duration clamped 1–240). Budget: minutes × 0.75 ÷ 24.5 min/mile; crow-flies reach = budget ÷ 1.25. Final clearing at 0.85–1.0 × reach; 12 constraint attempts, best-effort candidate kept (generation never fails).
- Spherical math ported directly (haversine/destination/bearing) — no map SDK dependency in domain.

**Execution note:** `/ios-parity port` spec first (R10). Test-first — the iOS test suite enumerates the invariants.

**Patterns to follow:** pure-domain conventions in `domain/` (no Android imports); `SealSpec.kt` for deterministic-hash style.

**Test scenarios:**
- Happy path: same (seed, duration, fix) → identical chain across runs.
- Happy path: duration bands produce 1 / 1–2 / 2–3 clearings respectively over many seeds.
- Edge case: duration clamped at 1 and 240 minutes; along-distance floor 250 m; radius always within 40–60 m.
- Edge case: constraints hold over ≥500 random seeds — ≥250 m from start, ≥300 m spacing, cumulative path ≤1.1 × reach; when unsatisfiable, a best-effort chain is still returned.
- Happy path: reroll keeps reached prefix, regenerates remainder from current position, budget = fraction-ahead with 625 m floor.
- Edge case: empty intention omitted from the digest (seed still valid); identical intention with different moment → different chain.

**Verification:** generator invariants pinned by tests mirroring iOS `SeekChainGeneratorTests`; no Android framework types in `domain/seek/`.

---

### U3. SeekEngine + stillness detector

**Goal:** The session engine: pulse clock, alignment, arrival debounce, stillness voting, grace, pause suspension, reroll, completion.

**Requirements:** R4 (iOS U2)

**Dependencies:** U2

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekEngine.kt` (SeekEngineEvent, SeekEnginePhase, closeness curve)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekStillnessDetector.kt`
- Modify: the location model + fused source (add per-fix bearing from `Location.hasBearing()`/`getBearing()` — the current pipeline carries no course data; extend the location fakes) — exact paths verified at implementation
- Create: a power-tier flow (from `PowerManager.isPowerSaveMode` + battery broadcasts) feeding the pulse-clock floor — or, if judged out of proportion, a documented normal-tier parity-minus matching iOS's no-guard fallback
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/seek/SeekEngineTest.kt`, `SeekStillnessDetectorTest.kt`

**Approach:**
- Coroutine-based pulse clock (replaces iOS's runloop Timer): interval linear 10 s @ ≤100 m → 60 s @ ≥2000 m, floor 30 s under low-power tier; generation-counter invalidation on reroll/stop.
- Alignment: 15 s course-sample window, circular-mean heading vs bearing, aligned iff |delta| ≤ 60°; positive-only. iOS's negative-course sentinel maps to Android's absent-bearing representation (`hasBearing() == false` → excluded from the window).
- Meditation does NOT suspend the engine (iOS parity: only pause/auto-pause suspend; pings continue through meditation).
- Arrival: 3 consecutive fixes inside the region with accuracy ≤50 m; bad fixes neither advance nor reset.
- Stillness: displacement-primary voting (committed decision) — window rand(45–90) s × 1.5, max displacement <15 m from anchor with ≥2 good fixes, ≥15 m veto + re-anchor; 5 s evaluation ticks; grace reveal at 240 s post-arrival. Pause suspends clock + stillness (restart on resume, no banked credit) and banks grace remainder.
- Reroll allowed in guiding/arrived; emits an immediate pulse with the stale pre-reroll distance (tangible confirmation, ece26a7).
- Inputs injected as flows (locations, builder pause status, power tier) — engine stays testable with `FakeClock`/`FakeLocationSource`.

**Execution note:** `/ios-parity port` spec first. Use `runCurrent()` for perpetual-tick tests (Stage 5-E: `advanceUntilIdle` never drains a `while isActive; delay` loop).

**Patterns to follow:** `data/proximity/ProximityDetectionService.kt` (location binding, dedup); reducer purity conventions.

**Test scenarios:**
- Happy path: distance sweep produces the linear interval curve; exact thresholds asserted with `advanceTimeBy(threshold − 1)` / `+1` (Stage 5-F pattern).
- Happy path: 3 in-region accurate fixes → `arrived` exactly once; one-way per clearing.
- Edge case: fix with accuracy 51 m inside the region neither advances nor resets the debounce count.
- Edge case: alignment flips only when smoothed delta crosses 60°; course-less fixes (negative course) excluded from the window.
- Happy path: stillness passes on <15 m displacement across the window; ≥15 m displacement vetoes and re-anchors.
- Edge case: grace fires reveal at 240 s despite failed voting; pause banks remaining grace and re-arms on resume.
- Happy path: reroll in `arrived` returns phase to `guiding`, stops stillness, emits immediate pulse with stale distance.
- Integration: full walk simulation — guiding → arrival → stillness → reveal per clearing → `seekComplete` emitted exactly once, engine quiet after.
- Error path: engine `stop()` mid-window cancels all timers (no post-stop emissions).
- Edge case: entering meditation mid-seek does not suspend the pulse clock or stillness voting; pause does.

**Verification:** event sequence for a simulated two-clearing walk matches the iOS engine contract in the port spec.

---

### U4. Seek persistence vocabulary

**Goal:** Persist seek-ness the way iOS does: mode + arrival events, marked arrival waypoints with ordinal labels, round-trip through export/import and share surfaces.

**Requirements:** R4 (iOS U3, shrunk — event path exists), R5

**Dependencies:** None (parallel with U2/U3)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEventType.kt` (+ `SEEK_MODE`, `SEEK_ARRIVAL`), `app/src/main/java/org/walktalkmeditate/pilgrim/data/Converters.kt` (unknown-value fallback)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekPersistence.kt` (reserved arrival marker, `isArrivalWaypoint` predicate, ordinal labels)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/entity/Waypoint.kt` + `app/src/main/java/org/walktalkmeditate/pilgrim/data/PilgrimDatabase.kt` (MIGRATION_8_9) — only if the entity lacks a usable marker field
- Modify: package converter — **implement manifest `events` export/import** (it currently exports an empty list and rebuilds events solely from pauses; the round-trip test cannot pass via an audit) using iOS's `"seekMode"`/`"seekArrival"` identifiers verbatim; audit the share payload builder; extend the exhaustive `when` branches in the event-replay/pause-compute paths
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/seek/SeekPersistenceTest.kt`; extend `data/PilgrimDatabaseMigrationTest.kt` if migration lands

**Approach:**
- One `SEEK_MODE` event at recording start (its timestamp is the summary's provenance "seeded at" source). One `SEEK_ARRIVAL` event + one marked waypoint per arrival; ordinal = persisted-arrival-count + 1 (not engine index — reroll-safe).
- Walks stay ordinary walks; unreached clearings never persist.
- Forward safety: add an `UNKNOWN` fallback so v1.2.0+ reads future vocabulary safely. Note: shipped v1.1.x maps unknown strings to `PAUSED`, and old importers *drop* unknown manifest events rather than falling back — both accepted (in-place downgrades unsupported).

**Execution note:** `/ios-parity port` spec first. Audit `PilgrimPackageConverter`-equivalent and share payload in the same PR (Stage 12: serialization surfaces ship broken when skipped).

**Patterns to follow:** `WalkEventType` + `Converters` existing enum handling; `MIGRATION_7_8` ALTER pattern; migration test KDoc conventions.

**Test scenarios:**
- Happy path: seek walk start persists exactly one `SEEK_MODE` event; wander walk persists none.
- Happy path: arrival persists event + waypoint with "First/Second/Third clearing" then "Clearing %d" labels.
- Edge case: reroll after two arrivals → next arrival labeled with ordinal 3 (persisted count, not engine index).
- Integration: `.pilgrim` export → import round-trips seek events and arrival waypoints losslessly.
- Error path: reading an unknown event-type value (future schema) maps to fallback, does not crash.
- Integration (if migration lands): v8 → v9 migration preserves existing rows; fresh install at v9 matches migrated schema.

**Verification:** a seek walk's events/waypoints survive export/import; share payload unchanged for wander walks.

---

### U5. Seek audio, haptics, and preferences

**Goal:** Sonar ping + reveal bowl with iOS's suppression rules, the four-part seek haptic vocabulary, and the seek preferences family.

**Requirements:** R4 (iOS U4), R7, R11

**Dependencies:** U3 (event vocabulary), can start against fakes

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/seek/SeekSoundPlayer.kt`; copy iOS assets → `app/src/main/res/raw/seek_ping` + `seek_bowl` (verbatim per R7)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/seek/SeekHaptics.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/seek/SeekPreferencesRepository.kt` + `DataStoreSeekPreferencesRepository.kt` + `app/src/main/java/org/walktalkmeditate/pilgrim/di/SeekPreferencesModule.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/seek/SeekSoundPlayerTest.kt` (Robolectric), `SeekHapticsTest.kt`, `data/seek/DataStoreSeekPreferencesRepositoryTest.kt`

**Approach:**
- BellPlayer clone: own `AudioFocusRequest` (GAIN_TRANSIENT_MAY_DUCK), manual `MediaPlayer()` → `setAudioAttributes` → `prepare` ordering, defensive `abandonFocus()` at top of `requestFocus()`.
- Ping: volume `0.55 + 0.45 × closeness` × sonar-volume pref; aligned → double-ping with 0.25 s generation-guarded gap; per-ping checks — sonar toggle, master sounds toggle, suppression (whisper playing, voice-guide playing, active talk recording). Bowl: on reveal + completion; ignores sonar toggle, respects master sounds; consumer released 4.5 s after completion.
- Haptics: tick (1 transient, intensity scales with closeness), aligned (2 soft transients 0.18 s apart), arrival (3 rising taps), breathIn (0.9 s swell) — `VibrationEffect` compositions with amplitude fallbacks; fire regardless of foreground state (Key Decision); gate each on audio-start success where coupled (no phantom buzz on denied focus).
- Prefs: `seekSonarEnabled` (true), `seekSonarVolume` (0.5), `seekLastDurationMinutes` (60), `seekSafetyShown` (false).

**Execution note:** `/ios-parity port` spec first. Robolectric `.build()` on the `AudioFocusRequest` and the MediaPlayer path is mandatory (R11 / Stage 2-F).

**Patterns to follow:** `audio/BellPlayer.kt`, `data/cairn/StonePlayer.kt`, `data/practice/*PreferencesRepository` family.

**Test scenarios:**
- Happy path: closeness 0 → volume 0.55×pref; closeness 1 → 1.0×pref; aligned ping fires twice.
- Error path: focus denied → no playback, no haptic, no crash.
- Edge case: suppression matrix — whisper playing / voice guide playing / talk recording each independently skip the ping (never queue); bowl still plays when only the sonar toggle is off.
- Edge case: completion releases the audio consumer after the bowl ring window; subsequent pings impossible.
- Error path: audio interruption mid-ping → players dropped, lazily re-armed on next play.
- Happy path: preferences round-trip with defaults; volume clamped to [0,1].
- Integration (Robolectric): `AudioFocusRequest.build()` + full MediaPlayer construct path execute without throwing.

**Verification:** ping/bowl behavior matrix from the port spec passes; assets identical to iOS bytes.

---

### U6. Map fog + pulse ring

**Goal:** Per-clearing fog circles with distance-driven thinning, reveal dissolve → persistent halo, and the one-shot pulse ring — as runtime style layers on `PilgrimMap`.

**Requirements:** R4 (iOS U5), R5 (`a0624d0` self-heal)

**Dependencies:** U3 (fog state derives from engine), U2

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/map/SeekFogRenderer.kt`; `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekFogModel.kt` (pure state: buckets, hysteresis, halo)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt` (renderer hook; layer ordering below route casing)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/seek/SeekFogModelTest.kt`

**Approach:**
- One `CircleLayer` + point `GeoJsonSource` per clearing; blur 1.0, map-aligned; geographic sizing via zoom-interpolated exponential radius expression; 1.5 s opacity/blur transitions set at creation (0 under reduce-motion) so later writes are GPU-eased; created at opacity 0 then written to target (free fade-in).
- Opacity buckets [150, 300, 600, 1200] m → [0.25, 0.35, 0.45, 0.55, 0.65] with 10% boundary hysteresis; arrival/reveal → 0 (the dissolve IS the moment) then persistent halo at 0.12 dawn; unrevealed clearings render nothing.
- Equality early-return keyed on whole fog state; `null == null` fast path keeps wander walks off the style entirely. Deferred-update queue while rendering paused/style loading; pulse tokens seen while paused are swallowed.
- Self-heal: one `layerExists` probe per pass (lock/unlock strips runtime layers with no style event on iOS; assume same class of risk) → full reinstall; also reinstall from style-reload callback.
- Pulse ring: one-shot `CircleLayer` recreated per pulse at the puck coordinate, 12 px/0.45 → 80 px/0 over 1.2 s transitions; skipped under reduce-motion.
- Fog bucket math on `Dispatchers.Default`; retro-audit existing `PilgrimMap` update blocks when adding the new state input (13-B/13-D flicker class).

**Execution note:** `/ios-parity port` spec first. This is the unit most likely to need the mid-phase device smoke check — schedule it after U7.

**Patterns to follow:** `PilgrimMap.kt` layer-ordering comment + fade-in timeout; `IncrementalRoute.kt` for update-gating style.

**Test scenarios:**
- Happy path: distance sweep produces the bucket ladder with hysteresis (crossing 300 m ±10% doesn't oscillate).
- Edge case: reveal transitions opacity to 0 then halo state; halo persists for the rest of the session.
- Edge case: fog state for unrevealed clearings is absent (not zero-opacity layers).
- Happy path: identical consecutive fog states produce no style writes (equality gate).
- Edge case: reduce-motion zeroes transition durations; pulse ring suppressed entirely.
- Integration: state emitted while paused is applied once on resume (single write, pulses swallowed).

**Verification:** `SeekFogModel` pins all bucket/hysteresis/halo math on JVM; renderer behavior confirmed at the mid-phase device smoke check.

---

### U7. Crescent + hour/celestial tinting

**Goal:** The crescent of light on the puck rim guiding toward the active clearing — flare-per-pulse, span opening as fog nears, viewport release, hour/starlight coloring — plus the celestial fog tints and seek gateway/weather lines.

**Requirements:** R4, R5 (crescent cluster `28417e8`..`b98536f`, `7c3d618`)

**Dependencies:** U6

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/map/SeekCrescentRenderer.kt` (SymbolLayer + pre-rendered arc bitmap), `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekCrescentModel.kt` (span buckets, visibility/release), `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekSkyLight.kt` (hour tints + celestial fog tint + gateway/weather lines)
- Modify: `core/celestial/` sun math — add instantaneous `solarElevationDegrees(lat, lon, instant)` ported from iOS `CelestialCalculator.swift`, with its own tests (consumed here and by U11)
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/seek/SeekCrescentModelTest.kt`, `SeekSkyLightTest.kt`

**Approach:**
- `SymbolLayer` at the walker coordinate, icon rotated to bearing, map-aligned; icon = pre-rendered arc bitmap (24 segments × 3 stacked passes, width/alpha peaking at apex) registered as a style image, cached per (span-bucket, light-token).
- Span [96, 86, 72, 60, 48]° keyed to fog buckets (inherits hysteresis). Rest opacity 0.55; per-pulse flare `0.75 + 0.15 × closeness` (aligned → 1.0), settle after 1.05 s, generation-guarded; single 1.0 s opacity transition eases everything. Reduce-motion: steady 0.8, no flares.
- Viewport release: when the active fog circle's screen footprint intersects the viewport (24 px inset, 24 px outset hysteresis to return) → one full flare then dissolve ("handoff exhale"); checks on camera-change (throttled ~0.12 s), map-idle, and every fog apply. Guard the off-screen clamp trap: verify how the Android SDK reports off-view coordinates before trusting screen-space checks (iOS clamped to (−1,−1) and released the crescent forever, 174e9e0).
- Colors: dawn family by solar elevation (night < −4°, golden < 8°, else midday) via existing `CelestialCalculator`; starlight family under constellation appearance mode. `SeekSkyLight` also computes the once-per-walk celestial fog tint (solstice/equinox turnings > full moon > none; gated on celestial awareness) and the seek gateway/weather greeting lines consumed by U8.
- Android naming is `SeekCrescent*` throughout (iOS "wisp" files implement the crescent — port spec must flag every wisp-named quote).

**Execution note:** `/ios-parity port` spec first, with an explicit wisp→crescent naming table.

**Patterns to follow:** `createPuckBitmap` in `PilgrimMap.kt` (bitmap → style image); `ui/design/seals/SealRenderer.kt` for deterministic arc drawing math.

**Test scenarios:**
- Happy path: span bucket ladder follows fog buckets including hysteresis inheritance.
- Happy path: solar-elevation → light-token boundaries (−4°, 8°) exact; starlight family selected under constellation mode.
- Edge case: viewport intersection with inset/outset hysteresis — entering releases once; leaving + re-entering the outset band doesn't flap.
- Edge case: off-screen coordinate handling — clamped/invalid screen points treated as off-screen, never as intersecting.
- Happy path: celestial tint precedence — turning beats full moon beats none; cross-quarter days keep ordinary fog.
- Edge case: reduce-motion → steady opacity, zero flares.

**Verification:** model math pinned on JVM; crescent look + release confirmed in the mid-phase device smoke check (below).

> **Milestone — mid-phase device smoke check (R12):** on the OnePlus 13: start a seek walk, observe fog thinning while approaching, hear the sonar ping (screen off included), watch the pulse ring + crescent flare, arrive at a clearing, observe dissolve → halo. Lock/unlock mid-walk to exercise the self-heal probe.

---

### U8. Mode plumbing + seek setup flow

**Goal:** Carry `WalkMode` from the selector through service and controller; the four-stage seek setup ritual (accuracy → duration → intention → gateway) ending with a GPS-locked chain.

**Requirements:** R4 (iOS U6), R5 (`1f727ce` gateway + weather voice; `85373c1` pre-walk surfacing arrives in U9)

**Dependencies:** U2, U3, U5, U7 (gateway consumes `SeekSkyLight` lines)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/path/WalkStartScreen.kt`, `walk/UiWalkController.kt`, `walk/WalkActionPublisher.kt`, `service/WalkTrackingService.kt`, `walk/WalkControllerImpl.kt`, `domain/WalkState.kt`/`WalkAction.kt` (mode-aware start), `domain/WalkMode.kt` (no availability change yet)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/seek/SeekSetupViewModel.kt` (SeekSetupStage machine), `ui/seek/SeekDurationSheet.kt`, `ui/seek/SeekGatewayOverlay.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/seek/SeekSetupViewModelTest.kt`; extend `walk/WalkControllerImplTest` + service decision tests

**Approach:**
- Stage machine: `verifyingAccuracy → durationQuestion → intention → transition → ready | cancelled(userDismissed | accuracyDeclined | gpsTimeout)`; wander walks are born ready; all advances guarded no-ops.
- Accuracy gate: coarse-only location grant → request fine upgrade; declined → alert → home (hard gate).
- Duration sheet: presets 30/60/120/180, last-used preselected (snapped to closest preset), safety caption until `seekSafetyShown` flips on first Begin.
- Intention required for seek (skip disabled, dismiss blocked).
- Gateway overlay (~6.2 s; reduce-motion text-only ~2.6 s): mist + two silent sonar rings + one breathIn haptic; celestial gateway line override; seek weather greeting replaces the wander greeting.
- GPS lock starts with the gateway: first fix ≤50 m accuracy generates the chain + boots the engine; 20 s timeout while still in transition → alert → cancelled; late timeout after ready stays silent.
- Extend the `decideStartAction`/state-decision cross-product tests to seek mode — the cached-`:tracker` second-walk race gates must hold (memory precedent: 6 review cycles missed it).

**Execution note:** `/ios-parity port` spec first (source: `SeekSetupStage` machine in `ActiveWalkViewModel.swift` + `SeekSetupFlowModifier.swift` + `SeekGatewayView.swift` — U6's planned shape diverged).

**Patterns to follow:** `ui/walk/IntentionSettingSheet.kt`; pre-walk haptic latch pattern (`rememberSaveable` first-frame latch, polish memory 2026-05-07).

**Test scenarios:**
- Happy path: full stage walk-through lands in ready with a generated chain.
- Error path: accuracy declined → cancelled(accuracyDeclined); duration dismissed → cancelled(userDismissed); no engine boot in either.
- Error path: no ≤50 m fix within 20 s during transition → cancelled(gpsTimeout) + alert; fix at 21 s after ready → no alert.
- Edge case: wander mode bypasses every stage (born ready); guarded advances are no-ops from wrong stages.
- Edge case: last-used duration 45 (legacy value) snaps to preset 30 or 60 deterministically; safety caption gone after first Begin.
- Integration: seek start → `SEEK_MODE` event persisted (U4) + engine bound to the service location feed.
- Integration: `decideStartAction` cross-product including seek × {fresh, cached-Finished, restored-Active} preserves the second-walk gates.

**Verification:** setup ritual matches the port spec's stage contract; wander behavior untouched.

---

### U9. Active-walk integration + options sheet

**Goal:** The app-scoped orchestrator wiring engine events to senses and persistence; seek section in the walk options sheet; reveal whisper.

**Requirements:** R4 (iOS U7), R5 (`85373c1`, `ece26a7`)

**Dependencies:** U3, U4, U5, U6, U7, U8

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/seek/SeekOrchestrator.kt` (@Singleton, eager via `PilgrimApp` Provider)
- Modify: `PilgrimApp.kt`, `ui/walk/WalkOptionsSheet.kt`, `ui/walk/ActiveWalkScreen.kt` (fog/crescent state feed)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/seek/SeekOrchestratorTest.kt`

**Approach:**
- Orchestrator observes `WalkControllerImpl.state` with BOTH first-emission skip AND restore-path filter (restored seek walks do not reboot the engine — Key Decision; Stage 5-B/5-G precedent).
- Event routing: pulse → ping + tick/aligned haptic + crescent flare token; arrived → persistence (U4) then arrival haptic; stillnessBegan → breathIn; revealedNext → bowl + fog reveal; seekComplete → bowl + consumer release + notification "seeking complete".
- Reveal whisper: 2.5 s after the bowl, one random locally-downloaded whisper; none available → bowl-only; generation-guarded.
- Options sheet seek section (self-gated on an active engine): sonar toggle + volume slider mirroring prefs live, "Seek anew" row (dismisses sheet, requests reroll); after completion the row stays visible, disabled, with a completed subtitle. Renders pre-departure too (the seek is alive from setup-ready).
- Every long-lived `collect` defends its body with try/catch re-throwing CancellationException (Stage 5-D: one throw silently kills the observer for the process lifetime).

**Execution note:** `/ios-parity port` spec first (source: `ActiveWalkViewModel+Seek.swift` — the iOS plan's `SeekOverlayViews.swift` never shipped).

**Patterns to follow:** `audio/MeditationBellObserver.kt` (state-observing earcon orchestrator), `walk/WalkFinalizationObserver` (finalize side-effects home).

**Test scenarios:**
- Happy path: engine event fan-out reaches audio, haptics, fog state, and persistence in the contract order (persist before ritual on arrival).
- Edge case: restored active seek walk (process death) → no engine boot, no fog, walk continues recording; events/waypoints intact.
- Edge case: reroll via "Seek anew" → immediate pulse fires; post-completion row disabled.
- Error path: whisper playback throws → orchestrator observer survives (next events still routed).
- Integration: wander walk lifecycle produces zero seek side effects.

**Verification:** simulated walk drives the full sensory contract; restore-path filter proven by a restart-shaped test.

---

### U10. Notification seek glance

**Goal:** Clearing distance + direction on the ongoing walk notification, throttled to glance changes.

**Requirements:** R4 (iOS U8), R6, R11

**Dependencies:** U3, U9

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/seek/SeekGlanceModel.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkNotificationFactory.kt`, `service/WalkTrackingService.kt` (push gating)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/seek/SeekGlanceModelTest.kt`; extend `service/WalkNotificationFactoryTest`

**Approach:**
- Glance derivation in-process: 100 m floor buckets capped at 2000 ("2 km +"), "close" under 100 m; direction ahead/left/right/behind from course-vs-bearing (ahead ≤45°, behind ≥135°), hidden when speed <0.4 m/s or course invalid; "seeking complete" terminal state.
- Notification text: seek line appended to the Active-state content via `walkNotificationText` (internal, testable without the service); units-aware (m/km vs mi).
- Push throttle: rebuild only when the glance state changes or ≥15 s elapsed (the bucket is the display rounding — Samsung suppression rule); wander walks unaffected.

**Execution note:** `/ios-parity port` spec first. Robolectric test calls the real `Notification` build path (R11).

**Patterns to follow:** `WalkNotificationFactory` internal-helper testing seam; `ui/walk/WalkFormat` for distance formatting.

**Test scenarios:**
- Happy path: distance → bucket → text ("~400 m", "~1.2 km", "2 km +", "close") in both unit systems.
- Edge case: speed 0.39 m/s or invalid course hides the direction word; bucket text remains.
- Edge case: consecutive identical glances produce no notification rebuild; 15 s floor forces one.
- Happy path: completion renders "seeking complete" and stops glance updates.
- Integration (Robolectric): seek-mode notification `.build()` succeeds with actions intact.

**Verification:** glance math pinned on JVM; notification content verified in the Phase 14 consolidated QA (screen-off pocket test).

---

### U11. Summary seek section + provenance/halos

**Goal:** The seek story on the walk summary: unknowns found, per-clearing rows with found-under captions and signs, "Along the way", provenance line, and arrival halos on the summary map.

**Requirements:** R4 (iOS U9), R5 (`e5c9735`, `8dc2b1f`, `2b88455`)

**Dependencies:** U4

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/SeekSummarySection.kt` + `SeekSummaryModel.kt`
- Modify: `ui/walk/WalkSummaryScreen.kt`, `ui/walk/WalkSummaryViewModel.kt`, summary map annotation handling (arrival halo kind), `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/SeekSummaryModelTest.kt`

**Approach:**
- Seek detection: `SEEK_MODE` event present; zero-arrival seeks render the standard summary (model returns null).
- Rows per reached clearing: label, arrival time, found-under caption (golden/midday/night from real solar elevation at that place and moment — existing `CelestialCalculator`), signs line ("1 photo · 2 voices · 1 mark"); then "Along the way" group; then provenance ("seeded at <time>" from the `SEEK_MODE` timestamp, phrased differently when an intention was voiced).
- Sign grouping: nearest arrival within 80 m; photos by capture fix, waypoints by position, voice recordings by nearest route sample (5-minute-after-arrival fallback); rest → Along the way.
- Summary map: arrival waypoints render as two-part halos (soft glow + bright core) in the hour's-light color, not pins; live-walk halos stay with the fog layer (U6).
- Unknowns-found phrasing: dedicated 1/2/3 strings + %d fallback; never totals ("X of Y" would reveal unreached clearings).

**Execution note:** `/ios-parity port` spec first. Locale rule: `Locale.US` for numeric formatting, `DateTimeFormatter` with explicit locale (Stage 5-A/6-B).

**Patterns to follow:** `ui/walk/LightReadingPresenter.kt` + `WalkLightReadingCard.kt` (gated narrative card), summary reveal choreography in `ui/walk/summary/`.

**Test scenarios:**
- Happy path: two-arrival walk groups signs to the correct clearings; 81 m photo lands in Along the way.
- Edge case: zero arrivals → model null → standard summary; wander walk → no section at all.
- Happy path: found-under caption boundaries match the solar-elevation thresholds; provenance uses the event timestamp, intention variant when voiced.
- Edge case: voice recording with no nearby route sample uses the 5-minute-after-arrival fallback.
- Happy path: 1/2/3 phrasing exact; 4+ uses the %d fallback.

**Verification:** model output for a fixture walk matches the port spec's rendering contract; halos visually confirmed in Phase 14 QA.

---

### U12. Goshuin seeking seals + journal mode glyph

**Goal:** Lifetime found-place milestones as goshuin seals, and the per-walk mode glyph in the journal quick view.

**Requirements:** R4, R5 (`d476bc3`, `d7402d5`, `cee3a15` fetch error handling, `3993b11` stable ordering)

**Dependencies:** U4

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestone.kt` (+ FirstUnknown, UnknownsFound), `ui/goshuin/GoshuinMilestones.kt` (detect inputs + `seekingMilestones` + `arrivalCounts`), `ui/home/WalkSnapshot.kt` (+ isSeek), `ui/home/HomeViewModel.kt` (bulk SEEK_MODE fetch), `ui/home/expand/ExpandCardSheet.kt` (glyph in HeaderRow)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkModeFootprints.kt`
- Test: extend `ui/goshuin/GoshuinMilestonesTest`; create `ui/home/WalkModeFootprintsTest.kt` (geometry via internal fun, Stage 3-C rule)

**Approach:**
- `seekingMilestones(arrivalsInWalk, arrivalsBefore)`: 0 arrivals → nothing; `arrivalsBefore == 0` → FirstUnknown; each threshold in [10, 25, 50, 100] where `before < t ≤ before + inWalk` → that seal (multi-cross walks earn several). Counting source: arrival-waypoint counts per walk (U4 predicate); lifetime prior = earlier-startDate walks excluding self.
- Guard the equal-value trap: milestone detection must not fire on zero-arrival ties (Stage 4-D `maxByOrNull` lesson generalizes).
- Stable ordering (`3993b11`): a `primaryMilestone(...)` selector picks the displayed seal deterministically (once-ever > threshold crossing > recurring; largest count within a tier) regardless of set-iteration order; `isOrderedBefore(date, id, date, id)` gives a strict walk ordering with a uuid tie-break so same-startDate walks never double-award or miss a seal. The bulk seek-id fetch surfaces errors instead of swallowing them (`cee3a15`).
- `WalkSnapshot.isSeek` via one bulk event fetch mapped to walk ids (never per-walk event faulting — iOS's own perf rule).
- Glyph: wander = mirrored footprint pair; seek = one footprint + Canvas trail of 6 dots dissolving upward (shrinking radius, fading opacity); static; accessibility-hidden.

**Execution note:** `/ios-parity port` spec first.

**Patterns to follow:** `GoshuinMilestones.detect()` purity; `@Immutable` discipline on `WalkSnapshot` (new field keeps stability); `ui/path/PathFootprints.kt` (WanderFootprints/SeekFootprints) + the shared `footprintPath` helper — reuse, don't re-derive glyph geometry.

**Test scenarios:**
- Happy path: first-ever arrival walk earns FirstUnknown; walk crossing 10 earns UnknownsFound(10).
- Edge case: walk taking lifetime count 8 → 12 earns exactly UnknownsFound(10); 0 → 26 earns FirstUnknown + 10 + 25.
- Edge case: seek walk with zero arrivals earns nothing; wander walks never earn seeking seals.
- Happy path: snapshot isSeek true only for walks with a SEEK_MODE event (bulk fetch, one query).
- Happy path: multi-milestone walk's displayed seal is deterministic across shuffled input orders.
- Edge case: two walks with identical startDate produce a stable strictly-before ordering via uuid tie-break (no double-award, no miss).
- Happy path: footprint-trail geometry (dot count, shrink, fade) pinned via internal function.

**Verification:** milestone math matches the iOS thresholds table; glyph renders in the quick view for a seeded seek walk.

---

### U13. Settings + ship flip

**Goal:** Sonar controls in Sound Settings, remaining strings, and the deliberate final commit flipping `WalkMode.Seek` to available.

**Requirements:** R4 (iOS U10, minus demo seeder — excluded)

**Dependencies:** U5 (prefs), U8–U12 complete; **flip lands only after the Phase 14 consolidated QA passes**

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/` sound settings screen (sonar toggle + volume row), `app/src/main/res/values/strings.xml`, `domain/WalkMode.kt` (`isAvailable` includes Seek)
- Test: extend sound-settings VM test; `domain/WalkModeTest`

**Approach:**
- Sonar section mirrors the in-walk controls through the same `SeekPreferencesRepository` (single source of truth).
- The availability flip is its own final commit — everything before ships dark, exactly like iOS (42563b8).

**Test scenarios:**
- Happy path: settings toggle/volume round-trip through the shared prefs; in-walk mirror reflects changes.
- Happy path: flip makes the Seek card selectable; "coming soon" subtitle gone; Together unchanged.

**Verification:** pre-flip builds show seek dark; post-flip the full setup ritual is reachable from Home.

> **Milestone — Phase 14 consolidated device QA (R12):** OnePlus 13 pass covering the full seek loop (screen on + pocket), notification glance, summary/goshuin/journal surfaces, interruption matrix — **including the carved-out PR #47 probes**: phone call during a talk (LOSS vs LOSS_TRANSIENT) and interruption-resume residuals. Gate U13's flip on this pass.

---

### U14. Data-driven scenery model

**Goal:** Scenery that remembers: threshold gates and found-place cairns decided from real walk history ahead of the lottery, plus the drift type.

**Requirements:** R8, R9 (Phase 15 start — depends on seek persistence + milestone math)

**Dependencies:** U4, U12, U13 (Phase 14 QA green — aligns the unit graph with the Phased Delivery ordering)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt` (deterministic branch, drift type, gateKind/stones in `SceneryPlacement`), `ui/home/WalkSnapshot.kt` (+ foundPlaces, threshold), `ui/home/HomeViewModel.kt` (chronological threshold computation)
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/WalkThreshold.kt`
- Test: extend `ui/home/scenery/SceneryGeneratorTest.kt`

**Approach:**
- Decision order ("meaning outranks the lottery"): threshold → torii always (gateKind = threshold kind); else seek + foundPlaces > 0 → cairn with `stones = min(2 + foundPlaces, 5)`; else the existing 35% lottery with drift at the retired random-torii 0.05 band — torii and cairn can never come from the lottery, and every pre-existing walk's rolled scenery is unchanged.
- `WalkThreshold`: `.seeking` when the walk's arrivals produce any seeking milestone (U12 math, arrivalsBefore accumulated chronologically during snapshot build); else `.practice` for walk #1 and every 10th; seeking outranks practice.
- Snapshot build: bulk fetches only (seek walk ids + arrival counts), no per-walk faulting.

**Execution note:** `/ios-parity port` spec first (source: `SceneryGenerator.swift` + `HomeViewModel.swift` at the pin).

**Patterns to follow:** existing `SceneryGenerator` FNV/salt determinism; `SceneryGeneratorTest` style.

**Test scenarios:**
- Happy path: threshold snapshot → torii 100% (many seeds); gateKind forwarded.
- Happy path: seek + foundPlaces 1/2/3/9 → cairn with stones 3/4/5/5.
- Edge case: threshold outranks cairn on a milestone-crossing seek walk; seek with zero arrivals never gets a cairn (falls to lottery).
- Happy path: lottery over many seeds mints drift ≈5% and never mints torii/cairn; overall scenery fraction stays ≈0.25–0.45.
- Happy path: determinism — same snapshot → identical placement; pre-existing non-gate walks keep their exact prior scenery type.
- Edge case: chronological accumulation — arrivalsBefore counts only earlier-startDate walks, excluding self.

**Verification:** iOS `SceneryGeneratorTests`' 11 cases reproduced and green.

---

### U15. New scenery renderers

**Goal:** Cairns with winter caps, drift's four seasonal faces, real moon phases, lit/unlit lanterns, gate-kind tints + seeking moss, and the shimenawa/shide fix.

**Requirements:** R8

**Dependencies:** U14

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/CairnScenery.kt`, `DriftScenery.kt`
- Modify: `ui/home/scenery/MoonScenery.kt` (real lunar phase), `LanternScenery.kt` (lit by hour), `ToriiScenery.kt` (gateKind tint, seeking moss, shimenawa/shide under-arch geometry, age-fade exemption hook), `SceneryShapes.kt` (cairn stones shape)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/CairnSceneryTest.kt` (geometry via internal funs), extend scenery tests

**Approach:**
- Cairn: 2–5 stacked ellipse stones, widest at base, alternating lean, no sway; dawn halo echo; winter (Dec–Feb) snow-cap ellipse.
- Drift: month+hour pick the face — spring petal fall, summer fireflies (glowing only when the walk met the dark: start ≥17 h or <6 h), autumn dragonflies, winter flurry; the only scenery that travels through its frame.
- Real moon: lunar illumination + waxing/waning from the existing `CelestialCalculator`; shadow-disc carve (Compose `BlendMode.DstOut`) offset toward the lit limb, 0.08 illumination floor keeps a hairline crescent.
- Lantern: lit (flicker + glow) only for walks starting ≥17 h or <6 h; daylight → static, animation paused.
- Torii: practice = rust tint, seeking = stone tint + five moss ellipses creeping up the pillars (heavier left); shimenawa rope hangs under the nuki (top-left path coordinates — iOS's eed14d1 fix baked in from the start) with three fluttering shide strips.
- Perf/a11y: hoist astro + seasonal-color reads outside animation frames; reduce-motion freezes all animation; everything `accessibilityHidden`/clear-semantics.

**Execution note:** `/ios-parity port` spec first (source: `SceneryItemView.swift`, `CairnStonesShape.swift` at the pin).

**Patterns to follow:** existing per-type scenery composables + `SceneryShapes.kt`; `CelestialCalculator` usage from Stage 6-A.

**Test scenarios:**
- Happy path: cairn stone geometry — count clamps 2–5, widths ascend base-down, alternating lean, base no-lean.
- Edge case: winter cap present only Dec/Jan/Feb.
- Happy path: drift face selection by month; fireflies glow only for meet-the-dark hours.
- Happy path: moon carve offset direction by waxing/waning; 0.08 floor at new moon.
- Edge case: lantern lit-window boundaries (17:00, 05:59).
- Happy path: seeking torii carries stone tint + 5 moss ellipses; practice torii rust tint, no moss.

**Verification:** geometry pinned via internal functions on JVM (Robolectric Canvas draws are stubs — Stage 3-C); visual pass in the Phase 15 device QA.

---

### U16. Scenery depth, age, and touch

**Goal:** Parallax by type, age fade matched to the walk's dot (seeking gates exempt), gate/cairn scroll-haptic vocabulary, and the placement parity check.

**Requirements:** R8

**Dependencies:** U14, U15

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItem.kt` (parallax weights, age-fade multiplication, seeking exemption), the scenery-hosting journal composition (placement audit), `ui/home/scroll/HapticEvent.kt` + `ScrollHapticState.kt` + `JournalHapticDispatcher.kt` (gateDot/cairnDot kinds)
- Test: extend `ui/home/scroll/ScrollHapticStateTest` (mirror iOS `ScrollHapticEngineTests`), scenery placement test

**Approach:**
- Parallax: per-type weights (mountain 3 … grass 12, butterfly 14, drift 16) as horizontal drift proportional to distance from viewport center, via `graphicsLayer` lambda reads (never composition-phase values — Stage 5-A).
- Age: scenery multiplies its walk-dot's fade (newest 1.0 → oldest 0.5); seeking gates render at full opacity always ("old stone grows older, not fainter").
- Touch: dot-kind vocabulary (plain/gate/cairn) consulted before the size fallback — gate crossing → heavy milestone thump, cairn → soft success double; no retrigger on the same index; scroll haptics fully suppressed under reduce-motion.
- Placement parity check: verify Android scenery is offset relative to its dot composable (iOS's 3f9d3db double-coordinate bug class); fix if the absolute-coordinate shape exists; confirm windowing/culling still holds with the new animated types on a deep (90-walk-scale) journal.

**Execution note:** `/ios-parity port` spec first.

**Patterns to follow:** `JournalHapticDispatcher` existing size vocabulary; Stage 3-F journal stride verification approach.

**Test scenarios:**
- Happy path: haptic kind mapping — gate → gateDot regardless of dot size; cairn → cairnDot; plain falls back to size vocabulary.
- Edge case: same-index crossing doesn't retrigger; missing dotKinds array falls back safely.
- Happy path: age-fade multiplication matches dot opacity; seeking-gate scenery stays 1.0 while its dot fades.
- Edge case: reduce-motion → zero scroll haptics, frozen parallax animation.
- Integration: deep-journal composition keeps scenery beside its own dot at multiple scroll depths (placement regression guard).

**Verification:** haptic tests mirror iOS's 5 cases; scroll-depth placement + jank checked in the Phase 15 device QA.

> **Milestone — Phase 15 consolidated device QA (R12):** OnePlus 13 journal pass on a seeded deep history: gates/cairns/drift/moons/lanterns render correctly light + dark, parallax and haptics feel right, scroll perf holds on a long journal, reduce-motion freezes everything.

---

### U17. Release prep — v1.2.0

**Goal:** Single release containing both phases.

**Requirements:** R13

**Dependencies:** U1–U16, both QA milestones green

**Files:**
- Modify: `app/build.gradle.kts` (versionName 1.2.0, versionCode bump), `CHANGELOG.md`, Play release notes

**Approach:**
- Follow the established release flow (release.yml runs from the tag's tree, skip-if-release-exists guard); staged rollout per v1.1.0 precedent.
- Per origin R2: before tagging, re-diff `c1745e8..iOS-tip` and triage any delta through the bounded fold-in rule.

**Test scenarios:**
- Test expectation: none — release chore; CI must be green.

**Verification:** release build produced; CHANGELOG curated; staged rollout started (per user instruction at release time — no auto-merge/auto-release).

---

## System-Wide Impact

- **Interaction graph:** `WalkControllerImpl.state` gains a new @Singleton observer (SeekOrchestrator) — its failure must never affect walk recording (supervised, defended collects). Audio focus now has one more transient consumer interleaving with bell, stones, soundscapes, voice guide, and talk recording — the suppression matrix in U5 is the contract.
- **Error propagation:** engine/orchestrator failures degrade seek senses silently but never the walk itself; persistence failures surface through the existing recordEvent error path.
- **State lifecycle risks:** cached-`:tracker` second-walk gates extended to seek (U8 cross-product tests); restore-path filter prevents stale-state engine boots; fog layers self-heal after lock/unlock.
- **API surface parity:** `.pilgrim` export/import and share payload carry the new event vocabulary (U4 audit); older app builds importing newer exports drop unknown manifest events (they never route through the enum fallback); the new `UNKNOWN` fallback protects v1.2.0+ readers only.
- **Integration coverage:** the U9 simulated-walk contract test and the two device QA milestones cover what unit mocks cannot (focus arbitration, notification throttling on OEM firmware, Mapbox layer lifecycle).
- **Unchanged invariants:** wander/meditation walk behavior, existing scenery determinism for non-gate walks, collective counter, Glance widget, and backend endpoints are untouched.

---

## Risk Analysis & Mitigation

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Mapbox runtime-layer lifecycle differs from iOS (self-heal, off-screen clamp) | Med | Med | Pure fog/crescent models tested on JVM; mid-phase device smoke check; self-heal probe ported day one |
| Audio-focus regression in the multi-consumer matrix | Med | High | U5 suppression-matrix tests + carved-out PR #47 phone-call probe in Phase 14 QA |
| OEM notification throttling swallows glance updates | Med | Med | Bucket-granularity throttle (Samsung rule); pocket test in QA |
| Room migration on production devices (if U4 needs a column) | Low | High | Established MIGRATION pattern + raw-SQLite migration tests; staged rollout |
| iOS lands 1.8.1 review churn mid-port | Med | Low | Origin R2 bounded fold-in routes deltas to the owning unit |
| Scenery perf on deep journals with new animated types | Low | Med | Existing windowing/culling; U16 deep-journal check; reduce-motion path |
| Displacement-only stillness voting feels wrong on-device | Med | Low | Matches iOS's shipped fallback; step-signal upgrade deferred as follow-up |

---

## Phased Delivery

- **Phase A — Engine:** U1, U2, U3, U4 (pure domain + persistence; everything dark)
- **Phase B — Senses:** U5, U6, U7 → mid-phase device smoke check
- **Phase C — Experience:** U8, U9, U10
- **Phase D — Arrival:** U11, U12, U13 → Phase 14 consolidated device QA (incl. PR #47 probes) → ship flip
- **Phase E — Scenery:** U14, U15, U16 → Phase 15 consolidated device QA
- **Release:** U17 (single v1.2.0)

---

## Documentation / Operational Notes

- Every port unit's `/ios-parity port` spec lands under `docs/parity/` pinned to `c1745e8` (R10) before implementation of that unit begins.
- Capture post-landing learnings in `docs/solutions/` (currently empty) — the fog-layer lifecycle and multi-consumer audio matrix are prime candidates.
- Origin R2 process rule: any iOS delta before v1.2.0 ships is re-diffed and triaged (fold-in vs user re-triage) — event-driven on the Apple 1.8.1 verdict.
- Per standing instruction: PRs are presented for review; no auto-merge.

---

## Sources & References

- **Origin document:** `docs/brainstorms/2026-07-14-ios-main-parity-retarget-requirements.md`
- iOS reference (read-only, pinned `c1745e8`): `../pilgrim-ios` — seek plan docs `docs/brainstorms/2026-07-06-seek-mode-requirements.md`, `docs/plans/2026-07-06-001-feat-seek-mode-plan.md`; shipped sources per the port-spec catalog (SeekChain/Generator/Seed/Engine/StillnessDetector/FogModel/Glance/Persistence, `PilgrimMapView+SeekFog/+SeekWisp`, `ActiveWalkViewModel+Seek`, `SeekGatewayView`, `SeekSummarySection`, `SceneryGenerator`, `SceneryItemView`, `CairnStonesShape`, `InkScrollView+Scenery`, `WalkModeFootprints`, `ScrollHapticEngine`)
- Android surfaces: see Context & Research file list
- Related PRs: iOS #45/#47 (prior parity sweep), Android #177–#193 (v1.7.0 sweep)
