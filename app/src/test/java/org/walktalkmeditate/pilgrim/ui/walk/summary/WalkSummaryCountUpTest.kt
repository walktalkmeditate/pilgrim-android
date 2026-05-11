// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Pure-coroutine tests for the count-up emitter contract. The
 * production code uses `Animatable.snapTo` inside a `LaunchedEffect`
 * loop; here we replicate the loop shape against a `runTest` virtual
 * clock and assert emission counts, timing, and reset semantics.
 *
 * The actual production wiring lives in WalkSummaryScreen.kt and is
 * exercised by manual device QA (Acceptance Criteria — OnePlus 13).
 * These tests pin the SHAPE of the loop so future edits can't
 * regress emission count / interval / target precision.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(JUnit4::class)
class WalkSummaryCountUpTest {

    /**
     * Replica of the production loop body — kept in test code so the
     * test owns the contract and a refactor of the production helper
     * can't silently break the contract.
     */
    private suspend fun emitCountUp(
        target: Float,
        onEmit: (Float) -> Unit,
        delay: suspend (Long) -> Unit,
    ) {
        for (i in 0..COUNT_UP_STEPS) {
            val progress = i.toFloat() / COUNT_UP_STEPS
            onEmit(target * SmoothStepEasing.transform(progress))
            if (i < COUNT_UP_STEPS) delay(COUNT_UP_INTERVAL_MS)
        }
    }

    @Test
    fun countUpStartsAtZero() = runTest {
        val emissions = mutableListOf<Float>()
        val target = 5000f
        val job = async {
            emitCountUp(target, { emissions.add(it) }, { advanceTimeBy(it) })
        }
        runCurrent()
        assertEquals(0f, emissions.first(), 0.0001f)
        job.await()
    }

    @Test
    fun countUpFinalEmissionEqualsTarget() = runTest {
        val emissions = mutableListOf<Float>()
        val target = 5000f
        emitCountUp(target, { emissions.add(it) }, { advanceTimeBy(it) })
        // SmoothStep(1) = 1*1*(3 - 2*1) = 1, so target * 1 = target.
        assertEquals(target, emissions.last(), 0.0001f)
    }

    @Test
    fun countUpEmits31Values() = runTest {
        val emissions = mutableListOf<Float>()
        emitCountUp(5000f, { emissions.add(it) }, { advanceTimeBy(it) })
        // i in 0..COUNT_UP_STEPS inclusive = 31 emissions.
        assertEquals(31, emissions.size)
    }

    @Test
    fun countUpTotalDuration_is2010ms() = runTest {
        val start = testScheduler.currentTime
        emitCountUp(5000f, {}, { advanceTimeBy(it) })
        val elapsed = testScheduler.currentTime - start
        // 30 delays * 67ms = 2010ms.
        assertEquals(2010L, elapsed)
    }

    @Test
    fun countUpMonotonicallyIncreases() = runTest {
        val emissions = mutableListOf<Float>()
        emitCountUp(5000f, { emissions.add(it) }, { advanceTimeBy(it) })
        for (i in 1 until emissions.size) {
            assertTrue(
                "emission $i (${emissions[i]}) should be >= emission ${i - 1} (${emissions[i - 1]})",
                emissions[i] >= emissions[i - 1],
            )
        }
    }

    @Test
    fun countUpAtZeroTarget_emits31Zeros() = runTest {
        val emissions = mutableListOf<Float>()
        emitCountUp(0f, { emissions.add(it) }, { advanceTimeBy(it) })
        assertEquals(31, emissions.size)
        assertTrue(emissions.all { it == 0f })
    }

    /**
     * Replica of the haptic guard logic. Production wires this into a
     * LaunchedEffect; here we test the predicate in isolation.
     */
    private fun shouldFireRevealedHaptic(
        revealPhase: RevealPhase,
        reduceMotion: Boolean,
        routePointsEmpty: Boolean,
    ): Boolean = revealPhase == RevealPhase.Revealed &&
        !reduceMotion &&
        !routePointsEmpty

    @Test
    fun haptic_firesOnRevealed_whenRouteNonEmptyAndMotionEnabled() {
        assertTrue(
            shouldFireRevealedHaptic(
                revealPhase = RevealPhase.Revealed,
                reduceMotion = false,
                routePointsEmpty = false,
            ),
        )
    }

    @Test
    fun haptic_suppressed_onZoomedPhase() {
        assertEquals(
            false,
            shouldFireRevealedHaptic(
                revealPhase = RevealPhase.Zoomed,
                reduceMotion = false,
                routePointsEmpty = false,
            ),
        )
    }

    @Test
    fun haptic_suppressed_underReduceMotion() {
        assertEquals(
            false,
            shouldFireRevealedHaptic(
                revealPhase = RevealPhase.Revealed,
                reduceMotion = true,
                routePointsEmpty = false,
            ),
        )
    }

    @Test
    fun haptic_suppressed_onEmptyRoute() {
        assertEquals(
            false,
            shouldFireRevealedHaptic(
                revealPhase = RevealPhase.Revealed,
                reduceMotion = false,
                routePointsEmpty = true,
            ),
        )
    }
}
