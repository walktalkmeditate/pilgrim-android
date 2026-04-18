// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

import java.time.LocalDate
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalTransformTest {

    @Test fun `winter peak in northern produces winter deltas`() {
        val adj = SeasonalColorEngine.seasonalTransform(
            date = LocalDate.of(2026, 1, 15),
            hemisphere = Hemisphere.Northern,
        )
        // At winter peak (day 15), only winter contributes meaningfully;
        // summer is at distance 181 (wrap 184), both > spread 91 → 0.
        // Spring contributes a sliver: day 15 to spring peak 105 = 90,
        // just inside spread 91. We tolerate a little bleed.
        assertTrue("hue=${adj.hueDelta} should be near -0.02", abs(adj.hueDelta + 0.02f) < 0.01f)
        assertTrue(
            "satMul=${adj.saturationMultiplier} should be near 0.85",
            abs(adj.saturationMultiplier - 0.85f) < 0.02f,
        )
        assertTrue(
            "briMul=${adj.brightnessMultiplier} should be near 0.95",
            abs(adj.brightnessMultiplier - 0.95f) < 0.02f,
        )
    }

    @Test fun `summer peak in northern produces summer deltas`() {
        val adj = SeasonalColorEngine.seasonalTransform(
            date = LocalDate.of(2026, 7, 15),
            hemisphere = Hemisphere.Northern,
        )
        assertTrue("hue=${adj.hueDelta} should be near +0.01", abs(adj.hueDelta - 0.01f) < 0.01f)
        assertTrue(
            "satMul=${adj.saturationMultiplier} should be near 1.15",
            abs(adj.saturationMultiplier - 1.15f) < 0.02f,
        )
        assertTrue(
            "briMul=${adj.brightnessMultiplier} should be near 1.03",
            abs(adj.brightnessMultiplier - 1.03f) < 0.02f,
        )
    }

    @Test fun `southern in July matches northern in January closely`() {
        val jan15Northern = SeasonalColorEngine.seasonalTransform(
            LocalDate.of(2026, 1, 15), Hemisphere.Northern,
        )
        val jul15Southern = SeasonalColorEngine.seasonalTransform(
            LocalDate.of(2026, 7, 15), Hemisphere.Southern,
        )
        // Southern hem day 196 → adjustedDayOfYear (196+182)%365+1 = 14.
        // Northern day 15 vs southern-adjusted day 14 — close but not identical.
        assertEquals(jan15Northern.hueDelta, jul15Southern.hueDelta, 0.003f)
        assertEquals(jan15Northern.saturationMultiplier, jul15Southern.saturationMultiplier, 0.005f)
        assertEquals(jan15Northern.brightnessMultiplier, jul15Southern.brightnessMultiplier, 0.005f)
    }

    @Test fun `equinox-ish spring peak gives spring deltas`() {
        val adj = SeasonalColorEngine.seasonalTransform(
            date = LocalDate.of(2026, 4, 15),   // day 105 = spring peak
            hemisphere = Hemisphere.Northern,
        )
        assertTrue("hue=${adj.hueDelta} should be near +0.02", abs(adj.hueDelta - 0.02f) < 0.01f)
        assertTrue(
            "satMul=${adj.saturationMultiplier} should be near 1.10",
            abs(adj.saturationMultiplier - 1.10f) < 0.02f,
        )
        assertTrue(
            "briMul=${adj.brightnessMultiplier} should be near 1.05",
            abs(adj.brightnessMultiplier - 1.05f) < 0.02f,
        )
    }

    @Test fun `adjustedDayOfYear northern identity`() {
        val doy = SeasonalColorEngine.adjustedDayOfYear(
            date = LocalDate.of(2026, 3, 15),
            hemisphere = Hemisphere.Northern,
        )
        assertEquals(LocalDate.of(2026, 3, 15).dayOfYear, doy)
    }

    @Test fun `adjustedDayOfYear southern shifts by 182`() {
        // March 15 (day 74) northern → (74+182) % 365 + 1 = 257 southern
        val doy = SeasonalColorEngine.adjustedDayOfYear(
            date = LocalDate.of(2026, 3, 15),
            hemisphere = Hemisphere.Southern,
        )
        assertEquals(257, doy)
    }
}
