// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalWeightTest {

    @Test fun `weight at peak day is 1`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 105, peakDay = 105, spread = 91f)
        assertEquals(1f, w, 0.0001f)
    }

    @Test fun `weight at peak plus spread is 0`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 105 + 91, peakDay = 105, spread = 91f)
        assertEquals(0f, w, 0.0001f)
    }

    @Test fun `weight at peak minus spread is 0`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 105 - 91, peakDay = 105, spread = 91f)
        assertEquals(0f, w, 0.0001f)
    }

    @Test fun `weight is symmetric around peak`() {
        val a = SeasonalColorEngine.seasonalWeight(dayOfYear = 95, peakDay = 105, spread = 91f)
        val b = SeasonalColorEngine.seasonalWeight(dayOfYear = 115, peakDay = 105, spread = 91f)
        assertTrue("a=$a b=$b", abs(a - b) < 0.0001f)
    }

    @Test fun `weight wraps across year boundary — winter peak Jan 15`() {
        val jan1 = SeasonalColorEngine.seasonalWeight(dayOfYear = 1, peakDay = 15, spread = 91f)
        val jan30 = SeasonalColorEngine.seasonalWeight(dayOfYear = 30, peakDay = 15, spread = 91f)
        assertTrue("jan1=$jan1", jan1 > 0.9f)
        assertTrue("jan30=$jan30", jan30 > 0.9f)
    }

    @Test fun `weight wrap-across-year day 360 near winter peak`() {
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 360, peakDay = 15, spread = 91f)
        assertTrue("w=$w should be > 0.8", w > 0.8f)
    }

    @Test fun `weight clamps to 0 well outside spread`() {
        // day 180 (June 29) vs winter peak (Jan 15): direct distance 165, wrap 200, min 165 > spread 91 → 0
        val w = SeasonalColorEngine.seasonalWeight(dayOfYear = 180, peakDay = 15, spread = 91f)
        assertEquals(0f, w, 0.0001f)
    }
}
