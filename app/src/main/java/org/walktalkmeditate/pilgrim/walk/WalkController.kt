// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
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
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkAction
import org.walktalkmeditate.pilgrim.domain.WalkEffect
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkReducer
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.haversineMeters

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

    /**
     * After a process kill mid-walk, the Walk row is still in Room with
     * `end_timestamp IS NULL` but the in-memory state is [WalkState.Idle].
     * This rebuilds a [WalkAccumulator] from persisted facts — start
     * timestamp, route samples (for distance and last location), and
     * walk events (replayed to recompute cumulative pause + meditation
     * totals and current state). Returns the restored [Walk] or null if
     * there was no unfinished walk to resume.
     *
     * No-op when the controller already has a non-Idle in-memory state
     * (assumes caller is about to restart the process, not duplicate
     * state from a running session).
     */
    suspend fun restoreActiveWalk(): Walk? = dispatchMutex.withLock {
        if (_state.value !is WalkState.Idle) return@withLock null
        val walk = repository.getActiveWalk() ?: return@withLock null

        val samples = repository.locationSamplesFor(walk.id)
        val events = repository.eventsFor(walk.id)

        var distance = 0.0
        var lastPoint: LocationPoint? = null
        for (sample in samples) {
            val point = LocationPoint(
                timestamp = sample.timestamp,
                latitude = sample.latitude,
                longitude = sample.longitude,
                horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                speedMetersPerSecond = sample.speedMetersPerSecond,
            )
            if (lastPoint != null) distance += haversineMeters(lastPoint, point)
            lastPoint = point
        }

        var totalPaused = 0L
        var totalMeditated = 0L
        var pendingPauseAt: Long? = null
        var pendingMeditationAt: Long? = null
        for (event in events) {
            when (event.eventType) {
                WalkEventType.PAUSED -> pendingPauseAt = event.timestamp
                WalkEventType.RESUMED -> pendingPauseAt?.let {
                    totalPaused += (event.timestamp - it).coerceAtLeast(0)
                    pendingPauseAt = null
                }
                WalkEventType.MEDITATION_START -> pendingMeditationAt = event.timestamp
                WalkEventType.MEDITATION_END -> pendingMeditationAt?.let {
                    totalMeditated += (event.timestamp - it).coerceAtLeast(0)
                    pendingMeditationAt = null
                }
                WalkEventType.WAYPOINT_MARKED -> Unit
            }
        }

        val accumulator = WalkAccumulator(
            walkId = walk.id,
            startedAt = walk.startTimestamp,
            lastLocation = lastPoint,
            distanceMeters = distance,
            totalPausedMillis = totalPaused,
            totalMeditatedMillis = totalMeditated,
        )
        _state.value = when {
            pendingPauseAt != null -> WalkState.Paused(accumulator, pausedAt = pendingPauseAt!!)
            pendingMeditationAt != null ->
                WalkState.Meditating(accumulator, meditationStartedAt = pendingMeditationAt!!)
            else -> WalkState.Active(accumulator)
        }
        walk
    }

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

            is WalkEffect.PersistLocation -> {
                // Best effort. Losing one location sample on a disk stall
                // is acceptable; killing a 90-minute walk is not. The
                // in-memory accumulator already has the point folded into
                // distanceMeters, so the drift is visible only in the
                // persisted route replay. Narrow catch: CancellationException
                // must propagate so structured concurrency still works, and
                // Errors (OutOfMemoryError, StackOverflowError) should not
                // be swallowed — let the process die loud.
                try {
                    repository.recordLocation(
                        RouteDataSample(
                            walkId = effect.walkId,
                            timestamp = effect.point.timestamp,
                            latitude = effect.point.latitude,
                            longitude = effect.point.longitude,
                            horizontalAccuracyMeters = effect.point.horizontalAccuracyMeters,
                            speedMetersPerSecond = effect.point.speedMetersPerSecond,
                        ),
                    )
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Log.w(TAG, "dropped location sample for walk ${effect.walkId}: ${e.message}")
                }
            }

            is WalkEffect.PersistEvent -> repository.recordEvent(
                WalkEvent(
                    walkId = effect.walkId,
                    timestamp = effect.timestamp,
                    eventType = effect.eventType,
                ),
            )

            is WalkEffect.FinalizeWalk -> {
                val finalized = repository.finishWalkAtomic(
                    walkId = effect.walkId,
                    endTimestamp = effect.endTimestamp,
                )
                check(finalized) {
                    "Finalize requested for walk ${effect.walkId}, but no row exists in " +
                        "the database. The in-memory state and persisted walk have diverged."
                }
            }
        }
    }

    private companion object {
        const val TAG = "WalkController"
    }
}
