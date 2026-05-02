// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

class CelestialSnapshotCalcTest {

    private fun epochMillisAt(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun snapshot_has_seven_positions() {
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 21))
        assertEquals(7, snap.positions.size)
    }

    @Test fun positions_iterate_Planet_entries_order() {
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 21))
        assertEquals(Planet.entries, snap.positions.map { it.planet })
    }

    @Test fun tropical_and_sidereal_differ_by_ayanamsa() {
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 21))
        val sunPos = snap.positions.first { it.planet == Planet.Sun }
        // sidereal sign should typically differ from tropical by ~24°
        // (ayanamsa for ~2026); just assert they are not identical
        // when the sign would shift due to the offset
        // (loose check: degree differs by > 20)
        val diff = ((sunPos.tropical.sign.ordinal0 * 30 + sunPos.tropical.degree) -
            (sunPos.sidereal.sign.ordinal0 * 30 + sunPos.sidereal.degree))
        val normalizedDiff = ((diff % 360.0) + 360.0) % 360.0
        assertTrue("expected ~ayanamsa offset, got $normalizedDiff", normalizedDiff in 20.0..30.0)
    }

    @Test fun elementBalance_counts_sum_to_seven() {
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 21))
        assertEquals(7, snap.elementBalance.counts.values.sum())
    }

    @Test fun elementBalance_returns_dominant_when_one_wins() {
        // Construct synthetic positions where Fire wins (4) and others tie at 1
        val positions = listOf(
            mkPos(Planet.Sun, 0.0),       // Aries (Fire)
            mkPos(Planet.Moon, 120.0),    // Leo (Fire)
            mkPos(Planet.Mercury, 240.0), // Sagittarius (Fire)
            mkPos(Planet.Venus, 30.0),    // Taurus (Earth)
            mkPos(Planet.Mars, 60.0),     // Gemini (Air)
            mkPos(Planet.Jupiter, 90.0),  // Cancer (Water)
            mkPos(Planet.Saturn, 150.0),  // Virgo (Earth)
        )
        val balance = CelestialSnapshotCalc.elementBalance(positions, ZodiacSystem.Tropical)
        // Fire = 3, Earth = 2, Air = 1, Water = 1 → Fire dominant
        assertEquals(ZodiacSign.Element.Fire, balance.dominant)
    }

    @Test fun elementBalance_returns_null_when_two_elements_tie() {
        // Fire and Earth both at 3, others at < 3 → tie → null
        val positions = listOf(
            mkPos(Planet.Sun, 0.0),       // Aries (Fire)
            mkPos(Planet.Moon, 120.0),    // Leo (Fire)
            mkPos(Planet.Mercury, 240.0), // Sagittarius (Fire)
            mkPos(Planet.Venus, 30.0),    // Taurus (Earth)
            mkPos(Planet.Mars, 150.0),    // Virgo (Earth)
            mkPos(Planet.Jupiter, 270.0), // Capricorn (Earth)
            mkPos(Planet.Saturn, 60.0),   // Gemini (Air)
        )
        val balance = CelestialSnapshotCalc.elementBalance(positions, ZodiacSystem.Tropical)
        assertNull(balance.dominant)
    }

    @Test fun planetaryHour_present() {
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 21), zoneId = ZoneId.of("UTC"))
        assertNotNull(snap.planetaryHour)
        assertNotNull(snap.planetaryHour.planet)
        assertNotNull(snap.planetaryHour.dayRuler)
    }

    @Test fun seasonalMarker_null_on_non_marker_day() {
        // June 1, 2026 — well away from solstice (June 21)
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 1))
        assertNull(snap.seasonalMarker)
    }

    @Test fun seasonalMarker_nonNull_on_summer_solstice() {
        // June 21, 2026 — sun should be near 90°, summer solstice
        val snap = CelestialSnapshotCalc.snapshot(epochMillisAt(2026, 6, 21))
        // Tolerant: check that SOME seasonal marker is detected (sun
        // near 90°). The exact marker depends on time-of-day fractional
        // longitude precision; SummerSolstice expected.
        assertEquals(SeasonalMarker.SummerSolstice, snap.seasonalMarker)
    }

    private fun mkPos(planet: Planet, longitude: Double): PlanetaryPosition {
        val tropical = PlanetCalc.zodiacPosition(longitude)
        return PlanetaryPosition(
            planet = planet,
            longitude = longitude,
            tropical = tropical,
            sidereal = tropical, // not exercised in element-balance tests
            isRetrograde = false,
            isIngress = false,
        )
    }
}
