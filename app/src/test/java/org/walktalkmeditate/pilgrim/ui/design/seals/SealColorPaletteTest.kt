// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon

/**
 * iOS parity `SealColorPalette.swift` @fcd2255 (v1.6.0). Seal ink is
 * chosen by the walk's favicon family + a hash byte, overridden by the
 * cardinal-turning accent on solstice/equinox days. No seasonal shift.
 */
class SealColorPaletteTest {

    // --- favicon → family ---

    @Test fun `flame maps to the warm family`() {
        assertEquals(SealColorPalette.rust.light, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 0, false))
        assertEquals(SealColorPalette.ember.light, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 1, false))
        assertEquals(SealColorPalette.sienna.light, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 2, false))
        assertEquals(SealColorPalette.copper.light, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 3, false))
    }

    @Test fun `leaf maps to the cool family`() {
        assertEquals(SealColorPalette.moss.light, SealColorPalette.sealInk(WalkFavicon.LEAF, null, 0, false))
        assertEquals(SealColorPalette.mist.light, SealColorPalette.sealInk(WalkFavicon.LEAF, null, 3, false))
    }

    @Test fun `star maps to the accent family`() {
        assertEquals(SealColorPalette.indigo.light, SealColorPalette.sealInk(WalkFavicon.STAR, null, 0, false))
        assertEquals(SealColorPalette.amethyst.light, SealColorPalette.sealInk(WalkFavicon.STAR, null, 3, false))
    }

    @Test fun `null favicon maps to the neutral family`() {
        assertEquals(SealColorPalette.stone.light, SealColorPalette.sealInk(null, null, 0, false))
        assertEquals(SealColorPalette.dawn.light, SealColorPalette.sealInk(null, null, 1, false))
        assertEquals(SealColorPalette.fog.light, SealColorPalette.sealInk(null, null, 2, false))
    }

    // --- hash byte wraps modulo family size ---

    @Test fun `hash byte wraps within the warm family`() {
        assertEquals(SealColorPalette.rust.light, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 4, false))
        assertEquals(SealColorPalette.ember.light, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 5, false))
    }

    @Test fun `hash byte wraps within the 3-entry neutral family`() {
        assertEquals(SealColorPalette.stone.light, SealColorPalette.sealInk(null, null, 3, false))
        assertEquals(SealColorPalette.dawn.light, SealColorPalette.sealInk(null, null, 4, false))
    }

    // --- dark variant ---

    @Test fun `dark theme picks the dark family variant`() {
        assertEquals(SealColorPalette.rust.dark, SealColorPalette.sealInk(WalkFavicon.FLAME, null, 0, true))
        assertEquals(SealColorPalette.stone.dark, SealColorPalette.sealInk(null, null, 0, true))
    }

    // --- turning override ---

    @Test fun `turning day overrides the favicon family`() {
        assertEquals(
            SealColorPalette.turningGold.light,
            SealColorPalette.sealInk(WalkFavicon.FLAME, SeasonalMarker.SummerSolstice, 0, false),
        )
        assertEquals(
            SealColorPalette.turningJade.light,
            SealColorPalette.sealInk(WalkFavicon.LEAF, SeasonalMarker.SpringEquinox, 1, false),
        )
        assertEquals(
            SealColorPalette.turningClaret.light,
            SealColorPalette.sealInk(WalkFavicon.STAR, SeasonalMarker.AutumnEquinox, 2, false),
        )
        assertEquals(
            SealColorPalette.turningIndigo.light,
            SealColorPalette.sealInk(null, SeasonalMarker.WinterSolstice, 0, false),
        )
    }

    @Test fun `turning seal uses the light variant even in dark theme`() {
        assertEquals(
            SealColorPalette.turningGold.light,
            SealColorPalette.sealInk(WalkFavicon.FLAME, SeasonalMarker.SummerSolstice, 0, true),
        )
    }

    @Test fun `cross-quarter markers do not override the favicon family`() {
        assertEquals(
            SealColorPalette.rust.light,
            SealColorPalette.sealInk(WalkFavicon.FLAME, SeasonalMarker.Beltane, 0, false),
        )
    }
}
