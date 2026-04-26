// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant
import kotlin.math.cos
import kotlin.math.floor

/**
 * Moon phase calculator — simplified synodic-period method (Meeus).
 *
 * Reference epoch: **2000-01-06 18:14 UTC** (a known new moon).
 * Synodic month: **29.530588770576 days**. Age is time since the
 * most recent new moon; illumination is the cosine curve between
 * successive new moons.
 *
 * This ports iOS's `LunarPhase` behavior, matching its phase-name
 * bucketing and epoch. Accuracy is coarse compared to a full Meeus
 * ephemeris (±5% illumination, ±1 day age in worst cases), but
 * sufficient for a contemplative UI that shows "waxing gibbous"
 * not "67.3% illuminated as of 14:22:35 UTC".
 */
internal object MoonCalc {

    /**
     * Synodic month length in days. Promoted from `private` to
     * `internal` so [MoonPhase.isWaxing] can derive the waxing/waning
     * boundary from the same source-of-truth constant rather than
     * duplicating the numeric literal.
     */
    internal const val SYNODIC_DAYS = 29.530588770576

    /** JD of the reference new moon: 2000-01-06 18:14 UTC. */
    private val EPOCH_JD: Double = julianDay(Instant.parse("2000-01-06T18:14:00Z"))

    private val PHASE_NAMES = listOf(
        "New Moon",
        "Waxing Crescent",
        "First Quarter",
        "Waxing Gibbous",
        "Full Moon",
        "Waning Gibbous",
        "Last Quarter",
        "Waning Crescent",
    )

    fun moonPhase(instant: Instant): MoonPhase {
        val jd = julianDay(instant)
        val raw = (jd - EPOCH_JD) % SYNODIC_DAYS
        // `%` in Kotlin preserves sign of dividend; make it non-negative.
        val age = if (raw < 0) raw + SYNODIC_DAYS else raw
        val illumination = 0.5 * (1.0 - cos(2.0 * Math.PI * age / SYNODIC_DAYS))
        val name = phaseName(age)
        return MoonPhase(name = name, illumination = illumination, ageInDays = age)
    }

    /** Standard Julian Day Number from a Unix instant. */
    private fun julianDay(instant: Instant): Double =
        instant.toEpochMilli() / MILLIS_PER_DAY + UNIX_EPOCH_JD

    private fun phaseName(age: Double): String {
        val bucketWidth = SYNODIC_DAYS / 8.0
        val idx = floor(age / bucketWidth).toInt().coerceIn(0, 7)
        return PHASE_NAMES[idx]
    }

    private const val MILLIS_PER_DAY = 86_400_000.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
}
