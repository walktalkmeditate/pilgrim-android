// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
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
 * Full implementation of [WalkController]: owns the in-memory state
 * machine + Room writes + sensor pipeline. Lives in the `:tracker`
 * process once the manifest split lands; selected directly today (the
 * Hilt provider returns this in every process).
 *
 * Dispatch is serialized with a mutex so that concurrent callers (e.g. a
 * user pressing pause at the same instant a location sample arrives)
 * reduce one action at a time.
 */
@Singleton
class WalkControllerImpl @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
    private val stepCounter: org.walktalkmeditate.pilgrim.sensor.StepCounter,
) : WalkController {
    private val _state = MutableStateFlow<WalkState>(WalkState.Idle)
    override val state: StateFlow<WalkState> = _state.asStateFlow()

    /**
     * `replay = 0` + `extraBufferCapacity = 4` so a late subscriber
     * misses any in-flight emission (matches iOS's fire-and-forget
     * semantics) and bursty triggers (e.g. fast Active → Meditate →
     * Active toggles) don't block the dispatch mutex if a slow observer
     * is mid-collect.
     */
    private val _bellTriggers = MutableSharedFlow<BellTrigger>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    override val bellTriggers: SharedFlow<BellTrigger> = _bellTriggers.asSharedFlow()

    /**
     * Direct passthrough of the @Singleton
     * [org.walktalkmeditate.pilgrim.sensor.StepCounter]'s hot StateFlow
     * (no `WhileSubscribed` re-wrap — Stage 5-G stale-cache trap).
     */
    override val liveSteps: StateFlow<Int?> = stepCounter.liveSteps

    private val dispatchMutex = Mutex()

    /**
     * Starts a new walk. Atomic under the dispatch mutex: the Room insert,
     * reducer transition, and state commit happen together, so duplicate
     * calls (double-tap, reentrant service start) cannot create orphan
     * Walk rows. Legal from [WalkState.Idle] (first walk) and
     * [WalkState.Finished] (subsequent walks after reviewing the summary);
     * throws otherwise.
     */
    override suspend fun startWalk(intention: String?): Walk = dispatchMutex.withLock {
        val current = _state.value
        check(current is WalkState.Idle || current is WalkState.Finished) {
            "startWalk requires Idle or Finished state but controller is currently $current"
        }
        val startedAt = clock.now()
        // Same trim/truncate/blank-check as `setIntention` so a future
        // caller (test, restore, deep-link) passing `"   "` or a 200-char
        // string can't land malformed text in Room.
        val sanitized = intention?.trim()
            ?.take(WalkController.MAX_INTENTION_CHARS)
            ?.takeIf { it.isNotBlank() }
        val walk = repository.startWalk(startTimestamp = startedAt, intention = sanitized)
        stepCounter.start()
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
        _bellTriggers.tryEmit(BellTrigger.WalkStart)
        walk
    }

    override suspend fun pauseWalk() {
        Log.i(TAG, "pauseWalk invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.Pause(at = clock.now()))
    }

    override suspend fun resumeWalk() {
        Log.i(TAG, "resumeWalk invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.Resume(at = clock.now()))
    }

    override suspend fun startMeditation() {
        Log.i(TAG, "startMeditation invoked from state=${_state.value::class.simpleName}")
        dispatch(WalkAction.MeditateStart(at = clock.now()))
        _bellTriggers.tryEmit(BellTrigger.MeditationStart)
    }

    override suspend fun endMeditation(endMillis: Long?) {
        Log.i(TAG, "endMeditation invoked from state=${_state.value::class.simpleName}")
        val at = endMillis ?: clock.now()
        dispatch(WalkAction.MeditateEnd(at = at))
        _bellTriggers.tryEmit(BellTrigger.MeditationEnd)
    }

    override suspend fun finishWalk() {
        Log.i(TAG, "finishWalk invoked from state=${_state.value::class.simpleName}")
        val wasInProgress = _state.value !is WalkState.Idle &&
            _state.value !is WalkState.Finished
        dispatch(WalkAction.Finish(at = clock.now()))
        // Only fire walk-end on a real terminal transition. Without
        // this guard, a redundant finish call (double-tap, notification
        // race) from already-Finished/Idle state would ring a stray
        // bell.
        if (wasInProgress) {
            _bellTriggers.tryEmit(BellTrigger.WalkEnd)
        }
    }

    /**
     * Stage 9.5-C: leaves the walk without saving. Active|Paused|Meditating
     * transitions to Idle and the walk row + all child rows are removed
     * via the `PurgeWalk` effect. Idle and Finished are no-ops; once a
     * walk reaches Finished the row has been committed to history and
     * deletion belongs to a different surface (Goshuin/Home long-press).
     */
    override suspend fun discardWalk() {
        Log.i(TAG, "discardWalk invoked from state=${_state.value::class.simpleName}")
        // iOS parity ActiveWalkViewModel.swift:243 — `cancel()` calls
        // `soundManagement.onWalkEnd()`, which rings the same
        // walk-end bell as the finish path (SoundManagement.swift:62-66).
        // Capture in-progress BEFORE dispatch so the post-Discard Idle
        // state doesn't mask it. The guard prevents a stray bell on the
        // pre-walk "Leave" — discard is a no-op from Idle/Finished.
        val wasInProgress = _state.value !is WalkState.Idle &&
            _state.value !is WalkState.Finished
        dispatch(WalkAction.Discard(at = clock.now()))
        if (wasInProgress) {
            _bellTriggers.tryEmit(BellTrigger.WalkEnd)
        }
    }

    override suspend fun recordLocation(point: LocationPoint) =
        dispatch(WalkAction.LocationSampled(point))

    /**
     * Stage 9.5-C: persist a free-text intention on the active walk. Trims
     * whitespace, truncates at [WalkController.MAX_INTENTION_CHARS], and
     * clears the field (writes null) when the resulting text is blank.
     * No-op when no walk is in progress (Idle / Finished).
     *
     * Held under [dispatchMutex] so a concurrent finishWalk()'s Finalize
     * effect can't interleave; the repo write is direct (no [WalkAction]
     * dispatched) because intention is metadata that doesn't participate
     * in the reducer's state-machine transitions.
     */
    override suspend fun setIntention(text: String) {
        dispatchMutex.withLock {
            val walkId = activeWalkIdOrNull(_state.value) ?: return@withLock
            val sanitized = text.trim()
                .take(WalkController.MAX_INTENTION_CHARS)
                .takeIf { it.isNotBlank() }
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
     * in-progress walk. Allowed from Active / Paused / Meditating.
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
    override suspend fun recordWaypoint(label: String?, icon: String?) {
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

    override suspend fun recoverStaleWalks(): Long? = dispatchMutex.withLock {
        val all = repository.allWalks()
        val unfinished = all.filter { it.endTimestamp == null }
        Log.i(
            TAG,
            "recoverStaleWalks: scan total=${all.size} unfinished=${unfinished.size} " +
                "ids=${unfinished.map { it.id }} state=${_state.value::class.simpleName}",
        )
        if (unfinished.isEmpty()) {
            return@withLock null
        }
        var mostRecentlyRecovered: Long? = null
        var mostRecentStart = Long.MIN_VALUE
        var mostRecentEndTs = 0L
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
                    mostRecentEndTs = endTs
                }
            } else {
                Log.w(TAG, "recoverStaleWalks: finishWalkAtomic returned false for walk=${walk.id}")
            }
        }
        // Warm-launch case: process survived swipe-from-recents (FGS
        // didn't fully tear down despite stopWithTask="true"). Controller
        // still holds the walk in memory as Active|Paused|Meditating.
        // We need to flip the in-memory state OUT of "in-progress" so
        // WalkStartScreen's `isInProgress` redirect doesn't navigate
        // back to ActiveWalkScreen on the next composition.
        //
        // CRITICAL: emit Finished, NOT Idle. WalkLifecycleObserver's
        // Idle branch deletes any in-flight WAV (treats Idle as the
        // discardWalk path). Recovery is logically a finish-without-tap,
        // not a discard — so the lifecycle observer's Finished branch
        // (commit row + keep WAV) is the correct route. WalkFinalizationObserver
        // also fires on Finished, which gets us transcription scheduling +
        // collective POST + widget refresh for free.
        val current = _state.value
        if (current is WalkState.Active ||
            current is WalkState.Paused ||
            current is WalkState.Meditating
        ) {
            val accumulator = when (current) {
                is WalkState.Active -> current.walk
                is WalkState.Paused -> current.walk
                is WalkState.Meditating -> current.walk
                else -> null
            }
            if (accumulator != null) {
                Log.i(
                    TAG,
                    "recoverStaleWalks: in-memory ${current::class.simpleName} → " +
                        "Finished(walkId=${accumulator.walkId}, endedAt=$mostRecentEndTs)",
                )
                _state.value = WalkState.Finished(walk = accumulator, endedAt = mostRecentEndTs)
            }
        }
        return@withLock mostRecentlyRecovered
    }

    override suspend fun restoreActiveWalk(): Walk? = dispatchMutex.withLock {
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
                altitudeMeters = sample.altitudeMeters,
                verticalAccuracyMeters = sample.verticalAccuracyMeters,
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
            syncStepCounter(from = current, to = next)
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

    /**
     * iOS parity `StepCounter.swift:75-83` — the pedometer records only
     * while `status == .recording` (Active). Pausing or entering
     * meditation suspends the counter; returning to Active resumes it
     * with a fresh segment so OS-counted steps during the suspended span
     * are excluded from the walk total.
     */
    private fun syncStepCounter(from: WalkState, to: WalkState) {
        if (from::class == to::class) return
        when (to) {
            is WalkState.Active -> if (from is WalkState.Paused || from is WalkState.Meditating) {
                stepCounter.resume()
            }
            is WalkState.Paused, is WalkState.Meditating ->
                if (from is WalkState.Active) stepCounter.pause()
            else -> Unit
        }
    }

    private suspend fun applyEffect(effect: WalkEffect) {
        when (effect) {
            WalkEffect.None -> Unit

            is WalkEffect.PersistLocation -> {
                // Best effort. Losing one location sample on a disk stall
                // is acceptable; killing a 90-minute walk is not.
                try {
                    repository.recordLocation(
                        RouteDataSample(
                            walkId = effect.walkId,
                            timestamp = effect.point.timestamp,
                            latitude = effect.point.latitude,
                            longitude = effect.point.longitude,
                            horizontalAccuracyMeters = effect.point.horizontalAccuracyMeters,
                            speedMetersPerSecond = effect.point.speedMetersPerSecond,
                            altitudeMeters = effect.point.altitudeMeters,
                            verticalAccuracyMeters = effect.point.verticalAccuracyMeters,
                        ),
                    )
                    // Mirror to the dedicated `altitude_samples` table so
                    // the post-walk summary's `altitudeSamples` query
                    // resolves a non-empty list. iOS keeps these separate
                    // because barometric altitude is more accurate than
                    // GPS; GPS altitude lands here as a best-effort
                    // fallback when no barometer feeds it.
                    val alt = effect.point.altitudeMeters
                    if (alt != null) {
                        repository.recordAltitude(
                            AltitudeSample(
                                walkId = effect.walkId,
                                timestamp = effect.point.timestamp,
                                altitudeMeters = alt,
                                verticalAccuracyMeters =
                                    effect.point.verticalAccuracyMeters,
                            ),
                        )
                    }
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
                // iOS parity Walk.steps: capture step-counter diff at finish.
                val steps = stepCounter.stop()
                repository.updateSteps(walkId = effect.walkId, steps = steps)
            }

            is WalkEffect.PurgeWalk -> {
                // Discard path: drop the sensor listener without persisting
                // (the walk row is being deleted anyway).
                stepCounter.stop()
                repository.deleteWalkById(effect.walkId)
            }
        }
    }

    internal companion object {
        private const val TAG = "WalkControllerImpl"
    }
}
