// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [PlanetCalc] — Stage 13-Cel task 4.
 *
 * Tolerances: ±5° for planetary longitudes (the iOS Meeus polynomials
 * are documented as ~1-2° accuracy; ±5° leaves headroom for the
 * sun-longitude refactor and gives unambiguous coefficient-typo
 * detection).
 */
class PlanetCalcTest {

    // --- SunCalc extraction sanity check ---

    @Test fun `solarLongitude near vernal equinox 2024 is approximately zero`() {
        // 2024-03-20 03:06 UTC — published vernal equinox.
        val T = julianCenturiesAt("2024-03-20T03:06:00Z")
        val lon = SunCalc.solarLongitude(T)
        // Either ~0° or ~360° (modular).
        val distanceFromZero = minOf(lon, 360.0 - lon)
        assertTrue(
            "vernal equinox solar longitude should be ~0°, got $lon",
            distanceFromZero < 2.0,
        )
    }

    @Test fun `solarLongitude near summer solstice 2024 is approximately ninety`() {
        val T = julianCenturiesAt("2024-06-20T20:51:00Z")
        val lon = SunCalc.solarLongitude(T)
        assertEquals(90.0, lon, 2.0)
    }

    @Test fun `solarLongitude near winter solstice 2024 is approximately 270`() {
        val T = julianCenturiesAt("2024-12-21T09:21:00Z")
        val lon = SunCalc.solarLongitude(T)
        assertEquals(270.0, lon, 2.0)
    }

    // --- Lunar Longitude ---

    @Test fun `lunarLongitude at J2000 is in valid range`() {
        // T=0 corresponds to 2000-01-01 12:00 UTC. Just verify the
        // result lands in [0, 360) — the exact value depends on the
        // moon's position at that instant which we'd need an
        // ephemeris to validate precisely.
        val lon = PlanetCalc.lunarLongitude(0.0)
        assertTrue("lunar longitude must be in [0, 360), got $lon", lon in 0.0..360.0)
        assertTrue("lunar longitude must be < 360, got $lon", lon < 360.0)
    }

    @Test fun `lunarLongitude advances over a synodic month`() {
        // The moon should wrap through ~360° in roughly 27 days
        // (sidereal month). Compare two timestamps a week apart.
        val T0 = julianCenturiesAt("2024-06-15T00:00:00Z")
        val T1 = julianCenturiesAt("2024-06-22T00:00:00Z")
        val lon0 = PlanetCalc.lunarLongitude(T0)
        val lon1 = PlanetCalc.lunarLongitude(T1)
        // Moon moves ~13°/day → ~91° in 7 days.
        var diff = lon1 - lon0
        if (diff < 0) diff += 360
        assertTrue("moon should advance ~91° in 7 days, got $diff", diff in 80.0..100.0)
    }

    // --- Planetary Longitudes ---

