// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WalkStatsTest {

    @Test
    fun `totalElapsedMillis is zero for idle`() {
        assertEquals(0L, WalkStats.totalElapsedMillis(WalkState.Idle, now = 10_000L))
    }

    @Test
    fun `totalElapsedMillis for active is wall clock difference`() {
        val state = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 1_000L))
        assertEquals(4_000L, WalkStats.totalElapsedMillis(state, now = 5_000L))
    }

    @Test
    fun `totalElapsedMillis for finished uses endedAt not now`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 1_000L),
            endedAt = 4_000L,
        )
        assertEquals(3_000L, WalkStats.totalElapsedMillis(state, now = 9_999L))
    }

    @Test
    fun `activeWalkingMillis subtracts completed pauses`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, totalPausedMillis = 2_000L),
        )
        assertEquals(8_000L, WalkStats.activeWalkingMillis(state, now = 10_000L))
    }

    @Test
    fun `activeWalkingMillis includes ongoing pause deduction`() {
        val state = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalPausedMillis = 1_000L),
            pausedAt = 5_000L,
        )
        // Wall elapsed = now(8_000) - startedAt(0) = 8_000
        // Completed pauses = 1_000
        // Ongoing pause = now(8_000) - pausedAt(5_000) = 3_000
        // Active = 8_000 - 1_000 - 3_000 = 4_000
        assertEquals(4_000L, WalkStats.activeWalkingMillis(state, now = 8_000L))
    }

    @Test
    fun `activeWalkingMillis includes ongoing meditation deduction`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 0L),
            meditationStartedAt = 4_000L,
        )
        // Wall elapsed = 10_000, ongoing meditation = 6_000, active = 4_000
        assertEquals(4_000L, WalkStats.activeWalkingMillis(state, now = 10_000L))
    }

    @Test
    fun `distanceMeters reads from accumulator`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 523.4),
        )
        assertEquals(523.4, WalkStats.distanceMeters(state), 0.001)
    }

    @Test
    fun `averagePace is null when distance too small`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 5.0),
        )
        assertNull(WalkStats.averagePaceSecondsPerKm(state, now = 10_000L))
    }

    @Test
    fun `averagePace computes seconds per kilometer from active time`() {
        // 1 km in 600 seconds of active walking = 600 sec/km (10 min/km).
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, distanceMeters = 1_000.0),
        )
        val pace = WalkStats.averagePaceSecondsPerKm(state, now = 600_000L)
        assertNotNull(pace)
        assertEquals(600.0, pace!!, 0.5)
    }

    @Test
    fun `averagePace accounts for pauses by using active time`() {
        // 1 km in 900s wall clock, 300s of that paused -> 600s active, 600 sec/km.
        val state = WalkState.Active(
            WalkAccumulator(
                walkId = 1L,
                startedAt = 0L,
                distanceMeters = 1_000.0,
                totalPausedMillis = 300_000L,
            ),
        )
        val pace = WalkStats.averagePaceSecondsPerKm(state, now = 900_000L)
        assertEquals(600.0, pace!!, 0.5)
    }
}
