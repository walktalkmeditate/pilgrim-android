// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinShareRenderer
import org.walktalkmeditate.pilgrim.widget.fogDark as widgetFogDark
import org.walktalkmeditate.pilgrim.widget.fogLight as widgetFogLight

/**
 * AF68 regression guard: the `fog` secondary-text token must clear a WCAG
 * contrast bar against its parchment background. Reads the live palette values,
 * so a revert to a low-contrast fog fails here. The exact targets match the
 * iOS-chosen post-fix values (fog.colorset @ pilgrim-ios 3c8c443): light fog on
 * light parchment ≈ 3.4 (AA-large / UI), dark fog on dark parchment ≈ 5.4
 * (AA-normal).
 */
class FogContrastTest {

    private fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun `light fog clears AA-large contrast on light parchment`() {
        val ratio = contrast(PilgrimPaletteLight.fog, PilgrimPaletteLight.parchment)
        assertTrue("light fog/parchment contrast $ratio must be >= 3.0", ratio >= 3.0)
    }

    @Test
    fun `dark fog clears AA-normal contrast on dark parchment`() {
        val ratio = contrast(PilgrimPaletteDark.fog, PilgrimPaletteDark.parchment)
        assertTrue("dark fog/parchment contrast $ratio must be >= 4.5", ratio >= 4.5)
    }

    @Test
    fun `the pre-AF68 light fog would have failed the bar`() {
        // Documents why AF68 changed it: the old #B8AFA2 was well under AA-large.
        val old = Color(0xFFB8AFA2)
        val ratio = contrast(old, PilgrimPaletteLight.parchment)
        assertTrue("old fog contrast $ratio should be < 3.0", ratio < 3.0)
    }

    @Test
    fun `fog tokens are the iOS post-fix values`() {
        // Pins the exact hex so any drift — partial or full — fails, not just a
        // sub-3.0 revert. iOS fog.colorset @ 3c8c443.
        assertEquals(Color(0xFF8A8175), PilgrimPaletteLight.fog)
        assertEquals(Color(0xFF948E88), PilgrimPaletteDark.fog)
    }

    @Test
    fun `the off-palette fog copies stay in sync with the token`() {
        // Glance can't read the Compose palette and the seal-share renderer is a
        // standalone bitmap, so each keeps its own fog copy. Pin them to the
        // palette so a future sync can't silently miss one.
        assertEquals(PilgrimPaletteLight.fog.toArgb(), widgetFogLight.toArgb())
        assertEquals(PilgrimPaletteDark.fog.toArgb(), widgetFogDark.toArgb())
        // The seal share image always renders light → tracks the light fog.
        assertEquals(PilgrimPaletteLight.fog.toArgb(), GoshuinShareRenderer.FOG)
    }
}
