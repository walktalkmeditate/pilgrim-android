// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * App-scoped coordinator that observes the walk-state flow and plays
 * the user-selected soundscape during meditation. Matches iOS: the
 * soundscape is a meditation-only ambient track, not a walk-long
 * layer. Enters play on `Meditating`, stops on any other state.
 *
 * Contrast with [org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuideOrchestrator]
 * (Stage 5-E): voice guides run a 30-second tick scheduler because
 * they're discrete prompts chosen at runtime. Soundscape is one
 * looping file, so there's no scheduler — just a single `play(file)`
 * on entry and a `stop()` on exit. The player's `REPEAT_MODE_ONE`
 * keeps the loop alive for as long as Meditating is the state.
 *
 * **Start delay.** On Meditating entry we wait ~800ms before calling
 * `play()`. The meditation start bell (Stage 5-B's
 * `MeditationBellObserver`) fires synchronously on the same transition;
 * starting the ambient loop in the same frame would step on the
 * bell's attack. An 800ms head start gives the bell room to breathe.
 *
 * **Cold-start / restore paths.** Unlike the bell observer — which
 * suppresses the bell on `Idle → Meditating` restore paths to avoid
 * a "welcome back" bell the user didn't trigger — the soundscape
 * DOES play on restore into Meditating. The ambient layer is part of
 * the environment, not a user-initiated event; restoring meditation
 * state should restore the soundscape with it.
 *
 * **Eligibility.** Soundscape plays only when:
 *  - a soundscape is selected (DataStore `selected_soundscape_id`)
 *  - the selected id matches an asset in the manifest
 *  - the file is present on disk and non-empty
 *
 * Ineligible → silent meditation. The next Meditating emission
 * re-checks (e.g., download completes mid-session).
 *
 * **Focus-loss handling** lives inside the player, not here. The
 * player auto-ducks on `LOSS_TRANSIENT_CAN_DUCK` (voice-guide prompt
 * firing) and auto-resumes on `GAIN` — the orchestrator never sees
 * it. The orchestrator only drives lifecycle boundaries.
 *
 * Same start-once / runs-for-process-lifetime shape as the other
 * audio observers (bell 5-B, voice-guide download 5-D, voice-guide
 * orchestrator 5-E). Called from `PilgrimApp.onCreate`.
 */
