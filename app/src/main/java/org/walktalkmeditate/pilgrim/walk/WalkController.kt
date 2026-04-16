// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAction
import org.walktalkmeditate.pilgrim.domain.WalkEffect
import org.walktalkmeditate.pilgrim.domain.WalkReducer
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Single source of truth for the in-memory walk state and the bridge
 * between the pure reducer and the (impure) persistence layer. Services
 * and ViewModels observe [state] and call the suspend APIs to transition.
 *
 * Dispatch is serialized with a mutex so that concurrent callers (e.g. a
 * user pressing pause at the same instant a location sample arrives)
 * reduce one action at a time.
 */
@Singleton
class WalkController @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
) {
    private val _state = MutableStateFlow<WalkState>(WalkState.Idle)
    val state: StateFlow<WalkState> = _state.asStateFlow()

    private val dispatchMutex = Mutex()

    /**
     * Starts a new walk. Atomic under the dispatch mutex: the Room insert,
     * reducer transition, and state commit happen together, so duplicate
     * calls (double-tap, reentrant service start) cannot create orphan
     * Walk rows. Legal from [WalkState.Idle] (first walk) and
     * [WalkState.Finished] (subsequent walks after reviewing the summary);
     * throws otherwise.
     */
    suspend fun startWalk(intention: String? = null): Walk = dispatchMutex.withLock {
        val current = _state.value
        check(current is WalkState.Idle || current is WalkState.Finished) {
            "startWalk requires Idle or Finished state but controller is currently $current"
        }
        val startedAt = clock.now()
        val walk = repository.startWalk(startTimestamp = startedAt, intention = intention)
        val (next, effect) = WalkReducer.reduce(
            current,
            WalkAction.Start(walkId = walk.id, at = startedAt),
        )
        applyEffect(effect)
        _state.value = next
        walk
    }

    suspend fun pauseWalk() = dispatch(WalkAction.Pause(at = clock.now()))

    suspend fun resumeWalk() = dispatch(WalkAction.Resume(at = clock.now()))

    suspend fun startMeditation() = dispatch(WalkAction.MeditateStart(at = clock.now()))

    suspend fun endMeditation() = dispatch(WalkAction.MeditateEnd(at = clock.now()))

    suspend fun finishWalk() = dispatch(WalkAction.Finish(at = clock.now()))

    suspend fun recordLocation(point: LocationPoint) = dispatch(WalkAction.LocationSampled(point))

    private suspend fun dispatch(action: WalkAction) {
        dispatchMutex.withLock {
            val current = _state.value
            val (next, effect) = WalkReducer.reduce(current, action)
            applyEffect(effect)
            _state.value = next
        }
    }

    private suspend fun applyEffect(effect: WalkEffect) {
        when (effect) {
            WalkEffect.None -> Unit

            is WalkEffect.PersistLocation -> repository.recordLocation(
                RouteDataSample(
                    walkId = effect.walkId,
                    timestamp = effect.point.timestamp,
                    latitude = effect.point.latitude,
                    longitude = effect.point.longitude,
                    horizontalAccuracyMeters = effect.point.horizontalAccuracyMeters,
                    speedMetersPerSecond = effect.point.speedMetersPerSecond,
                ),
            )

            is WalkEffect.PersistEvent -> repository.recordEvent(
                WalkEvent(
                    walkId = effect.walkId,
                    timestamp = effect.timestamp,
                    eventType = effect.eventType,
                ),
            )

            is WalkEffect.FinalizeWalk -> {
                val walk = repository.getWalk(effect.walkId)
                    ?: error(
                        "Finalize requested for walk ${effect.walkId}, but no row exists in " +
                            "the database. The in-memory state and persisted walk have diverged.",
                    )
                repository.finishWalk(walk, endTimestamp = effect.endTimestamp)
            }
        }
    }
}
