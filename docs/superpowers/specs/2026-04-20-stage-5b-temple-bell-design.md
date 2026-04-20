# Stage 5-B — Temple bell

**Status:** design
**Stage in port plan:** Phase 5 (Meditation + voice guides) — 5-B (second sub-stage)
**Depends on:** Stage 5-A (MeditationScreen / meditation lifecycle), Stage 2-E (`AudioFocusCoordinator`), existing domain-layer `WalkState.Meditating` + reducer + `WalkController.state`.
**iOS reference:** `../pilgrim-ios/Pilgrim/Models/Audio/BellPlayer.swift` (55 lines — `AVAudioPlayer` + medium-impact haptic + `AudioSessionCoordinator`).

## Goal

A soft temple bell chimes at the boundary of a meditation session — once on entry, once on exit. It marks the threshold: the user feels and hears the transition between walking and sitting, and again between sitting and walking. Silent meditation (Stage 5-A) becomes *audibly framed*.

No UI changes. The bell is a pure audio layer observing domain state transitions.

## Non-goals (deferred to later sub-stages)

- **Voice guide catalog** — Stage 5-C (HTTP manifest + Room catalog + WorkManager downloads)
- **Voice guide playback UI** — Stage 5-D (picker + ExoPlayer + rhythm picker)
- **Ambient soundscape** — Stage 5-E (lazy-downloaded loop)
- **Bell haptic pairing.** iOS's `BellPlayer.play` fires a medium-impact `UIImpactFeedbackGenerator` alongside `AVAudioPlayer.play`. Compose's `LocalHapticFeedback` only works in composable scope; the `MeditationBellObserver` is a `@Singleton` outside composition. Adding `android.os.Vibrator` requires the `VIBRATE` permission (not currently declared). Haptic pairing lands in Stage 5-D's voice-guide UI work alongside other `LocalHapticFeedback` call sites, OR as a targeted Stage 5-B-2 if on-device QA finds the bell alone lacks presence.
- **Multiple bell assets / user-selectable bell.** iOS exposes a catalog; we ship one bundled bell and one asset path. Stage 5-C's manifest infrastructure later enables lazy-downloaded alternates.
- **Start-bell vs end-bell differentiation.** iOS uses one asset for both. We match — single `bell.ogg`, played the same way at start + end. If later on-device QA wants a softer end bell, easy to extend.
- **SoundPool.** Industry wisdom says SoundPool for very short low-latency sounds; for a 2-3 second bell played twice per session, `MediaPlayer`'s completion-callback simplicity wins. No measurable latency difference for the bell-on-meditation-boundary use case.
- **Overlap with voice recording.** Stage 2-B voice recording is already paused when the user taps Meditate (they can't both record and meditate — the `MeditationScreen` takes over). No concurrent focus contention.

## Experience arc

1. User on `ActiveWalkScreen`, state = `Active`, taps *Meditate*. `WalkViewModel.startMeditation()` → reducer → state = `Meditating`.
2. **`MeditationBellObserver` detects the Active → Meditating transition.** Fires `BellPlayer.play()`.
3. Bell plays: soft, resonant, ~3s duration. Begins attenuating any concurrent media playback via `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so a podcast ducks briefly then resumes.
4. `ActiveWalkScreen`'s state-class observer nav's to `MeditationScreen`. User sits with the breath.
5. Later — user taps *Done*, or the walk is externally finished. State transitions Meditating → Active (or Meditating → Finished).
6. **`MeditationBellObserver` detects the Meditating → non-Meditating transition.** Fires `BellPlayer.play()` again.
7. Bell plays. User returns to walking (or to the summary screen).

Crucially: **no bell fires on app cold-start even if the restored state is Meditating.** The observer discards its first emission. Only real, observed transitions ring the bell.

## Architecture

### New: `res/raw/bell.ogg` — one bundled asset

A temple-bell-like tone: fundamental ~523 Hz (C5) with harmonic overtones at 1046/1569/2615 Hz, sharp attack, 3-second exponential decay, fade-out over the last 500ms.

**Generation:** the `BUILD` step uses `sox` (available on build hosts) to synthesize the asset:

```bash
sox -n -r 44100 -c 1 app/src/main/res/raw/bell.ogg \
  synth 3.0 pluck C5 pluck G5 pluck E6 \
  gain -3 \
  fade 0.005 3.0 0.5 \
  compand 0.0,1.0 6:-70,-60,-20 -5 -90 0.2
```

Produces a ~30–50 KB OGG Vorbis file. Committed to the repo (not regenerated at build time — determinism + build-host portability).

### New: `BellPlayer.kt` — `@Singleton` audio wrapper

```kotlin
@Singleton
class BellPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusCoordinator,
) {
    fun play()
    fun release()
}
```

- On `play()`: request ducking focus via `AudioFocusCoordinator.requestBellDucking(onLoss = ::onFocusLost)`. If granted, create a fresh `MediaPlayer`, load from `R.raw.bell`, set `OnCompletionListener` to release + abandon focus, call `prepare()` + `start()`. If denied (e.g., phone call active), no-op.
- `onFocusLost()` — rare (bell is transient-ducking; OS shouldn't reclaim). If it does, stop the player and abandon focus.
- `release()` — called by `onCleared`-equivalent hook OR when the app process terminates. Releases any cached player + abandons focus. Since `BellPlayer` is `@Singleton`, `release()` is primarily for test cleanup — in production the player lives for the process's lifetime.

**Why a fresh `MediaPlayer` per play:** `MediaPlayer.reset()` + `setDataSource` + `prepare()` per play is ~50-150ms latency. For a bell that's preceded by a clear user action (tap Meditate, tap Done), 150ms latency is imperceptible. Keeping a pre-prepared `MediaPlayer` alive between sessions would require careful state-machine management (prepared vs started vs completed) and a `reset()` between plays — more complexity than the savings justify.

Alternative trade-off considered (`SoundPool`): pre-loaded audio in memory, ~0ms latency. Requires tracking load completion + stream-completion via polling (no native callback). The bell plays at a UI-paced moment, not a time-critical one. MediaPlayer's completion callback is cleaner. Skipping SoundPool for now; revisit if Stage 5-E's soundscape needs low-latency looping.

### Extended: `AudioFocusCoordinator.requestBellDucking(onLoss)` — new method

```kotlin
/**
 * Request transient focus for a short audible cue (bell, earcon).
 * Uses AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK so concurrent media
 * playback (music, podcast) is briefly attenuated rather than
 * paused. USAGE_MEDIA + CONTENT_TYPE_SONIFICATION routes the
 * sound through speakers (not earpiece) and marks it as a
 * non-speech, non-music audible notification.
 */
