// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlinx.coroutines.launch
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
    private val watchdog: WalkTrackingWatchdog,
    @WalkFinalizationScope private val scope: CoroutineScope,
) : WalkController {

    /**
     * Source-of-truth StateFlow. Driven by [stateCollector] which
     * tracks transitions between Room observations of the active
     * walk and synthesizes [WalkState.Finished] on the active→null
     * transition by re-reading the just-finalized row from Room.
     *
     * Without that synthesis, the active walk's row dropping out of
     * `observeActiveWalk` (because tracker just set `end_timestamp`)
     * would collapse straight to Idle, and the UI summary screen
     * navigation (which keys off `state is Finished`) would never
     * fire. Users would see the Path screen instead of their walk
     * summary on tap-Finish.
     */
    private val _state = MutableStateFlow<WalkState>(WalkState.Idle)
    override val state: StateFlow<WalkState> = _state.asStateFlow()

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
        // Drive [_state] from Room. Owns the active→null→finished
        // transition synthesis that [stateIn]-based derivation can't
        // express (see below).
        stateCollector()
        // Bell trigger derivation: observe Room transitions and emit
        // the corresponding [BellTrigger] for each user-initiated
        // event. Skip-first pattern: the FIRST emission of each
        // observation is the restore snapshot and must not re-fire
        // bells that already rang in a prior process.
        observeBellTriggers()
    }

    /**
     * Drives [_state] from Room observations. The hard case it solves:
     *
     *  - User taps Finish → tracker dispatches finishWalk → walk row
     *    `end_timestamp` is set in Room → multi-instance invalidation
     *    re-fires UI's `observeActiveWalk()` → row is filtered out
     *    (`WHERE end_timestamp IS NULL`) → UI sees null.
     *  - A naive null→Idle mapping would skip [WalkState.Finished]
     *    entirely. UI navigation routes to the post-walk summary on
     *    `state is Finished`; without that emission, tap-Finish lands
     *    on the Path screen instead of the summary.
     *
     * On every active→null transition we re-read the previously-
     * observed walk by id and inspect its `end_timestamp`:
     *  - end_timestamp non-null → walk finalized → emit Finished
     *    with the full accumulator reconstructed from samples + events.
     *  - row missing → walk discarded (PurgeWalk effect deletes the
     *    row + cascades to samples/events) → emit Idle.
     *
     * The first observation is always treated as a restore snapshot
     * (no synthetic Finished emission for state we observed AFTER it
     * had already finalized in a prior process).
     */
    private fun stateCollector() {
        scope.launch {
            var prevActiveWalk: Walk? = null
            var firstEmission = true
            repository.observeActiveWalk()
                // Dedupe active-walk emissions on (id, endTimestamp).
                // Room re-fires observeActive whenever the walks table
                // is dirtied — including the second write that
                // WalkEffect.FinalizeWalk does for `walks.steps`. Without
                // dedup, the tracker's finalize bundle (finishWalkAtomic
                // + updateSteps) produces two null emissions: the first
                // synthesizes WalkState.Finished, the second collapses
                // to Idle because prevActiveWalk was reset between the
                // two. UI then navigates Active → Summary → popBackStack
                // to Path within a single frame, dropping the user on
                // Path instead of the summary.
                //
                // Compare by (id, endTimestamp) so a steps-only update
                // for the SAME active walk still emits (UI live step
                // count); only re-emissions of the same null or same
                // (id, endTimestamp) tuple are dropped.
                .distinctUntilChanged { a, b ->
                    a?.id == b?.id && a?.endTimestamp == b?.endTimestamp
                }
                .flatMapLatest { walk ->
                    if (walk == null) {
                        flowOf(null)
                    } else {
                        combine(
                            repository.observeEventsForWalk(walk.id),
                            repository.observeLocationSamples(walk.id),
                        ) { events, samples ->
                            Triple(walk, events, samples)
                        }
                    }
                }
                .collect { tuple ->
                    if (tuple == null) {
                        // Active walk disappeared. Distinguish finish
                        // (row exists with end_timestamp) from
                        // discard (row deleted) by re-reading the
                        // previously-active walk's row.
                        val newState: WalkState = if (firstEmission || prevActiveWalk == null) {
                            // App-start with no walk in progress, or
                            // the prior state was already non-active.
                            // Plain Idle.
                            WalkState.Idle
                        } else {
                            val finalized = repository.getWalk(prevActiveWalk!!.id)
                            if (finalized != null && finalized.endTimestamp != null) {
                                val samples = repository.locationSamplesFor(finalized.id)
                                val events = repository.eventsFor(finalized.id)
                                buildFinishedAccumulatorState(finalized, events, samples)
                            } else {
                                // Row missing → discard path (PurgeWalk).
                                WalkState.Idle
                            }
                        }
                        prevActiveWalk = null
                        firstEmission = false
                        _state.value = newState
                    } else {
                        val (walk, events, samples) = tuple
                        prevActiveWalk = walk
                        firstEmission = false
                        _state.value = buildActiveState(walk, events, samples)
                    }
                }
        }
    }

    private fun buildFinishedAccumulatorState(
        walk: Walk,
        events: List<WalkEvent>,
        samples: List<RouteDataSample>,
    ): WalkState.Finished {
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
        return WalkState.Finished(
            accumulator,
            endedAt = walk.endTimestamp ?: walk.startTimestamp,
        )
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
        val walk = try {
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
        // Belt-and-suspenders: AlarmManager watchdog periodically
        // verifies the FGS is still alive in :tracker. If REDELIVER_
        // INTENT revival fails (hardened ROM, repeated o-kill), the
        // watchdog re-issues startForegroundService to wake tracker.
        watchdog.schedule()
        return walk
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
        watchdog.cancel()
    }

    override suspend fun discardWalk() {
        actionPublisher.discard()
        watchdog.cancel()
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
