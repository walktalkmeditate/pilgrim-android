// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Sun-times calculator — NOAA Simplified Solar Position Algorithm.
 *
 * Computes sunrise, sunset, and solar noon for a latitude/longitude
 * on the UTC date containing [instant]. Accuracy ≈ ±1 minute for
 * latitudes up to ~70°; beyond that the atmospheric-refraction
 * assumptions (−0.833° constant) begin to fail.
 *
 * Polar handling: [SunTimes.sunrise] and [SunTimes.sunset] are null
 * when the sun doesn't cross the horizon on the requested date —
 * polar day (continuous daylight) and polar night (continuous
 * darkness). [SunTimes.solarNoon] is always non-null: it's the
 * instant the sun is highest in the sky, even if that peak is
 * below the horizon.
 *
 * Reference: https://gml.noaa.gov/grad/solcalc/calcdetails.html.
 */
internal object SunCalc {

    /** −0.833° = standard refraction (−0.583°) + sun radius (−0.25°). */
    private const val HORIZON_ZENITH_DEG = 90.833

    fun sunTimes(instant: Instant, latitude: Double, longitude: Double): SunTimes {
        val date = instant.atOffset(ZoneOffset.UTC).toLocalDate()
        val midnightUtc = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val jdMidnight = julianDay(midnightUtc)

        // Compute solar parameters at approximate solar noon UTC.
        val approxNoonFrac = 0.5 - longitude / 360.0
        val tCenturies = julianCenturies(jdMidnight + approxNoonFrac)

        val geomMeanLong = normalizeDeg(280.46646 + tCenturies * (36000.76983 + tCenturies * 0.0003032))
        val geomMeanAnom = 357.52911 + tCenturies * (35999.05029 - 0.0001537 * tCenturies)
        val eccentricity = 0.016708634 - tCenturies * (0.000042037 + 0.0000001267 * tCenturies)

        val sunEqCenter = run {
            val m = Math.toRadians(geomMeanAnom)
            sin(m) * (1.914602 - tCenturies * (0.004817 + 0.000014 * tCenturies)) +
                sin(2 * m) * (0.019993 - 0.000101 * tCenturies) +
                sin(3 * m) * 0.000289
        }

        val sunTrueLong = geomMeanLong + sunEqCenter
        val sunAppLong = sunTrueLong - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * tCenturies))

        val meanObliquity = run {
            val s = 21.448 - tCenturies * (46.815 + tCenturies * (0.00059 - tCenturies * 0.001813))
            23.0 + (26.0 + s / 60.0) / 60.0
        }
        val obliquityCorr = meanObliquity + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * tCenturies))

        val declRad = asin(sin(Math.toRadians(obliquityCorr)) * sin(Math.toRadians(sunAppLong)))

        val y = tan(Math.toRadians(obliquityCorr / 2)).let { it * it }
        val eqTime = run {
            val lRad = Math.toRadians(geomMeanLong)
            val mRad = Math.toRadians(geomMeanAnom)
            val raw = y * sin(2 * lRad) -
                2 * eccentricity * sin(mRad) +
                4 * eccentricity * y * sin(mRad) * cos(2 * lRad) -
                0.5 * y * y * sin(4 * lRad) -
                1.25 * eccentricity * eccentricity * sin(2 * mRad)
            Math.toDegrees(raw) * 4.0 // minutes
        }

        // Hour angle at horizon, with clamp for polar regions.
        val latRad = Math.toRadians(latitude)
        val cosH = (cos(Math.toRadians(HORIZON_ZENITH_DEG)) - sin(latRad) * sin(declRad)) /
            (cos(latRad) * cos(declRad))

        val polar = cosH !in -1.0..1.0
        val hourAngleDeg = if (polar) 0.0 else Math.toDegrees(acos(cosH.coerceIn(-1.0, 1.0)))

        // Solar noon in UTC minutes from midnight. 720 - 4*lon - eqTime.
        val solarNoonMinutes = 720.0 - 4.0 * longitude - eqTime
        val sunriseMinutes = solarNoonMinutes - 4.0 * hourAngleDeg
        val sunsetMinutes = solarNoonMinutes + 4.0 * hourAngleDeg

        val solarNoon = midnightUtc.plus(
            floor(solarNoonMinutes * 60_000.0).toLong(),
            ChronoUnit.MILLIS,
        )

        val sunrise = if (polar) null else midnightUtc.plus(
            floor(sunriseMinutes * 60_000.0).toLong(),
            ChronoUnit.MILLIS,
        )

        val sunset = if (polar) null else midnightUtc.plus(
            floor(sunsetMinutes * 60_000.0).toLong(),
            ChronoUnit.MILLIS,
        )

        return SunTimes(sunrise = sunrise, sunset = sunset, solarNoon = solarNoon)
    }

    private fun julianDay(instant: Instant): Double =
        instant.toEpochMilli() / 86_400_000.0 + 2_440_587.5

    private fun julianCenturies(jd: Double): Double = (jd - 2_451_545.0) / 36_525.0

    private fun normalizeDeg(deg: Double): Double {
        val r = deg % 360.0
        return if (r < 0) r + 360.0 else r
    }
}
