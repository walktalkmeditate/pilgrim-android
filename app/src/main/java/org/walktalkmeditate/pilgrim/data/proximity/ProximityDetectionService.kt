// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.proximity

import android.location.Location
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * iOS parity `ProximityDetectionService.swift@db4196e`. Watches the
 * live location stream for proximity entries/exits against a set of
 * [ProximityTarget]s. Emits [ProximityEvent]s on a hot SharedFlow.
 *
 * Constants (verbatim iOS):
 *  - 5s location sample throttle (`sample(5.seconds)`)
 *  - 42m whisper radius / 108m cairn radius (per-target field, not
 *    looked up by type)
 *  - 1.2x exit hysteresis (re-arm only after `distance > radius * 1.2`)
 *  - dedup via `notifiedTargetIDs: Set<String>`; cleared by
 *    [resetSession] OR by an Exited event firing for that id
 *
 * `bindToLocation` cancels any prior subscription. `stopListening`
 * cancels the subscription AND clears the dedup set.
 * `suppressTarget(id)` inserts the synthesized id directly into the
 * dedup set so a just-placed item doesn't immediately re-encounter
 * during the same walk.
 */
@OptIn(FlowPreview::class)
@Singleton
open class ProximityDetectionService @Inject constructor() {

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()

    @Volatile private var targets: Set<ProximityTarget> = emptySet()
    private val notifiedTargetIDs: MutableSet<String> = mutableSetOf()

    private val _events = MutableSharedFlow<ProximityEvent>(extraBufferCapacity = 8)
    open val events: SharedFlow<ProximityEvent> = _events.asSharedFlow()

    private var locationJob: Job? = null

    /**
     * iOS parity — atomically replace the target set. Existing
     * dedup entries for ids that no longer appear in the new set
     * are RETAINED (notifiedTargetIDs is independent of targets).
     * Matches iOS subtle behavior: stale dedup-but-no-target entries
     * are harmless — the distance check simply never finds them.
     */
    open fun updateTargets(newTargets: Set<ProximityTarget>) {
        targets = newTargets
    }

    /**
     * iOS parity `bindToLocation`. Cancels any prior subscription
     * and starts a new one with a 5-second `sample` throttle —
     * emits the most-recent value seen in each 5-second window.
     */
    open fun bindToLocation(locations: Flow<Location?>) {
        locationJob?.cancel()
        locationJob = scope.launch {
            locations
                .sample(LOCATION_SAMPLE_PERIOD)
                .collect { loc ->
                    if (loc == null) return@collect
                    evaluate(loc.latitude, loc.longitude)
                }
        }
    }

    /**
     * iOS parity `resetSession`. Clears `notifiedTargetIDs` ONLY —
     * does NOT cancel the location subscription. Called at walk
     * start so a target encountered on a prior walk is fresh again
     * for the new session.
     */
    open suspend fun resetSession() {
        mutex.withLock { notifiedTargetIDs.clear() }
    }

    /**
     * iOS parity `stopListening`. Cancels the location subscription
     * AND clears `notifiedTargetIDs`. Called at walk end / discard.
     */
    open suspend fun stopListening() {
        locationJob?.cancel()
        locationJob = null
        mutex.withLock { notifiedTargetIDs.clear() }
    }

    /**
     * iOS parity `suppressTarget`. Inserts the synthesized id (e.g.
     * `whisper-abc123` / `cairn-xyz789`) into the dedup set so a
     * just-placed item is not immediately fired as a proximity
     * encounter on the next location tick.
     */
    open suspend fun suppressTarget(id: String) {
        mutex.withLock { notifiedTargetIDs += id }
    }

    private suspend fun evaluate(latitude: Double, longitude: Double) {
        // Snapshot the target set (volatile read) so a concurrent
        // updateTargets doesn't change the working set mid-iteration.
        val snapshot = targets
        if (snapshot.isEmpty()) return
        // Collect events first (no I/O — just math), emit outside
        // the mutex. The mutex protects notifiedTargetIDs.
        val toEmit = mutableListOf<ProximityEvent>()
        mutex.withLock {
            for (target in snapshot) {
                val distance = haversineMeters(
                    latitude,
                    longitude,
                    target.latitude,
                    target.longitude,
                )
                val isNotified = target.id in notifiedTargetIDs
                if (!isNotified && distance <= target.radius) {
                    notifiedTargetIDs += target.id
                    toEmit += ProximityEvent(
                        target = target,
                        distanceMeters = distance,
                        direction = ProximityEvent.Direction.Entered,
                    )
                } else if (isNotified && distance > target.radius * EXIT_HYSTERESIS) {
                    notifiedTargetIDs -= target.id
                    toEmit += ProximityEvent(
                        target = target,
                        distanceMeters = distance,
                        direction = ProximityEvent.Direction.Exited,
                    )
                }
            }
        }
        for (event in toEmit) _events.emit(event)
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        // Android's Location.distanceBetween is more accurate than a
        // hand-rolled haversine and is what the rest of the app uses
        // (matches the WalkAccumulator path).
        val out = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, out)
        return out[0].toDouble()
    }

    companion object {
        val LOCATION_SAMPLE_PERIOD = 5.seconds
        const val WHISPER_RADIUS_M = 42.0
        const val CAIRN_RADIUS_M = 108.0
        const val EXIT_HYSTERESIS = 1.2
    }
}
