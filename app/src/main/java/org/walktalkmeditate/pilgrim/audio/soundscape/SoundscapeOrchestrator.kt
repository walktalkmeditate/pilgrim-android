// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.soundscape

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.BellDurationResolver
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
 * **Start delay.** On Meditating entry we hold the ambient loop until
 * the meditation-start bell (Stage 5-B's `MeditationBellObserver`,
 * which fires synchronously on the same transition) has finished
 * ringing. iOS parity `SoundManagement.swift:68-78` waits
 * `max(0.5s, bellDuration)` — a fixed 800ms was wrong because bells
 * are user-downloadable and a longer bell would be cut short by the
 * soundscape. The duration is resolved via [BellDurationResolver]
 * (default impl prepares the selected bell to read its real length;
 * falls back to ~3.0s for the bundled bell). The delay is
 * `max(BELL_DELAY_FLOOR_MS, resolvedBellDurationMs)`.
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
 * orchestrator 5-E). Started from `WalkTrackingService.startTracking`
 * (the `:tracker` process), NOT `PilgrimApp.onCreate` — soundscape
 * plays only during `Meditating`, which is reachable only from an
 * Active walk, so `:tracker` is always alive when it matters, and
 * running it there means the ambient loop survives a UI-process
 * o-kill mid-meditation. [start] is idempotent so a reused `:tracker`
 * process doesn't double-wire it.
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
    private val bellDurationResolver: BellDurationResolver,
    @SoundscapePlaybackScope private val scope: CoroutineScope,
) {
    // Guards against double-wiring. The orchestrator is a process-lifetime
    // @Singleton; under the :tracker process split a cached :tracker
    // process reused across walks would call start() once per walk, and
    // two observe() collectors would each spawn a playJob on Meditating →
    // two ExoPlayers looping the same file.
    @Volatile private var started = false

    /**
     * Manual "play soundscape during the active walk" request, driven by
     * the WalkOptionsSheet toggle (iOS parity `SoundManagement.toggleSoundscape`).
     * Independent of meditation: when on, the ambient loop plays through
     * the Active/Paused walk; meditation still auto-plays regardless.
     * Reset on walk end (Idle/Finished) — matches iOS, where
     * `onWalkEnd`/`onMeditationEnd` stop the player.
     *
     * Lives here (the `:tracker` singleton) rather than DataStore because
     * `pilgrim_prefs` is single-process: a UI-process write wouldn't reach
     * `:tracker`. The UI routes the toggle as a service intent, which the
     * service forwards to [setManualSoundscapeRequested].
     */
    private val manualRequested = MutableStateFlow(false)

    /**
     * Mid-walk soundscape selection override (iOS parity
     * `onSelectSoundscape`). Same cross-process reasoning as
     * [manualRequested]: the Settings-tab DataStore write isn't visible
     * to `:tracker`, so a sheet selection arrives as a service intent and
     * is applied here. Null → fall back to the [selectedAssetId] read at
     * walk start.
     */
    private val selectionOverride = MutableStateFlow(Selection.None)

    /**
     * Effective asset id the current/next session should play —
     * `selectionOverride ?: selectedAssetId`. Captured at spawn time so
     * the async [attemptPlay] resolves the same asset the spawn decision
     * used, even if the flows change underneath it.
     */
    @Volatile private var currentEffectiveId: String? = null

    /** Service-forwarded toggle from the WalkOptionsSheet. */
    fun setManualSoundscapeRequested(on: Boolean) {
        if (on) selectionOverride.value = selectionOverride.value.copy(cleared = false)
        manualRequested.value = on
    }

    /**
     * Service-forwarded mid-walk selection. Selecting a soundscape also
     * turns playback on (iOS `onSelectSoundscape` plays immediately).
     */
    fun selectSoundscape(assetId: String) {
        selectionOverride.value = Selection(assetId = assetId, cleared = false)
        manualRequested.value = true
    }

    /**
     * Service-forwarded mid-walk explicit deselection. Tells the orchestrator
     * "play nothing" regardless of what [selectedAssetId] (the cross-process
     * DataStore mirror) still reports — the UI process's `catalog.deselect()`
     * write does NOT propagate to `:tracker`'s DataStore reader. Used by
     * SoundscapePickerViewModel when the user taps the currently-selected
     * row to deselect. The `cleared` flag specifically masks Meditating's
     * auto-play predicate (`enabled && effectiveId != null`) which is
     * otherwise insensitive to [manualRequested]. Re-armed on the next
     * [selectSoundscape] call or on the Idle/Finished resetManualRequest.
     */
    fun clearSoundscapeSelection() {
        selectionOverride.value = Selection(assetId = null, cleared = true)
        manualRequested.value = false
    }

    fun start() {
        if (started) return
        started = true
        scope.launch { observe() }
        // Parallel collector: live-apply user soundscape volume to the
        // player. Runs for the orchestrator's lifetime; the cold spawn
        // logic in `observe()` stays untouched (intentional — folding
        // volume into the existing combine would also restart playback
        // on every volume tweak, which is exactly what we don't want).
        // The first emission (StateFlow's current value) seeds the
        // player's userVolume before any `play()` runs, so the very
        // first soundscape session also honors the pref.
        scope.launch {
            soundsPreferences.soundscapeVolume.collect { v ->
                player.setVolume(v)
            }
        }
    }

    private suspend fun observe() {
        var playJob: Job? = null
        // Track which asset is currently spawned so a mid-meditation
        // X→Y selection swap (user opens Settings → Sound Settings →
        // Soundscape → picks a different one — Settings is a tab and
        // does NOT exit meditation) actually re-spawns playback with
        // the new asset. Without this, the new assetId would be silently
        // ignored because `playJob?.isActive == true` for the old one.
        var spawnedAssetId: String? = null

        // Combine five signals:
        //  - walkState: Meditating auto-plays; Active/Paused plays only
        //    when the manual toggle is on; Idle/Finished always stop.
        //  - soundsEnabled: master mute. Stage 10-B.
        //  - selectedAssetId: the DataStore-backed selection (read at
        //    walk start under the :tracker split). Clearing it
        //    mid-session must cancel + stop, otherwise ExoPlayer keeps
        //    looping a deleted file until it Errors.
        //  - manualRequested: the WalkOptionsSheet toggle (walk-long
        //    soundscape, iOS parity). Off → no Active/Paused playback.
        //  - selectionOverride: a mid-walk sheet selection that the
        //    single-process DataStore can't deliver to :tracker.
        //
        // Spawn decision happens per-emission (not inside
        // runSessionLoop) so the retry-budget logic stays intact for
        // legitimately-muted-and-then-unmuted sessions.
        combine(
            walkState,
            soundsPreferences.soundsEnabled,
            selectedAssetId,
            manualRequested,
            selectionOverride,
        ) { state, enabled, assetId, manualOn, override ->
            // `override.cleared` masks the DataStore-mirrored
            // [selectedAssetId] for the explicit-deselect path
            // ([clearSoundscapeSelection]). Without it, Meditating's
            // `enabled && effectiveId != null` predicate would keep
            // spawning playback because the UI process's
            // `catalog.deselect()` write isn't visible to `:tracker`'s
            // DataStore reader and `selectedAssetId` still holds the
            // old non-null id.
            val effective = if (override.cleared) null else (override.assetId ?: assetId)
            PlaybackInputs(state, enabled, effective, manualOn)
        }
            .collect { (state, enabled, effectiveId, manualOn) ->
                // Decide whether soundscape should play in this state, and
                // whether the meditation-start bell delay applies.
                //  - Meditating: auto-play, bell-delayed on first spawn.
                //  - Active/Paused: play only when the manual toggle is on;
                //    immediate (no bell delay — the bell is a meditation cue).
                //  - Idle/Finished: never play; reset the manual request so
                //    a fresh walk starts silent (iOS onWalkEnd stops + the
                //    toggle resets).
                val applyStartDelayIfSpawning: Boolean? = when (state) {
                    is WalkState.Meditating -> if (enabled && effectiveId != null) true else null
                    is WalkState.Active,
                    is WalkState.Paused -> if (enabled && manualOn && effectiveId != null) false else null
                    WalkState.Idle,
                    is WalkState.Finished -> {
                        resetManualRequest()
                        null
                    }
                }

                if (applyStartDelayIfSpawning == null) {
                    playJob?.cancel(); playJob = null
                    spawnedAssetId = null
                    safeStopPlayer()
                    return@collect
                }

                // Spawn when (a) no job is active OR (b) the effective
                // asset changed and we need to swap. Cancel the old job +
                // stop the player BEFORE spawning so ExoPlayer doesn't
                // briefly play both files.
                val needsSwap = playJob?.isActive == true && spawnedAssetId != effectiveId
                if (needsSwap) {
                    playJob?.cancel()
                    playJob = null
                    // iOS parity SoundscapePlayer.swift:30-33 — a swap
                    // crossfades to the new asset WITHOUT re-arming audio
                    // focus, so the in-flight voice guide isn't preempted (BUG A2).
                    safeStopPlayerForSwap()
                }
                // `isActive != true` catches (1) first-ever-null,
                // (2) cancelled, and (3) completed-but-not-null.
                if (playJob?.isActive != true) {
                    spawnedAssetId = effectiveId
                    currentEffectiveId = effectiveId
                    // Bell delay applies to meditation-start only and never
                    // to a swap (iOS SoundscapePlayer.swift:30-33 crossfade
                    // plays immediately).
                    val applyStartDelay = applyStartDelayIfSpawning && !needsSwap
                    playJob = scope.launch { runSessionLoop(applyStartDelay) }
                }
            }
    }

    private fun resetManualRequest() {
        // StateFlow dedupes, so these are no-ops when already cleared and
        // won't churn the combine.
        if (manualRequested.value) manualRequested.value = false
        if (selectionOverride.value != Selection.None) selectionOverride.value = Selection.None
    }

    /**
     * In-process selection state held by the orchestrator. Distinct from
     * the DataStore-mirrored [selectedAssetId] so a mid-walk picker tap
     * can override (or explicitly clear) the persisted choice without
     * waiting on the next walk's reload. The `cleared` flag carries the
     * "explicit deselect" signal cross-process (UI's DataStore write
     * doesn't reach :tracker — see [clearSoundscapeSelection]).
     */
    internal data class Selection(
        val assetId: String?,
        val cleared: Boolean = false,
    ) {
        companion object {
            val None = Selection(assetId = null, cleared = false)
        }
    }

    private data class PlaybackInputs(
        val state: WalkState,
        val soundsEnabled: Boolean,
        val effectiveId: String?,
        // Named distinctly from the [manualRequested] StateFlow field to
        // avoid shadowing it inside the collect lambda.
        val isManualOn: Boolean,
    )

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
    private suspend fun runSessionLoop(applyStartDelay: Boolean) {
        var retryBudget = 1
        try {
            // iOS parity `SoundManagement.swift:68-78` —
            // max(0.5s, actualBellDuration). Bells are user-downloadable
            // so resolve the live duration; never start the ambient loop
            // before the meditation-start bell has finished ringing.
            //
            // Scoped to meditation-start ONLY (`applyStartDelay`). A
            // mid-meditation soundscape swap must play immediately —
            // iOS's crossfade (SoundscapePlayer.swift:30-33) has no
            // start delay; applying it on swap would leave multi-second
            // silence on every track change (BUG A1 regression).
            if (applyStartDelay) {
                val bellMs = bellDurationResolver.meditationStartBellDurationMs()
                delay(maxOf(BELL_DELAY_FLOOR_MS, bellMs))
            }
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
        // Resolve the effective id captured at spawn (override ?: selection)
        // rather than re-reading selectedAssetId, so a manual mid-walk
        // selection plays the chosen asset even though :tracker's
        // single-process DataStore never saw the Settings write.
        val id = currentEffectiveId ?: return null
        val asset = manifestService.asset(id) ?: return null
        if (asset.type != AudioAssetType.SOUNDSCAPE) return null
        return if (fileStore.isAvailable(asset)) asset else null
    }

    /**
     * Swap-path stop: keeps audio focus held so the in-flight voice
     * guide isn't preempted. iOS parity SoundscapePlayer.swift:30-33
     * (crossfade does not deactivate the audio session). BUG A2.
     */
    private fun safeStopPlayerForSwap() {
        try {
            player.stopForSwap()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "player.stopForSwap failed", t)
        }
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

        /**
         * iOS parity `SoundManagement.swift:69` — `max(0.5, ...)`. Floor
         * the bell-aware delay so a bell shorter than 500ms (or a
         * "None" selection that resolves to a tiny value) still leaves
         * a beat of silence before the ambient loop starts.
         */
        const val BELL_DELAY_FLOOR_MS = 500L
        const val RETRY_DELAY_MS = 250L
    }
}
