// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkStatsTotalMeditatedMillisTest {

    @Test
    fun `idle returns zero`() {
        assertEquals(0L, WalkStats.totalMeditatedMillis(WalkState.Idle, now = 10_000L))
    }

    @Test
    fun `active returns accumulator value verbatim`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 60_000L),
        )
        assertEquals(60_000L, WalkStats.totalMeditatedMillis(state, now = 999_999L))
    }

    @Test
    fun `paused returns accumulator value verbatim`() {
        val state = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 120_000L),
            pausedAt = 200_000L,
        )
        assertEquals(120_000L, WalkStats.totalMeditatedMillis(state, now = 300_000L))
    }

    @Test
    fun `meditating adds running slice on top of accumulator`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 90_000L),
            meditationStartedAt = 100_000L,
        )
        // running slice = now(130_000) - startedAt(100_000) = 30_000
        // total = 90_000 + 30_000 = 120_000
        assertEquals(120_000L, WalkStats.totalMeditatedMillis(state, now = 130_000L))
    }

    @Test
    fun `meditating with clock skew clamps running slice to zero`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 90_000L),
            meditationStartedAt = 200_000L,
        )
        // now < startedAt → coerceAtLeast(0) → running slice = 0
        // total = 90_000 + 0 = 90_000 (NOT a negative)
        assertEquals(90_000L, WalkStats.totalMeditatedMillis(state, now = 100_000L))
    }

    @Test
    fun `finished returns accumulator value verbatim`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 200_000L),
            endedAt = 500_000L,
        )
        assertEquals(200_000L, WalkStats.totalMeditatedMillis(state, now = 999_999L))
    }
}
