// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import android.app.Application
import androidx.compose.ui.graphics.Color
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimLightColors
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * iOS parity `WalkDotView.swift:166-180` (dot) + `InkScrollView.swift:
 * 287-303,657-674` (thread) @fcd2255. A walk's dot/thread color is:
 *  - turning day → the cardinal accent (raw for the dot, × 0.85 for the
 *    thread), NOT seasonally shifted;
 *  - otherwise → the season base (moss/rust/dawn/ink by calendar month)
 *    seasonally shifted (Full for the dot, Moderate for the thread).
 *
 * The shift path calls `android.graphics.Color` so those cases run under
 * Robolectric; the pure base/accent selectors run as plain JUnit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkDotColorTest {

    private val base = pilgrimLightColors()

    // --- season base selection (pure) ---

    @Test fun `spring months map to moss`() {
        listOf(3, 4, 5).forEach { assertEquals("month $it", base.moss, seasonBaseColor(it, base)) }
    }

    @Test fun `summer months map to rust`() {
        listOf(6, 7, 8).forEach { assertEquals("month $it", base.rust, seasonBaseColor(it, base)) }
    }

    @Test fun `autumn months map to dawn`() {
        listOf(9, 10, 11).forEach { assertEquals("month $it", base.dawn, seasonBaseColor(it, base)) }
    }

    @Test fun `winter months map to ink`() {
        listOf(12, 1, 2).forEach { assertEquals("month $it", base.ink, seasonBaseColor(it, base)) }
    }

    // --- turning accent selection (pure) ---

    @Test fun `cardinal turnings map to their accent`() {
        assertEquals(base.turningJade, turningAccentColor(SeasonalMarker.SpringEquinox, base))
        assertEquals(base.turningGold, turningAccentColor(SeasonalMarker.SummerSolstice, base))
        assertEquals(base.turningClaret, turningAccentColor(SeasonalMarker.AutumnEquinox, base))
        assertEquals(base.turningIndigo, turningAccentColor(SeasonalMarker.WinterSolstice, base))
    }

    @Test fun `cross-quarter and null markers have no accent`() {
        assertNull(turningAccentColor(SeasonalMarker.Beltane, base))
        assertNull(turningAccentColor(SeasonalMarker.Imbolc, base))
        assertNull(turningAccentColor(SeasonalMarker.Lughnasadh, base))
        assertNull(turningAccentColor(SeasonalMarker.Samhain, base))
        assertNull(turningAccentColor(null, base))
    }

    // --- full ink composition (Robolectric) ---

    private val julyDate = LocalDate.of(2026, 7, 15)

    @Test fun `turning dot uses the raw accent unshifted`() {
        val dot = walkInkColor(
            marker = SeasonalMarker.SummerSolstice,
            date = julyDate,
            base = base,
            hemisphere = Hemisphere.Northern,
            intensity = SeasonalColorEngine.Intensity.Full,
            turningOpacity = 1.0f,
        )
        // Raw turning token — no seasonal shift applied.
        assertEquals(base.turningGold, dot)
    }

    @Test fun `turning thread dims the accent to 0_85 without shifting hue`() {
        val thread = walkInkColor(
            marker = SeasonalMarker.SummerSolstice,
            date = julyDate,
            base = base,
            hemisphere = Hemisphere.Northern,
            intensity = SeasonalColorEngine.Intensity.Moderate,
            turningOpacity = 0.85f,
        )
        assertEquals("alpha", 0.85f, thread.alpha, 0.01f)
        assertEquals("red", base.turningGold.red, thread.red, 0.001f)
        assertEquals("green", base.turningGold.green, thread.green, 0.001f)
        assertEquals("blue", base.turningGold.blue, thread.blue, 0.001f)
    }

    @Test fun `summer non-turning dot uses the rust season base, not moss`() {
        val dot = walkInkColor(
            marker = null,
            date = julyDate,
            base = base,
            hemisphere = Hemisphere.Northern,
            intensity = SeasonalColorEngine.Intensity.Full,
            turningOpacity = 1.0f,
        )
        val expectedRust = SeasonalColorEngine.applySeasonalShift(
            base.rust, SeasonalColorEngine.Intensity.Full, julyDate, Hemisphere.Northern,
        )
        val shiftedMoss = SeasonalColorEngine.applySeasonalShift(
            base.moss, SeasonalColorEngine.Intensity.Full, julyDate, Hemisphere.Northern,
        )
        assertEquals(expectedRust, dot)
        assertNotEquals("summer dot must not be moss-derived", shiftedMoss, dot)
    }

    @Test fun `dot full intensity differs from thread moderate intensity`() {
        val dot = walkInkColor(
            null, julyDate, base, Hemisphere.Northern,
            SeasonalColorEngine.Intensity.Full, 1.0f,
        )
        val thread = walkInkColor(
            null, julyDate, base, Hemisphere.Northern,
            SeasonalColorEngine.Intensity.Moderate, 0.85f,
        )
        assertNotEquals(dot, thread)
    }

    // --- public wiring (Robolectric): season varies across the year ---

    @Test fun `walkDotColor varies between summer and winter walks`() {
        val zone = ZoneId.systemDefault()
        val summerMs = LocalDate.of(2026, 7, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val winterMs = LocalDate.of(2026, 1, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val summer: Color = walkDotColor(summerMs, base, Hemisphere.Northern)
        val winter: Color = walkDotColor(winterMs, base, Hemisphere.Northern)
        assertNotEquals("dots must change color across seasons", summer, winter)
    }
}
