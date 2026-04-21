// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Chaldean planetary-hour calculator.
 *
 * Given an instant, a time zone, and (optionally) the day's
 * sunrise/sunset, returns the ruling planet of the hour + the
 * ruling planet of the day-of-week.
 *
 * Day ruler by day-of-week:
 *  - Sunday → Sun, Monday → Moon, Tuesday → Mars, Wednesday → Mercury,
 *    Thursday → Jupiter, Friday → Venus, Saturday → Saturn.
 *
 * Chaldean order (slowest to fastest): Saturn, Jupiter, Mars, Sun,
 * Venus, Mercury, Moon. The hour-of-day ruler is
 * `chaldean[(dayRulerIndex + hourIndex) mod 7]`, where `hourIndex`
 * is 0–11 during daylight (sunrise → sunset split into 12 equal
 * "planetary hours") and 12–23 during night (sunset → next sunrise
 * split into 12).
 *
 * If real sunrise/sunset aren't available (polar region, no GPS),
 * falls back to a 6:00–18:00 split — matches iOS's shortcut.
 */
internal object PlanetaryHourCalc {

    /** Chaldean order, slowest planet first. */
    private val CHALDEAN: List<Planet> = listOf(
        Planet.Saturn, Planet.Jupiter, Planet.Mars, Planet.Sun,
        Planet.Venus, Planet.Mercury, Planet.Moon,
    )

    fun planetaryHour(
        instant: Instant,
        zoneId: ZoneId,
        sunTimes: SunTimes?,
    ): PlanetaryHour {
        val zoned = instant.atZone(zoneId)
        val dayRuler = rulerOf(zoned.dayOfWeek)
        val hourIndex = hourIndex(instant, zoneId, sunTimes)
        val dayRulerIdx = CHALDEAN.indexOf(dayRuler)
        val planet = CHALDEAN[(dayRulerIdx + hourIndex).mod(7)]
        return PlanetaryHour(planet = planet, dayRuler = dayRuler)
    }

    private fun rulerOf(dow: DayOfWeek): Planet = when (dow) {
        DayOfWeek.SUNDAY -> Planet.Sun
        DayOfWeek.MONDAY -> Planet.Moon
        DayOfWeek.TUESDAY -> Planet.Mars
        DayOfWeek.WEDNESDAY -> Planet.Mercury
        DayOfWeek.THURSDAY -> Planet.Jupiter
        DayOfWeek.FRIDAY -> Planet.Venus
        DayOfWeek.SATURDAY -> Planet.Saturn
    }

    /**
     * Compute the hour index 0..23 — 0..11 during daylight
     * (equal-width segments of sunrise→sunset), 12..23 during night
     * (sunset → next sunrise split into 12).
     *
     * Falls back to a 6:00..18:00 device-local split when sunTimes
     * is null or polar (sunrise/sunset null).
     */
    private fun hourIndex(
        instant: Instant,
        zoneId: ZoneId,
        sunTimes: SunTimes?,
    ): Int {
        val sunrise = sunTimes?.sunrise
        val sunset = sunTimes?.sunset
        if (sunrise == null || sunset == null) {
            return fallbackHourIndex(instant, zoneId)
        }

        // Degenerate: sunrise == sunset produces a zero-length day
        // span AND a full 24h night span; to avoid divide-by-zero in
        // the daytime branch below (and to keep behavior coherent),
        // route to the 6am–6pm fallback. SunCalc cannot produce this
        // — polar day returns null sunrise/sunset — but a 6-B test
        // fixture constructing SunTimes directly might.
        val daySpanMs = Duration.between(sunrise, sunset).toMillis()
        if (daySpanMs <= 0L) return fallbackHourIndex(instant, zoneId)

        // Are we within [sunrise, sunset) today? If so, daytime.
        if (!instant.isBefore(sunrise) && instant.isBefore(sunset)) {
            val elapsedMs = Duration.between(sunrise, instant).toMillis()
            val segment = (elapsedMs * 12L) / daySpanMs
            return segment.toInt().coerceIn(0, 11)
        }

        // Nighttime. Night span = 24h − day span. Reference point is
        // sunset. If the walk is before today's sunrise, adjust by
        // subtracting 24h from the reference so the elapsed
        // calculation stays positive.
        val nightSpanMs = 24L * 60L * 60L * 1000L - daySpanMs
        // Symmetric divide-by-zero guard for a 24h daySpan (sunrise +
        // 24h == sunset). Same reachability note as above.
        if (nightSpanMs <= 0L) return fallbackHourIndex(instant, zoneId)
        val refSunset = if (instant.isBefore(sunrise)) {
            sunset.minus(Duration.ofDays(1))
        } else {
            sunset
        }
        val elapsedMs = Duration.between(refSunset, instant).toMillis()
            .coerceAtLeast(0L)
        val segment = (elapsedMs * 12L) / nightSpanMs
        return (12 + segment.toInt()).coerceIn(12, 23)
    }

    /** 6am–6pm fallback when real sun times unavailable. */
    private fun fallbackHourIndex(instant: Instant, zoneId: ZoneId): Int {
        val h = instant.atZone(zoneId).hour
        return if (h in 6..17) h - 6 else {
            // Night: 18..23 → 12..17, 0..5 → 18..23.
            if (h >= 18) 12 + (h - 18) else 18 + h
        }
    }
}
