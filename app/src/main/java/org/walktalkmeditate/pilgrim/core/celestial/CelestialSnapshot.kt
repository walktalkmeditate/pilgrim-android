// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

/**
 * Top-level celestial state for one moment in time. Time-only (no
 * location dependency). Consumed by Walk Summary celestial line +
 * SeasonalMarker callout.
 */
data class CelestialSnapshot(
    val positions: List<PlanetaryPosition>,
    val planetaryHour: PlanetaryHour,
    val elementBalance: ElementBalance,
    val system: ZodiacSystem,
    val seasonalMarker: SeasonalMarker?,
) {
    fun position(planet: Planet): PlanetaryPosition? = positions.firstOrNull { it.planet == planet }
}
