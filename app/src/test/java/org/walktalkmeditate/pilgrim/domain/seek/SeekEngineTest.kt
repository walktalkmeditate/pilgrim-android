// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Parity: iOS `SeekEngineTests.swift@c1745e8` plus the plan's Android-only
 * scenarios (exact interval thresholds, meditation non-suspension, banked
 * grace, two-clearing contract). Port spec:
 * docs/parity/2026-07-14-port-seek-engine-u3.md.
 *
 * Time seams: the pulse clock runs on the TestScope's virtual scheduler
 * (`advanceTimeBy`/`runCurrent`); stillness and grace read the injected
 * [Clock], driven here by [nowMillis]. Tests that exercise both advance
 * both explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeekEngineTest {

    private val home = SeekPoint(latitude = 42.8782, longitude = -8.5448)

    private var nowMillis = 10_000_000L
    private val clock = Clock { nowMillis }
    private val events = mutableListOf<SeekEngineEvent>()
    private val locations = MutableSharedFlow<LocationPoint>(extraBufferCapacity = 16)
    private val walkStates = MutableSharedFlow<WalkState>(extraBufferCapacity = 16)
    private val tiers = MutableSharedFlow<SeekPowerTier>(extraBufferCapacity = 16)

    // Helpers

    private fun makeChain(count: Int, spacingMeters: Double = 1000.0): SeekChain {
        val clearings = (1..count).map { index ->
            SeekClearing(
                center = SeekChainGenerator.destination(
                    from = home,
                    bearingDegrees = 0.0,
                    distanceMeters = spacingMeters * index,
                ),
                radiusMeters = 50.0,
            )
        }
        return SeekChain(clearings = clearings, budgetMeters = 5000.0)
    }

    private fun TestScope.makeEngine(
        clearingCount: Int = 1,
        windowMillis: Long? = 60_000L,
    ): SeekEngine {
        val engine = SeekEngine(
            chain = makeChain(clearingCount),
            scope = backgroundScope,
            clock = clock,
            locations = locations,
            walkStates = walkStates,
            powerTiers = tiers,
            stillnessWindowOverrideMillis = windowMillis,
        )
        backgroundScope.launch { engine.events.collect { events += it } }
        runCurrent()
        return engine
    }

    private fun fix(
        at: SeekPoint,
        accuracy: Float? = 10f,
        course: Float? = null,
    ): LocationPoint = LocationPoint(
        timestamp = nowMillis,
        latitude = at.latitude,
        longitude = at.longitude,
        horizontalAccuracyMeters = accuracy,
        bearingDegrees = course,
    )

    private fun point(metersNorthOfHome: Double): SeekPoint =
        SeekChainGenerator.destination(
            from = home,
            bearingDegrees = 0.0,
            distanceMeters = metersNorthOfHome,
        )

    /** Returns the clock time at which the arrival transition fired. */
    private fun arriveAt(engine: SeekEngine, center: SeekPoint): Long {
        var arrivalTime = 0L
        repeat(SeekEngineTuning.ARRIVAL_FIX_COUNT) {
            arrivalTime = nowMillis
            engine.processLocation(fix(at = center))
            nowMillis += 1_000
        }
        return arrivalTime
    }

    private fun pulses(): List<SeekEngineEvent.Pulse> =
        events.filterIsInstance<SeekEngineEvent.Pulse>()

    private fun stillnessBeganCount(): Int =
        events.count { it is SeekEngineEvent.StillnessBegan }

    private fun revealedNextCount(index: Int): Int =
        events.count { it == SeekEngineEvent.RevealedNext(activeIndex = index) }

    private fun accumulator() = WalkAccumulator(walkId = 1L, startedAt = 0L)

    private fun paused() = WalkState.Paused(accumulator(), pausedAt = nowMillis)

    private fun active() = WalkState.Active(accumulator())

    private fun meditating() = WalkState.Meditating(accumulator(), meditationStartedAt = nowMillis)

    // Closeness + cadence curves

    @Test
    fun `closeness shares the cadence curve`() {
        assertEquals(0.0, SeekEngine.closeness(2000.0), 0.001)
        assertEquals(0.0, SeekEngine.closeness(5000.0), 0.001)
        assertEquals(1.0, SeekEngine.closeness(100.0), 0.001)
        assertEquals(1.0, SeekEngine.closeness(40.0), 0.001)
        assertEquals(0.5, SeekEngine.closeness(1050.0), 0.001)
    }

    @Test
    fun `pulse interval maps distance linearly and clamps at ends`() {
        assertEquals(60_000L, SeekEngine.pulseIntervalMillis(3000.0, SeekPowerTier.NORMAL))
        assertEquals(60_000L, SeekEngine.pulseIntervalMillis(2000.0, SeekPowerTier.NORMAL))
        assertEquals(35_000L, SeekEngine.pulseIntervalMillis(1050.0, SeekPowerTier.NORMAL))
        assertEquals(10_000L, SeekEngine.pulseIntervalMillis(100.0, SeekPowerTier.NORMAL))
        assertEquals(10_000L, SeekEngine.pulseIntervalMillis(40.0, SeekPowerTier.NORMAL))

        var previous = Long.MAX_VALUE
        var distance = 2200.0
        while (distance >= 80.0) {
            val interval = SeekEngine.pulseIntervalMillis(distance, SeekPowerTier.NORMAL)
            assertTrue("cadence must shorten monotonically", interval <= previous)
            previous = interval
            distance -= 40.0
        }
    }

    @Test
    fun `pulse interval low tier raises floor`() {
        assertEquals(30_000L, SeekEngine.pulseIntervalMillis(100.0, SeekPowerTier.LOW))
        assertEquals(60_000L, SeekEngine.pulseIntervalMillis(2000.0, SeekPowerTier.LOW))
        assertEquals(10_000L, SeekEngine.pulseIntervalMillis(100.0, SeekPowerTier.NORMAL))
    }

    // Collector resilience

    @Test
    fun `a throwing locations flow dies quietly and sibling collectors keep working`() = runTest {
        // A broken feed must never escape into the session scope (it
        // would crash the process); the engine degrades — no more fixes
        // — while the walk-state and tier collectors stay alive.
        val engine = SeekEngine(
            chain = makeChain(1),
            scope = backgroundScope,
            clock = clock,
            locations = flow {
                emit(fix(at = home))
                throw IllegalStateException("gps feed exploded")
            },
            walkStates = walkStates,
            powerTiers = tiers,
            stillnessWindowOverrideMillis = 60_000L,
        )
        backgroundScope.launch { engine.events.collect { events += it } }
        runCurrent()
        engine.start()
        runCurrent()

        assertEquals("the fix before the failure landed", 1000.0, engine.distanceToActiveMeters.value!!, 1.0)
        assertEquals("the dead feed is counted, not crashed on", 1, engine.inputFaultCount)

        tiers.emit(SeekPowerTier.LOW)
        runCurrent()
        assertEquals("the tier collector survives the dead feed", SeekPowerTier.LOW, engine.currentTier)

        walkStates.emit(paused())
        runCurrent()
        walkStates.emit(active())
        runCurrent()
        assertEquals("the walk-state collector survives too", SeekEnginePhase.GUIDING, engine.phase.value)
    }

    // AE2: misalignment is positive-only

    @Test
    fun `walking directly away pulses unaligned and nothing else`() = runTest {
        val engine = makeEngine()
        var position = home
        repeat(6) {
            position = SeekChainGenerator.destination(
                from = position,
                bearingDegrees = 180.0,
                distanceMeters = 30.0,
            )
            engine.processLocation(fix(at = position, course = 180f))
            nowMillis += 2_000
            engine.emitPulse()
        }
        runCurrent()
        assertEquals("walking away produces pulses and nothing else", 6, events.size)
        assertEquals(6, pulses().size)
        assertTrue(pulses().none { it.aligned })
    }

    // Alignment smoothing + cone

    @Test
    fun `course flapping within smoothing window does not flip alignment`() = runTest {
        val engine = makeEngine()
        for (course in listOf(355f, 5f, 0f, 90f, 2f, 358f)) {
            engine.processLocation(fix(at = home, course = course))
            nowMillis += 2_000
            engine.emitPulse()
        }
        runCurrent()
        assertEquals(6, pulses().size)
        assertTrue(
            "one corner flap inside the smoothing window must not flip alignment",
            pulses().all { it.aligned },
        )
    }

    @Test
    fun `stale course samples age past smoothing window`() = runTest {
        val engine = makeEngine()
        repeat(3) {
            engine.processLocation(fix(at = home, course = 0f))
            nowMillis += 2_000
        }
        nowMillis += SeekEngineTuning.HEADING_WINDOW_MILLIS + 5_000
        engine.processLocation(fix(at = home, course = 180f))
        engine.emitPulse()
        runCurrent()
        assertEquals(
            "only the fresh reversed course remains",
            false,
            pulses().last().aligned,
        )
    }

    @Test
    fun `alignment flips as the smoothed delta crosses the sixty degree cone`() = runTest {
        val engine = makeEngine()
        engine.processLocation(fix(at = home, course = 59.9f))
        engine.emitPulse()
        nowMillis += SeekEngineTuning.HEADING_WINDOW_MILLIS + 5_000
        engine.processLocation(fix(at = home, course = 60.1f))
        engine.emitPulse()
        runCurrent()
        assertEquals(listOf(true, false), pulses().map { it.aligned })
    }

    @Test
    fun `course-less fixes are excluded from the alignment window`() = runTest {
        val engine = makeEngine()
        engine.processLocation(fix(at = home, course = null))
        engine.emitPulse()
        nowMillis += 2_000
        engine.processLocation(fix(at = home, course = 0f))
        engine.emitPulse()
        runCurrent()
        assertEquals(
            "no course samples yet, so alignment must read false",
            listOf(false, true),
            pulses().map { it.aligned },
        )
    }

    // Arrival debounce

    @Test
    fun `single stray fix inside does not arrive`() = runTest {
        val engine = makeEngine()
        val center = point(1000.0)
        engine.processLocation(fix(at = center))
        nowMillis += 1_000
        engine.processLocation(fix(at = point(800.0)))
        nowMillis += 1_000
        engine.processLocation(fix(at = center))
        nowMillis += 1_000
        engine.processLocation(fix(at = center))
        runCurrent()
        assertEquals(SeekEnginePhase.GUIDING, engine.phase.value)
        assertEquals(0, events.count { it is SeekEngineEvent.Arrived })
    }

    @Test
    fun `three consecutive gated fixes arrive and pause pulse clock`() = runTest {
        val engine = makeEngine()
        arriveAt(engine, point(1000.0))
        runCurrent()
        assertEquals(SeekEnginePhase.ARRIVED, engine.phase.value)
        assertEquals(1, events.count { it == SeekEngineEvent.Arrived(clearingIndex = 0) })

        engine.emitPulse()
        runCurrent()
        assertTrue("no pulses while arrived", pulses().isEmpty())
    }

    @Test
    fun `low accuracy fixes neither advance nor reset debounce`() = runTest {
        val engine = makeEngine()
        val center = point(1000.0)
        engine.processLocation(fix(at = center, accuracy = 10f))
        nowMillis += 1_000
        engine.processLocation(fix(at = point(700.0), accuracy = 80f))
        nowMillis += 1_000
        engine.processLocation(fix(at = center, accuracy = 51f))
        nowMillis += 1_000
        engine.processLocation(fix(at = center, accuracy = null))
        nowMillis += 1_000
        engine.processLocation(fix(at = center, accuracy = 10f))
        nowMillis += 1_000
        runCurrent()
        assertEquals("only two good fixes so far", SeekEnginePhase.GUIDING, engine.phase.value)
        engine.processLocation(fix(at = center, accuracy = 50f))
        runCurrent()
        assertEquals("50 m sits exactly on the gate", SeekEnginePhase.ARRIVED, engine.phase.value)
    }

    // Pulse heartbeat

    @Test
    fun `first fix arms the pulse heartbeat`() = runTest {
        val engine = makeEngine()
        engine.processLocation(fix(at = point(500.0)))
        val interval = SeekEngine.pulseIntervalMillis(
            engine.distanceToActiveMeters.value!!,
            SeekPowerTier.NORMAL,
        )
        advanceTimeBy(interval)
        runCurrent()
        assertEquals(1, pulses().size)
    }

    @Test
    fun `pulse fires at the exact scheduled interval`() = runTest {
        val engine = makeEngine()
        engine.processLocation(fix(at = point(-50.0)))
        assertEquals(1050.0, engine.distanceToActiveMeters.value!!, 0.001)

        advanceTimeBy(34_999)
        runCurrent()
        assertTrue("nothing before the 35 s mark", pulses().isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pulses().size)

        advanceTimeBy(35_000)
        runCurrent()
        assertEquals("the heartbeat reschedules itself", 2, pulses().size)
    }

    @Test
    fun `stop silences pulse timer`() = runTest {
        val engine = makeEngine()
        engine.processLocation(fix(at = point(500.0)))
        engine.stop()
        advanceTimeBy(600_000)
        runCurrent()
        assertTrue("no pulse after stop", events.isEmpty())
    }

    @Test
    fun `stop mid stillness window emits nothing afterwards`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        arriveAt(engine, point(1000.0))
        runCurrent()
        val eventsBeforeStop = events.size
        engine.stop()

        nowMillis += 600_000
        advanceTimeBy(600_000)
        runCurrent()
        engine.evaluateStillness(nowMillis)
        runCurrent()
        assertEquals("stopped engine must stay silent", eventsBeforeStop, events.size)
    }

    // Stillness reveal

    @Test
    fun `stillness begins then reveals next clearing`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        arriveAt(engine, point(1000.0))

        engine.processLocation(fix(at = point(1000.0)))
        nowMillis += 1_000
        val beganAt = nowMillis
        engine.processLocation(fix(at = point(1003.0)))
        runCurrent()
        assertEquals(1, stillnessBeganCount())

        engine.evaluateStillness(beganAt + 89_999)
        runCurrent()
        assertEquals("60 s base window is lengthened to 90 s", SeekEnginePhase.ARRIVED, engine.phase.value)
        engine.evaluateStillness(beganAt + 90_000)
        runCurrent()
        assertEquals(1, revealedNextCount(1))
        assertEquals(SeekEnginePhase.GUIDING, engine.phase.value)
        assertEquals(1, engine.activeIndex.value)
    }

    @Test
    fun `stillness evaluation ticks ride the five second timer`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        arriveAt(engine, point(1000.0))
        engine.processLocation(fix(at = point(1000.0)))
        nowMillis += 1_000
        engine.processLocation(fix(at = point(1002.0)))
        runCurrent()
        assertEquals(1, stillnessBeganCount())

        // No further fixes: the repeating 5 s tick alone must carry the
        // window to completion once the clock passes it.
        repeat(19) {
            nowMillis += 5_000
            advanceTimeBy(5_000)
            runCurrent()
        }
        assertEquals(1, revealedNextCount(1))
        assertEquals(SeekEnginePhase.GUIDING, engine.phase.value)
    }

    // AE4: grace fallback

    @Test
    fun `grace reveals quietly without stillness`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        val arrivalTime = arriveAt(engine, point(1000.0))

        engine.evaluateStillness(arrivalTime + SeekEngineTuning.GRACE_MILLIS - 1)
        runCurrent()
        assertEquals("one tick shy of grace", 0, revealedNextCount(1))
        engine.evaluateStillness(arrivalTime + SeekEngineTuning.GRACE_MILLIS)
        runCurrent()
        assertEquals(1, revealedNextCount(1))
        assertEquals("grace reveal is quiet — no stillness ever began", 0, stillnessBeganCount())
        assertEquals(SeekEnginePhase.GUIDING, engine.phase.value)
        assertEquals(1, engine.activeIndex.value)
    }

    // Pause suspension

    @Test
    fun `pause during stillness freezes and resume keeps active clearing`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        engine.start()
        runCurrent()

        arriveAt(engine, point(1000.0))
        engine.processLocation(fix(at = point(1000.0)))
        nowMillis += 1_000
        engine.processLocation(fix(at = point(1000.0)))
        runCurrent()
        assertEquals(1, stillnessBeganCount())

        walkStates.emit(paused())
        runCurrent()
        nowMillis += 600_000
        engine.evaluateStillness(nowMillis)
        runCurrent()
        assertEquals("suspension freezes the ritual", SeekEnginePhase.ARRIVED, engine.phase.value)
        assertEquals(0, revealedNextCount(1))

        walkStates.emit(active())
        runCurrent()
        assertEquals("resume keeps the same active clearing", 0, engine.activeIndex.value)
        assertEquals(SeekEnginePhase.ARRIVED, engine.phase.value)

        engine.processLocation(fix(at = point(1000.0)))
        nowMillis += 1_000
        val beganAt = nowMillis
        engine.processLocation(fix(at = point(1002.0)))
        runCurrent()
        assertEquals("stillness window restarted after resume", 2, stillnessBeganCount())
        engine.evaluateStillness(beganAt + 90_000)
        runCurrent()
        assertEquals(1, revealedNextCount(1))
    }

    @Test
    fun `pause banks grace remainder and re-arms on resume`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        val arrivalTime = arriveAt(engine, point(1000.0))

        nowMillis = arrivalTime + 100_000
        engine.handleWalkState(paused())
        nowMillis = arrivalTime + 600_000
        engine.handleWalkState(active())

        val reArmedDeadline = arrivalTime + 600_000 + 140_000
        engine.evaluateStillness(reArmedDeadline - 1)
        runCurrent()
        assertEquals("banked remainder not yet spent", 0, revealedNextCount(1))
        engine.evaluateStillness(reArmedDeadline)
        runCurrent()
        assertEquals(1, revealedNextCount(1))
    }

    // Meditation must NOT suspend (iOS keeps the builder status recording)

    @Test
    fun `meditation does not suspend the pulse clock but pause does`() = runTest {
        val engine = makeEngine()
        engine.processLocation(fix(at = point(-50.0)))

        engine.handleWalkState(meditating())
        advanceTimeBy(35_000)
        runCurrent()
        assertEquals("meditation pulses at normal cadence", 1, pulses().size)

        engine.handleWalkState(paused())
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("pause silences the heartbeat", 1, pulses().size)
    }

    @Test
    fun `meditation does not suspend stillness voting`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        arriveAt(engine, point(1000.0))
        engine.handleWalkState(meditating())

        engine.processLocation(fix(at = point(1000.0)))
        nowMillis += 1_000
        val beganAt = nowMillis
        engine.processLocation(fix(at = point(1002.0)))
        runCurrent()
        assertEquals("voting continues through meditation", 1, stillnessBeganCount())

        engine.evaluateStillness(beganAt + 90_000)
        runCurrent()
        assertEquals(1, revealedNextCount(1))
    }

    // Completion

    @Test
    fun `final reveal emits seek complete once then engine goes quiet`() = runTest {
        val engine = makeEngine(clearingCount = 1)
        val arrivalTime = arriveAt(engine, point(1000.0))
        engine.evaluateStillness(arrivalTime + SeekEngineTuning.GRACE_MILLIS)
        runCurrent()
        assertEquals(1, events.count { it == SeekEngineEvent.SeekComplete })
        assertEquals(SeekEnginePhase.COMPLETE, engine.phase.value)

        val countAfterComplete = events.size
        engine.processLocation(fix(at = home))
        engine.emitPulse()
        engine.evaluateStillness(nowMillis + 600_000)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals("complete engine must stay silent", countAfterComplete, events.size)
    }

    @Test
    fun `empty chain starts complete`() = runTest {
        val engine = makeEngine(clearingCount = 0)
        assertEquals(SeekEnginePhase.COMPLETE, engine.phase.value)
    }

    // Seek anew (reroll)

    @Test
    fun `seek anew swaps remainder keeps prefix and active index and stale pulses no op`() = runTest {
        val engine = makeEngine(clearingCount = 3)
        val arrivalTime = arriveAt(engine, point(1000.0))
        engine.evaluateStillness(arrivalTime + SeekEngineTuning.GRACE_MILLIS + 1)
        runCurrent()
        assertEquals(1, engine.activeIndex.value)

        val before = engine.chain.value
        val staleGeneration = engine.pulseGeneration
        engine.seekAnew(currentLocation = point(1100.0))
        runCurrent()

        assertEquals(3, engine.chain.value.clearings.size)
        assertEquals("reached prefix is kept", before.clearings[0], engine.chain.value.clearings[0])
        assertNotEquals("active clearing rerolled", before.clearings[1], engine.chain.value.clearings[1])
        assertEquals(1, engine.activeIndex.value)
        assertEquals(SeekEnginePhase.GUIDING, engine.phase.value)
        assertTrue(engine.pulseGeneration > staleGeneration)

        val eventCount = events.size
        engine.pulseTimerFired(generation = staleGeneration)
        runCurrent()
        assertEquals("stale pulse generation must no-op", eventCount, events.size)
    }

    @Test
    fun `seek anew with prior distance pulses before next fix`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        engine.processLocation(fix(at = home))
        assertNotNull(engine.distanceToActiveMeters.value)

        engine.seekAnew(currentLocation = home)
        runCurrent()
        assertNull(
            "the published distance resets until the next fix",
            engine.distanceToActiveMeters.value,
        )
        assertEquals("the reroll answers with an immediate feedback pulse", 1, pulses().size)

        engine.pulseTimerFired(generation = engine.pulseGeneration)
        runCurrent()
        assertEquals(
            "the heartbeat continues across the reroll on the stale distance",
            2,
            pulses().size,
        )
    }

    @Test
    fun `seek anew emits an immediate feedback pulse carrying the stale distance`() = runTest {
        val engine = makeEngine(clearingCount = 1)
        engine.processLocation(fix(at = point(500.0)))
        val staleDistance = engine.distanceToActiveMeters.value!!
        runCurrent()
        events.clear()

        engine.seekAnew(currentLocation = point(100.0))
        runCurrent()
        assertEquals(1, pulses().size)
        assertEquals(staleDistance, pulses().single().distanceMeters, 0.0001)
    }

    @Test
    fun `seek anew in arrived returns to guiding and stops stillness`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        arriveAt(engine, point(1000.0))
        runCurrent()
        assertEquals(SeekEnginePhase.ARRIVED, engine.phase.value)

        engine.seekAnew(currentLocation = point(1000.0))
        runCurrent()
        assertEquals(SeekEnginePhase.GUIDING, engine.phase.value)
        assertEquals(0, engine.activeIndex.value)

        // The stillness machinery is gone: neither voting nor grace can
        // fire a reveal any more.
        engine.evaluateStillness(nowMillis + 600_000)
        advanceTimeBy(600_000)
        runCurrent()
        assertEquals(0, revealedNextCount(1))
    }

    @Test
    fun `seek anew with seed regenerates deterministically`() = runTest {
        val first = makeEngine(clearingCount = 2)
        val second = makeEngine(clearingCount = 2)
        val third = makeEngine(clearingCount = 2)

        first.seekAnew(currentLocation = home, seed = 7uL)
        second.seekAnew(currentLocation = home, seed = 7uL)
        third.seekAnew(currentLocation = home, seed = 8uL)

        assertEquals("same seed, same reroll", first.chain.value, second.chain.value)
        assertNotEquals("a different seed is sent a different way", first.chain.value, third.chain.value)
    }

    // Power tier

    @Test
    fun `tier flow reaches engine and widens floor`() = runTest {
        val engine = makeEngine()
        engine.start()
        runCurrent()
        assertEquals(SeekPowerTier.NORMAL, engine.currentTier)

        engine.processLocation(fix(at = point(1000.0 - 100.0)))
        tiers.emit(SeekPowerTier.LOW)
        runCurrent()
        assertEquals(SeekPowerTier.LOW, engine.currentTier)

        // The live timer was rescheduled onto the 30 s floor: nothing at
        // the old 10 s cadence, a pulse exactly at the floor.
        advanceTimeBy(29_999)
        runCurrent()
        assertTrue(pulses().isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, pulses().size)
    }

    // Integration: the full two-clearing contract

    @Test
    fun `two clearing walk emits the full event contract`() = runTest {
        val engine = makeEngine(clearingCount = 2)
        engine.start()
        runCurrent()

        suspend fun send(point: LocationPoint) {
            locations.emit(point)
            runCurrent()
        }

        // Guiding → clearing 0.
        repeat(3) {
            send(fix(at = point(1000.0)))
            nowMillis += 1_000
        }
        // Stillness at clearing 0.
        send(fix(at = point(1000.0)))
        nowMillis += 1_000
        send(fix(at = point(1002.0)))
        nowMillis += 90_000
        send(fix(at = point(1001.0)))

        // Guiding → clearing 1.
        repeat(3) {
            send(fix(at = point(2000.0)))
            nowMillis += 1_000
        }
        // Stillness at clearing 1.
        send(fix(at = point(2000.0)))
        nowMillis += 1_000
        send(fix(at = point(2002.0)))
        nowMillis += 90_000
        send(fix(at = point(2001.0)))
        runCurrent()

        assertEquals(
            listOf<SeekEngineEvent>(
                SeekEngineEvent.Arrived(clearingIndex = 0),
                SeekEngineEvent.StillnessBegan(clearingIndex = 0),
                SeekEngineEvent.RevealedNext(activeIndex = 1),
                SeekEngineEvent.Arrived(clearingIndex = 1),
                SeekEngineEvent.StillnessBegan(clearingIndex = 1),
                SeekEngineEvent.SeekComplete,
            ),
            events,
        )
        assertEquals(SeekEnginePhase.COMPLETE, engine.phase.value)

        // The completed engine dropped its subscriptions: further input
        // changes nothing.
        send(fix(at = home))
        assertEquals(6, events.size)
    }
}
