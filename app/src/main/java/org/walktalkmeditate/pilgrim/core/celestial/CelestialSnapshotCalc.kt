// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

/**
 * Top-level calculator entry — composes PlanetCalc + PlanetaryHourCalc
 * + element-balance into a single immutable snapshot for one moment.
 * Time-only (no location dependency); planetary-hour falls back to
 * fixed 6am/6pm split when sunTimes are unavailable.
 *
 * Verbatim port of iOS `CelestialCalculator.snapshot(for:system:)`.
 */
object CelestialSnapshotCalc {

    fun snapshot(
        atEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        system: ZodiacSystem = ZodiacSystem.Tropical,
    ): CelestialSnapshot {
        val instant = Instant.ofEpochMilli(atEpochMillis)
        val jd = SunCalc.julianDayFromEpochMillis(atEpochMillis)
        val T = SunCalc.julianCenturies(jd)

        val ayanamsa = PlanetCalc.ayanamsa(T)
        val positions = Planet.entries.map { planet ->
            val longitude = when (planet) {
                Planet.Sun -> SunCalc.solarLongitude(T)
                Planet.Moon -> PlanetCalc.lunarLongitude(T)
                Planet.Mercury -> PlanetCalc.mercuryLongitude(T)
                Planet.Venus -> PlanetCalc.venusLongitude(T)
                Planet.Mars -> PlanetCalc.marsLongitude(T)
                Planet.Jupiter -> PlanetCalc.jupiterLongitude(T)
                Planet.Saturn -> PlanetCalc.saturnLongitude(T)
            }
            val tropical = PlanetCalc.zodiacPosition(longitude)
            val sidereal = PlanetCalc.zodiacPosition(longitude - ayanamsa)
            // iOS `CelestialCalculator.swift:415-431` checks ingress
            // against the active-system longitude, not the raw tropical.
            // A planet at tropical 1° (Aries 1° → ingress) is at
            // sidereal ~337° (Pisces 7° → not ingress) — Lahiri shifts
            // the boundary by ~24°. Without this branch, Sidereal users
            // would see ingress flags computed against the wrong frame.
            // Latent today (no UI consumer); guards a future ingress
            // badge from inheriting the bug.
            val activeLongitude = if (system == ZodiacSystem.Tropical) longitude else longitude - ayanamsa
            PlanetaryPosition(
                planet = planet,
                longitude = longitude,
                tropical = tropical,
                sidereal = sidereal,
                isRetrograde = PlanetCalc.isRetrograde(planet, T),
                isIngress = PlanetCalc.isIngress(activeLongitude),
            )
        }

        val sunLon = positions.first { it.planet == Planet.Sun }.longitude

        return CelestialSnapshot(
            positions = positions,
            planetaryHour = PlanetaryHourCalc.planetaryHour(instant, zoneId, sunTimes = null),
            elementBalance = elementBalance(positions, system),
            system = system,
            seasonalMarker = SeasonalMarkerCalc.seasonalMarker(sunLon),
        )
    }

    fun elementBalance(positions: List<PlanetaryPosition>, system: ZodiacSystem): ElementBalance {
        val counts = ZodiacSign.Element.entries.associateWith { 0 }.toMutableMap()
        for (p in positions) {
            val zp = if (system == ZodiacSystem.Tropical) p.tropical else p.sidereal
            counts[zp.sign.element] = (counts[zp.sign.element] ?: 0) + 1
        }
        val maxCount = counts.values.max()
        val winners = counts.filterValues { it == maxCount }.keys
        val dominant = if (winners.size == 1) winners.first() else null
        return ElementBalance(counts = counts.toMap(), dominant = dominant)
    }
}