fun requestBellDucking(onLossListener: (() -> Unit)? = null): Boolean
```

Same pattern as `requestTransient()` / `requestMediaPlayback()` — delegates to a private `request(usage, contentType, gainMode, onLoss)` that generalizes over the focus parameters. Small refactor of the existing `request(...)` method.

### New: `MeditationBellObserver.kt` — `@Singleton` transition observer

```kotlin
@Singleton
class MeditationBellObserver @Inject constructor(
    controller: WalkController,
    private val bellPlayer: BellPlayer,
    @MeditationBellScope scope: CoroutineScope,
) {
    init {
        scope.launch {
            var lastStateClass: KClass<out WalkState>? = null
            controller.state.collect { state ->
                val curr = state::class
                val prev = lastStateClass
                lastStateClass = curr
                // Skip the first emission — observing the CURRENT state
                // of a @Singleton at app start is not a transition,
                // regardless of what the state happens to be (cold
                // process → Idle; restored session → Meditating).
                if (prev == null) return@collect
                val wasMeditating = prev == WalkState.Meditating::class
                val isMeditating = curr == WalkState.Meditating::class
                if (wasMeditating != isMeditating) {
                    bellPlayer.play()
                }
            }
        }
    }
}
```

One transition → one bell. `wasMeditating xor isMeditating` is true for both directions (Active→Meditating, Meditating→Active, Meditating→Finished). Same bell for both — iOS does likewise.

### New: Hilt module wiring

- `AudioModule` (new or existing) provides:
  - `BellPlayer` as `@Singleton` (Hilt `@Inject constructor` picks this up automatically).
  - `MeditationBellObserver` — needs to be EAGERLY INSTANTIATED at app start so its `init { scope.launch { ... } }` block runs. Standard pattern: add to `PilgrimApp.onCreate()` via Hilt's `@EntryPoint` or via a custom `@Singleton` that injects `MeditationBellObserver` and references it during app init.
  
  Cleanest: inject into `PilgrimApp` (the Application class). Hilt triggers the singleton graph on `@HiltAndroidApp` Application's creation. A reference in `onCreate` forces instantiation.

- `@MeditationBellScope` — a custom qualifier for the `CoroutineScope` injected into the observer. Provided via:
  ```kotlin
  @Provides @Singleton @MeditationBellScope
  fun provideMeditationBellScope(): CoroutineScope =
      CoroutineScope(SupervisorJob() + Dispatchers.Default)
  ```
  `SupervisorJob` so one failed child doesn't cancel the whole scope; `Dispatchers.Default` because the observer work is pure Flow collection + occasional MediaPlayer creation (which is fine on Default, not Main-required). Same shape as `HemisphereRepositoryScope` (Stage 3-D).

## Testing

### `BellPlayerTest` (Robolectric)

- `play - requests bell-ducking focus`: `FakeAudioFocusCoordinator` records the call. Assert `requestBellDucking` was invoked.
- `play - when focus denied, does not create MediaPlayer`: fake returns false; assert no side-effects.
- `play - when focus granted, eventually abandons focus`: harder under Robolectric (MediaPlayer's completion callback is shaky in unit tests). Skip this assertion; rely on integration QA.

### `MeditationBellObserverTest` (plain JUnit + fake player + TestScope)

- First state emission is Idle → no bell (first-emission guard).
- Idle → Active → no bell (not a meditation transition).
- Active → Meditating → 1 bell.
- Meditating → Active → 1 bell.
- Meditating → Finished → 1 bell.
- Meditating → Meditating (same-state re-emission of identical `WalkState.Meditating::class` — won't happen in practice, but guard) → 0 bells (guarded by state-class comparison).
- Full sequence: Idle → Active → Meditating → Active → Meditating → Finished → 4 bells (4 Meditating-boundary crossings).

Observer uses `FakeWalkController` exposing a `MutableStateFlow<WalkState>`. `FakeBellPlayer` counts `play()` calls.

### `AudioFocusCoordinatorTest` (extension)

If the existing test file covers `requestTransient` / `requestMediaPlayback`, add a case for `requestBellDucking`: verify `USAGE_MEDIA + CONTENT_TYPE_SONIFICATION + AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` are set on the request. If the existing tests use a real `AudioManager`, the new test can too (Robolectric provides a shadow).

### What is NOT tested (spec-explicit)

- The bell actually makes sound (Robolectric MediaPlayer is stubbed; device QA verifies).
- Focus ducking of concurrent media (device QA verifies).
- `bell.ogg` file integrity (committed binary; checksum at worktree commit time confirms it decodes).
- Ducking behavior during a phone call (skip — edge case, phone call takes precedence anyway and bell-denial is the correct behavior).

## What's on the commit

- New: `app/src/main/res/raw/bell.ogg` (sox-generated, ~30-50 KB committed binary)
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/BellPlayer.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/MeditationBellObserver.kt`
- New: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/MeditationBellScope.kt` (qualifier annotation + scope provider)
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/BellPlayerTest.kt`
- New: `app/src/test/java/org/walktalkmeditate/pilgrim/audio/MeditationBellObserverTest.kt`
- Modified: `AudioFocusCoordinator.kt` — refactor to parameterize gain mode + content type; add `requestBellDucking`.
- Modified: `AudioFocusCoordinatorTest.kt` — add bell-ducking test.
- Modified: `di/AudioModule.kt` — add `@Provides @MeditationBellScope` for the observer's CoroutineScope.
- Modified: `PilgrimApp.kt` — inject `MeditationBellObserver` to force instantiation on app start.

