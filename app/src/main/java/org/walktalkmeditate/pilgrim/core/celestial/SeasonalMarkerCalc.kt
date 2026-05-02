// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import kotlin.math.abs

/**
 * Maps the sun's ecliptic longitude to one of 8 turning points
 * (equinoxes, solstices, cross-quarter days) when within ±1.5° of
 * the canonical angle. Pure function; verbatim port of iOS
 * `CelestialCalculator.seasonalMarker(sunLongitude:)`.
 *
 * iOS uses strict `< 1.5` (not `<=`); this port preserves that.
 */
internal object SeasonalMarkerCalc {
    private const val THRESHOLD = 1.5

    private val anchors = listOf(
        0.0 to SeasonalMarker.SpringEquinox,
        45.0 to SeasonalMarker.Beltane,
        90.0 to SeasonalMarker.SummerSolstice,
        135.0 to SeasonalMarker.Lughnasadh,
        180.0 to SeasonalMarker.AutumnEquinox,
        225.0 to SeasonalMarker.Samhain,
        270.0 to SeasonalMarker.WinterSolstice,
        315.0 to SeasonalMarker.Imbolc,
    )

    fun seasonalMarker(sunLongitude: Double): SeasonalMarker? {
        val normalized = ((sunLongitude % 360.0) + 360.0) % 360.0
        for ((anchor, marker) in anchors) {
            val rawDiff = abs(normalized - anchor)
            val diff = if (rawDiff > 180.0) 360.0 - rawDiff else rawDiff
            if (diff < THRESHOLD) return marker
        }
        return null
    }
}
