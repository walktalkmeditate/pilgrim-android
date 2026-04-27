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
        // Same trim/truncate/blank-check as `setIntention` so a future
        // caller (test, restore, deep-link) passing `"   "` or a 200-char
        // string can't land malformed text in Room.
        val sanitized = intention?.trim()?.take(MAX_INTENTION_CHARS)?.takeIf { it.isNotBlank() }
        val walk = repository.startWalk(startTimestamp = startedAt, intention = sanitized)
        val (next, effect) = WalkReducer.reduce(
            current,
            WalkAction.Start(walkId = walk.id, at = startedAt),
        )
        applyEffect(effect)
        _state.value = next
        // Log presence, not content — intentions can carry privacy-sensitive
        // text ("mourning Y", "anxiety about Z") that we don't want landing
        // in logcat where other debug tooling might capture it.
        Log.i(TAG, "startWalk id=${walk.id} intentionSet=${sanitized != null} at=$startedAt")
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

    /**
     * Stage 9.5-C: leaves the walk without saving. Active|Paused|Meditating
     * transitions to Idle and the walk row + all child rows are removed
     * via the `PurgeWalk` effect. Idle and Finished are no-ops; once a
     * walk reaches Finished the row has been committed to history and
     * deletion belongs to a different surface (Goshuin/Home long-press).
     */
    suspend fun discardWalk() {
        Log.i(TAG, "discardWalk invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.Discard(at = clock.now()))
    }

    suspend fun recordLocation(point: LocationPoint) = dispatch(WalkAction.LocationSampled(point))

    /**
     * Stage 9.5-C: persist a free-text intention on the active walk. Trims
     * whitespace, truncates at [MAX_INTENTION_CHARS], and clears the field
     * (writes null) when the resulting text is blank. No-op when no walk
     * is in progress (Idle / Finished).
     *
     * Held under [dispatchMutex] so a concurrent finishWalk()'s Finalize
     * effect can't interleave; the repo write is direct (no [WalkAction]
     * dispatched) because intention is metadata that doesn't participate
     * in the reducer's state-machine transitions.
     */
    suspend fun setIntention(text: String) {
        dispatchMutex.withLock {
            val walkId = activeWalkIdOrNull(_state.value) ?: return@withLock
            val sanitized = text.trim().take(MAX_INTENTION_CHARS).takeIf { it.isNotBlank() }
            repository.updateWalkIntention(walkId = walkId, intention = sanitized)
        }
    }

    private fun activeWalkIdOrNull(state: WalkState): Long? = when (state) {
        is WalkState.Active -> state.walk.walkId
        is WalkState.Paused -> state.walk.walkId
        is WalkState.Meditating -> state.walk.walkId
        else -> null
    }

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
    suspend fun recordWaypoint(label: String? = null, icon: String? = null) {
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
                        label = label,
                        icon = icon,
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
    /**
     * On cold launch, finalize any walk row whose `end_timestamp IS NULL`
     * — these are walks the OS killed (swipe-from-recents → process death,
     * force-stop, low-memory kill) without going through the normal
     * `finishWalk` path. iOS does the equivalent via JSON checkpoint
     * recovery; Android does it from Room directly because writes are
     * incremental.
     *
     * Sets each walk's end_timestamp to the last-recorded route sample's
     * timestamp (the latest evidence of activity), or to the start
     * timestamp + 1ms if no samples exist (degenerate walk that never
     * got a fix). Returns the id of the most-recent recovered walk so
     * the Path tab can show a transient banner; null when nothing
     * needed recovery.
     *
     * Called once from app init before [restoreActiveWalk]. After this
     * runs, no walk has end_timestamp NULL, so restoreActiveWalk
     * becomes a no-op — by design, mirroring iOS's "swipe ends the
     * walk" UX. If the user wanted to keep walking, they'll see the
     * recovery banner and start a new walk.
     */
    suspend fun recoverStaleWalks(): Long? = dispatchMutex.withLock {
        val all = repository.allWalks()
        val unfinished = all.filter { it.endTimestamp == null }
        Log.i(
            TAG,
            "recoverStaleWalks: scan total=${all.size} unfinished=${unfinished.size} " +
                "ids=${unfinished.map { it.id }} state=${_state.value::class.simpleName}",
        )
        // Warm-launch case: process survived swipe-from-recents (FGS
        // didn't fully tear down despite stopWithTask="true"). Controller
        // still holds the walk in memory as Active|Paused|Meditating.
        // Force the in-memory state back to Idle so the UI doesn't redirect
        // to ActiveWalkScreen on the next composition. Done UNCONDITIONALLY
        // before any reads of `_state` below — the in-memory state is
        // authoritatively wrong if any walks are unfinished AND the
        // controller is non-Idle (the swipe meant "end this walk").
        if (unfinished.isNotEmpty() && _state.value !is WalkState.Idle) {
            Log.i(TAG, "recoverStaleWalks: resetting in-memory state ${_state.value::class.simpleName} → Idle")
            _state.value = WalkState.Idle
        }
        if (unfinished.isEmpty()) {
            return@withLock null
        }
        var mostRecentlyRecovered: Long? = null
        var mostRecentStart = Long.MIN_VALUE
        for (walk in unfinished) {
            val lastSample = repository.lastLocationSampleFor(walk.id)
            val endTs = lastSample?.timestamp ?: (walk.startTimestamp + 1L)
            val finalized = repository.finishWalkAtomic(walkId = walk.id, endTimestamp = endTs)
            if (finalized) {
                Log.i(
                    TAG,
                    "recoverStaleWalks: finalized walk=${walk.id} startedAt=${walk.startTimestamp} " +
                        "endedAt=$endTs (lastSample=${lastSample != null})",
                )
                if (walk.startTimestamp > mostRecentStart) {
                    mostRecentStart = walk.startTimestamp
                    mostRecentlyRecovered = walk.id
                }
            } else {
                Log.w(TAG, "recoverStaleWalks: finishWalkAtomic returned false for walk=${walk.id}")
            }
        }
        return@withLock mostRecentlyRecovered
    }

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

            is WalkEffect.PurgeWalk -> repository.deleteWalkById(effect.walkId)
        }
    }

    internal companion object {
        private const val TAG = "WalkController"
        // Single source of truth for the intention character cap. UI surfaces
        // (IntentionSettingDialog) reference this so the controller-side
        // sanitize and the UI-side `take(N)` can never silently desync.
        const val MAX_INTENTION_CHARS = 140
    }
}
