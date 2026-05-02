// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Planetary-position calculator — simplified Meeus polynomials for
 * the seven classical planets plus retrograde / zodiac / ingress /
 * Lahiri ayanamsa helpers. Verbatim port of iOS
 * `CelestialCalculator.swift` (lunar + Mercury/Venus/Mars/Jupiter/
 * Saturn longitude families, geocentric corrections, retrograde
 * detection, zodiac mapping, ingress check, ayanamsa).
 *
 * Accuracy is contemplative-grade (~1-2° per the iOS notes; tests
 * use ±5°). Sun longitude is delegated to [SunCalc.solarLongitude]
 * so the Meeus apparent-longitude polynomial has one source-of-truth
 * across both modules.
 *
 * All inputs use Julian centuries since J2000.0 — obtain via
 * `SunCalc.julianCenturies(SunCalc.julianDay(instant))` or the
 * `julianDayFromEpochMillis` overload.
 */
internal object PlanetCalc {

    // --- Lunar Longitude (Simplified Meeus) ---

    fun lunarLongitude(T: Double): Double {
        val Lm = 218.3165 + 481267.8813 * T
        val D = 297.8502 + 445267.1115 * T
        val M = 357.5291 + 35999.0503 * T
        val Mm = 134.9634 + 477198.8676 * T
        val F = 93.2720 + 483202.0175 * T

        val longitude = Lm +
            6.289 * sin(Math.toRadians(Mm)) -
            1.274 * sin(Math.toRadians(2 * D - Mm)) +
            0.658 * sin(Math.toRadians(2 * D)) +
            0.214 * sin(Math.toRadians(2 * Mm)) -
            0.186 * sin(Math.toRadians(M)) -
            0.114 * sin(Math.toRadians(2 * F))

        return SunCalc.normalizeDeg(longitude)
    }

    // --- Planetary Longitudes (Simplified Heliocentric → Geocentric) ---

    fun mercuryLongitude(T: Double): Double {
        val L = SunCalc.normalizeDeg(252.2509 + 149472.6746 * T)
        val M = meanAnomaly(L = L, perihelionLongitude = 77.4561)
        val helio = L + 23.4400 * sin(Math.toRadians(M)) + 2.9818 * sin(Math.toRadians(2 * M))
        return geocentricForInnerPlanet(helioLongitude = SunCalc.normalizeDeg(helio), distance = 0.387, T = T)
    }

    fun venusLongitude(T: Double): Double {
        val L = SunCalc.normalizeDeg(181.9798 + 58517.8157 * T)
        val M = meanAnomaly(L = L, perihelionLongitude = 131.5637)
        val helio = L + 0.7758 * sin(Math.toRadians(M)) + 0.0033 * sin(Math.toRadians(2 * M))
        return geocentricForInnerPlanet(helioLongitude = SunCalc.normalizeDeg(helio), distance = 0.723, T = T)
    }

    fun marsLongitude(T: Double): Double {
        val L = SunCalc.normalizeDeg(355.4330 + 19140.2993 * T)
        val M = meanAnomaly(L = L, perihelionLongitude = 336.0602)
        val helio = L + 10.6912 * sin(Math.toRadians(M)) + 0.6228 * sin(Math.toRadians(2 * M))
        return geocentricForOuterPlanet(helioLongitude = SunCalc.normalizeDeg(helio), distance = 1.524, T = T)
    }

    fun jupiterLongitude(T: Double): Double {
        val L = SunCalc.normalizeDeg(34.3515 + 3034.9057 * T)
        val M = meanAnomaly(L = L, perihelionLongitude = 14.3312)
        val helio = L + 5.5549 * sin(Math.toRadians(M)) + 0.1683 * sin(Math.toRadians(2 * M))
        return geocentricForOuterPlanet(helioLongitude = SunCalc.normalizeDeg(helio), distance = 5.203, T = T)
    }

    fun saturnLongitude(T: Double): Double {
        val L = SunCalc.normalizeDeg(50.0774 + 1222.1138 * T)
        val M = meanAnomaly(L = L, perihelionLongitude = 93.0572)
        val helio = L + 6.3585 * sin(Math.toRadians(M)) + 0.2204 * sin(Math.toRadians(2 * M))
        return geocentricForOuterPlanet(helioLongitude = SunCalc.normalizeDeg(helio), distance = 9.537, T = T)
    }

    // --- Geocentric Corrections ---

