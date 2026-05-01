// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {

    @Test fun emptyAltitudes_returnsEmpty() {
        assertTrue(computeElevationSparklinePoints(emptyList(), 100).isEmpty())
    }

    @Test fun underTwoSamples_returnsEmpty() {
        assertTrue(computeElevationSparklinePoints(listOf(100.0), 100).isEmpty())
    }

    @Test fun monotonicAscent_yieldsAscendingPath() {
        // Higher altitudes → lower y (top of frame is highest).
        // y = 1 - (alt - min) / (max - min)
        val points = computeElevationSparklinePoints(
            altitudes = listOf(100.0, 110.0, 120.0, 130.0),
            targetWidthBuckets = 100,
        )
        assertEquals(4, points.size)
        // First sample is min altitude → y = 1 (bottom)
        assertEquals(1f, points.first().yFraction, 0.0001f)
        // Last sample is max altitude → y = 0 (top)
        assertEquals(0f, points.last().yFraction, 0.0001f)
        assertTrue(points.last().yFraction < points.first().yFraction)
    }

    @Test fun mixedAltitudes_normalizesAcrossRange() {
        val points = computeElevationSparklinePoints(
            altitudes = listOf(100.0, 200.0, 100.0, 200.0),
            targetWidthBuckets = 100,
        )
        assertEquals(4, points.size)
        assertEquals(1f, points[0].yFraction, 0.0001f)
        assertEquals(0f, points[1].yFraction, 0.0001f)
        assertEquals(1f, points[2].yFraction, 0.0001f)
        assertEquals(0f, points[3].yFraction, 0.0001f)
    }

    @Test fun stride_caps_buckets_at_target_width() {
        // 200 samples + targetWidthBuckets = 50 → step = 4 → 50 buckets
        val altitudes = (0 until 200).map { it.toDouble() }
        val points = computeElevationSparklinePoints(altitudes, targetWidthBuckets = 50)
        assertTrue(points.size <= 50)
        assertTrue(points.size >= 50 - 1)
    }
}
