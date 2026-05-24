// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.service

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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
 * pipeline that already keeps `:tracker` alive.
 *
 * This mirrors `WalkViewModel`'s proximity blocks exactly (bind →
 * fetch → updateTargets → auto-play), reusing the same per-process
 * singletons. The UI process keeps its own copy ONLY for the on-screen
 * proximity banner + map markers; the UI's auto-play was removed so the
 * whisper isn't played twice when both processes are alive.
 */
@Singleton
class BackgroundWhisperAutoPlayer @Inject constructor(
    private val geoCacheService: GeoCacheService,
    private val proximityService: ProximityDetectionService,
    private val whisperManifestService: WhisperManifestService,
    private val whisperPlayer: WhisperPlayer,
    private val practicePreferences: PracticePreferencesRepository,
    private val soundsPreferences: SoundsPreferencesRepository,
) {

    /**
     * Wire the proximity → auto-play pipeline onto [scope] (the walk
     * service's scope), fed by the controller's [walkState]. Safe to
     * call once per `startTracking`.
     */
    fun start(scope: CoroutineScope, walkState: Flow<WalkState>) {
        // Fresh dedup set per walk so a whisper encountered on a prior
        // walk fires again this session.
        scope.launch { proximityService.resetSession() }

        // Feed the live walk location to the proximity detector (it
        // samples internally at 5s).
        scope.launch {
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
        scope.launch {
            var lastFetchAt = 0L
            walkState
                .map { it.activeOrPausedLocation() }
                .filterNotNull()
                .collect { (lat, lon) ->
                    val now = System.currentTimeMillis()
                    if (now - lastFetchAt < FETCH_THROTTLE_MS) return@collect
                    lastFetchAt = now
                    geoCacheService.fetchIfNeeded(lat, lon)
                }
        }

        // Keep the proximity target set in sync with the cached whispers.
        scope.launch {
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
        scope.launch {
            proximityService.events
                .filter { it.direction == ProximityEvent.Direction.Entered }
                .collect { event ->
                    if (event.target.type != ProximityTarget.Type.Whisper) return@collect
                    if (!practicePreferences.autoPlayWhisperOnProximity.value) return@collect
                    if (!soundsPreferences.soundsEnabled.value) return@collect
                    if (whisperManifestService.manifest.value == null) {
                        whisperManifestService.refresh()
                    }
                    val cacheId = event.target.id.removePrefix("whisper-")
                    val cached = geoCacheService.whispers.value
                        .firstOrNull { it.id == cacheId } ?: return@collect
                    val category = cached.resolvedCategory ?: return@collect
                    val definition = whisperManifestService.randomWhisper(category)
                        ?: return@collect
                    whisperPlayer.play(definition)
                }
        }
    }

    /** Tear down at walk end/discard: stop the detector + clear dedup. */
    suspend fun stop() {
        proximityService.stopListening()
        geoCacheService.invalidateLastFetch()
    }

    private fun WalkState.activeOrPausedLocation(): Pair<Double, Double>? = when (this) {
        is WalkState.Active -> walk.lastLocation?.let { it.latitude to it.longitude }
        is WalkState.Paused -> walk.lastLocation?.let { it.latitude to it.longitude }
        else -> null
    }

    private companion object {
        const val FETCH_THROTTLE_MS = 300_000L
    }
}
