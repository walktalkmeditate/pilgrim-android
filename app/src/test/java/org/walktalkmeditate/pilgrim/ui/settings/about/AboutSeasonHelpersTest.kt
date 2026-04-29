// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutSeasonHelpersTest {

    @Test
    fun `march in northern hemisphere is spring`() {
        val instant = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Spring, AboutSeasonHelpers.season(instant, latitude = 47.6, ZoneOffset.UTC))
    }

    @Test
    fun `march in southern hemisphere is autumn`() {
        val instant = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Autumn, AboutSeasonHelpers.season(instant, latitude = -33.9, ZoneOffset.UTC))
    }

    @Test
    fun `january in northern is winter, southern is summer`() {
        val instant = LocalDate.of(2026, 1, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Winter, AboutSeasonHelpers.season(instant, latitude = 47.6, ZoneOffset.UTC))
        assertEquals(Season.Summer, AboutSeasonHelpers.season(instant, latitude = -33.9, ZoneOffset.UTC))
    }

    @Test
    fun `july in northern is summer, southern is winter`() {
        val instant = LocalDate.of(2026, 7, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Summer, AboutSeasonHelpers.season(instant, latitude = 47.6, ZoneOffset.UTC))
        assertEquals(Season.Winter, AboutSeasonHelpers.season(instant, latitude = -33.9, ZoneOffset.UTC))
    }

    @Test
    fun `october in northern is autumn, southern is spring`() {
        val instant = LocalDate.of(2026, 10, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Autumn, AboutSeasonHelpers.season(instant, latitude = 47.6, ZoneOffset.UTC))
        assertEquals(Season.Spring, AboutSeasonHelpers.season(instant, latitude = -33.9, ZoneOffset.UTC))
    }

    @Test
    fun `equator latitude treated as northern`() {
        // Spec says `latitude >= 0` is northern.
        val instant = LocalDate.of(2026, 6, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Summer, AboutSeasonHelpers.season(instant, latitude = 0.0, ZoneOffset.UTC))
    }
}
