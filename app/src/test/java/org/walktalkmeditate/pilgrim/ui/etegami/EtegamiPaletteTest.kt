// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EtegamiPaletteTest {

    @Test
    fun `dawn band covers hours 5 to 7`() {
        val dawn = EtegamiPalettes.forHour(5)
        assertEquals(dawn, EtegamiPalettes.forHour(6))
        assertEquals(dawn, EtegamiPalettes.forHour(7))
    }

    @Test
    fun `morning band covers hours 8 to 10`() {
        val morning = EtegamiPalettes.forHour(8)
        assertEquals(morning, EtegamiPalettes.forHour(9))
        assertEquals(morning, EtegamiPalettes.forHour(10))
        assertNotEquals(morning, EtegamiPalettes.forHour(7))
        assertNotEquals(morning, EtegamiPalettes.forHour(11))
    }

    @Test
    fun `midday band covers hours 11 to 13`() {
        val midday = EtegamiPalettes.forHour(11)
        assertEquals(midday, EtegamiPalettes.forHour(12))
        assertEquals(midday, EtegamiPalettes.forHour(13))
    }

    @Test
    fun `afternoon band covers hours 14 to 16`() {
        val afternoon = EtegamiPalettes.forHour(14)
        assertEquals(afternoon, EtegamiPalettes.forHour(15))
        assertEquals(afternoon, EtegamiPalettes.forHour(16))
    }

    @Test
    fun `evening band covers hours 17 to 19`() {
        val evening = EtegamiPalettes.forHour(17)
        assertEquals(evening, EtegamiPalettes.forHour(18))
        assertEquals(evening, EtegamiPalettes.forHour(19))
    }

    @Test
    fun `night band covers hours 20 through 4`() {
        val night = EtegamiPalettes.forHour(20)
        for (h in listOf(20, 21, 22, 23, 0, 1, 2, 3, 4)) {
            assertEquals("hour $h should be night", night, EtegamiPalettes.forHour(h))
        }
    }

    @Test
    fun `all 24 hours map to a palette`() {
        for (h in 0..23) {
            val p = EtegamiPalettes.forHour(h)
            // Sanity: paper and ink must differ so text is legible
            assertNotEquals("hour $h has same paper/ink", p.paper, p.ink)
        }
    }

    @Test
    fun `night palette inverts — dark paper with light ink`() {
        val night = EtegamiPalettes.forHour(2)
        val midday = EtegamiPalettes.forHour(12)
        // Night paper is darker than night ink (inversion signal); not
        // asserting exact hex but relative luminance ordering.
        val nightPaperLuma = night.paper.run { red * 0.299f + green * 0.587f + blue * 0.114f }
        val nightInkLuma = night.ink.run { red * 0.299f + green * 0.587f + blue * 0.114f }
        val middayPaperLuma = midday.paper.run { red * 0.299f + green * 0.587f + blue * 0.114f }
        val middayInkLuma = midday.ink.run { red * 0.299f + green * 0.587f + blue * 0.114f }
        assert(nightPaperLuma < nightInkLuma) { "night paper should be darker than night ink" }
        assert(middayPaperLuma > middayInkLuma) { "midday paper should be lighter than midday ink" }
    }
}