## Risks and mitigations

- **Bell plays at wrong moment (e.g., mid-walk when state stays Active):** observer's `wasMeditating xor isMeditating` guard ensures only meditation-boundary transitions fire. Full state-sequence test covers.
- **Multiple bells fire on rapid tap (double-tap Meditate):** the reducer dedupes at the controller level — second MeditateStart from Meditating state is a no-op (`reduceMeditating` has no MeditateStart branch). No state transition, no bell. Already protected.
- **Bell tries to play while audio focus is denied:** `BellPlayer.play()` checks focus return, no-ops if denied. No crash, no silent error. Could log for debugging.
- **MediaPlayer leak if `onCompletion` doesn't fire:** belt-and-suspenders `setOnErrorListener` also releases; plus a `Handler.postDelayed(3500) { forceRelease() }` safety net ensures cleanup even if completion callback misses.
- **`bell.ogg` fails to decode on a specific device:** OGG Vorbis is in Android's mandatory-supported codec set (API 21+). Not a real concern.
- **Bell asset gets large (>100 KB APK impact):** sox parameters are tuned for a short ~3s bell at mono 44.1kHz OGG — expected 30-50 KB. If the generated file exceeds ~80 KB, tighten the duration or drop to 22.05 kHz (fine for a bell).
- **Observer scope cancellation on app teardown:** `SupervisorJob` + the scope is leaked at process exit (standard `@Singleton` pattern). No explicit cancel; process death handles cleanup.

