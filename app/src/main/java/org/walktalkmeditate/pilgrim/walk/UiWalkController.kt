// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * UI-process [WalkController]: derives [state] reactively from Room
 * (walks + walk_events + route_data_samples) and forwards every
 * mutation to the `:tracker` process via [WalkActionPublisher].
 *
 * The tracker process runs the authoritative [WalkControllerImpl] —
 * full in-memory state machine, GPS pipeline, step counter. UI's
 * controller is read-only with respect to in-memory state and stateless
 * with respect to mutations.
 *
 * **Why Room is the cross-process source of truth.** SQLite with
 * multi-instance invalidation already gives us a coherent, ordered,
 * persistent log of every walk transition (PAUSED / RESUMED /
 * MEDITATION_START / MEDITATION_END events, plus walk rows for
 * Start/End and route_data_samples for distance / lastLocation).
 * Re-implementing a cross-process state channel via AIDL or Messenger
 * would duplicate that log while adding serialization, bind-lifecycle,
 * and process-restart fragility.
 *
 * **bellTriggers.** Derived from observing Room transitions:
 *  - WalkStart: active-walk row appearing for the first time after
 *    process start
 *  - WalkEnd: active-walk row's `end_timestamp` transitioning from null
 *    to non-null, or the row disappearing (discard path)
 *  - MeditationStart / MeditationEnd: new MEDITATION_START /
 *    MEDITATION_END walk_event rows
 *
 * First-emission discard mirrors the [WalkLifecycleObserver] /
 * [WalkFinalizationObserver] pattern — observing the CURRENT Room
 * state at app-init is not a transition (it's a restore), so we don't
 * replay bells for past transitions.
 *
 * **liveSteps.** Reads from the active walk row's `steps` column,
 * which the tracker's step-flush ticker writes every 30 s while
 * Active. Acceptable display lag for an unattended-pocket walk; the
 * post-walk summary still shows the canonical finish-time value
 * because the FinalizeWalk effect overwrites it.
 *
 * **startWalk semantics.** Fires ACTION_START with intention + fresh
 * flag, then awaits the new walk row to appear in Room. Returns that
 * Walk for the caller's downstream side effects (weather schedule,
 * celestial greeting). 5 s timeout guards against the tracker failing
 * to start (FGS restrictions, permission revoke, etc.).
 */
