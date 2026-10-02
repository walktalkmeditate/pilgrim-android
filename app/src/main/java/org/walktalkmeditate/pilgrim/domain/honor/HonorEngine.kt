// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import kotlin.math.max
import kotlin.math.min
import org.walktalkmeditate.pilgrim.domain.ArrivalDebounce
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.wgs84MidLatitudeMeters

/**
 * Metres between a fix and a point, at the four call sites iOS measures
 * with `CLLocation.distance(from:)`: the moment radii, the two voice drops,
 * and arrival (parity spec B §16.1, D1–D4).
 */
typealias HonorDistance = (from: WayCoordinate, to: WayCoordinate) -> Double

/** `CLLocation.distance(from:)` as the golden traces pin it: see [wgs84MidLatitudeMeters]. */
val WGS84_HONOR_DISTANCE: HonorDistance = { from, to -> wgs84MidLatitudeMeters(from.lat, from.lon, to.lat, to.lon) }

enum class HonorPhase { WALKING, ARRIVED }

sealed class HonorEngineEvent {
    data class MomentReached(val moment: WayMoment) : HonorEngineEvent()
    data class VoiceStart(val moment: WayMoment) : HonorEngineEvent()
    data object VoicePause : HonorEngineEvent()
    data object VoiceResume : HonorEngineEvent()
    data class VoiceDropped(val moment: WayMoment) : HonorEngineEvent()
    data class SoftTap(val offWayMeters: Double) : HonorEngineEvent()
    data class Arrived(val theirSeconds: Double, val yourSeconds: Double) : HonorEngineEvent()
}

/**
 * Session engine for an honor walk (iOS `HonorEngine.swift@7c200bf`):
 * keeps position along the Way, moves the companion on the walker's
 * clock, triggers moments, and detects arrival. Persists nothing.
 *
 * Pure and synchronous. Each input is a call, and each call returns the
 * events it produced, in iOS's order; handle them in list order, after the
 * call returns, and feed any nested [voiceDidFinish] result straight back
 * (parity spec B §3). The engine sees every accepted fix: the gates hold
 * voice starts, never processing.
 *
 * Two clocks come in from outside. [clock] is the wall clock behind the
 * 120 s re-acquire, its 10 s retry, and the 120 s soft tap, read only when
 * a fix arrives. [updateActiveDuration] is the engine clock: elapsed walk
 * time minus pauses, the pause in progress included, with sittings left
 * in (spec B §7, owner decision 1). `WalkStats.activeWalkingMillis` also
 * takes out meditation, so it must not feed it.
 *
 * The stage-only water caption (`HonorEngineEvent.markAhead`) is not
 * ported; see [HonorMomentTracker].
 */
