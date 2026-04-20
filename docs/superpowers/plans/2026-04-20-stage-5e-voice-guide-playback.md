# Stage 5-E Implementation Plan — Voice-Guide Prompt Playback

**Spec:** `docs/superpowers/specs/2026-04-20-stage-5e-voice-guide-playback-design.md`
**Worktree:** `/Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5e-voice-guide-playback`
**Branch:** `stage-5e/voice-guide-playback` off `main`

## Prefix for every test run

```sh
export JAVA_HOME=~/.asdf/installs/java/temurin-17.0.18+8 && export PATH=$JAVA_HOME/bin:$PATH
```

Full phase-gate verify:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Task ordering

Each task ends in a working commit. Tests ship with their task — no deferrals.

---

### Task 0 — Worktree + commit spec + plan

```sh
cd /Users/rubberduck/GitHub/momentmaker/pilgrim-android
git worktree add /Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5e-voice-guide-playback -b stage-5e/voice-guide-playback main
cp docs/superpowers/specs/2026-04-20-stage-5e-voice-guide-playback-design.md \
   /Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5e-voice-guide-playback/docs/superpowers/specs/
cp docs/superpowers/plans/2026-04-20-stage-5e-voice-guide-playback.md \
   /Users/rubberduck/GitHub/momentmaker/.worktrees/stage-5e-voice-guide-playback/docs/superpowers/plans/
```

Commit: `docs(plan): Stage 5-E voice-guide playback design + plan`

---

### Task 1 — `VoiceGuideScheduler` + pure-JVM tests

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideScheduler.kt`

Port of iOS's `VoiceGuideScheduler.swift`. Pure Kotlin, no Android deps. Uses the project's `Clock` interface for time.

```kotlin
class VoiceGuideScheduler(
    private val context: SchedulerContext,
    prompts: List<VoiceGuidePrompt>,
    private val scheduling: PromptDensity,
    private val clock: Clock,
    private val random: (bound: Int) -> Int = { kotlin.random.Random.Default.nextInt(it) },
) {
    enum class SchedulerContext(val settlingSec: Int, val closingSec: Int) {
        Walk(settlingSec = 20 * 60, closingSec = 45 * 60),
        Meditation(settlingSec = 5 * 60, closingSec = 15 * 60),
    }

    private enum class Phase { Settling, Deepening, Closing }

    private val prompts = prompts.sortedBy { it.seq }
    private var sessionStartMillis: Long? = null
    private var lastPlayedMillis: Long? = null
    private var nextIntervalSec: Int = scheduling.initialDelaySec
    private val played = mutableSetOf<String>()
    private var isPlaying = false
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
        val span = (scheduling.densityMaxSec - scheduling.densityMinSec).coerceAtLeast(1)
        nextIntervalSec = scheduling.densityMinSec + random(span)
    }

    /** Abort a play that started but never completed (error/focus loss). */
    fun markPlaybackAborted() { isPlaying = false }

    fun decide(isPaused: Boolean, isRecordingVoice: Boolean): VoiceGuidePrompt? {
        val started = sessionStartMillis ?: return null
        if (isPaused || isRecordingVoice || isPlaying) return null
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
        val phaseFiltered = prompts.filter { it.phase == null || it.phase == phase }
        val pool = phaseFiltered.ifEmpty { prompts }
        pool.firstOrNull { it.id !in played }?.let { return it }
        prompts.firstOrNull { it.id !in played }?.let { return it }
        played.clear()
        return pool.firstOrNull()
    }
}
```

**Test file:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideSchedulerTest.kt`

Pure JUnit, no Robolectric. Uses `FakeClock` (advanced via a `var now: Long`).

Cases:
1. `decide returns null before start()`
2. `decide returns null before initialDelaySec elapses`
3. `decide returns a prompt at exactly initialDelaySec`
4. `after markPlayed, decide returns null until nextIntervalSec`
5. `phase settling selects only "settling" phased prompts when any exist`
6. `phase with no matching prompts falls back to all-prompt pool`
7. `prompts with null phase are always selectable`
8. `cycling — after all played, history resets and cycle restarts with pool.first`
9. `setPostMeditationSilence blocks decide for the configured window`
10. `isPaused short-circuits decide`
11. `isRecordingVoice short-circuits decide`
12. `isPlaying short-circuits decide`
13. `markPlaybackAborted resets isPlaying without marking prompt played`
14. `markPlayed draws next interval within densityMin..Max`

Commit: `feat(voiceguide): Stage 5-E scheduler`

---

### Task 2 — `VoiceGuidePlayer` interface + `ExoPlayerVoiceGuidePlayer` + Robolectric test

**Files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuidePlayer.kt` (interface + State sealed class)
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/ExoPlayerVoiceGuidePlayer.kt`

Copy `ExoPlayerVoicePlaybackController.kt` as the template. Key changes:
- Plays from a `File`, not a `VoiceRecording` entity.
- `onFinished: () -> Unit` callback on completion OR focus loss (both paths call it, guarded by AtomicBoolean).
- `USAGE_MEDIA + CONTENT_TYPE_SPEECH` audio attributes.
- `handleAudioFocus = false` on ExoPlayer — coordinator owns focus.
- Requests via `audioFocus.requestMediaPlayback(onLossListener = { stop() })`.

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
```

Cleanup guard pattern (from `BellPlayer`):
```kotlin
private val completionFired = AtomicBoolean(false)