@Singleton
class UiWalkController @Inject constructor(
    private val repository: WalkRepository,
    private val actionPublisher: WalkActionPublisher,
    @WalkFinalizationScope private val scope: CoroutineScope,
) : WalkController {

    override val state: StateFlow<WalkState> = repository.observeActiveWalk()
        .flatMapLatest { walk ->
            if (walk == null) {
                // Walk row finalized OR discarded. We can't easily
                // distinguish here; both terminal states collapse to
                // Idle as far as the live state flow is concerned.
                // Finished is only emitted briefly during the
                // post-tap transition window when the walk row still
                // exists with end_timestamp set; once the observe-active
                // query stops including it (predicate is
                // `end_timestamp IS NULL`), we fall back to Idle.
                flowOf<WalkState>(WalkState.Idle)
            } else if (walk.endTimestamp != null) {
                // Defensive: observeActive's predicate already excludes
                // finished rows, but a race window during the
                // FinalizeWalk effect could surface one. Treat as
                // Finished so observers fire their finalize bundles.
                buildFinishedState(walk)
            } else {
                combine(
                    repository.observeEventsForWalk(walk.id),
                    repository.observeLocationSamples(walk.id),
                ) { events, samples ->
                    buildActiveState(walk, events, samples)
                }
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, WalkState.Idle)

    private val _bellTriggers = MutableSharedFlow<BellTrigger>(
        replay = 0,
        extraBufferCapacity = 4,
    )
    override val bellTriggers: SharedFlow<BellTrigger> = _bellTriggers.asSharedFlow()

    /**
     * Lagged step count: reads `walks.steps` column updated by the
     * tracker's 30 s flush ticker. Compose recomposes the steps row
     * once per 30 s, which is unobtrusive (steps drift slowly anyway).
     */
    override val liveSteps: StateFlow<Int?> = repository.observeActiveWalk()
        .map { walk -> walk?.steps }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Bell trigger derivation: observe Room transitions and emit
        // the corresponding [BellTrigger] for each user-initiated
        // event. Skip-first pattern: the FIRST emission of each
        // observation is the restore snapshot and must not re-fire
        // bells that already rang in a prior process.
        observeBellTriggers()
    }

    private fun observeBellTriggers() {
        // WalkStart + WalkEnd: derived from active-walk row presence
        // + end_timestamp. Tracks the most recently observed active
        // walkId so we can distinguish "fresh new walk" from "the
        // existing walk's row updated (e.g. steps flush)".
        var prevActiveWalkId: Long? = null
        var firstEmission = true
        repository.observeActiveWalk()
            .onEach { walk ->
                val currentId = walk?.id
                // First emission = restore snapshot. Capture state +
                // skip bell emission.
                if (firstEmission) {
                    firstEmission = false
                    prevActiveWalkId = currentId
                    return@onEach
                }
                if (prevActiveWalkId == null && currentId != null) {
                    _bellTriggers.tryEmit(BellTrigger.WalkStart)
                } else if (prevActiveWalkId != null && currentId == null) {
                    // Active walk row gone. Could be either Finish
                    // (end_timestamp set, predicate excludes it) or
                    // Discard (row deleted). Either way the walk is
                    // over → fire WalkEnd. discardWalk + finishWalk
                    // share this emission path, mirroring
                    // [WalkControllerImpl.discardWalk]'s parity with
                    // iOS's `soundManagement.onWalkEnd()` on cancel.
                    _bellTriggers.tryEmit(BellTrigger.WalkEnd)
                }
                prevActiveWalkId = currentId
            }
            .launchIn(scope)

        // Meditation start/end: derived from new MEDITATION_START /
        // MEDITATION_END walk_event rows. Per-walk observation
        // restarts when the active walk changes.
        var lastSeenEventCount = 0
        var firstEventEmission = true
        var lastObservedWalkId: Long? = null
        repository.observeActiveWalk()
            .map { it?.id }
            .distinctUntilChanged()
            .flatMapLatest { walkId ->
                // Reset per-walk bookkeeping when the active walk
                // changes. A null walkId means no active walk —
                // nothing to observe.
                if (walkId == null) {
                    lastSeenEventCount = 0
                    firstEventEmission = true
                    lastObservedWalkId = null
                    flowOf<List<WalkEvent>>(emptyList())
                } else {
                    if (lastObservedWalkId != walkId) {
                        lastSeenEventCount = 0
                        firstEventEmission = true
                        lastObservedWalkId = walkId
                    }
                    repository.observeEventsForWalk(walkId)
                }
            }
            .onEach { events ->
                if (firstEventEmission) {
                    firstEventEmission = false
                    lastSeenEventCount = events.size
                    return@onEach
                }
                if (events.size <= lastSeenEventCount) {
                    lastSeenEventCount = events.size
                    return@onEach
                }
                // New events appended since last emission. Emit a
                // bell for each meditation-start/end.
                events.drop(lastSeenEventCount).forEach { event ->
                    when (event.eventType) {
                        WalkEventType.MEDITATION_START ->
                            _bellTriggers.tryEmit(BellTrigger.MeditationStart)
                        WalkEventType.MEDITATION_END ->
                            _bellTriggers.tryEmit(BellTrigger.MeditationEnd)
                        else -> Unit
                    }
                }
                lastSeenEventCount = events.size
            }
            .launchIn(scope)
    }

    private suspend fun buildActiveState(
        walk: Walk,
        events: List<WalkEvent>,
        samples: List<RouteDataSample>,
    ): WalkState {
        val points = samples.map { it.toLocationPoint() }
        val distance = walkDistanceMeters(points)
        val totals = replayWalkEventTotals(events = events, closeAt = null)
        val accumulator = WalkAccumulator(
            walkId = walk.id,
            startedAt = walk.startTimestamp,
            lastLocation = points.lastOrNull(),
            distanceMeters = distance,
            totalPausedMillis = totals.totalPausedMillis,
            totalMeditatedMillis = totals.totalMeditatedMillis,
        )
        return when {
            totals.pendingPauseAt != null ->
                WalkState.Paused(accumulator, pausedAt = totals.pendingPauseAt!!)
            totals.pendingMeditationAt != null ->
                WalkState.Meditating(accumulator, meditationStartedAt = totals.pendingMeditationAt!!)
            else -> WalkState.Active(accumulator)
        }
    }

    private suspend fun buildFinishedState(walk: Walk): kotlinx.coroutines.flow.Flow<WalkState> {
        val samples = repository.locationSamplesFor(walk.id)
        val events = repository.eventsFor(walk.id)
        val points = samples.map { it.toLocationPoint() }
        val distance = walkDistanceMeters(points)
        val totals = replayWalkEventTotals(events = events, closeAt = walk.endTimestamp)
        val accumulator = WalkAccumulator(
            walkId = walk.id,
            startedAt = walk.startTimestamp,
            lastLocation = points.lastOrNull(),
            distanceMeters = distance,
            totalPausedMillis = totals.totalPausedMillis,
            totalMeditatedMillis = totals.totalMeditatedMillis,
        )
        return flowOf(
            WalkState.Finished(accumulator, endedAt = walk.endTimestamp ?: walk.startTimestamp),
        )
    }

    private fun RouteDataSample.toLocationPoint(): LocationPoint = LocationPoint(
        timestamp = timestamp,
        latitude = latitude,
        longitude = longitude,
        horizontalAccuracyMeters = horizontalAccuracyMeters,
        speedMetersPerSecond = speedMetersPerSecond,
        altitudeMeters = altitudeMeters,
        verticalAccuracyMeters = verticalAccuracyMeters,
    )

    // --- Mutations: fire intent to tracker, optionally await Room ---

    override suspend fun startWalk(intention: String?): Walk {
        actionPublisher.start(intention)
        // Wait for the tracker to insert the walk row. 5 s timeout
        // covers worst-case tracker spin-up. Any later timeout
        // bubbles as TimeoutCancellationException, which
        // WalkViewModel.startWalk's try/catch maps to a no-op
        // (matches the existing IllegalStateException path).
        return try {
            withTimeout(START_AWAIT_TIMEOUT_MS) {
                repository.observeActiveWalk().filterNotNull().first()
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // The tracker did not produce a row in time. Treat as
            // IllegalStateException so callers can roll back UI state
            // identically to the same-process controller's start
            // rejection path.
            throw IllegalStateException("tracker did not start walk within ${START_AWAIT_TIMEOUT_MS} ms", t)
        }
    }

    override suspend fun pauseWalk() {
        actionPublisher.pause()
    }

    override suspend fun resumeWalk() {
        actionPublisher.resume()
    }

    override suspend fun startMeditation() {
        actionPublisher.startMeditation()
    }

    override suspend fun endMeditation(endMillis: Long?) {
        actionPublisher.endMeditation(endMillis)
    }

    override suspend fun finishWalk() {
        actionPublisher.finish()
    }

    override suspend fun discardWalk() {
        actionPublisher.discard()
    }

    override suspend fun recordLocation(point: LocationPoint) {
        // GPS samples originate IN the tracker process via
        // FusedLocationSource → WalkControllerImpl.recordLocation.
        // UI process never has a reason to inject samples; this is
        // dead code in the UI-process binding. Logging at debug so
        // a future caller that wires it up notices the no-op.
        Log.d(TAG, "recordLocation called on UI controller — ignored (tracker owns GPS)")
    }

    override suspend fun setIntention(text: String) {
        actionPublisher.setIntention(text)
    }

    override suspend fun recordWaypoint(label: String?, icon: String?) {
        actionPublisher.markWaypoint(label, icon)
    }

    /**
     * Recovery scan + finalize for orphan walks. The tracker process
     * owns the in-memory state machine; UI's controller can finalize
     * orphan rows directly because the actual state-machine flip is
     * irrelevant in UI (UI's state derives from Room). Returns the
     * most-recently recovered walkId so MainActivity can show the
     * "recovered" banner.
     */
    override suspend fun recoverStaleWalks(): Long? {
        val all = repository.allWalks()
        val unfinished = all.filter { it.endTimestamp == null }
        if (unfinished.isEmpty()) return null
        var mostRecentlyRecovered: Long? = null
        var mostRecentStart = Long.MIN_VALUE
        for (walk in unfinished) {
            val lastSample = repository.lastLocationSampleFor(walk.id)
            val endTs = lastSample?.timestamp ?: (walk.startTimestamp + 1L)
            val finalized = repository.finishWalkAtomic(walkId = walk.id, endTimestamp = endTs)
            if (finalized && walk.startTimestamp > mostRecentStart) {
                mostRecentStart = walk.startTimestamp
                mostRecentlyRecovered = walk.id
            }
        }
        return mostRecentlyRecovered
    }

    /**
     * No-op in UI process: state restoration is handled reactively
     * by [state] (derived from Room). Returns the active walk row if
     * one exists, for parity with the controller interface.
     */
    override suspend fun restoreActiveWalk(): Walk? = repository.getActiveWalk()

    private companion object {
        private const val TAG = "UiWalkController"
        private const val START_AWAIT_TIMEOUT_MS = 5_000L
    }
}
