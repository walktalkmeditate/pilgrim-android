// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import android.util.Log
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.voiceguide.PromptDensity
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideFileStore
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestService
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePack
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePrompt
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * App-scoped coordinator that observes the walk-state flow and
 * drives [VoiceGuideScheduler] + [VoiceGuidePlayer] on state
 * transitions. Takes `StateFlow<WalkState>` + `StateFlow<String?>`
 * (selected pack id) via dedicated qualifiers rather than the
 * full `WalkController` + `VoiceGuideSelectionRepository` so tests
 * can inject `MutableStateFlow`s directly. Matches the
 * `MeditationBellObserver` (Stage 5-B) + `VoiceGuideDownloadObserver`
 * (Stage 5-D) pattern — [start] once from `PilgrimApp.onCreate`,
 * subscription lives for the app process.
 *
 * Per-session lifecycle:
 *  - `Active` (and eligible pack downloaded): spawn walk-context
 *    scheduler coroutine. If arriving from `Meditating`, seed a
 *    random 10–15 min post-meditation silence window so the walk
 *    guide doesn't resume mid-breath.
 *  - `Meditating`: cancel walk job, spawn meditation-context
 *    scheduler coroutine (if pack has meditation prompts).
 *  - `Paused` / `Idle` / `Finished`: cancel both, stop player.
 *
 * Each scheduler coroutine runs a 30-second tick loop that feeds
 * decisions to the player. The player's `onFinished` callback
 * closes the loop by advancing the scheduler's play history.
 *
 * MVP behavior notes (deferred deltas from iOS):
 *  - The player's `onFinished` fires on ALL completion paths
 *    including aborts (stop, focus loss, decode error). The
 *    orchestrator's `{ sched.markPlayed(prompt.id) }` lambda
 *    therefore marks prompts played even when cut off mid-stream.
 *    Net effect: the scheduler moves on to a different prompt
 *    next tick rather than replaying the interrupted one — user
 *    hears variety instead of repeats. iOS distinguishes the two.
 *  - The scheduler's `isRecordingVoice` guard is hardcoded `false`
 *    by this orchestrator. Voice-memo recording doesn't pause the
 *    guide via this path (the OS-level audio-focus loss already
 *    stops the player via `AUDIOFOCUS_LOSS_TRANSIENT`). When a
 *    voice-recording-state flow exists, wire it here.
 *  - Deselecting the pack mid-session doesn't abort the current
 *    scheduler (`eligiblePackOrNull` is checked once per session).
 *    Pack-switch mid-session is out of scope per Stage 5-E design.
 */