private fun fireCompletionOnce() {
    if (!completionFired.compareAndSet(false, true)) return
    val cb = onFinishedRef.getAndSet(null) ?: return
    cb()
}
```
Called from: MediaSource completion listener, `onAudioFocusLost`, `stop()`, `release()`.

**Test file:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/ExoPlayerVoiceGuidePlayerTest.kt`

Robolectric + `@Config(sdk = [34], application = Application::class)`. Mandatory per CLAUDE.md:

1. `play constructs ExoPlayer with correct audio attributes without crashing`
2. `play requests audio focus via coordinator`
3. `play without focus grant enters Error state`
4. `stop abandons focus and transitions to Idle`
5. `audio focus loss invokes onFinished exactly once`
6. `release releases ExoPlayer native resources`

Uses a `FakeAudioFocusCoordinator` that records invocations + controls grant/loss.

Commit: `feat(voiceguide): Stage 5-E ExoPlayer-backed player`

---

### Task 3 — `VoiceGuidePlaybackScope` qualifier + DI wiring

**Files:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuidePlaybackScope.kt` — `@Qualifier @Retention(BINARY) annotation class VoiceGuidePlaybackScope`
- `app/src/main/java/org/walktalkmeditate/pilgrim/di/VoiceGuideModule.kt` — MODIFIED

Additions to `VoiceGuideModule`:

```kotlin
@Binds @Singleton
abstract fun bindVoiceGuidePlayer(impl: ExoPlayerVoiceGuidePlayer): VoiceGuidePlayer

// in companion:
@Provides @Singleton @VoiceGuidePlaybackScope
fun provideVoiceGuidePlaybackScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

No new test here — the DI graph is exercised by the orchestrator + PilgrimApp tests.

Commit: `feat(voiceguide): Stage 5-E DI — scope + player binding`

---

