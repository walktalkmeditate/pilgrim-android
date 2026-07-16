// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkState

sealed class SeekEngineEvent {
    data class Pulse(val aligned: Boolean, val distanceMeters: Double) : SeekEngineEvent()
    data class Arrived(val clearingIndex: Int) : SeekEngineEvent()
    data class StillnessBegan(val clearingIndex: Int) : SeekEngineEvent()

    /** Fired for both stillness and grace reveals. */
    data class RevealedNext(val activeIndex: Int) : SeekEngineEvent()
    data object SeekComplete : SeekEngineEvent()
}

enum class SeekEnginePhase {
    GUIDING,
    ARRIVED,

    /**
     * Reserved iOS-parity value (`SeekGlance.swift:28@c1745e8`): nothing
     * produces it at the pinned anchor — the engine commits reveals
     * atomically (GUIDING → GUIDING/COMPLETE) and the orchestrator (U9)
     * passes the engine phase straight through, matching iOS's
     * `updateSeekFog`. The fog and crescent models handle it defensively
     * (dissolved fog, hidden crescent) should a future reveal ritual
     * ever park here.
     */
    REVEALING,
    COMPLETE,
}

/**
 * Power input for the pulse-clock floor. Collapses iOS's four tiers to the
 * two the engine distinguishes — [LOW] applies the cadence floor (iOS low +
 * critical), [NORMAL] does not (iOS normal + meditation). Port spec D1.
 */
enum class SeekPowerTier {
    NORMAL,
    LOW,
}

/**
 * Starting values from the plan — cadence curve, cone width, smoothing
 * window, and debounce are on-device tuning candidates, not commitments.
 * The reroll budget floor lives in [SeekTuning.REROLL_MIN_BUDGET_METERS]
 * (single source shared with [SeekChain.regeneratingRemainder]).
 */
object SeekEngineTuning {
    const val FAR_DISTANCE_METERS = 2000.0
    const val NEAR_DISTANCE_METERS = 100.0
    const val FAR_PULSE_INTERVAL_MILLIS = 60_000L
    const val NEAR_PULSE_INTERVAL_MILLIS = 10_000L
    const val LOW_POWER_PULSE_FLOOR_MILLIS = 30_000L
    const val ALIGNMENT_CONE_DEGREES = 60.0
    const val HEADING_WINDOW_MILLIS = 15_000L
    const val ARRIVAL_FIX_COUNT = 3
    const val ARRIVAL_ACCURACY_METERS = 50.0
    const val GRACE_MILLIS = 240_000L
    val STILLNESS_WINDOW_RANGE_SECONDS = 45.0..90.0
    const val STILLNESS_CHECK_INTERVAL_MILLIS = 5_000L
}

/**
 * Session engine for a seek: consumes the ordered clearing chain, binds to
 * the injected walk streams, and publishes pulse/arrival/reveal events.
 * Service like ProximityDetectionService — it persists nothing and never
 * touches GPS power; the tier arrives as an input.
 *
 * Concurrency contract: all engine state is confined to [scope]'s
 * dispatcher (the iOS engine's main-queue delivery). The orchestrator must
 * build [scope] on a single-threaded dispatcher and call [seekAnew]/[stop]
 * from it. Port spec: docs/parity/2026-07-14-port-seek-engine-u3.md.
 */