@Singleton
class VoiceGuideOrchestrator @Inject constructor(
    @VoiceGuideObservedWalkState
    private val walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @VoiceGuideSelectedPackId
    private val selectedPackId: StateFlow<@JvmSuppressWildcards String?>,
    private val manifestService: VoiceGuideManifestService,
    private val fileStore: VoiceGuideFileStore,
    private val player: VoiceGuidePlayer,
    private val clock: Clock,
    @VoiceGuidePlaybackScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        var walkJob: Job? = null
        var meditationJob: Job? = null
        var exitingMeditation = false

        walkState.collect { state ->
            when (state) {
                is WalkState.Active -> {
                    meditationJob?.cancel(); meditationJob = null
                    // `walkJob?.isActive != true` catches three cases:
                    // (1) null — first-ever spawn, (2) cancelled, and
                    // CRITICAL (3) completed-but-not-null. Plain
                    // `== null` misses (3): if the eligible-pack check
                    // returns null (pack not yet downloaded, no
                    // selection, etc.), `runSchedulerLoop` returns
                    // normally and `walkJob` stays referencing a
                    // completed Job. Without the `isActive` check,
                    // no new scheduler would spawn for the rest of
                    // the walk even after a pack becomes eligible
                    // (e.g., download completes + auto-selects).
                    if (walkJob?.isActive != true) {
                        val pack = eligiblePackOrNullSync()
                        if (pack != null) {
                            // Only clear `exitingMeditation` (and
                            // consume the silence window) when we
                            // actually spawn with an eligible pack.
                            // If the pack is briefly ineligible here
                            // (e.g., transient FS state), a subsequent
                            // Active emission — fired on every GPS
                            // sample — re-attempts the spawn with the
                            // silence buffer still armed.
                            val silenceSec =
                                if (exitingMeditation) randomPostMeditationSilenceSec() else 0
                            exitingMeditation = false
                            walkJob = scope.launch {
                                try {
                                    runSchedulerLoop(
                                        pack = pack,
                                        ctx = VoiceGuideScheduler.SchedulerContext.Walk,
                                        postMedSilenceSec = silenceSec,
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    Log.w(TAG, "walk scheduler loop failed", t)
                                }
                            }
                        }
                    }
                }
                is WalkState.Meditating -> {
                    walkJob?.cancel(); walkJob = null
                    exitingMeditation = true
                    if (meditationJob?.isActive != true) {
                        val pack = eligiblePackOrNullSync()
                        // Only spawn when BOTH pack-is-eligible AND
                        // pack-has-meditation-prompts. Otherwise
                        // treat as a silent meditation session.
                        if (pack != null && pack.meditationPrompts != null &&
                            pack.meditationScheduling != null) {
                            meditationJob = scope.launch {
                                try {
                                    runSchedulerLoop(
                                        pack = pack,
                                        ctx = VoiceGuideScheduler.SchedulerContext.Meditation,
                                    )
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (t: Throwable) {
                                    Log.w(TAG, "meditation scheduler loop failed", t)
                                }
                            }
                        }
                    }
                }
                is WalkState.Paused,
                WalkState.Idle,
                is WalkState.Finished -> {
                    walkJob?.cancel(); walkJob = null
                    meditationJob?.cancel(); meditationJob = null
                    exitingMeditation = false
                    safeStopPlayer()
                }
            }
        }
    }

    private suspend fun runSchedulerLoop(
        pack: VoiceGuidePack,
        ctx: VoiceGuideScheduler.SchedulerContext,
        postMedSilenceSec: Int = 0,
    ) {
        val (prompts: List<VoiceGuidePrompt>, density: PromptDensity) = when (ctx) {
            VoiceGuideScheduler.SchedulerContext.Walk ->
                pack.prompts to pack.scheduling
            VoiceGuideScheduler.SchedulerContext.Meditation -> {
                // Caller-side eligibility check already verified these
                // are non-null, but pass the check again as a defense
                // for future callers that skip the observer's guard.
                val medPrompts = pack.meditationPrompts ?: return
                val medDensity = pack.meditationScheduling ?: return
                medPrompts to medDensity
            }
        }
        val sched = VoiceGuideScheduler(ctx, prompts, density, clock)
        sched.start()
        if (postMedSilenceSec > 0) sched.setPostMeditationSilence(postMedSilenceSec)

        try {
            while (currentCoroutineContext().isActive) {
                val prompt = sched.decide(isPaused = false, isRecordingVoice = false)
                if (prompt != null) playOrSkip(prompt, sched)
                delay(TICK_INTERVAL_MS)
            }
        } finally {
            safeStopPlayer()
        }
    }

    private fun playOrSkip(
        prompt: VoiceGuidePrompt,
        sched: VoiceGuideScheduler,
    ) {
        // Filesystem read here is a few `exists + length` syscalls —
        // cheap, and the orchestrator scope is `Dispatchers.Default`
        // (CPU pool), not Main, so there's no ANR risk. Avoiding a
        // `withContext(Dispatchers.IO)` hop keeps the tick loop
        // test-advanceable via `StandardTestDispatcher`.
        val file = fileStore.fileForPrompt(prompt.r2Key)
        if (!file.exists() || file.length() == 0L) {
            // Pack gone or file deleted mid-session. Skip without
            // marking played; orchestrator-level cancellation takes
            // care of the whole-pack removal case.
            Log.w(TAG, "prompt file missing: ${prompt.r2Key}")
            return
        }
        sched.markPlaybackStarted()
        // `player.play` is non-suspend and returns immediately after
        // posting to the main handler — it can't throw CE from the
        // coroutine (cancellation lands at the `delay()` in the outer
        // loop) and any main-thread exception inside the post would
        // never propagate here. No try/catch needed.
        player.play(file) { sched.markPlayed(prompt.id) }
    }

    /**
     * Non-suspend eligibility check called from the observer's
     * collect lambda. Returns the pack only if it's selected, present
     * in the manifest, and fully downloaded. If the manifest's
     * initial load hasn't completed yet (true race on a fresh cold
     * start followed immediately by a walk — improbable given the
     * UI onboarding flow), `manifestService.pack(id)` returns null
     * and we treat the pack as ineligible. The next `Active`
     * emission (GPS sample a second or two later) retries.
     *
     * Keeping this non-suspend avoids a subtle `StateFlow` conflation
     * risk: a suspend call inside `walkState.collect { }` could miss
     * intermediate state transitions (e.g., `Active → Meditating →
     * Active` while we're awaiting a Deferred). Observer state
     * (walkJob/meditationJob/exitingMeditation) depends on seeing
     * every transition.
     *
     * `selectedPackId.value` assumes the upstream `stateIn(
     * WhileSubscribed)` flow has captured the persisted DataStore
     * value. Safe in production because `VoiceGuideDownloadObserver`
     * keeps `packStates` (→ `selectedPackId`) subscribed for the app
     * process lifetime.
     */
    private fun eligiblePackOrNullSync(): VoiceGuidePack? {
        val packId = selectedPackId.value ?: return null
        val pack = manifestService.pack(id = packId) ?: return null
        // Filesystem read on Default is fine — a couple of `exists +
        // length` syscalls, not Main-thread, no ANR risk.
        return if (fileStore.isPackDownloaded(pack)) pack else null
    }

    private fun safeStopPlayer() {
        try {
            player.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "player.stop failed", t)
        }
    }

    private fun randomPostMeditationSilenceSec(): Int =
        Random.Default.nextInt(POST_MED_SILENCE_MIN_SEC, POST_MED_SILENCE_MAX_SEC + 1)

    private companion object {
        const val TAG = "VoiceGuideOrch"
        const val TICK_INTERVAL_MS = 30_000L
        const val POST_MED_SILENCE_MIN_SEC = 10 * 60 // 10 minutes
        const val POST_MED_SILENCE_MAX_SEC = 15 * 60 // 15 minutes
    }
}