## Success criteria

- Tapping *Meditate* during a walk: a soft bell plays. UI transitions to `MeditationScreen` as before.
- Tapping *Done* on `MeditationScreen`: a bell plays. UI pops back to `ActiveWalkScreen` as before.
- Finishing the walk while in meditation (via notification action): bell plays once on the Meditating → Finished transition, UI transitions to the walk summary.
- Rotating the device mid-meditation: no bell fires. Rotating at any other time: no bell.
- Backgrounding + foregrounding the app during meditation: no bell fires (observer sees no transition — app is still Meditating throughout).
- Playing music/podcast during a walk: when the bell fires, the music ducks briefly then resumes.
- Receiving a phone call mid-meditation: bell-play requests focus but may be denied; no crash.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` green.
- No new lint warnings.

## Open questions (answered)

- *Start bell vs end bell distinction?* No. One bell asset, played on both transitions. Matches iOS. If device QA wants softer end bell, easy to add a second asset + per-transition selector.
- *SoundPool vs MediaPlayer?* MediaPlayer. Clean completion callback; one-shot overhead (50-150ms) is imperceptible at UI-paced bell moments.
- *Bundle vs lazy-download?* Bundle. The manifest-driven download infrastructure is Stage 5-C. Bundling one small bell in `res/raw/` for 5-B doesn't pre-bake a pattern 5-C will rip out — `BellPlayer` loads from `R.raw.bell`, and if later Stage 5-E adds user-selectable bells, they can live alongside (lazy file URIs + same `MediaPlayer` wrapper). No architectural lock-in.
- *Where does the bell trigger live?* `@Singleton MeditationBellObserver` subscribing to `WalkController.state`. Not in the reducer (keeps reducer pure), not in the UI (bell should fire even if UI isn't composed — e.g., future widget/notification-driven meditation start). Clean separation.
- *Observer initial-emission handling?* Discard the first emission via a `prev = null` sentinel. Matches Stage 5-A's `hasSeenMeditating` latch lesson ("LaunchedEffect observers fire on first composition — need latch for transitioned-away semantics").
- *Haptic in this stage?* No. `LocalHapticFeedback` is composable-only; `Vibrator` needs manifest permission. Defer to Stage 5-D paired with voice-guide UI work (composable scope).
- *`release()` lifecycle?* Called from test `@After` only. Production: `@Singleton` lives for the process.
