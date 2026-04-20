# Stage 5-E Design — Voice-Guide Prompt Playback

**Date:** 2026-04-20
**Author:** Pilgrim Android port
**Status:** Design — pending approval

## Context

Stages 5-A through 5-D wired everything *around* voice guides: the meditation state machine (5-A), the temple bell for meditation boundaries (5-B), the manifest fetcher (5-C), and the picker + download flow (5-D). A user can now browse packs, download one, and select it — but **nothing plays**. Stage 5-E is the bridge: take the selected, downloaded pack and play its prompts at the right moments during a walk or meditation session.

## Scope

**In scope:**
- **`VoiceGuideScheduler`** — pure Kotlin state machine ported 1:1 from iOS's `VoiceGuideScheduler.swift`. Takes elapsed seconds, play history, and a `PromptDensity` config; decides whether/what to play on each 30-second tick. Phase-gated prompt selection (settling / deepening / closing).
- **`VoiceGuidePlayer`** — ExoPlayer-backed (reuse Stage 2-F's `ExoPlayerVoicePlaybackController` pattern). Plays a single AAC file with `USAGE_MEDIA + CONTENT_TYPE_SPEECH`. Uses the existing `AudioFocusCoordinator.requestMediaPlayback` so voice-guide playback naturally yields to voice-memo recording (single-owner coordinator).
- **`VoiceGuideOrchestrator`** — `@Singleton` that observes `WalkController.state` + `VoiceGuideCatalogRepository` + `VoiceGuideSelectionRepository`. Spawns walk- or meditation-context scheduler coroutines on state transitions; tears them down on end. Wires the scheduler's "play X now" callback to the player.
- **Integration with `WalkState.Active` and `WalkState.Meditating`** — walk guide pauses when meditation starts; meditation guide (separate scheduler, separate thresholds, separate pack.meditationPrompts) runs during meditation; post-meditation silence buffer (random 10–15 min) before walk guide resumes.
- **Tests:**
  - Pure-Kotlin algorithm tests for `VoiceGuideScheduler` (fixed inputs → exact play decisions, phase filtering, history cycling, all guard branches).
  - Robolectric smoke test for `VoiceGuidePlayer` (mandatory per CLAUDE.md — exercises the production ExoPlayer `.build()` path).
  - Orchestrator state-transition tests (fake scheduler + fake player, assert correct spawn/teardown across `Active → Meditating → Active → Finished`).

**NOT in scope (deferred):**
- Soundscape catalog + downloads + playback (Stage 5-F or a parallel stage). The player requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` so when soundscape lands later, it responds to the loss callback by dipping its own volume — no changes to the voice-guide player required then.
- Pack-switch-mid-session UX (iOS has this via the walk options sheet; our MVP doesn't).
- Playback history persistence across sessions (iOS's `history.json`). In Stage 5-E, the scheduler keeps an in-memory `Set<String>` of played prompt ids per *session*; on app restart or new walk, history resets.
- Per-user volume control for voice vs. soundscape mix.
- Prompt fade in/out curves (iOS doesn't fade voice either — hard start/stop is intentional).
- Full device QA pass.

## Recommended approach

A `@Singleton` `VoiceGuideOrchestrator` observes `WalkController.state` and spawns context-appropriate scheduler coroutines (walk ↔ meditation) on transitions, each pumping a 30-second tick into a pure-Kotlin `VoiceGuideScheduler` that emits "play prompt X now" events to a standalone `VoiceGuidePlayer` (ExoPlayer + `AudioFocusCoordinator.requestMediaPlayback`). The scheduler is a 1:1 port of iOS's algorithm — same guard chain, same phase-gated selection, same random-interval draw. Session-scoped in-memory history; no persistence this stage.

### Why this approach

Matches both iOS's proven architecture (separate walk/meditation scheduler instances, shared player singleton, callback-driven decision function) and the project's existing patterns (`MeditationBellObserver`-style long-lived scope, `ExoPlayerVoicePlaybackController` as the playback template). Orchestrator-as-observer is a direct copy of the Stage 5-B bell pattern that works; it naturally rides the walk service's process-alive guarantee without needing to live *in* the service. Using `AudioFocusCoordinator.requestMediaPlayback` (instead of rolling our own per-play request like `BellPlayer`) gives us free single-owner coordination with the voice-memo recorder — when the user records a memo mid-walk, voice guide stops automatically, no bespoke plumbing.

### What was considered and rejected

- **Scheduler hosted in `WalkTrackingService`** (tethered to service scope): cleaner lifecycle but mixes audio concerns into the location/tracking service and harder to test.
- **MediaPlayer (matching `BellPlayer`) instead of ExoPlayer**: simpler API but ExoPlayer has first-class pause/cancel semantics for long-form audio, reusing Stage 2-F precedent reduces divergence, and media3 is Google's path forward.
- **Single combined Scheduler handling both walk and meditation**: iOS uses two separate instances, switches cleanly on state, and keeps thresholds scoped per-context. One scheduler would have to branch internally on current state — more state, harder to test.
- **Persist play history to DataStore/JSON this stage**: iOS does it, and it's nice-to-have, but the user explicitly deferred it. In-memory per-session history is complete enough to exercise the "prefer unplayed" / "cycle when exhausted" logic — persistence is a drop-in later (just swap the `HashSet` for a file-backed store).
- **Custom per-play `AudioFocusRequest` (BellPlayer pattern)**: works, but voice guide is lifecycle-bound (not fire-and-forget like a bell) and shares with the voice-memo recorder — the coordinator's single-owner semantics are exactly what we want.
- **Elapsed-seconds from `WalkStats.activeWalkingMillis`** (ticker-flow-derived): iOS uses wall-clock elapsed-since-start. Matching iOS is simpler and avoids entangling voice-guide scheduling with the paused-time-exclusion semantics of the walk-stats tracker. Scheduler uses its own `clock.now() - sessionStartMillis`.

## Architecture

### Data flow

```
WalkController.state: StateFlow<WalkState>
   │  Idle / Active / Paused / Meditating / Finished
   ▼
VoiceGuideOrchestrator  (@Singleton, scope = SupervisorJob + Default)
   │   — observes WalkController.state
   │   — also observes VoiceGuideSelectionRepository.selectedPackId
   │   — also observes VoiceGuideManifestService.packs + VoiceGuideFileStore
   │   — gates: pack selected? downloaded?
   ▼
spawns per-session:
   ┌─ Walk-context coroutine ────────────────────────┐
   │  VoiceGuideScheduler (walk: 20/45 min thresholds)│
   │     + pack.scheduling + pack.prompts             │
   │     tick every 30 s                              │
   │     → onShouldPlay(prompt) ─► VoiceGuidePlayer   │
   └──────────────────────────────────────────────────┘
   
   ┌─ Meditation-context coroutine (only when state = Meditating) ─┐
   │  VoiceGuideScheduler (meditation: 5/15 min thresholds)         │
   │     + pack.meditationScheduling + pack.meditationPrompts       │
   │     tick every 30 s                                            │
   │     → onShouldPlay(prompt) ─► VoiceGuidePlayer                 │
   └────────────────────────────────────────────────────────────────┘

VoiceGuidePlayer (@Singleton)
   │  ExoPlayer + AudioFocusCoordinator.requestMediaPlayback
   │  plays <filesDir>/voice_guide_prompts/<r2Key>
   │  onCompletion → calls scheduler.markPlayed(promptId)
```

### Key types

#### `VoiceGuideScheduler` (new, pure Kotlin, no Android deps)

```kotlin
class VoiceGuideScheduler(
    private val context: SchedulerContext,
    private val prompts: List<VoiceGuidePrompt>,
    private val scheduling: PromptDensity,
    private val clock: Clock,
    private val random: () -> Int = { Random.Default.nextInt(it) },
) {
    enum class SchedulerContext(val settlingSec: Int, val closingSec: Int) {
        Walk(settlingSec = 20 * 60, closingSec = 45 * 60),
        Meditation(settlingSec = 5 * 60, closingSec = 15 * 60),
    }

    private enum class Phase { Settling, Deepening, Closing }

    // Mutable state — all access from the owning coroutine, single-threaded.
    private var sessionStartMillis: Long? = null
    private var lastPlayedMillis: Long? = null
    private var nextIntervalSec: Int = scheduling.initialDelaySec
    private val played: MutableSet<String> = mutableSetOf()
    private var isPlaying: Boolean = false
    private var silenceUntilMillis: Long? = null

    fun start() { sessionStartMillis = clock.now() }
    fun setPostMeditationSilence(durationSec: Int) {
        silenceUntilMillis = clock.now() + durationSec * 1_000L
    }
    fun markPlaybackStarted() { isPlaying = true }
    fun markPlayed(promptId: String) {
        played += promptId
        isPlaying = false
        lastPlayedMillis = clock.now()
        // Draw next interval within density bounds.
        val range = (scheduling.densityMaxSec - scheduling.densityMinSec).coerceAtLeast(1)
        nextIntervalSec = scheduling.densityMinSec + random(range)
    }

    /**
     * Decision function. Returns the prompt to play right now, or null
     * if nothing should play yet. The caller is responsible for
     * actually playing it and calling [markPlaybackStarted] / [markPlayed].
     */
    fun decide(isPaused: Boolean, isRecordingVoice: Boolean): VoiceGuidePrompt? {
        val started = sessionStartMillis ?: return null
        if (isPaused) return null
        if (isRecordingVoice) return null
        if (isPlaying) return null
        val now = clock.now()
        silenceUntilMillis?.let { if (now < it) return null }
        val elapsedSec = ((now - started) / 1_000L).toInt()
        if (elapsedSec < scheduling.initialDelaySec) return null
        lastPlayedMillis?.let {
            val sinceLastSec = ((now - it) / 1_000L).toInt()
            if (sinceLastSec < nextIntervalSec) return null
        }
        return nextPrompt(elapsedSec)
    }

    private fun phaseFor(elapsedSec: Int): Phase = when {
        elapsedSec < context.settlingSec -> Phase.Settling
        elapsedSec >= context.closingSec -> Phase.Closing
        else -> Phase.Deepening
    }

    private fun nextPrompt(elapsedSec: Int): VoiceGuidePrompt? {
        val phase = phaseFor(elapsedSec).name.lowercase()
        val sorted = prompts.sortedBy { it.seq }
        val phaseFiltered = sorted.filter { p -> p.phase == null || p.phase == phase }
        val pool = phaseFiltered.ifEmpty { sorted }
        // Step 1: prefer unplayed within phase pool
        pool.firstOrNull { it.id !in played }?.let { return it }
        // Step 2: fallback to first unplayed across all sorted
        sorted.firstOrNull { it.id !in played }?.let { return it }
        // Step 3: cycle — reset history, return pool's first
        played.clear()
        return pool.firstOrNull()
    }
}
```

#### `VoiceGuidePlayer` (new, ExoPlayer-backed, `@Singleton`)

```kotlin
interface VoiceGuidePlayer {
    val state: StateFlow<State>
    fun play(file: File, onFinished: () -> Unit)
    fun stop()
    fun release()

    sealed class State {
        data object Idle : State()
        data object Playing : State()
        data class Error(val reason: String) : State()
    }
}

@Singleton
class ExoPlayerVoiceGuidePlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioFocus: AudioFocusCoordinator,
) : VoiceGuidePlayer {
    // Lazy-init ExoPlayer on main looper — copy 2-F pattern.
    // USAGE_MEDIA + CONTENT_TYPE_SPEECH; handleAudioFocus = false (coordinator owns focus).
    // requestMediaPlayback via coordinator → single-owner across recorder + playback.
    // On MediaPlayer completion listener → invoke onFinished() then stop().
    // On audio-focus loss → stop() (do not auto-resume; user re-initiates implicitly via next scheduler tick).
}
```

Sharing `AudioFocusCoordinator.requestMediaPlayback` means:
- Voice recording (`WalkVoiceRecordingManagement`) holds focus → our guide won't start.
- Our guide holds focus → a voice-memo tap abandons guide focus, guide stops.
- Free interruption handling without bespoke plumbing.

#### `VoiceGuideOrchestrator` (new, `@Singleton`)

```kotlin
@Singleton
class VoiceGuideOrchestrator @Inject constructor(
    private val walkController: WalkController,
    private val catalog: VoiceGuideCatalogRepository,
    private val manifestService: VoiceGuideManifestService,
    private val fileStore: VoiceGuideFileStore,
    private val selection: VoiceGuideSelectionRepository,
    private val player: VoiceGuidePlayer,
    private val clock: Clock,
    @VoiceGuidePlaybackScope private val scope: CoroutineScope,
) {
    fun start() { scope.launch { observe() } }

    private suspend fun observe() {
        var walkJob: Job? = null
        var meditationJob: Job? = null
        walkController.state.collect { state ->
            when (state) {
                is WalkState.Active -> {
                    meditationJob?.cancel(); meditationJob = null
                    walkJob = walkJob ?: spawnWalkScheduler()
                }
                is WalkState.Meditating -> {
                    // Walk scheduler pauses via isPaused flag; don't cancel.
                    walkJob?.cancel(); walkJob = null  // or pause-sentinel
                    meditationJob = meditationJob ?: spawnMeditationScheduler()
                }
                is WalkState.Paused, WalkState.Idle, is WalkState.Finished -> {
                    walkJob?.cancel(); walkJob = null
                    meditationJob?.cancel(); meditationJob = null
                    player.stop()
                }
            }
        }
    }

    private fun spawnWalkScheduler(): Job = scope.launch {
        val pack = eligiblePackOrNull() ?: return@launch
        val sched = VoiceGuideScheduler(Walk, pack.prompts, pack.scheduling, clock)
        runTickLoop(sched, pack)
    }

    private fun spawnMeditationScheduler(): Job = scope.launch {
        val pack = eligiblePackOrNull() ?: return@launch
        val prompts = pack.meditationPrompts ?: return@launch
        val sched = VoiceGuideScheduler(Meditation, prompts, pack.meditationScheduling ?: return@launch, clock)
        runTickLoop(sched, pack)
    }

    private suspend fun runTickLoop(sched: VoiceGuideScheduler, pack: VoiceGuidePack) {
        sched.start()
        while (currentCoroutineContext().isActive) {
            val prompt = sched.decide(isPaused = false, isRecordingVoice = false)
            if (prompt != null) {
                val file = fileStore.fileForPrompt(prompt.r2Key)
                if (file.exists() && file.length() > 0) {
                    sched.markPlaybackStarted()
                    player.play(file) { sched.markPlayed(prompt.id) }
                }
            }
            delay(TICK_INTERVAL_MS)
        }
    }
    private companion object { const val TICK_INTERVAL_MS = 30_000L }
}
```

The orchestrator is registered in `PilgrimApp.onCreate` via `voiceGuideOrchestrator.start()` (same pattern as `MeditationBellObserver` and `VoiceGuideDownloadObserver`).

### Post-meditation silence buffer

When `Meditating → Active` transition fires (end of meditation), orchestrator calls `walkScheduler.setPostMeditationSilence(durationSec)` with a random value between 600–900s (10–15 min) before spawning/resuming the walk scheduler. The scheduler's `decide` short-circuits while `silenceUntilMillis > clock.now()`.

### Scope qualifier

New: `@VoiceGuidePlaybackScope` for the orchestrator's long-lived scope. `SupervisorJob() + Dispatchers.Default`. Same pattern as `MeditationBellScope`, `VoiceGuideCatalogScope`.

### DI updates

- `VoiceGuideModule` gains:
  - `@Binds VoiceGuidePlayer` → `ExoPlayerVoiceGuidePlayer`
  - `@Provides @VoiceGuidePlaybackScope CoroutineScope`
- `PilgrimApp` injects + starts `VoiceGuideOrchestrator`.

## Testing strategy

**`VoiceGuideSchedulerTest`** (pure JVM, no Robolectric):
- `decide` returns null before `start()` is called.
- `decide` returns null before `initialDelaySec` elapses.
- `decide` returns a prompt at exactly `initialDelaySec`.
- After `markPlayed`, `decide` returns null until `nextIntervalSec` elapses.
- Phase filtering: prompts with `phase = "opening"` only selected before settling threshold.
- Phase filtering: prompts with `phase = null` always selectable.
- History cycle: after all prompts played, history resets and cycle restarts.
- Post-meditation silence: `setPostMeditationSilence(600)` blocks `decide` for 600 seconds.
- Pause / record guards short-circuit.

Uses a `FakeClock: Clock` that returns a controlled `now()`. Tests advance time directly, no coroutine virtual-time needed.

**`ExoPlayerVoiceGuidePlayerTest`** (Robolectric, per CLAUDE.md mandate):
- Construct the real `ExoPlayer.Builder(...).setAudioAttributes(...).build()` — catches any manifest/attribute validation regression.
- Verify `AudioFocusCoordinator.requestMediaPlayback` invoked with correct listener.
- Verify `stop()` abandons focus and transitions state to `Idle`.

**`VoiceGuideOrchestratorTest`** (Robolectric or pure JVM with fakes):
- `Idle → Active` with eligible pack → walk scheduler launched.
- `Idle → Active` without selection → no scheduler.
- `Active → Meditating → Active` → walk stops, meditation starts, meditation stops, walk resumes with silence buffer.
- `Active → Finished` → all schedulers cancelled, player stopped.
- Selection deselect mid-session → schedulers cancelled, player stopped.
- Pack deleted mid-session → scheduler halts (next tick sees missing file, skips gracefully).

Uses fake `WalkController.state` (MutableStateFlow), fake `VoiceGuidePlayer` that records play calls, fake `Clock`.

## Error & edge-case behaviors

- **Prompt file missing mid-session**: `file.exists()` check before `player.play()`. Skip the tick, keep scheduler running. Pack state transitions to NotDownloaded on next catalog re-read (via `applyProgress`), but scheduler is already committed to this session — it'll keep trying and skipping until the session ends.
- **Audio focus denied**: `player.play` returns (state becomes Error); scheduler's `markPlayed` is NOT called, so prompt isn't marked played and will be retried. But `isPlaying = true` was set → must reset in the player's onFinished-or-error path.
- **User records voice memo mid-guide**: coordinator's single-owner semantics pull focus; our player's loss listener stops playback. Scheduler state: `isPlaying = true` stuck. Fix: player's loss listener invokes the `onFinished` callback (which calls `markPlayed` with the prompt id) so scheduler can re-enter the decision loop cleanly.
- **Walk finished mid-play**: orchestrator cancels the walk scheduler, `player.stop()` called, loss listener does its cleanup. Scheduler coroutine dies; fresh one spawns next walk.
- **Pack selected but not downloaded**: `eligiblePackOrNull()` returns null → no scheduler spawn.
- **Meditating with pack that has no `meditationPrompts`**: `spawnMeditationScheduler` returns early. Walk scheduler also doesn't run (we're in Meditating state). Silent no-op — no meditation guide this session.
- **Scheduler coroutine cancellation**: cooperative via `currentCoroutineContext().isActive`. The `delay(30_000)` is a cancellation point.

## Sequencing

1. `VoiceGuideScheduler` + tests (pure Kotlin, no Android).
2. `VoiceGuidePlayer` + `ExoPlayerVoiceGuidePlayer` + Robolectric test.
3. `VoiceGuidePlaybackScope` qualifier + `VoiceGuideModule` updates.
4. `VoiceGuideOrchestrator` + tests.
5. `PilgrimApp.onCreate` wiring to start the orchestrator.
6. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — full verify.

## Non-goals (explicit)

- **No UI surface in Stage 5-E**. The existing picker (5-D) is the only UI touching voice guides this stage. No volume slider, no "currently playing" indicator on Active Walk — that's polish for a later stage.
- **No persistence of play history.** Matches the intent's explicit defer.
- **No soundscape.** The player requests `GAIN_TRANSIENT_MAY_DUCK` so the future 5-F soundscape handles the loss listener and dips itself — no rewrites needed then.
- **No pack-switch-mid-session.** Selection changes mid-walk cancel the current scheduler cleanly; the next walk picks up the new selection.

## References

- Stage 5-C spec: `docs/superpowers/specs/2026-04-20-stage-5c-voice-guide-manifest-design.md`
- Stage 5-D spec: `docs/superpowers/specs/2026-04-20-stage-5d-voice-guide-picker-downloads-design.md`
- iOS scheduler: `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuideScheduler.swift`
- iOS player: `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuidePlayer.swift`
- iOS management: `../pilgrim-ios/Pilgrim/Models/Audio/VoiceGuide/VoiceGuideManagement.swift`, `MeditationGuideManagement.swift`
- Android ExoPlayer precedent: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/ExoPlayerVoicePlaybackController.kt`
- Android audio-focus precedent: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/AudioFocusCoordinator.kt`
- Android observer pattern: `app/src/main/java/org/walktalkmeditate/pilgrim/audio/MeditationBellObserver.kt`