class HonorEngine(
    val way: Way,
    val softTapEnabled: Boolean,
    voicesEnabled: Boolean,
    private val clock: Clock,
    private val distance: HonorDistance = WGS84_HONOR_DISTANCE,
) {
    val geometry = WayGeometry(way.route)

    var progressFrac: Double = 0.0
        private set
    var distanceRemainingMeters: Double = geometry.totalMeters
        private set
    var offWayMeters: Double = 0.0
        private set
    var isOnWay: Boolean = false
        private set
    var companionFrac: Double = 0.0
        private set
    var phase: HonorPhase = HonorPhase.WALKING
        private set
    var startFrac: Double? = null
        private set
    var companionT0: Double = 0.0
        private set

    /**
     * Along-Way credit toward arrival: progress earned on the Way through
     * windowed fixes, plus a re-acquire's jump capped at the Way's own
     * pace. GPS jitter is credited once, never cumulatively.
     */
    val distanceWalkedMeters: Double get() = walkedFrac * geometry.totalMeters

    /** Begin's frac-0 fallback (nothing within 60 m) is an approach, not a joining. */
    val isAnchoredOnWay: Boolean get() = startFrac != null && !anchoredByFallback

    private var progressHighWater = 0.0
    private var walkedFrac = 0.0
    private val arrival = ArrivalDebounce(
        requiredFixes = HonorTuning.ARRIVAL_FIX_COUNT,
        accuracyMeters = HonorTuning.ARRIVAL_ACCURACY_METERS,
    )
    private val moments = HonorMomentTracker(
        moments = way.moments,
        geometry = geometry,
        voicesEnabled = voicesEnabled,
        distance = distance,
    )
    private var gates = HonorMomentTracker.Gates()
    private var activeDuration = 0.0
    private var anchoredByFallback = false

    /**
     * The engine clock when the Way was (re-)anchored. The companion's clock
     * and `yourSeconds` both run from here, so an approach walk to the
     * trailhead counts toward neither.
     */
    private var anchorActiveDuration = 0.0
    private var offWaySinceMillis: Long? = null

    /** The engine clock when the walker left the Way: a re-acquire's pace credit is earned walking, not paused. */
    private var offWayActiveDuration = 0.0
    private var lastReacquireAttemptMillis: Long? = null
    private var softTapSinceMillis: Long? = null
    private var softTapArmed = true

    // Inputs

    fun updateActiveDuration(seconds: Double) {
        activeDuration = seconds
        // Until the fallback anchor is replaced by a real join, the companion
        // waits at the start (HonorEngine.swift:126-129@7c200bf).
        if (startFrac == null || anchoredByFallback) return
        companionFrac = geometry.frac(atElapsed = companionT0 + sinceAnchorSeconds)
    }

    fun setGates(
        paused: Boolean,
        meditating: Boolean,
        recording: Boolean,
        externalAudio: Boolean,
    ): List<HonorEngineEvent> {
        gates = HonorMomentTracker.Gates(
            paused = paused,
            meditating = meditating,
            recording = recording,
            externalAudio = externalAudio,
        )
        return moments.gatesDidChange(gates).map { it.toEvent() }
    }

    fun voiceDidFinish(): List<HonorEngineEvent> = moments.voiceDidFinish(gates).map { it.toEvent() }

    /**
     * The engine's private state (parity spec B §2.2), for the `:tracker`
     * session to persist and revive. iOS persists none of it and never
     * resumes a walk; the published `isOnWay`, `offWayMeters`, and
     * `companionFrac` are left out because the next fix or tick recomputes them.
     */
    data class Snapshot(
        val phase: HonorPhase,
        val startFrac: Double?,
        val anchoredByFallback: Boolean,
        val anchorActiveSeconds: Double,
        val companionT0: Double,
        val progressFrac: Double,
        val progressHighWater: Double,
        val walkedFrac: Double,
        val offWaySinceMillis: Long?,
        val offWayActiveSeconds: Double,
        val lastReacquireAttemptMillis: Long?,
        val softTapSinceMillis: Long?,
        val softTapArmed: Boolean,
        val arrivalInsideFixes: Int,
        val tracker: HonorMomentTracker.Snapshot,
    )

    fun snapshot(): Snapshot = Snapshot(
        phase = phase,
        startFrac = startFrac,
        anchoredByFallback = anchoredByFallback,
        anchorActiveSeconds = anchorActiveDuration,
        companionT0 = companionT0,
        progressFrac = progressFrac,
        progressHighWater = progressHighWater,
        walkedFrac = walkedFrac,
        offWaySinceMillis = offWaySinceMillis,
        offWayActiveSeconds = offWayActiveDuration,
        lastReacquireAttemptMillis = lastReacquireAttemptMillis,
        softTapSinceMillis = softTapSinceMillis,
        softTapArmed = softTapArmed,
        arrivalInsideFixes = arrival.consecutiveInside,
        tracker = moments.snapshot(),
    )

    /** Picks up where [snapshot] left off, with no voice playing; call [updateActiveDuration] next. */
    fun restore(snapshot: Snapshot) {
        phase = snapshot.phase
        startFrac = snapshot.startFrac
        anchoredByFallback = snapshot.anchoredByFallback
        anchorActiveDuration = snapshot.anchorActiveSeconds
        companionT0 = snapshot.companionT0
        progressFrac = snapshot.progressFrac
        progressHighWater = snapshot.progressHighWater
        walkedFrac = snapshot.walkedFrac
        offWaySinceMillis = snapshot.offWaySinceMillis
        offWayActiveDuration = snapshot.offWayActiveSeconds
        lastReacquireAttemptMillis = snapshot.lastReacquireAttemptMillis
        softTapSinceMillis = snapshot.softTapSinceMillis
        softTapArmed = snapshot.softTapArmed
        arrival.restore(snapshot.arrivalInsideFixes)
        moments.restore(snapshot.tracker)
        distanceRemainingMeters = (1 - progressFrac) * geometry.totalMeters
        companionFrac = if (startFrac == null) 0.0 else geometry.frac(atElapsed = companionT0)
    }

    fun processLocation(point: LocationPoint): List<HonorEngineEvent> {
        val accuracy = point.horizontalAccuracyMeters?.toDouble() ?: return emptyList()
        if (!(accuracy >= 0 && accuracy <= HonorTuning.FIX_ACCURACY_METERS)) return emptyList()
        val coordinate = WayCoordinate(lat = point.latitude, lon = point.longitude)
        val events = mutableListOf<HonorEngineEvent>()

        if (startFrac == null) anchor(coordinate)
        track(coordinate)
        distanceRemainingMeters = (1 - progressFrac) * geometry.totalMeters
        evaluateSoftTap()?.let { events += it }
        evaluateArrival(coordinate, accuracy)?.let { events += it }

        val speed = point.speedMetersPerSecond?.toDouble()
        val stationary = speed != null && speed >= 0 && speed < HonorTuning.STATIONARY_SPEED
        moments.update(coordinate, progressFrac = progressFrac, gates = gates, isStationary = stationary)
            .mapTo(events) { it.toEvent() }
        return events
    }

    // Position

    /** Never negative, even if a re-anchor races a stale clock value. */
    private val sinceAnchorSeconds: Double get() = max(0.0, activeDuration - anchorActiveDuration)

    private fun anchor(coordinate: WayCoordinate) {
        val hit = geometry.lowestFrac(withinMeters = HonorTuning.ON_WAY_METERS, of = coordinate)?.frac
        anchoredByFallback = hit == null
        val frac = hit ?: 0.0
        startFrac = frac
        progressFrac = frac
        progressHighWater = frac
        walkedFrac = 0.0
        anchorActiveDuration = activeDuration
        companionT0 = geometry.elapsed(atFrac = frac)
        companionFrac = geometry.frac(atElapsed = companionT0)
    }

    /** Leaves [progressFrac] alone: the caller has already set it. */
    private fun reanchor(frac: Double) {
        anchoredByFallback = false
        startFrac = frac
        progressHighWater = frac
        walkedFrac = 0.0
        anchorActiveDuration = activeDuration
        companionT0 = geometry.elapsed(atFrac = frac)
        companionFrac = geometry.frac(atElapsed = companionT0)
    }

    private fun track(coordinate: WayCoordinate) {
        val windowSpan = if (geometry.totalMeters > 0) HonorTuning.WINDOW_METERS / geometry.totalMeters else 1.0
        val lower = max(0.0, progressFrac - HonorTuning.BACKWARD_TOLERANCE)
        val upper = min(1.0, progressFrac + windowSpan)
        val local = geometry.nearest(to = coordinate, within = lower..upper)
        offWayMeters = clampedMeters(local.meters)
        if (local.meters <= HonorTuning.ON_WAY_METERS) {
            isOnWay = true
            offWaySinceMillis = null
            lastReacquireAttemptMillis = null
            progressFrac = local.frac
            if (anchoredByFallback) {
                reanchor(progressFrac)
            } else {
                walkedFrac += max(0.0, progressFrac - progressHighWater)
                progressHighWater = max(progressHighWater, progressFrac)
            }
            return
        }
        isOnWay = false
        val time = clock.now()
        if (offWaySinceMillis == null) {
            offWaySinceMillis = time
            offWayActiveDuration = activeDuration
        }
        val dueForRetry = lastReacquireAttemptMillis
            ?.let { secondsBetween(it, time) >= HonorTuning.REACQUIRE_RETRY_SECONDS } ?: true
        val since = offWaySinceMillis
        if (since != null && secondsBetween(since, time) >= HonorTuning.REACQUIRE_SECONDS && dueForRetry) {
            lastReacquireAttemptMillis = time
            // Forward first: on an out-and-back the return leg shares the
            // outbound leg's pavement, and the global lowest frac would drag
            // progress back to the outbound leg (HonorEngine.swift:221-225@7c200bf).
            val ahead = geometry.lowestFrac(withinMeters = HonorTuning.ON_WAY_METERS, of = coordinate, fromFrac = lower)
            val found = ahead ?: geometry.lowestFrac(withinMeters = HonorTuning.ON_WAY_METERS, of = coordinate)
            if (found != null) {
                // Credit the jump no faster than the Way was walked, on walking
                // time rather than wall time (HonorEngine.swift:226-238@7c200bf).
                if (geometry.totalSeconds > 0) {
                    val jump = max(0.0, found.frac - progressHighWater)
                    val offWayWalking = max(0.0, activeDuration - offWayActiveDuration)
                    val paceFrac = offWayWalking / geometry.totalSeconds
                    walkedFrac += max(0.0, min(jump, paceFrac))
                }
                progressFrac = found.frac
                progressHighWater = max(progressHighWater, progressFrac)
                offWayMeters = clampedMeters(found.meters)
                isOnWay = true
                offWaySinceMillis = null
                lastReacquireAttemptMillis = null
                if (anchoredByFallback) reanchor(found.frac)
            }
        }
    }

    // Soft tap

    private fun evaluateSoftTap(): HonorEngineEvent? {
        // Nothing joined yet, so nothing has been left (HonorEngine.swift:264-268@7c200bf).
        if (anchoredByFallback) return null
        if (phase != HonorPhase.WALKING || !softTapEnabled) return null
        if (offWayMeters <= HonorTuning.ON_WAY_METERS) {
            softTapSinceMillis = null
            softTapArmed = true
            return null
        }
        if (!(softTapArmed && offWayMeters > HonorTuning.SOFT_TAP_METERS)) {
            if (offWayMeters <= HonorTuning.SOFT_TAP_METERS) softTapSinceMillis = null
            return null
        }
        val time = clock.now()
        val since = softTapSinceMillis ?: time
        softTapSinceMillis = since
        if (secondsBetween(since, time) >= HonorTuning.SOFT_TAP_SECONDS) {
            softTapArmed = false
            softTapSinceMillis = null
            return HonorEngineEvent.SoftTap(offWayMeters = offWayMeters)
        }
        return null
    }

    // Arrival

    private fun evaluateArrival(coordinate: WayCoordinate, accuracy: Double): HonorEngineEvent? {
        if (phase != HonorPhase.WALKING) return null
        val last = geometry.points.lastOrNull() ?: return null
        val start = startFrac ?: return null
        // Half of what lay ahead at the anchor, as along-Way credit. There is
        // no ends-coincide check: on a loop inside the 300 m window this can
        // pass at Begin, as iOS ships it (pilgrim-ios #100, spec B §11.3).
        val aheadAtBegin = max(0.0, 1 - start)
        if (!(progressFrac >= HonorTuning.ARRIVAL_MIN_FRAC &&
                walkedFrac >= HonorTuning.ARRIVAL_MIN_DISTANCE_RATIO * aheadAtBegin)
        ) {
            arrival.reset()
            return null
        }
        val end = WayCoordinate(lat = last.lat, lon = last.lon)
        val arrived = arrival.register(
            distance = distance(coordinate, end),
            radius = HonorTuning.ARRIVAL_RADIUS_METERS,
            accuracy = accuracy,
        )
        if (!arrived) return null
        phase = HonorPhase.ARRIVED
        return HonorEngineEvent.Arrived(
            theirSeconds = geometry.totalSeconds - companionT0,
            yourSeconds = sinceAnchorSeconds,
        )
    }

    private fun HonorMomentTracker.Action.toEvent(): HonorEngineEvent = when (this) {
        is HonorMomentTracker.Action.Reached -> HonorEngineEvent.MomentReached(moment)
        is HonorMomentTracker.Action.VoiceStart -> HonorEngineEvent.VoiceStart(moment)
        HonorMomentTracker.Action.VoicePause -> HonorEngineEvent.VoicePause
        HonorMomentTracker.Action.VoiceResume -> HonorEngineEvent.VoiceResume
        is HonorMomentTracker.Action.VoiceDropped -> HonorEngineEvent.VoiceDropped(moment)
    }

    private companion object {
        /** Beyond any Way's length: a sentinel or a bad fix, and every consumer formats it as a number. */
        const val MAX_REPORTED_OFF_WAY_METERS = 100_000.0

        fun clampedMeters(meters: Double): Double =
            if (meters.isFinite()) min(meters, MAX_REPORTED_OFF_WAY_METERS) else MAX_REPORTED_OFF_WAY_METERS

        /** iOS's `Date.timeIntervalSince`, on the injected clock's milliseconds. */
        fun secondsBetween(earlierMillis: Long, laterMillis: Long): Double = (laterMillis - earlierMillis) / 1000.0
    }
}