@Singleton
class SoundscapeOrchestrator @Inject constructor(
    @SoundscapeObservedWalkState
    private val walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @SoundscapeSelectedAssetId
    private val selectedAssetId: StateFlow<@JvmSuppressWildcards String?>,
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val player: SoundscapePlayer,
    private val soundsPreferences: SoundsPreferencesRepository,
    @SoundscapePlaybackScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        var playJob: Job? = null

        // Stage 10-B master sounds toggle: combine walkState with the
        // soundsEnabled flag so flipping the toggle mid-meditation
        // cancels playback and does not spawn while muted. Spawn
        // decision happens here at the per-emission level (not inside
        // runSessionLoop) so the existing retry-budget logic stays
        // intact for legitimately-muted-and-then-unmuted sessions.
        combine(walkState, soundsPreferences.soundsEnabled) { state, enabled ->
            state to enabled
        }.collect { (state, enabled) ->
            when (state) {
                is WalkState.Meditating -> {
                    if (!enabled) {
                        // Master toggle is OFF — cancel any in-flight
                        // session and stop the player. No spawn.
                        playJob?.cancel(); playJob = null
                        safeStopPlayer()
                        return@collect
                    }
                    // `isActive != true` catches (1) first-ever-null,
                    // (2) cancelled, and (3) completed-but-not-null.
                    // With the `player.state` observer below keeping
                    // the job alive for the duration of meditation,
                    // case (3) is now rare (only if the initial
                    // eligibility check returns null — e.g., no
                    // soundscape selected). Stage 5-E lesson.
                    if (playJob?.isActive != true) {
                        playJob = scope.launch { runSessionLoop() }
                    }
                }
                is WalkState.Active,
                is WalkState.Paused,
                WalkState.Idle,
                is WalkState.Finished -> {
                    playJob?.cancel(); playJob = null
                    safeStopPlayer()
                }
            }
        }
    }

    /**
     * Per-meditation-session loop: waits the start-delay, dispatches
     * `player.play(file)`, then suspends observing `player.state`
     * for the duration of the session so a mid-session `Error`
     * transition (STATE_ENDED on a REPEAT_MODE_ONE loop or a codec
     * error) can trigger a single retry. Without this observer, the
     * playJob would complete immediately after the fire-and-forget
     * `player.play()` and the orchestrator would miss the Error —
     * soundscape would go silent until the user exited meditation
     * and re-entered.
     *
     * The retry budget is one per meditation session. A second
     * consecutive Error ends the session silently rather than
     * hammering on a genuinely broken file. On `Meditating → other`
     * state transition, `scope.launch` is cancelled and the
     * `CancellationException` unwinds through the `collect`.
     */
    private suspend fun runSessionLoop() {
        var retryBudget = 1
        try {
            delay(START_DELAY_MS)
            if (!attemptPlay()) return
            // Suspend on `player.state` for the rest of the session.
            // `collect` runs until `playJob.cancel()` fires (from the
            // walkState observer) or we explicitly return. Re-entry
            // via retryBudget keeps emissions flowing.
            player.state.collect { s ->
                if (s is SoundscapePlayer.State.Error && retryBudget > 0) {
                    retryBudget -= 1
                    Log.w(TAG, "soundscape mid-session error; retrying once")
                    delay(RETRY_DELAY_MS)
                    attemptPlay()
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "soundscape session loop failed", t)
        }
    }

    /**
     * One-shot play attempt. Returns `true` if `player.play` was
     * dispatched, `false` if the asset was ineligible or the file
     * vanished. Non-suspend `fileFor` + `exists + length` reads are
     * safe on `Dispatchers.Default` — a couple of syscalls, no ANR
     * risk, and `SoundscapeFileStore.fileFor` is pure (no mkdirs).
     */
    private fun attemptPlay(): Boolean {
        val asset = eligibleSoundscapeOrNullSync() ?: return false
        val file = fileStore.fileFor(asset)
        if (!(file.exists() && file.length() > 0L)) {
            Log.w(TAG, "file vanished during start delay: ${asset.id}")
            return false
        }
        // Final defensive gate-check immediately before `player.play()`.
        // The combine-driven cancellation in `observe()` covers the
        // common case (toggle OFF → playJob.cancel + safeStopPlayer)
        // but a 1-frame race exists between `delay(START_DELAY_MS)`
        // resuming and this synchronous block running. Without this
        // line, a rapid OFF→ON→OFF in that micro-window could let a
        // burst of audio fire before the cancellation lands. Reading
        // `.value` on the Eagerly StateFlow is non-suspend and current.
        if (!soundsPreferences.soundsEnabled.value) return false
        player.play(file)
        return true
    }

    /**
     * Returns the selected soundscape [AudioAsset] only if present in
     * the manifest AND the file is on disk and non-empty. Non-suspend
     * so the observer's collect lambda can run it without a suspend
     * hop that could conflate concurrent state transitions (Stage 5-E
     * pattern).
     *
     * Filesystem read is `isAvailable(asset)` — a couple of syscalls
     * on `Dispatchers.Default`, no ANR risk.
     */
    private fun eligibleSoundscapeOrNullSync(): AudioAsset? {
        val id = selectedAssetId.value ?: return null
        val asset = manifestService.asset(id) ?: return null
        if (asset.type != AudioAssetType.SOUNDSCAPE) return null
        return if (fileStore.isAvailable(asset)) asset else null
    }

    private fun safeStopPlayer() {
        try {
            player.stop()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Re-throw to preserve structured concurrency. SoundscapePlayer.stop()
            // is currently non-suspend, but the interface doesn't prevent a future
            // impl from suspending or launching internally — guard against silent
            // CE swallowing now per CLAUDE.md's "never silently swallow exceptions"
            // policy.
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "player.stop failed", t)
        }
    }

    private companion object {
        const val TAG = "SoundscapeOrch"
        const val START_DELAY_MS = 800L
        const val RETRY_DELAY_MS = 250L
    }
}
