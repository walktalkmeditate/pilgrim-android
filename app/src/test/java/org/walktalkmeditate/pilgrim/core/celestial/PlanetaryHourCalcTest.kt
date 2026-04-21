// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanetaryHourCalcTest {

    @Test fun `sunday 7am fallback yields Venus`() {
        // 2024-06-23 is a Sunday. 07:00 UTC with zoneId = UTC and no
        // sunTimes → fallback path: hourIndex = 1, dayRuler = Sun.
        // Chaldean[(3 + 1) % 7] = Chaldean[4] = Venus.
        val result = PlanetaryHourCalc.planetaryHour(
            instant = Instant.parse("2024-06-23T07:00:00Z"),
            zoneId = ZoneOffset.UTC,
            sunTimes = null,
        )
        assertEquals(Planet.Sun, result.dayRuler)
        assertEquals(Planet.Venus, result.planet)
    }

    @Test fun `monday noon fallback yields Mercury`() {
        // 2024-06-24 is Monday. 12:00 UTC, fallback → hourIndex = 6,
        // dayRuler = Moon. Chaldean[(6 + 6) % 7] = Chaldean[5] = Mercury.
        val result = PlanetaryHourCalc.planetaryHour(
            instant = Instant.parse("2024-06-24T12:00:00Z"),
            zoneId = ZoneOffset.UTC,
            sunTimes = null,
        )
        assertEquals(Planet.Moon, result.dayRuler)
        assertEquals(Planet.Mercury, result.planet)
    }

    @Test fun `wednesday exact sunrise returns day ruler`() {
        // Wednesday 2024-06-26, 05:00 UTC = exact synthetic sunrise.
        // First planetary hour of the day is always the day ruler.
        val sunrise = Instant.parse("2024-06-26T05:00:00Z")
        val sunset = Instant.parse("2024-06-26T20:00:00Z")
        val result = PlanetaryHourCalc.planetaryHour(
            instant = sunrise,
            zoneId = ZoneOffset.UTC,
            sunTimes = SunTimes(sunrise, sunset, sunrise.plusSeconds(6 * 3600L)),
        )
        assertEquals(Planet.Mercury, result.dayRuler)
        assertEquals(Planet.Mercury, result.planet)
    }

    @Test fun `tuesday exact sunset returns Saturn`() {
        // Tuesday 2024-06-25 sunset at 20:00 UTC. Hour index at sunset
        // is 12 (first night hour). Tuesday dayRuler = Mars (idx 2).
        // Chaldean[(2 + 12) % 7] = Chaldean[0] = Saturn.
        val sunrise = Instant.parse("2024-06-25T05:00:00Z")
        val sunset = Instant.parse("2024-06-25T20:00:00Z")
        val result = PlanetaryHourCalc.planetaryHour(
            instant = sunset,
            zoneId = ZoneOffset.UTC,
            sunTimes = SunTimes(sunrise, sunset, sunrise.plusSeconds(6 * 3600L)),
        )
        assertEquals(Planet.Mars, result.dayRuler)
        assertEquals(Planet.Saturn, result.planet)
    }

    @Test fun `polar day falls back to 6am-6pm split`() {
        // sunTimes present but with null sunrise/sunset (polar day).
        // Sunday 10:00 UTC → fallback hourIndex = 4, dayRuler = Sun
        // (idx 3). Chaldean[(3 + 4) % 7] = Chaldean[0] = Saturn.
        val result = PlanetaryHourCalc.planetaryHour(
            instant = Instant.parse("2024-06-23T10:00:00Z"),
            zoneId = ZoneOffset.UTC,
            sunTimes = SunTimes(
                sunrise = null,
                sunset = null,
                solarNoon = Instant.parse("2024-06-23T12:00:00Z"),
            ),
        )
        assertEquals(Planet.Sun, result.dayRuler)
        assertEquals(Planet.Saturn, result.planet)
    }

    @Test fun `night after sunset yields hour index in 12 to 23 range`() {
        // 21:00 UTC on a day with 05:00-20:00 sun. Past sunset.
        // nightSpan = 9h. elapsed = 1h. segment = 1*12/9 = 1.
        // hourIndex = 12 + 1 = 13. Synthetic check.
        val instant = Instant.parse("2024-06-26T21:00:00Z")
        val sunrise = Instant.parse("2024-06-26T05:00:00Z")
        val sunset = Instant.parse("2024-06-26T20:00:00Z")
        val result = PlanetaryHourCalc.planetaryHour(
            instant = instant,
            zoneId = ZoneOffset.UTC,
            sunTimes = SunTimes(sunrise, sunset, sunrise.plusSeconds(6 * 3600L)),
        )
        // dayRuler Wednesday = Mercury (idx 5). Chaldean[(5+13) % 7]
        // = Chaldean[4] = Venus.
        assertEquals(Planet.Mercury, result.dayRuler)
        assertEquals(Planet.Venus, result.planet)
    }

    @Test fun `pre-sunrise night treated as previous night span`() {
        // 03:00 UTC, BEFORE today's sunrise 05:00. daySpan = 15h,
        // nightSpan = 9h. refSunset = yesterday 20:00. elapsed = 7h.
        // segment = 7*12/9 = 84/9 = 9 (int). hourIndex = 12 + 9 = 21.
        // 2024-06-26 is Wednesday (we use the device-zone day-of-week
        // at the instant, which maps pre-dawn hours to "Wednesday"
        // rather than "Tuesday night" — iOS has the same
        // simplification).
        val instant = Instant.parse("2024-06-26T03:00:00Z")
        val sunrise = Instant.parse("2024-06-26T05:00:00Z")
        val sunset = Instant.parse("2024-06-26T20:00:00Z")
        val result = PlanetaryHourCalc.planetaryHour(
            instant = instant,
            zoneId = ZoneOffset.UTC,
            sunTimes = SunTimes(sunrise, sunset, sunrise.plusSeconds(6 * 3600L)),
        )
        assertEquals(Planet.Mercury, result.dayRuler)
        // Chaldean[(5 + 21) % 7] = Chaldean[26 % 7 = 5] = Mercury.
        assertEquals(Planet.Mercury, result.planet)
    }

    @Test fun `zone id different from instant shifts the weekday`() {
        // Instant is Monday 2024-06-24 at 23:00 UTC. In a zone that's
        // UTC+2 (CEST), local time is Tuesday 01:00. dayRuler should
        // be Mars (Tuesday), not Moon (Monday).
        val result = PlanetaryHourCalc.planetaryHour(
            instant = Instant.parse("2024-06-24T23:00:00Z"),
            zoneId = ZoneId.of("Europe/Paris"),
            sunTimes = null,
        )
        assertEquals(Planet.Mars, result.dayRuler)
    }

    @Test fun `every dayOfWeek maps to distinct ruler`() {
        // 2024-06-23 is Sunday. Walk 7 consecutive days, expect all
        // distinct rulers.
        val rulers = (0..6).map { i ->
            PlanetaryHourCalc.planetaryHour(
                instant = Instant.parse("2024-06-23T10:00:00Z").plusSeconds(i * 86_400L),
                zoneId = ZoneOffset.UTC,
                sunTimes = null,
            ).dayRuler
        }
        assertEquals(rulers.toSet().size, rulers.size)
    }
}
