// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.units.UnitSystem

class ShareStatFormatTest {

    @Test
    fun `distance is null when not moved`() {
        assertNull(ShareStatFormat.distance(0.0, UnitSystem.Metric))
        assertNull(ShareStatFormat.distance(-5.0, UnitSystem.Imperial))
    }

    @Test
    fun `distance uses one decimal in both unit systems`() {
        assertEquals("1.7 km", ShareStatFormat.distance(1_700.0, UnitSystem.Metric))
        // 2735.3 m / 1609.344 = 1.6997… mi → "1.7 mi"
        assertEquals("1.7 mi", ShareStatFormat.distance(2_735.3, UnitSystem.Imperial))
    }

    @Test
    fun `duration is null for zero`() {
        assertNull(ShareStatFormat.duration(0.0))
    }

    @Test
    fun `duration shows minutes under an hour`() {
        assertEquals("38m", ShareStatFormat.duration(38 * 60.0))
        // Truncates toward zero like Swift Int().
        assertEquals("38m", ShareStatFormat.duration(38 * 60.0 + 59.0))
    }

    @Test
    fun `duration shows hours and minutes over an hour`() {
        assertEquals("1h 5m", ShareStatFormat.duration(3_900.0))
    }

    @Test
    fun `elevation hidden at or below one meter`() {
        assertNull(ShareStatFormat.elevation(1.0, UnitSystem.Metric))
        assertNull(ShareStatFormat.elevation(0.4, UnitSystem.Imperial))
    }

    @Test
    fun `elevation truncates to integer per unit`() {
        assertEquals("12 m", ShareStatFormat.elevation(12.9, UnitSystem.Metric))
        // 4 m * 3.28084 = 13.12 ft → "13 ft"
        assertEquals("13 ft", ShareStatFormat.elevation(4.0, UnitSystem.Imperial))
    }

    @Test
    fun `activity breakdown null when neither meditation nor reflection`() {
        assertNull(ShareStatFormat.activityBreakdown(0.0, 0.0))
    }

    @Test
    fun `activity breakdown joins present parts`() {
        assertEquals("10m meditation", ShareStatFormat.activityBreakdown(600.0, 0.0))
        assertEquals("3m reflection", ShareStatFormat.activityBreakdown(0.0, 200.0))
        assertEquals(
            "10m meditation, 3m reflection",
            ShareStatFormat.activityBreakdown(600.0, 200.0),
        )
    }

    @Test
    fun `steps null when absent or zero`() {
        assertNull(ShareStatFormat.steps(null))
        assertNull(ShareStatFormat.steps(0))
    }

    @Test
    fun `steps grouped with ascii separators`() {
        assertEquals("3,932", ShareStatFormat.steps(3_932))
    }
}