### Task 4 — `VoiceGuideOrchestrator` + tests

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestrator.kt`

```kotlin
@Singleton
class VoiceGuideOrchestrator @Inject constructor(
    private val walkController: WalkController,
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
        var exitingMeditation = false

        walkController.state.collect { state ->
            when (state) {
                is WalkState.Active -> {
                    meditationJob?.cancel(); meditationJob = null
                    if (walkJob == null) {
                        walkJob = scope.launch {
                            runSchedulerLoop(
                                ctx = VoiceGuideScheduler.SchedulerContext.Walk,
                                postMedSilenceSec = if (exitingMeditation) randomSilenceSec() else 0,
                            )
                        }
                        exitingMeditation = false
                    }
                }
                is WalkState.Meditating -> {
                    walkJob?.cancel(); walkJob = null
                    exitingMeditation = true
                    if (meditationJob == null) {
                        meditationJob = scope.launch {
                            runSchedulerLoop(ctx = VoiceGuideScheduler.SchedulerContext.Meditation)
                        }
                    }
                }
                is WalkState.Paused, WalkState.Idle, is WalkState.Finished -> {
                    walkJob?.cancel(); walkJob = null
                    meditationJob?.cancel(); meditationJob = null
                    exitingMeditation = false
                    try { player.stop() } catch (t: Throwable) { Log.w(TAG, "player.stop", t) }
                }
            }
        }
    }

    private suspend fun runSchedulerLoop(
        ctx: VoiceGuideScheduler.SchedulerContext,
        postMedSilenceSec: Int = 0,
    ) {
        val pack = eligiblePackOrNull() ?: return
        val prompts: List<VoiceGuidePrompt>
        val density: PromptDensity
        when (ctx) {
            VoiceGuideScheduler.SchedulerContext.Walk -> {
                prompts = pack.prompts
                density = pack.scheduling
            }
            VoiceGuideScheduler.SchedulerContext.Meditation -> {
                prompts = pack.meditationPrompts ?: return
                density = pack.meditationScheduling ?: return
            }
        }
        val sched = VoiceGuideScheduler(ctx, prompts, density, clock)
        sched.start()
        if (postMedSilenceSec > 0) sched.setPostMeditationSilence(postMedSilenceSec)

        try {
            while (currentCoroutineContext().isActive) {
                val prompt = sched.decide(isPaused = false, isRecordingVoice = false)
                if (prompt != null) {
                    val file = fileStore.fileForPrompt(prompt.r2Key)
                    if (file.exists() && file.length() > 0L) {
                        sched.markPlaybackStarted()
                        try {
                            player.play(file) { sched.markPlayed(prompt.id) }
                        } catch (t: Throwable) {
                            Log.w(TAG, "player.play failed for ${prompt.r2Key}", t)
                            sched.markPlaybackAborted()
                        }
                    } else {
                        Log.w(TAG, "prompt file missing/empty: ${prompt.r2Key}")
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        } finally {
            try { player.stop() } catch (t: Throwable) { Log.w(TAG, "player.stop in finally", t) }
        }
    }

    private suspend fun eligiblePackOrNull(): VoiceGuidePack? {
        val packId = selection.selectedPackId.value ?: return null
        manifestService.initialLoad.await()
        val pack = manifestService.pack(id = packId) ?: return null
        return if (withContext(Dispatchers.IO) { fileStore.isPackDownloaded(pack) }) pack else null
    }

    private fun randomSilenceSec(): Int =
        kotlin.random.Random.nextInt(POST_MED_SILENCE_MIN_SEC, POST_MED_SILENCE_MAX_SEC + 1)

    private companion object {
        const val TAG = "VoiceGuideOrch"
        const val TICK_INTERVAL_MS = 30_000L
        const val POST_MED_SILENCE_MIN_SEC = 10 * 60   // 10 min
        const val POST_MED_SILENCE_MAX_SEC = 15 * 60   // 15 min
    }
}
```

**Test file:** `app/src/test/java/org/walktalkmeditate/pilgrim/audio/voiceguide/VoiceGuideOrchestratorTest.kt`

Uses fakes: `FakeVoiceGuidePlayer` (records play/stop + captures onFinished), mutable `WalkController.state` surrogate (or direct `MutableStateFlow<WalkState>`), in-memory manifest service stand-in with a preloaded pack, real `VoiceGuideFileStore` on Robolectric tmp, real `VoiceGuideSelectionRepository` on tmp DataStore.

Because the orchestrator's scheduler loop uses `delay(30_000)`, tests use `runTest(UnconfinedTestDispatcher())` + `advanceTimeBy` to step past the tick boundary.

Cases:
1. `no selection → no scheduler spawn on Active`
2. `selection + not-downloaded pack → no spawn`
3. `eligible pack + Active → walk scheduler launched, eventually plays after initialDelay`
4. `Active → Meditating → walk job cancelled, meditation job launched`
5. `Meditating → Active → meditation cancelled, walk relaunched with silence buffer`
6. `Finished → both jobs cancelled, player.stop() called`
7. `Paused → both jobs cancelled, player.stop() called`
8. `Meditating without pack.meditationPrompts → no meditation scheduler`
9. `selection cleared mid-walk → walk scheduler self-terminates on next iteration (pack becomes ineligible)` — optional, stretch.

Commit: `feat(voiceguide): Stage 5-E orchestrator`

---

### Task 5 — Wire orchestrator into `PilgrimApp.onCreate`

**File:** `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt` — MODIFIED

Add next to the existing `voiceGuideDownloadObserver.start()`:

```kotlin
@Inject lateinit var voiceGuideOrchestrator: VoiceGuideOrchestrator

// in onCreate, after voiceGuideDownloadObserver.start():
voiceGuideOrchestrator.start()
```

No new tests; existing orchestrator tests cover the start path.

Verify:
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Commit: `feat(voiceguide): Stage 5-E wire orchestrator into PilgrimApp`

---

## Self-review checklist

- [x] Spec coverage: Scheduler, Player, Orchestrator, DI, PilgrimApp wiring — one task each.
- [x] No placeholders.
- [x] Each task ends in a commit + tests.
- [x] `VoiceGuideScheduler` type surface is consistent across tasks (used by orchestrator test fakes).
- [x] `VoiceGuidePlayer` interface defined before the impl test references it.
- [x] ExoPlayer `.build()` exercised in Robolectric per CLAUDE.md.
- [x] Stage 5-D M1 (CAS-stuck-true on pre-cancelled scope) — not applicable here; the orchestrator uses simple Job cancellation, no CAS.
- [x] Stage 5-E pattern audit: every `@Singleton` observer defending suspend calls inside `collect` (lesson from Stage 5-D closing review). Orchestrator's `collect { when (state) { ... } }` doesn't call suspend functions that can throw application-level exceptions in its branches, but the `try/catch` around `player.stop()` and `player.play()` is defensive.
- [x] `viewModelScope.launch defaults to Main` — orchestrator runs on `@VoiceGuidePlaybackScope` which is `Dispatchers.Default`; file I/O explicitly wrapped in `withContext(Dispatchers.IO)` in `eligiblePackOrNull`.

## Risks

- **`delay(30_000)` inside `runSchedulerLoop`** — tests must advance virtual time past 30s to exercise tick behavior. `UnconfinedTestDispatcher` + `advanceTimeBy` is the pattern.
- **Silence buffer race**: the `exitingMeditation` flag is set in the `Meditating` branch but read (and cleared) in the `Active` branch. State-flow emissions are serial in `collect`, so no concurrency issue. But: `Meditating → Finished` (user skips "end meditation" and hits "finish walk" directly) should NOT trigger a silence buffer — the `Finished` branch clears `exitingMeditation`. Covered.
- **`manifestService.initialLoad.await()` in `eligiblePackOrNull`** — at app cold start with a pending walk (restore path), init might still be loading. Await is correct; only risk is if init crashes, but the finally in init always completes the Deferred.
- **ExoPlayer lazy-init on main looper** — matches the 2-F precedent; test under Robolectric drives via a TestLooper.
