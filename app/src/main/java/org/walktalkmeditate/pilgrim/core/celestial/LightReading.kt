// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Post-walk Light Reading aggregate — a pure-data composition of
 * [MoonPhase], optional [SunTimes] (null when no location is
 * available), [PlanetaryHour], and a [Koan]. Produced once from a
 * walk's id + start timestamp + first GPS location (if any).
 *
 * This is the domain layer for Stage 6-A. The Walk Summary UI
 * (6-B) will render a card from this; the card layout, typography,
 * emoji selection, and planetary colors are UI concerns that stay
 * out of the data shape.
 *
 * Determinism: `from(walkId, startedAt, location, zoneId)` is a
 * pure function. Same inputs always produce an equal aggregate.
 * [LocationPoint]'s `toString()` is not used as seed — only the
 * walkId + timestamp contribute, so two walks from the same
 * coordinates still pick distinct koans.
 */
data class LightReading(
    val moon: MoonPhase,
    val sun: SunTimes?,
    val planetaryHour: PlanetaryHour,
    val koan: Koan,
) {
    companion object {
        /**
         * Compute a LightReading. Pure — no I/O, no clock reads.
         *
         * @param walkId the walk's primary-key id (used as part of
         *   the koan seed).
         * @param startedAtEpochMs the walk's start timestamp in UTC
         *   epoch milliseconds. Used for moon/sun/planetary-hour
         *   computation AND as part of the koan seed.
         * @param location first GPS sample from the walk, or null
         *   if the walker never got a fix. When null, [sun] is null
         *   and planetary hour falls back to the 6am–6pm device-zone
         *   split.
         * @param zoneId the zone used to derive day-of-week and local
         *   hour for planetary-hour calculation. Defaults to the
         *   device's system zone. Does NOT affect moon or sun
         *   (those are in UTC).
         */
        fun from(
            walkId: Long,
            startedAtEpochMs: Long,
            location: LocationPoint?,
            zoneId: ZoneId = ZoneId.systemDefault(),
        ): LightReading {
            val instant = Instant.ofEpochMilli(startedAtEpochMs)
            val moon = MoonCalc.moonPhase(instant)
            val sun = location?.let {
                SunCalc.sunTimes(instant, it.latitude, it.longitude)
            }
            val planetaryHour = PlanetaryHourCalc.planetaryHour(instant, zoneId, sun)
            val koan = KoanPicker.pick(walkId, startedAtEpochMs)
            return LightReading(
                moon = moon,
                sun = sun,
                planetaryHour = planetaryHour,
                koan = koan,
            )
        }
    }
}
