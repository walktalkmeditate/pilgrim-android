// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
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

    // --- SealSpec convenience overload: routes through hash byte[30] + favicon ---

    @Test fun `sealInk from SealSpec routes through hash byte 30 and favicon`() {
        val spec = SealSpec(
            uuid = "deadbeef-cafe",
            startMillis = 1_700_000_000_000L,
            distanceMeters = 4_200.0,
            durationSeconds = 1_500.0,
            displayDistance = "4.20",
            unitLabel = "km",
            ink = Color.Transparent,
            favicon = WalkFavicon.LEAF,
        )
        val expected = SealColorPalette.sealInk(
            favicon = WalkFavicon.LEAF,
            marker = turningMarkerForEpochMillis(spec.startMillis),
            hashByte = sealHashBytes(spec).u(30),
            isDark = false,
        )
        assertEquals(expected, SealColorPalette.sealInk(spec, isDark = false))
    }

    @Test fun `southern hemisphere flips the turning seal color`() {
        // 2025-12-21 noon UTC — astronomical winter solstice (→ indigo);
        // below the equator it is the summer solstice (→ gold).
        val decSolsticeMs = LocalDate.of(2025, 12, 21)
            .atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun spec(southern: Boolean) = SealSpec(
            uuid = "solstice",
            startMillis = decSolsticeMs,
            distanceMeters = 1_000.0,
            durationSeconds = 600.0,
            displayDistance = "1.00",
            unitLabel = "km",
            ink = Color.Transparent,
            favicon = WalkFavicon.FLAME,
            southernHemisphere = southern,
        )
        assertEquals(SealColorPalette.turningIndigo.light, SealColorPalette.sealInk(spec(false), isDark = false))
        assertEquals(SealColorPalette.turningGold.light, SealColorPalette.sealInk(spec(true), isDark = false))
    }
}
