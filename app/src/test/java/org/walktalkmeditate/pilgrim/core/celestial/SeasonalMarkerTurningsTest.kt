// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.R

class SeasonalMarkerTurningsTest {
    @Test
    fun cardinal_markers_are_turning() {
        assertTrue(SeasonalMarker.SpringEquinox.isTurning())
        assertTrue(SeasonalMarker.SummerSolstice.isTurning())
        assertTrue(SeasonalMarker.AutumnEquinox.isTurning())
        assertTrue(SeasonalMarker.WinterSolstice.isTurning())
    }

    @Test
    fun cross_quarter_markers_are_not_turning() {
        assertFalse(SeasonalMarker.Imbolc.isTurning())
        assertFalse(SeasonalMarker.Beltane.isTurning())
        assertFalse(SeasonalMarker.Lughnasadh.isTurning())
        assertFalse(SeasonalMarker.Samhain.isTurning())
    }

    @Test
    fun cardinal_markers_have_kanji() {
        assertEquals("春分", SeasonalMarker.SpringEquinox.kanji())
        assertEquals("夏至", SeasonalMarker.SummerSolstice.kanji())
        assertEquals("秋分", SeasonalMarker.AutumnEquinox.kanji())
        assertEquals("冬至", SeasonalMarker.WinterSolstice.kanji())
    }

    @Test
    fun cross_quarter_markers_have_no_kanji() {
        assertNull(SeasonalMarker.Imbolc.kanji())
        assertNull(SeasonalMarker.Beltane.kanji())
        assertNull(SeasonalMarker.Lughnasadh.kanji())
        assertNull(SeasonalMarker.Samhain.kanji())
    }

    @Test
    fun banner_text_res_resolves_for_cardinals_only() {
        assertEquals(R.string.turning_equinox_banner, SeasonalMarker.SpringEquinox.bannerTextRes())
        assertEquals(R.string.turning_equinox_banner, SeasonalMarker.AutumnEquinox.bannerTextRes())
        assertEquals(R.string.turning_solstice_banner, SeasonalMarker.SummerSolstice.bannerTextRes())
        assertEquals(R.string.turning_solstice_banner, SeasonalMarker.WinterSolstice.bannerTextRes())
        assertNull(SeasonalMarker.Imbolc.bannerTextRes())
    }
}
