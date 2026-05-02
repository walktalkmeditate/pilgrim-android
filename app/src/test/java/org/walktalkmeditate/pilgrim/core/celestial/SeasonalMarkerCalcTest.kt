// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonalMarkerCalcTest {
    @Test fun spring_equinox_at_zero() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(0.0))
    @Test fun summer_solstice_at_90() = assertEquals(SeasonalMarker.SummerSolstice, SeasonalMarkerCalc.seasonalMarker(90.0))
    @Test fun autumn_equinox_at_180() = assertEquals(SeasonalMarker.AutumnEquinox, SeasonalMarkerCalc.seasonalMarker(180.0))
    @Test fun winter_solstice_at_270() = assertEquals(SeasonalMarker.WinterSolstice, SeasonalMarkerCalc.seasonalMarker(270.0))
    @Test fun beltane_at_45() = assertEquals(SeasonalMarker.Beltane, SeasonalMarkerCalc.seasonalMarker(45.0))
    @Test fun lughnasadh_at_135() = assertEquals(SeasonalMarker.Lughnasadh, SeasonalMarkerCalc.seasonalMarker(135.0))
    @Test fun samhain_at_225() = assertEquals(SeasonalMarker.Samhain, SeasonalMarkerCalc.seasonalMarker(225.0))
    @Test fun imbolc_at_315() = assertEquals(SeasonalMarker.Imbolc, SeasonalMarkerCalc.seasonalMarker(315.0))
    // iOS uses strict < 1.5, so 1.4 is inside and 1.5 is at the boundary (excluded)
    @Test fun threshold_edge_inside() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(1.4))
    @Test fun threshold_edge_exactly_at_boundary_is_null() = assertNull(SeasonalMarkerCalc.seasonalMarker(1.5))
    @Test fun outside_threshold_null() = assertNull(SeasonalMarkerCalc.seasonalMarker(2.0))
    @Test fun negative_normalized() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(-0.5))
    @Test fun above_360_normalized() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(360.5))
    // Near-360 wraps to SpringEquinox via circular distance (mirrors iOS abs(lon - 360.0) < threshold branch)
    @Test fun near_360_spring_equinox() = assertEquals(SeasonalMarker.SpringEquinox, SeasonalMarkerCalc.seasonalMarker(359.6))
}
