// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class LunarMarkerCalcTest {

    private fun snap(id: Long, ms: Long) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = ms, distanceM = 1000.0, durationSec = 600.0,
        averagePaceSecPerKm = 360.0, cumulativeDistanceM = 1000.0,
        talkDurationSec = 0L, meditateDurationSec = 0L, favicon = null,
        isShared = false, weatherCondition = null,
    )

    @Test
    fun returns_empty_when_fewer_than_two_snapshots() {
        assertEquals(emptyList<LunarMarker>(),
            computeLunarMarkers(emptyList(), emptyList(), 360f))
        assertEquals(emptyList<LunarMarker>(),
            computeLunarMarkers(
                listOf(snap(1, 1_700_000_000_000L)),
                listOf(DotPosition(180f, 100f)),
                360f,
            ))
    }

    @Test
    fun finds_full_moon_in_january_2024_window() {
        // 2024-01-25 17:54 UTC was a full moon. Bracket with two
        // snapshots a week before and after.
        val before = 1_705_708_800_000L  // 2024-01-20T00:00 UTC
        val after = 1_706_572_800_000L   // 2024-01-30T00:00 UTC
        val snapshots = listOf(snap(2, after), snap(1, before)) // newest-first
        val positions = listOf(
            DotPosition(centerXPx = 180f, yPx = 50f),
            DotPosition(centerXPx = 180f, yPx = 150f),
        )
        val markers = computeLunarMarkers(snapshots, positions, viewportWidthPx = 360f)
        assertTrue("expected at least one event", markers.isNotEmpty())
        assertTrue("expected full marker", markers.any { it.illumination > 0.5 })
    }

    @Test
    fun marker_positioned_opposite_side_of_midpoint() {
        val before = 1_705_708_800_000L
        val after = 1_706_572_800_000L
        val snapshots = listOf(snap(2, after), snap(1, before))
        val positions = listOf(
            DotPosition(centerXPx = 250f, yPx = 50f),
            DotPosition(centerXPx = 250f, yPx = 150f),
        )
        val markers = computeLunarMarkers(snapshots, positions, viewportWidthPx = 360f)
        assertTrue(markers.isNotEmpty())
        markers.forEach { m ->
            assertTrue("xPx must be left of midX=250 when midX > viewport/2",
                m.xPx < 250f)
        }
    }

    @Test
    fun interpolatePosition_handles_identical_startMs() {
        val a = DotPosition(100f, 50f)
        val b = DotPosition(200f, 150f)
        val snapshots = listOf(snap(1, 1_700_000_000_000L), snap(2, 1_700_000_000_000L))
        val res = interpolatePosition(1_700_000_000_000L, snapshots, listOf(a, b))
        assertNotNull("identical startMs must NOT throw — verbatim iOS edge case", res)
        assertEquals(0.5, res!!.third, 1e-9)
    }
}
