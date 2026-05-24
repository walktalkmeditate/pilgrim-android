// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.proximity.GeoCacheService
import org.walktalkmeditate.pilgrim.data.proximity.ProximityDetectionService
import org.walktalkmeditate.pilgrim.data.proximity.ProximityEvent
import org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.whisper.WhisperManifestService
import org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Runs whisper proximity auto-play inside the `:tracker` foreground
 * service, so a nearby whisper plays even when the screen is locked and
 * the UI process has been backgrounded / o-killed mid-walk.
 *
 * iOS plays whispers backgrounded because its single process stays alive
 * via the `location` background mode, letting a view-layer observer drive
 * playback. On Android the walk runs in `:tracker` (the UI process is
 * expendable — that's why the walk pipeline was split out), so the
 * proximity detection + playback must live HERE, alongside the GPS
 * pipeline that already keeps `:tracker` alive. The `:tracker` FGS
 * declares `mediaPlayback` (in addition to `location`) so the OS permits
 * the background audio that backs this feature.
 *
 * This mirrors `WalkViewModel`'s proximity blocks exactly (bind →
 * fetch → updateTargets → auto-play), reusing the same per-process
 * singletons. The UI process keeps its own copy ONLY for the on-screen
 * proximity banner + map markers; the UI's auto-play was removed so the
 * whisper isn't played twice when both processes are alive.
 *
 * The location feed includes BOTH `Active` and `Paused` walks — this
 * matches `WalkViewModel.bindToLocation` (and iOS), which keeps the
 * proximity set warm across a pause so resuming next to a whisper still
 * fires.
 *
 * ## Accepted cross-process limitations
 *
 * `:tracker` and the UI process each own a per-process copy of these
 * singletons (Hilt graphs are per-process). When both are alive the
 * duplication is benign but visible:
 *
 *  - **Double geo-cache fetch** — both processes call
 *    `fetchIfNeeded` on their own [GeoCacheService]. Each enforces its
 *    own 10 km threshold + ETag, so the cost is one extra conditional
 *    GET, not duplicate data.
 *  - **No cross-process play suppression** — the UI process can't see
 *    that `:tracker` just played a whisper (or vice-versa). In practice
 *    only `:tracker` auto-plays (the UI's auto-play was removed); the
 *    UI's tap-to-play is user-initiated, so a tap landing in the same
 *    instant as an auto-play could double up. Rare and self-correcting
 *    (WhisperPlayer's main channel stops the prior clip on a new play).
 *
 * Closing these would require an IPC channel between the processes —
 * deliberately out of scope; the walk pipeline was split precisely to
 * keep `:tracker` independent of the UI process.
 */
@Singleton
class BackgroundWhisperAutoPlayer internal constructor(
    private val geoCacheService: GeoCacheService,
    private val proximityService: ProximityDetectionService,
    private val whisperManifestService: WhisperManifestService,
    private val whisperPlayer: WhisperPlayer,
    private val practicePreferences: PracticePreferencesRepository,
    private val soundsPreferences: SoundsPreferencesRepository,
    private val currentTimeMillis: () -> Long,
) {
    @Inject
    constructor(
        geoCacheService: GeoCacheService,
        proximityService: ProximityDetectionService,
        whisperManifestService: WhisperManifestService,
        whisperPlayer: WhisperPlayer,
        practicePreferences: PracticePreferencesRepository,
        soundsPreferences: SoundsPreferencesRepository,
    ) : this(
        geoCacheService = geoCacheService,
        proximityService = proximityService,
        whisperManifestService = whisperManifestService,
        whisperPlayer = whisperPlayer,
        practicePreferences = practicePreferences,
        soundsPreferences = soundsPreferences,
        currentTimeMillis = System::currentTimeMillis,
    )

    /**
     * Child job owning every collector this session. Cancelled by
     * [stop] (deterministically, before the detector is torn down) and
     * replaced on each [start] so a re-entrant `startTracking` can't
     * double-wire the pipeline.
     */
    private var sessionJob: Job? = null

    /**
     * Wire the proximity → auto-play pipeline onto [scope] (the walk
     * service's scope), fed by the controller's [walkState]. Idempotent
     * — a second call tears down the prior wiring first.
     */
    fun start(scope: CoroutineScope, walkState: Flow<WalkState>) {
        // Idempotent: drop any prior session's collectors before
        // re-wiring (START_REDELIVER_INTENT can re-enter startTracking).
        sessionJob?.cancel()
        val job = SupervisorJob(scope.coroutineContext[Job])
        sessionJob = job
        val sessionScope = CoroutineScope(scope.coroutineContext + job)

        // Fresh dedup set per walk so a whisper encountered on a prior
        // walk fires again this session.
        sessionScope.launch { proximityService.resetSession() }

        // Warm the manifest once up front rather than inside the events
        // collector — refresh() is a network call, and suspending the
        // events collector backpressures the proximity SharedFlow
        // (extraBufferCapacity = 8, SUSPEND overflow), which would stall
        // detection. A failed/in-flight refresh just means randomWhisper
        // returns null and the entry is skipped (same as no manifest).
        sessionScope.launch {
            if (whisperManifestService.manifest.value == null) {
                runCatching { whisperManifestService.refresh() }
            }
        }

        // Feed the live walk location to the proximity detector (it
        // samples internally at 5s).
        sessionScope.launch {
            proximityService.bindToLocation(
                walkState
                    .map { it.activeOrPausedLocation() }
                    .map { loc ->
                        loc?.let {
                            android.location.Location("walk").apply {
                                latitude = it.first
                                longitude = it.second
                            }
                        }
                    },
            )
        }

        // Pull nearby whispers/cairns into the geo cache as the walk moves
        // (the cache enforces its own 10km re-fetch threshold + ETag).
        sessionScope.launch {
            var lastFetchAt = 0L
            walkState
                .map { it.activeOrPausedLocation() }
                .filterNotNull()
                .collect { (lat, lon) ->
                    val now = currentTimeMillis()
                    if (now - lastFetchAt < FETCH_THROTTLE_MS) return@collect
                    lastFetchAt = now
                    try {
                        geoCacheService.fetchIfNeeded(lat, lon)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // A network hiccup must not kill the collector —
                        // the next sample retries. Whisper-fetch failures
                        // are silent (iOS parity).
                        Log.w(TAG, "geocache fetch failed: ${e.message}")
                    }
                }
        }

        // Keep the proximity target set in sync with the cached whispers.
        sessionScope.launch {
            geoCacheService.whispers.collect { whispers ->
                proximityService.updateTargets(
                    whispers.mapTo(mutableSetOf()) { w ->
                        ProximityTarget(
                            id = ProximityTarget.whisperId(w.id),
                            latitude = w.latitude,
                            longitude = w.longitude,
                            radius = ProximityDetectionService.WHISPER_RADIUS_M,
                            type = ProximityTarget.Type.Whisper,
                        )
                    },
                )
            }
        }

        // Auto-play a random whisper from the encountered category, gated
        // on the user's prefs (mirrors WalkViewModel + iOS handleProximityEvent).
        sessionScope.launch {
            proximityService.events
                .filter { it.direction == ProximityEvent.Direction.Entered }
                .collect { event -> handleEntered(event) }
        }
    }

    /**
     * Tear down at walk end/discard: cancel this session's collectors
     * FIRST (so a buffered `Entered` event can't drive a play() during
     * teardown), then stop the detector + clear dedup.
     */
    suspend fun stop() {
        sessionJob?.cancelAndJoin()
        sessionJob = null
        proximityService.stopListening()
        geoCacheService.invalidateLastFetch()
    }

    private fun handleEntered(event: ProximityEvent) {
        if (event.target.type != ProximityTarget.Type.Whisper) return
        if (!practicePreferences.autoPlayWhisperOnProximity.value) return
        if (!soundsPreferences.soundsEnabled.value) return
        val cacheId = event.target.id.removePrefix("whisper-")
        val cached = geoCacheService.whispers.value
            .firstOrNull { it.id == cacheId } ?: return
        val category = cached.resolvedCategory ?: return
        val definition = whisperManifestService.randomWhisper(category) ?: return
        whisperPlayer.play(definition)
    }

    private fun WalkState.activeOrPausedLocation(): Pair<Double, Double>? = when (this) {
        is WalkState.Active -> walk.lastLocation?.let { it.latitude to it.longitude }
        is WalkState.Paused -> walk.lastLocation?.let { it.latitude to it.longitude }
        else -> null
    }

    private companion object {
        const val TAG = "BgWhisperAutoPlayer"
        const val FETCH_THROTTLE_MS = 300_000L
    }
}
