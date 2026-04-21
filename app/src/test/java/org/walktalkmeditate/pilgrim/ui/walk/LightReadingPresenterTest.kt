// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.core.celestial.Planet
import org.walktalkmeditate.pilgrim.core.celestial.PlanetaryHour
import org.walktalkmeditate.pilgrim.core.celestial.SunTimes

class LightReadingPresenterTest {

    @Test fun `phaseEmoji maps all 8 canonical phase names`() {
        val map = mapOf(
            "New Moon" to "\uD83C\uDF11",
            "Waxing Crescent" to "\uD83C\uDF12",
            "First Quarter" to "\uD83C\uDF13",
            "Waxing Gibbous" to "\uD83C\uDF14",
            "Full Moon" to "\uD83C\uDF15",
            "Waning Gibbous" to "\uD83C\uDF16",
            "Last Quarter" to "\uD83C\uDF17",
            "Waning Crescent" to "\uD83C\uDF18",
        )
        map.forEach { (name, expected) ->
            assertEquals("phaseEmoji($name)", expected, LightReadingPresenter.phaseEmoji(name))
        }
    }

    @Test fun `phaseEmoji unknown name falls back to full-moon glyph`() {
        assertEquals("\uD83C\uDF15", LightReadingPresenter.phaseEmoji("Blood Moon"))
        assertEquals("\uD83C\uDF15", LightReadingPresenter.phaseEmoji(""))
    }

    @Test fun `moonLine formats with phase name and rounded percent`() {
        val wx = MoonPhase(name = "Waxing Gibbous", illumination = 0.78, ageInDays = 10.0)
        assertEquals("Waxing Gibbous · 78% lit", LightReadingPresenter.moonLine(wx))

        val full = MoonPhase(name = "Full Moon", illumination = 1.0, ageInDays = 14.5)
        assertEquals("Full Moon · 100% lit", LightReadingPresenter.moonLine(full))

        val nearNew = MoonPhase(name = "New Moon", illumination = 0.001, ageInDays = 0.1)
        assertEquals("New Moon · 0% lit", LightReadingPresenter.moonLine(nearNew))
    }

    @Test fun `moonLine clamps out-of-range illumination`() {
        // Defensive — shouldn't happen from MoonCalc but guards future bugs.
        val over = MoonPhase(name = "Full Moon", illumination = 1.5, ageInDays = 14.5)
        assertEquals("Full Moon · 100% lit", LightReadingPresenter.moonLine(over))

        val under = MoonPhase(name = "New Moon", illumination = -0.1, ageInDays = 0.0)
        assertEquals("New Moon · 0% lit", LightReadingPresenter.moonLine(under))
    }

    @Test fun `planetaryHourLine maps all 7 day rulers to correct weekdays`() {
        val expected = mapOf(
            Planet.Sun to "Sunday",
            Planet.Moon to "Monday",
            Planet.Mars to "Tuesday",
            Planet.Mercury to "Wednesday",
            Planet.Jupiter to "Thursday",
            Planet.Venus to "Friday",
            Planet.Saturn to "Saturday",
        )
        expected.forEach { (ruler, dayName) ->
            val hour = PlanetaryHour(planet = Planet.Sun, dayRuler = ruler)
            val line = LightReadingPresenter.planetaryHourLine(hour)
            assertEquals("Hour of Sun · $dayName", line)
        }
    }

    @Test fun `planetaryHourLine includes the hour's ruling planet`() {
        val hour = PlanetaryHour(planet = Planet.Venus, dayRuler = Planet.Venus)
        assertEquals("Hour of Venus · Friday", LightReadingPresenter.planetaryHourLine(hour))

        val mercuryHour = PlanetaryHour(planet = Planet.Mercury, dayRuler = Planet.Mars)
        assertEquals("Hour of Mercury · Tuesday", LightReadingPresenter.planetaryHourLine(mercuryHour))
    }

    @Test fun `sunLine formats both rise and set in supplied zone`() {
        // Paris summer solstice: 03:47 UTC / 19:58 UTC. CEST = UTC+2 →
        // 05:47 / 21:58.
        val sun = SunTimes(
            sunrise = Instant.parse("2024-06-21T03:47:00Z"),
            sunset = Instant.parse("2024-06-21T19:58:00Z"),
            solarNoon = Instant.parse("2024-06-21T11:52:00Z"),
        )
        val line = LightReadingPresenter.sunLine(sun, ZoneId.of("Europe/Paris"))
        assertEquals("Sunrise 05:47 · Sunset 21:58", line)
    }

    @Test fun `sunLine returns null when sun is null`() {
        assertNull(LightReadingPresenter.sunLine(null, ZoneId.of("UTC")))
    }

    @Test fun `sunLine returns null for polar day or night`() {
        val polar = SunTimes(
            sunrise = null,
            sunset = null,
            solarNoon = Instant.parse("2024-06-21T12:00:00Z"),
        )
        assertNull(LightReadingPresenter.sunLine(polar, ZoneId.of("UTC")))
    }

    @Test fun `sunLine renders only sunrise when sunset is null`() {
        val sun = SunTimes(
            sunrise = Instant.parse("2024-06-21T03:47:00Z"),
            sunset = null,
            solarNoon = Instant.parse("2024-06-21T11:52:00Z"),
        )
        val line = LightReadingPresenter.sunLine(sun, ZoneId.of("Europe/Paris"))
        assertEquals("Sunrise 05:47", line)
    }

    @Test fun `sunLine renders only sunset when sunrise is null`() {
        val sun = SunTimes(
            sunrise = null,
            sunset = Instant.parse("2024-06-21T19:58:00Z"),
            solarNoon = Instant.parse("2024-06-21T11:52:00Z"),
        )
        val line = LightReadingPresenter.sunLine(sun, ZoneId.of("Europe/Paris"))
        assertEquals("Sunset 21:58", line)
    }

    @Test fun `planetDotColor returns a distinct color per planet`() {
        val colors = Planet.values().map { LightReadingPresenter.planetDotColor(it) }
        // All 7 distinct.
        assertEquals(7, colors.toSet().size)
        // None of them are fully transparent or pure black.
        colors.forEach {
            assertNotEquals(androidx.compose.ui.graphics.Color.Black, it)
            assertNotEquals(androidx.compose.ui.graphics.Color.Transparent, it)
        }
    }
}