class SeekEngine(
    chain: SeekChain,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val locations: Flow<LocationPoint>,
    private val walkStates: Flow<WalkState>,
    private val powerTiers: Flow<SeekPowerTier>,
    private val stillnessWindowOverrideMillis: Long? = null,
    private val windowRng: Random = Random.Default,
) {

    private val _chain = MutableStateFlow(chain)
    val chain: StateFlow<SeekChain> = _chain.asStateFlow()

    private val _activeIndex = MutableStateFlow(0)
    val activeIndex: StateFlow<Int> = _activeIndex.asStateFlow()

    private val _phase = MutableStateFlow(
        if (chain.clearings.isEmpty()) SeekEnginePhase.COMPLETE else SeekEnginePhase.GUIDING,
    )
    val phase: StateFlow<SeekEnginePhase> = _phase.asStateFlow()

    private val _distanceToActiveMeters = MutableStateFlow<Double?>(null)
    val distanceToActiveMeters: StateFlow<Double?> = _distanceToActiveMeters.asStateFlow()

    private val _events = MutableSharedFlow<SeekEngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SeekEngineEvent> = _events.asSharedFlow()

    var currentTier: SeekPowerTier = SeekPowerTier.NORMAL
        private set

    var pulseGeneration: Int = 0
        private set

    /**
     * Inputs (or whole feeds) dropped by [start]'s guards. The engine is
     * framework-free — no logger to route failures through — so they
     * surface here for tests and diagnostics instead of escaping into
     * the session scope and crashing the process.
     */
    var inputFaultCount: Int = 0
        private set

    private var collectorJobs: List<Job> = emptyList()
    private var pulseJob: Job? = null
    private var stillnessCheckJob: Job? = null
    private var stillnessDetector: SeekStillnessDetector? = null
    private var graceDeadlineMillis: Long? = null
    private var suspendedGraceRemainingMillis: Long? = null
    private var isSuspended = false
    private var consecutiveInsideCount = 0
    private var lastCoordinate: SeekPoint? = null
    private val courseSamples = ArrayDeque<CourseSample>()

    /**
     * Deliberately stale: carries the pre-reroll distance so the sonar
     * heartbeat keeps pulsing across [seekAnew] until the next fix supplies
     * the true distance to the replacement clearing.
     */
    private var rerollPulseDistance: Double? = null

    private data class CourseSample(val timestampMillis: Long, val courseDegrees: Double)

    // Public surface

    fun start() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs = listOf(
            collectQuietly(locations) { processLocation(it) },
            collectQuietly(walkStates) { handleWalkState(it) },
            collectQuietly(powerTiers) { handleTier(it) },
        )
    }

    /**
     * A poisoned input is dropped and a broken feed ends quietly —
     * counted in [inputFaultCount] — so one failure degrades guidance
     * instead of killing sibling collectors or crashing the process
     * (the SeekOrchestrator collector idiom, minus the logging the
     * domain layer cannot host).
     */
    private fun <T> collectQuietly(source: Flow<T>, handle: (T) -> Unit): Job = scope.launch {
        try {
            source.collect { value ->
                try {
                    handle(value)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    inputFaultCount += 1
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            inputFaultCount += 1
        }
    }

    fun stop() {
        invalidatePulseTimer()
        stopStillnessMachinery()
        collectorJobs.forEach { it.cancel() }
        collectorJobs = emptyList()
    }

    /**
     * Reroll. The remaining budget is a v1 estimate — distance walked is
     * not tracked here, so the budget scales by the fraction of clearings
     * still ahead, clamped so the regenerated remainder is never
     * degenerate. Callers may pass a [SeekSeed] so the reroll carries the
     * same provenance as the original generation; null falls back to OS
     * entropy (tests, callers without an intention in reach).
     */
    fun seekAnew(currentLocation: SeekPoint, seed: ULong? = null) {
        val phaseNow = _phase.value
        if (phaseNow != SeekEnginePhase.GUIDING && phaseNow != SeekEnginePhase.ARRIVED) return
        if (phaseNow == SeekEnginePhase.ARRIVED) {
            stopStillnessMachinery()
            _phase.value = SeekEnginePhase.GUIDING
        }
        val current = _chain.value
        val fractionAhead =
            1 - _activeIndex.value.toDouble() / max(current.clearings.size, 1).toDouble()
        val remainingBudget = max(
            current.budgetMeters * fractionAhead,
            SeekTuning.REROLL_MIN_BUDGET_METERS,
        )
        val rng: Random = seed?.let { SeekSeededGenerator(it) } ?: Random.Default
        _chain.value = current.regeneratingRemainder(
            fromActiveIndex = _activeIndex.value,
            current = currentLocation,
            remainingBudgetMeters = remainingBudget,
            rng = rng,
        )
        consecutiveInsideCount = 0
        rerollPulseDistance = _distanceToActiveMeters.value
        _distanceToActiveMeters.value = null
        invalidatePulseTimer()
        if (rerollPulseDistance != null) {
            schedulePulse()
            // The immediate pulse IS the reroll's feedback: one ping, one
            // haptic, one ring the moment the new clearing exists.
            emitPulse()
        }
    }

    // Pulse clock

    internal fun pulseTimerFired(generation: Int) {
        if (generation != pulseGeneration) return
        emitPulse()
        schedulePulse()
    }

    internal fun emitPulse() {
        if (_phase.value != SeekEnginePhase.GUIDING || isSuspended) return
        val distance = _distanceToActiveMeters.value ?: rerollPulseDistance ?: return
        _events.tryEmit(SeekEngineEvent.Pulse(aligned = isAligned(), distanceMeters = distance))
    }

    private fun ensurePulseScheduled() {
        if (pulseJob?.isActive == true) return
        schedulePulse()
    }

    private fun schedulePulse() {
        pulseGeneration += 1
        val generation = pulseGeneration
        pulseJob?.cancel()
        pulseJob = null
        if (_phase.value != SeekEnginePhase.GUIDING || isSuspended) return
        val distance = _distanceToActiveMeters.value ?: rerollPulseDistance ?: return
        val intervalMillis = pulseIntervalMillis(distance, currentTier)
        pulseJob = scope.launch {
            delay(intervalMillis)
            pulseTimerFired(generation)
        }
    }

    private fun invalidatePulseTimer() {
        pulseGeneration += 1
        pulseJob?.cancel()
        pulseJob = null
    }

    // Location intake

    internal fun processLocation(point: LocationPoint) {
        if (isSuspended) return
        val phaseNow = _phase.value
        if (phaseNow != SeekEnginePhase.GUIDING && phaseNow != SeekEnginePhase.ARRIVED) return
        val coordinate = SeekPoint(latitude = point.latitude, longitude = point.longitude)
        lastCoordinate = coordinate
        recordCourse(point)
        val active = activeClearing ?: return
        val distance = SeekChainGenerator.distance(coordinate, active.center)
        _distanceToActiveMeters.value = distance
        rerollPulseDistance = null

        when (phaseNow) {
            SeekEnginePhase.GUIDING ->
                updateArrivalDebounce(point, distance, active.radiusMeters)
            SeekEnginePhase.ARRIVED -> {
                stillnessDetector?.recordLocation(point)
                evaluateStillness(clock.now())
            }
            else -> Unit
        }
    }

    /**
     * Fixes worse than the accuracy gate neither advance nor reset the
     * consecutive count — a momentary multipath fix must not erase honest
     * progress toward arrival, and must never fake it either.
     */
    private fun updateArrivalDebounce(point: LocationPoint, distance: Double, radius: Double) {
        val accuracy = point.horizontalAccuracyMeters
        if (accuracy == null || accuracy < 0 || accuracy > SeekEngineTuning.ARRIVAL_ACCURACY_METERS) {
            ensurePulseScheduled()
            return
        }
        consecutiveInsideCount = if (distance <= radius) consecutiveInsideCount + 1 else 0
        if (consecutiveInsideCount >= SeekEngineTuning.ARRIVAL_FIX_COUNT) {
            transitionToArrived()
        } else {
            ensurePulseScheduled()
        }
    }

    private fun transitionToArrived() {
        _phase.value = SeekEnginePhase.ARRIVED
        consecutiveInsideCount = 0
        invalidatePulseTimer()
        val baseWindowMillis = stillnessWindowOverrideMillis
            ?: (
                windowRng.nextDouble(
                    SeekEngineTuning.STILLNESS_WINDOW_RANGE_SECONDS.start,
                    SeekEngineTuning.STILLNESS_WINDOW_RANGE_SECONDS.endInclusive,
                ) * 1000
                ).roundToLong()
        val detector = SeekStillnessDetector(baseWindowMillis)
        detector.start()
        stillnessDetector = detector
        graceDeadlineMillis = clock.now() + SeekEngineTuning.GRACE_MILLIS
        startStillnessCheckTimer()
        _events.tryEmit(SeekEngineEvent.Arrived(clearingIndex = _activeIndex.value))
    }

    // Stillness and reveal

    internal fun evaluateStillness(atMillis: Long) {
        if (_phase.value != SeekEnginePhase.ARRIVED || isSuspended) return
        val detector = stillnessDetector ?: return
        when (detector.evaluate(atMillis)) {
            SeekStillnessDetector.Update.BEGAN ->
                _events.tryEmit(SeekEngineEvent.StillnessBegan(clearingIndex = _activeIndex.value))
            SeekStillnessDetector.Update.COMPLETED -> {
                reveal()
                return
            }
            SeekStillnessDetector.Update.NONE -> Unit
        }
        val deadline = graceDeadlineMillis
        if (deadline != null && atMillis >= deadline) {
            reveal()
        }
    }

    private fun reveal() {
        stopStillnessMachinery()
        val nextIndex = _activeIndex.value + 1
        if (nextIndex >= _chain.value.clearings.size) {
            _phase.value = SeekEnginePhase.COMPLETE
            stop()
            _events.tryEmit(SeekEngineEvent.SeekComplete)
            return
        }
        _activeIndex.value = nextIndex
        _phase.value = SeekEnginePhase.GUIDING
        _distanceToActiveMeters.value = null
        rerollPulseDistance = null
        consecutiveInsideCount = 0
        _events.tryEmit(SeekEngineEvent.RevealedNext(activeIndex = nextIndex))
    }

    private fun stopStillnessMachinery() {
        stillnessDetector?.stop()
        stillnessDetector = null
        stillnessCheckJob?.cancel()
        stillnessCheckJob = null
        graceDeadlineMillis = null
        suspendedGraceRemainingMillis = null
    }

    private fun startStillnessCheckTimer() {
        stillnessCheckJob?.cancel()
        stillnessCheckJob = scope.launch {
            while (isActive) {
                delay(SeekEngineTuning.STILLNESS_CHECK_INTERVAL_MILLIS)
                try {
                    evaluateStillness(clock.now())
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Throwable) {
                    inputFaultCount += 1
                }
            }
        }
    }

    // Suspension. Meditation deliberately falls in the no-op bucket:
    // iOS keeps the builder status at `.recording` through meditation,
    // so the engine keeps pulsing and voting at normal cadence there —
    // WalkState.Meditating must neither suspend nor resume (spec D4).

    internal fun handleWalkState(state: WalkState) {
        when (state) {
            is WalkState.Paused -> suspendEngine()
            is WalkState.Active -> resumeFromSuspension()
            else -> Unit
        }
    }

    private fun suspendEngine() {
        if (isSuspended) return
        isSuspended = true
        invalidatePulseTimer()
        stillnessCheckJob?.cancel()
        stillnessCheckJob = null
        graceDeadlineMillis?.let { deadline ->
            suspendedGraceRemainingMillis = max(0L, deadline - clock.now())
            graceDeadlineMillis = null
        }
        stillnessDetector?.suspend()
    }

    private fun resumeFromSuspension() {
        if (!isSuspended) return
        isSuspended = false
        suspendedGraceRemainingMillis?.let { remaining ->
            graceDeadlineMillis = clock.now() + remaining
            suspendedGraceRemainingMillis = null
        }
        stillnessDetector?.resume()
        when (_phase.value) {
            SeekEnginePhase.ARRIVED -> startStillnessCheckTimer()
            SeekEnginePhase.GUIDING -> ensurePulseScheduled()
            else -> Unit
        }
    }

    internal fun handleTier(tier: SeekPowerTier) {
        currentTier = tier
        if (pulseJob?.isActive == true) {
            schedulePulse()
        }
    }

    // Alignment

    private val activeClearing: SeekClearing?
        get() = _chain.value.clearings.getOrNull(_activeIndex.value)

    private fun isAligned(): Boolean {
        val coordinate = lastCoordinate ?: return false
        val active = activeClearing ?: return false
        val heading = smoothedHeading(courseSamples.map { it.courseDegrees }) ?: return false
        val bearing = SeekChainGenerator.bearingDegrees(from = coordinate, to = active.center)
        return abs(angleDelta(heading, bearing)) <= SeekEngineTuning.ALIGNMENT_CONE_DEGREES
    }

    private fun recordCourse(point: LocationPoint) {
        val course = point.bearingDegrees
        if (course != null) {
            courseSamples += CourseSample(
                timestampMillis = point.timestamp,
                courseDegrees = course.toDouble(),
            )
        }
        val newest = courseSamples.lastOrNull()?.timestampMillis ?: return
        val cutoff = newest - SeekEngineTuning.HEADING_WINDOW_MILLIS
        courseSamples.removeAll { it.timestampMillis < cutoff }
    }

    companion object {

        /**
         * 0 far → 1 near, on the same clamp the cadence uses — ping volume
         * and haptic intensity share this curve so ear and skin agree.
         */
        fun closeness(distanceMeters: Double): Double {
            val near = SeekEngineTuning.NEAR_DISTANCE_METERS
            val far = SeekEngineTuning.FAR_DISTANCE_METERS
            val clamped = distanceMeters.coerceIn(near, far)
            return 1 - (clamped - near) / (far - near)
        }

        fun pulseIntervalMillis(distanceMeters: Double, tier: SeekPowerTier): Long {
            val near = SeekEngineTuning.NEAR_DISTANCE_METERS
            val far = SeekEngineTuning.FAR_DISTANCE_METERS
            val clamped = distanceMeters.coerceIn(near, far)
            val fraction = (clamped - near) / (far - near)
            val intervalMillis = (
                SeekEngineTuning.NEAR_PULSE_INTERVAL_MILLIS +
                    fraction * (
                        SeekEngineTuning.FAR_PULSE_INTERVAL_MILLIS -
                            SeekEngineTuning.NEAR_PULSE_INTERVAL_MILLIS
                        )
                ).roundToLong()
            return when (tier) {
                SeekPowerTier.LOW ->
                    max(intervalMillis, SeekEngineTuning.LOW_POWER_PULSE_FLOOR_MILLIS)
                SeekPowerTier.NORMAL -> intervalMillis
            }
        }

        /**
         * Circular mean over the smoothing window — a single corner flap
         * cannot flip alignment the way per-fix comparison would.
         */
        fun smoothedHeading(courses: List<Double>): Double? {
            if (courses.isEmpty()) return null
            var x = 0.0
            var y = 0.0
            for (course in courses) {
                val radians = Math.toRadians(course)
                x += cos(radians)
                y += sin(radians)
            }
            if (x == 0.0 && y == 0.0) return null
            return Math.toDegrees(atan2(y, x))
        }

        fun angleDelta(a: Double, b: Double): Double = ((a - b + 540.0) % 360.0) - 180.0
    }
}
