// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * Moon phase snapshot at an instant.
 *
 * All fields are derivable from the instant alone — no location needed.
 * Uses the simplified synodic-period method (Meeus), same as iOS's
 * `LunarPhase`: reference new moon at 2000-01-06 18:14 UTC, synodic
 * month 29.530588770576 days.
 */
data class MoonPhase(
    /**
     * English phase name — one of the 8 canonical buckets:
     * "New Moon", "Waxing Crescent", "First Quarter", "Waxing Gibbous",
     * "Full Moon", "Waning Gibbous", "Last Quarter", "Waning Crescent".
     */
    val name: String,
    /** Fraction of the Moon's disc illuminated, in [0.0, 1.0]. */
    val illumination: Double,
    /** Days since the last new moon, in [0.0, 29.530588770576). */
    val ageInDays: Double,
)
