// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkFormatTest {

    @Test
    fun `duration formats short walks as M_SS`() {
        assertEquals("0:00", WalkFormat.duration(0))
        assertEquals("0:30", WalkFormat.duration(30_000))
        assertEquals("5:00", WalkFormat.duration(5 * 60 * 1_000L))
        assertEquals("59:59", WalkFormat.duration(59 * 60 * 1_000L + 59_000))
    }

    @Test
    fun `duration formats long walks as H_MM_SS`() {
        assertEquals("1:00:00", WalkFormat.duration(60 * 60 * 1_000L))
        assertEquals("1:23:45", WalkFormat.duration((1 * 3600 + 23 * 60 + 45) * 1_000L))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0:00", WalkFormat.duration(-1_000L))
    }

    @Test
    fun `distance below 100m uses meters`() {
        assertEquals("0 m", WalkFormat.distance(0.0))
        assertEquals("25 m", WalkFormat.distance(25.3))
        assertEquals("99 m", WalkFormat.distance(99.4))
    }

    @Test
    fun `distance at or above 100m uses kilometers`() {
        assertEquals("0.10 km", WalkFormat.distance(100.0))
        assertEquals("1.23 km", WalkFormat.distance(1_234.0))
        assertEquals("12.34 km", WalkFormat.distance(12_340.0))
    }

    @Test
    fun `pace formats seconds-per-km as M_SS per km`() {
        assertEquals("10:00 /km", WalkFormat.pace(600.0))
        assertEquals("6:30 /km", WalkFormat.pace(6 * 60.0 + 30.0))
    }

    @Test
    fun `pace returns em-dash for null or undefined`() {
        assertEquals("—", WalkFormat.pace(null))
    }

    // --- Stage 4-B: typed distance label (goshuin seal center text) ---

    @Test
    fun `distanceLabel below 100m returns meter value`() {
        assertEquals(DistanceLabel("0", "m"), WalkFormat.distanceLabel(0.0))
        assertEquals(DistanceLabel("25", "m"), WalkFormat.distanceLabel(25.3))
        assertEquals(DistanceLabel("99", "m"), WalkFormat.distanceLabel(99.4))
    }

    @Test
    fun `distanceLabel at 100m boundary crosses to km`() {
        assertEquals(DistanceLabel("0.10", "km"), WalkFormat.distanceLabel(100.0))
    }

    @Test
    fun `distanceLabel at or above 100m uses two-decimal km`() {
        assertEquals(DistanceLabel("1.23", "km"), WalkFormat.distanceLabel(1_234.0))
        assertEquals(DistanceLabel("12.34", "km"), WalkFormat.distanceLabel(12_340.0))
    }

    @Test
    fun `distanceLabel 99_9 rounds up to 100m meter unit`() {
        // Rounding edge: 99.9 rounds to 100, but 99.9 < 100.0 so the
        // branch stays in meters. Result: "100 m" (unit stays m, value
        // is the round).
        assertEquals(DistanceLabel("100", "m"), WalkFormat.distanceLabel(99.9))
    }
}
