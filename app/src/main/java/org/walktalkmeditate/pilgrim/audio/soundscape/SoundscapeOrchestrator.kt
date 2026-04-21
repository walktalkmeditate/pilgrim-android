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
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
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
    @SoundscapePlaybackScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        var playJob: Job? = null

        walkState.collect { state ->
            when (state) {
                is WalkState.Meditating -> {
                    // `isActive != true` catches (1) first-ever-null,
                    // (2) cancelled, and (3) completed-but-not-null
                    // (play dispatch completed, soundscape looping
                    // inside the player now, job done). Without the
                    // (3) branch, a Meditating → non-Meditating →
                    // Meditating cycle would find `playJob` non-null
                    // and skip the re-play. Stage 5-E lesson.
                    if (playJob?.isActive != true) {
                        playJob = scope.launch {
                            try {
                                delay(START_DELAY_MS)
                                val asset = eligibleSoundscapeOrNullSync()
                                if (asset != null) {
                                    val file = fileStore.fileFor(asset)
                                    // Re-check on the delay side —
                                    // the file could have been deleted
                                    // during the 800ms window.
                                    if (file.exists() && file.length() > 0L) {
                                        player.play(file)
                                    } else {
                                        Log.w(TAG, "file vanished during start delay: ${asset.id}")
                                    }
                                }
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (t: Throwable) {
                                Log.w(TAG, "soundscape start failed", t)
                            }
                        }
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
        } catch (t: Throwable) {
            Log.w(TAG, "player.stop failed", t)
        }
    }

    private companion object {
        const val TAG = "SoundscapeOrch"
        const val START_DELAY_MS = 800L
    }
}