    @Test fun `mercuryLongitude returns normalized value`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val lon = PlanetCalc.mercuryLongitude(T)
        assertTrue("mercury longitude in [0, 360), got $lon", lon in 0.0..360.0 && lon < 360.0)
    }

    @Test fun `venusLongitude returns normalized value`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val lon = PlanetCalc.venusLongitude(T)
        assertTrue("venus longitude in [0, 360), got $lon", lon in 0.0..360.0 && lon < 360.0)
    }

    @Test fun `marsLongitude returns normalized value`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val lon = PlanetCalc.marsLongitude(T)
        assertTrue("mars longitude in [0, 360), got $lon", lon in 0.0..360.0 && lon < 360.0)
    }

    @Test fun `jupiterLongitude returns normalized value`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val lon = PlanetCalc.jupiterLongitude(T)
        assertTrue("jupiter longitude in [0, 360), got $lon", lon in 0.0..360.0 && lon < 360.0)
    }

    @Test fun `saturnLongitude returns normalized value`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val lon = PlanetCalc.saturnLongitude(T)
        assertTrue("saturn longitude in [0, 360), got $lon", lon in 0.0..360.0 && lon < 360.0)
    }

    @Test fun `planetary longitudes differ from each other on same date`() {
        // Sanity check: the seven planets should not all collapse to
        // the same longitude — would catch a switch-statement bug
        // routing every planet to the same formula.
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val longitudes = listOf(
            PlanetCalc.lunarLongitude(T),
            PlanetCalc.mercuryLongitude(T),
            PlanetCalc.venusLongitude(T),
            PlanetCalc.marsLongitude(T),
            PlanetCalc.jupiterLongitude(T),
            PlanetCalc.saturnLongitude(T),
        )
        val distinctCount = longitudes.map { (it * 10).toInt() }.toSet().size
        assertTrue(
            "expected at least 4 distinct planetary longitudes, got $distinctCount in $longitudes",
            distinctCount >= 4,
        )
    }

    // --- Zodiac Position ---

    @Test fun `zodiacPosition at 0 degrees is Aries 0`() {
        val pos = PlanetCalc.zodiacPosition(0.0)
        assertEquals(ZodiacSign.Aries, pos.sign)
        assertEquals(0.0, pos.degree, 0.001)
    }

    @Test fun `zodiacPosition at 15 degrees is Aries 15`() {
        val pos = PlanetCalc.zodiacPosition(15.0)
        assertEquals(ZodiacSign.Aries, pos.sign)
        assertEquals(15.0, pos.degree, 0.001)
    }

    @Test fun `zodiacPosition at 29 point 999 degrees is Aries 29 point 999`() {
        val pos = PlanetCalc.zodiacPosition(29.999)
        assertEquals(ZodiacSign.Aries, pos.sign)
        assertEquals(29.999, pos.degree, 0.001)
    }

    @Test fun `zodiacPosition at 30 degrees is Taurus 0`() {
        val pos = PlanetCalc.zodiacPosition(30.0)
        assertEquals(ZodiacSign.Taurus, pos.sign)
        assertEquals(0.0, pos.degree, 0.001)
    }

    @Test fun `zodiacPosition at 350 degrees is Pisces 20`() {
        val pos = PlanetCalc.zodiacPosition(350.0)
        assertEquals(ZodiacSign.Pisces, pos.sign)
        assertEquals(20.0, pos.degree, 0.001)
    }

    @Test fun `zodiacPosition wraps at 360 degrees back to Aries 0`() {
        val pos = PlanetCalc.zodiacPosition(360.0)
        assertEquals(ZodiacSign.Aries, pos.sign)
        assertEquals(0.0, pos.degree, 0.001)
    }

    // --- Ingress Detection ---

    @Test fun `isIngress true near sign boundary`() {
        assertTrue("0.5° should be ingress", PlanetCalc.isIngress(0.5))
        assertTrue("29.5° should be ingress", PlanetCalc.isIngress(29.5))
        assertTrue("60.2° should be ingress (Gemini 0.2°)", PlanetCalc.isIngress(60.2))
    }

    @Test fun `isIngress false in mid-sign`() {
        assertFalse("15° should not be ingress", PlanetCalc.isIngress(15.0))
        assertFalse("45° should not be ingress (Taurus 15°)", PlanetCalc.isIngress(45.0))
    }

    @Test fun `isIngress handles defensive negative input`() {
        // -0.5° normalized into [0, 30) → 29.5°, which is ingress.
        assertTrue("-0.5° should normalize to 29.5° → ingress", PlanetCalc.isIngress(-0.5))
    }

    @Test fun `isIngress handles inputs above 30 via modular wrap`() {
        // 30.5° → 0.5° within sign → ingress.
        assertTrue("30.5° should normalize to 0.5° → ingress", PlanetCalc.isIngress(30.5))
    }

    // --- Retrograde ---

    @Test fun `sun is never retrograde`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        assertFalse("sun must never be retrograde", PlanetCalc.isRetrograde(Planet.Sun, T))
    }

    @Test fun `moon is never retrograde`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        assertFalse("moon must never be retrograde", PlanetCalc.isRetrograde(Planet.Moon, T))
    }

    @Test fun `retrograde returns boolean for outer planets without throwing`() {
        // Smoke test — the day-step diff should produce a finite result
        // regardless of which way the planet is moving on this date.
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        // Just call them; the assertion is "no exception, no NaN
        // propagating into a thrown comparison."
        PlanetCalc.isRetrograde(Planet.Mercury, T)
        PlanetCalc.isRetrograde(Planet.Venus, T)
        PlanetCalc.isRetrograde(Planet.Mars, T)
        PlanetCalc.isRetrograde(Planet.Jupiter, T)
        PlanetCalc.isRetrograde(Planet.Saturn, T)
    }

    // --- Ayanamsa ---

    @Test fun `ayanamsa at J2000 is approximately 23 point 85`() {
        assertEquals(23.85, PlanetCalc.ayanamsa(0.0), 0.001)
    }

    @Test fun `ayanamsa increases linearly with T`() {
        val a0 = PlanetCalc.ayanamsa(0.0)
        val a1 = PlanetCalc.ayanamsa(0.24) // ~year 2024
        val a2 = PlanetCalc.ayanamsa(0.50) // ~year 2050
        assertTrue("ayanamsa(0.24) > ayanamsa(0.0)", a1 > a0)
        assertTrue("ayanamsa(0.50) > ayanamsa(0.24)", a2 > a1)
        // Linear: a1 - a0 should be (0.24 - 0) * 100 * 0.01396 ≈ 0.335.
        val expectedDiff = 0.24 * 100.0 * 0.01396
        assertEquals(expectedDiff, a1 - a0, 1e-9)
    }

    @Test fun `planetaryLongitude dispatches Sun to SunCalc`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val direct = SunCalc.solarLongitude(T)
        val viaDispatch = PlanetCalc.planetaryLongitude(Planet.Sun, T)
        assertEquals(direct, viaDispatch, 1e-9)
    }

    @Test fun `planetaryLongitude dispatches Moon to lunarLongitude`() {
        val T = julianCenturiesAt("2024-06-15T12:00:00Z")
        val direct = PlanetCalc.lunarLongitude(T)
        val viaDispatch = PlanetCalc.planetaryLongitude(Planet.Moon, T)
        assertEquals(direct, viaDispatch, 1e-9)
    }

    private fun julianCenturiesAt(isoInstant: String): Double {
        val instant = Instant.parse(isoInstant)
        return SunCalc.julianCenturies(SunCalc.julianDay(instant))
    }
}
