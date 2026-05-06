// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class MilestoneCalcTest {

    private fun snap(id: Long, distM: Double, cumM: Double) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = 1_700_000_000_000L + id * 86_400_000L,
        distanceM = distM, durationSec = 600.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = cumM, talkDurationSec = 0L, meditateDurationSec = 0L,
        favicon = null, isShared = false, weatherCondition = null,
    )

    @Test
    fun thresholds_first_five_and_size_and_last() {
        val ts = milestoneThresholds()
        assertEquals(listOf(100_000.0, 500_000.0, 1_000_000.0, 2_000_000.0, 3_000_000.0),
            ts.subList(0, 5))
        assertEquals(102, ts.size)
        assertEquals(100_000_000.0, ts.last(), 0.0)
    }

    @Test
    fun returns_empty_when_fewer_than_two_snapshots() {
        assertEquals(emptyList<MilestonePosition>(),
            computeMilestonePositions(emptyList(), emptyList()))
        assertEquals(emptyList<MilestonePosition>(),
            computeMilestonePositions(
                listOf(snap(1, 50_000.0, 50_000.0)),
                listOf(DotPosition(180f, 100f)),
            ))
    }

    /**
     * 4 walks of 30 km each, cumulative 30/60/90/120 km. Exactly ONE
     * marker (100 km), placed on the 4th-oldest walk = newest by display
     * order = index 0 in newest-first ordering.
     */
    @Test
    fun four_30km_walks_yield_single_100km_marker_on_4th_oldest() {
        val newest = snap(4, 30_000.0, 120_000.0)
        val third = snap(3, 30_000.0, 90_000.0)
        val second = snap(2, 30_000.0, 60_000.0)
        val oldest = snap(1, 30_000.0, 30_000.0)
        val snapshots = listOf(newest, third, second, oldest)
        val positions = listOf(
            DotPosition(180f, 50f),
            DotPosition(180f, 150f),
            DotPosition(180f, 250f),
            DotPosition(180f, 350f),
        )
        val markers = computeMilestonePositions(snapshots, positions)
        assertEquals(1, markers.size)
        assertEquals(100_000.0, markers[0].distanceM, 0.0)
        assertEquals(50f, markers[0].yPx, 0.001f)
    }

    @Test
    fun cumulative_50_120_600_1050_yields_three_markers() {
        val s = listOf(
            snap(4, 450_000.0, 1_050_000.0),
            snap(3, 480_000.0, 600_000.0),
            snap(2, 70_000.0, 120_000.0),
            snap(1, 50_000.0, 50_000.0),
        )
        val p = listOf(
            DotPosition(180f, 50f),
            DotPosition(180f, 150f),
            DotPosition(180f, 250f),
            DotPosition(180f, 350f),
        )
        val markers = computeMilestonePositions(s, p)
        assertEquals(3, markers.size)
        assertTrue(markers.any { it.distanceM == 100_000.0 })
        assertTrue(markers.any { it.distanceM == 500_000.0 })
        assertTrue(markers.any { it.distanceM == 1_000_000.0 })
    }
}
