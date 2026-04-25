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
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkAction
import org.walktalkmeditate.pilgrim.domain.WalkEffect
import org.walktalkmeditate.pilgrim.domain.WalkReducer
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

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
        // Log presence, not content — intentions can carry privacy-sensitive
        // text ("mourning Y", "anxiety about Z") that we don't want landing
        // in logcat where other debug tooling might capture it.
        Log.i(TAG, "startWalk id=${walk.id} intentionSet=${intention != null} at=$startedAt")
        walk
    }

    suspend fun pauseWalk() {
        Log.i(TAG, "pauseWalk invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.Pause(at = clock.now()))
    }

    suspend fun resumeWalk() {
        Log.i(TAG, "resumeWalk invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.Resume(at = clock.now()))
    }

    suspend fun startMeditation() {
        Log.i(TAG, "startMeditation invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.MeditateStart(at = clock.now()))
    }

    suspend fun endMeditation() {
        Log.i(TAG, "endMeditation invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.MeditateEnd(at = clock.now()))
    }

    suspend fun finishWalk() {
        Log.i(TAG, "finishWalk invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.Finish(at = clock.now()))
    }

    suspend fun recordLocation(point: LocationPoint) = dispatch(WalkAction.LocationSampled(point))

    /**
     * Stage 9-B: insert a Waypoint at the current location for the
     * in-progress walk. Allowed from Active / Paused / Meditating —
     * the controller method is permissive so a future in-app waypoint
     * button (Phase 10) can call it from any non-finished state. The
     * notification UI hides the button during Meditating; that's a
     * UI choice, not a controller constraint.
     *
     * No-op (silent) when:
     *  - State is Idle / Finished — no walk in progress.
     *  - Accumulator's `lastLocation` is null — no GPS fix yet.
     *
     * Held under [dispatchMutex] so a concurrent finishWalk()'s
     * Finalize effect can't interleave and produce a Waypoint with
     * timestamp > Walk.endTimestamp.
     *
     * Best-effort: any Throwable from the repository write is logged
     * + swallowed. A failed waypoint must not crash the walk.
     */
    suspend fun recordWaypoint() {
        dispatchMutex.withLock {
            val accumulator = when (val s = _state.value) {
                is WalkState.Active -> s.walk
                is WalkState.Paused -> s.walk
                is WalkState.Meditating -> s.walk
                else -> return@withLock
            }
            val location = accumulator.lastLocation ?: return@withLock
            try {
                repository.addWaypoint(
                    Waypoint(
                        walkId = accumulator.walkId,
                        timestamp = clock.now(),
                        latitude = location.latitude,
                        longitude = location.longitude,
                        label = null,
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "recordWaypoint failed", t)
            }
        }
    }

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
        if (_state.value !is WalkState.Idle) {
            Log.i(TAG, "restoreActiveWalk skipped: state=${_state.value::class.simpleName}")
            return@withLock null
        }
        val walk = repository.getActiveWalk()
        if (walk == null) {
            Log.i(TAG, "restoreActiveWalk found no unfinished walk")
            return@withLock null
        }

        val samples = repository.locationSamplesFor(walk.id)
        val events = repository.eventsFor(walk.id)

        val points = samples.map { sample ->
            LocationPoint(
                timestamp = sample.timestamp,
                latitude = sample.latitude,
                longitude = sample.longitude,
                horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                speedMetersPerSecond = sample.speedMetersPerSecond,
            )
        }
        val distance = walkDistanceMeters(points)
        val lastPoint = points.lastOrNull()
        val totals = replayWalkEventTotals(events = events, closeAt = null)

        val accumulator = WalkAccumulator(
            walkId = walk.id,
            startedAt = walk.startTimestamp,
            lastLocation = lastPoint,
            distanceMeters = distance,
            totalPausedMillis = totals.totalPausedMillis,
            totalMeditatedMillis = totals.totalMeditatedMillis,
        )
        val pendingPause = totals.pendingPauseAt
        val pendingMeditation = totals.pendingMeditationAt
        val restored = when {
            pendingPause != null -> WalkState.Paused(accumulator, pausedAt = pendingPause)
            pendingMeditation != null ->
                WalkState.Meditating(accumulator, meditationStartedAt = pendingMeditation)
            else -> WalkState.Active(accumulator)
        }
        _state.value = restored
        Log.i(
            TAG,
            "restoreActiveWalk id=${walk.id} samples=${samples.size} events=${events.size} " +
                "distanceM=${distance.toInt()} state=${restored::class.simpleName}",
        )
        walk
    }

    private suspend fun dispatch(action: WalkAction) {
        dispatchMutex.withLock {
            val current = _state.value
            val (next, effect) = WalkReducer.reduce(current, action)
            applyEffect(effect)
            _state.value = next
            // LocationSampled arrives ~once per second and would flood the
            // log; emit every 10th sample at DEBUG and leave the rest
            // silent. Other actions are rare enough to log every time.
            val actionName = action::class.simpleName
            val fromName = current::class.simpleName
            val toName = next::class.simpleName
            if (action is WalkAction.LocationSampled) {
                if (fromName != toName) {
                    Log.i(TAG, "dispatch $actionName: $fromName → $toName (state changed)")
                }
            } else {
                Log.i(TAG, "dispatch $actionName: $fromName → $toName effect=${effect::class.simpleName}")
            }
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