    private fun geocentricForInnerPlanet(helioLongitude: Double, distance: Double, T: Double): Double {
        val sunLon = SunCalc.solarLongitude(T)
        val earthHelioLon = SunCalc.normalizeDeg(sunLon + 180.0)
        val diff = Math.toRadians(helioLongitude - earthHelioLon)
        val elongation = Math.toDegrees(atan2(sin(diff) * distance, cos(diff) * distance - 1.0))
        return SunCalc.normalizeDeg(sunLon + elongation)
    }

    private fun geocentricForOuterPlanet(helioLongitude: Double, distance: Double, T: Double): Double {
        val sunLon = SunCalc.solarLongitude(T)
        val earthHelioLon = SunCalc.normalizeDeg(sunLon + 180.0)
        val diffDeg = helioLongitude - earthHelioLon
        val diffRad = Math.toRadians(diffDeg)
        val parallax = Math.toDegrees(atan2(sin(diffRad), cos(diffRad) * distance - 1.0))
        return SunCalc.normalizeDeg(helioLongitude + parallax - diffDeg)
    }

    private fun meanAnomaly(L: Double, perihelionLongitude: Double): Double =
        SunCalc.normalizeDeg(L - perihelionLongitude)

    // --- Retrograde Detection ---

    /**
     * Returns true if [planet] appears retrograde at Julian-centuries
     * [T]: longitude has decreased over the past day (normalized to
     * the shorter arc through ±180°). Sun and Moon are never
     * retrograde from a geocentric frame.
     */
    fun isRetrograde(planet: Planet, T: Double): Boolean {
        if (planet == Planet.Sun || planet == Planet.Moon) return false

        val deltaT = 1.0 / 36525.0
        val lon1 = planetaryLongitude(planet, T - deltaT)
        val lon2 = planetaryLongitude(planet, T)

        var diff = lon2 - lon1
        if (diff > 180) diff -= 360
        if (diff < -180) diff += 360

        return diff < 0
    }

    /**
     * Geocentric ecliptic longitude switch for any [planet] at
     * Julian-centuries [T]. Public so [isRetrograde] can reuse the
     * same dispatch table; callers building celestial snapshots
     * should prefer it over individual `*Longitude(T)` calls.
     */
    fun planetaryLongitude(planet: Planet, T: Double): Double = when (planet) {
        Planet.Sun -> SunCalc.solarLongitude(T)
        Planet.Moon -> lunarLongitude(T)
        Planet.Mercury -> mercuryLongitude(T)
        Planet.Venus -> venusLongitude(T)
        Planet.Mars -> marsLongitude(T)
        Planet.Jupiter -> jupiterLongitude(T)
        Planet.Saturn -> saturnLongitude(T)
    }

    // --- Zodiac Position ---

    /**
     * Map an ecliptic longitude (any real, normalized internally)
     * to a [ZodiacPosition] with sign + degree-within-sign in
     * `[0.0, 30.0)`.
     */
    fun zodiacPosition(longitude: Double): ZodiacPosition {
        val normalizedLon = SunCalc.normalizeDeg(longitude)
        val signIndex = (normalizedLon / 30.0).toInt() % 12
        val degree = normalizedLon - signIndex * 30.0
        return ZodiacPosition(sign = ZodiacSign.fromIndex(signIndex), degree = degree)
    }

    /**
     * True when the longitude is within 1° of a sign boundary.
     * Uses a defensive double-modulo so negative or >360° inputs
     * still bucket correctly (iOS uses `truncatingRemainder` which
     * preserves sign; this Kotlin form normalizes to `[0, 30)` first).
     */
    fun isIngress(longitude: Double): Boolean {
        val degree = ((longitude % 30.0) + 30.0) % 30.0
        return degree < 1.0 || degree > 29.0
    }

    // --- Sidereal Conversion (Lahiri Ayanamsa) ---

    /**
     * Lahiri ayanamsa offset in degrees — the precessional drift
     * applied to convert tropical longitude to sidereal. Linear
     * approximation: `23.85 + 0.01396 * (julianYear - 2000)` where
     * `julianYear = 2000 + T * 100`. Sufficient for contemplative
     * UI; full Lahiri would need a higher-order polynomial.
     */
    fun ayanamsa(T: Double): Double {
        val julianYear = 2000.0 + T * 100.0
        return 23.85 + 0.01396 * (julianYear - 2000.0)
    }
}
