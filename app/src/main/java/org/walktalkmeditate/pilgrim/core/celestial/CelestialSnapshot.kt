// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.practice.ZodiacSystem

/**
 * Top-level celestial state for one moment in time. Time-only (no
 * location dependency). Consumed by Walk Summary celestial line +
 * SeasonalMarker callout.
 *
 * `@Immutable` because the class holds `List<PlanetaryPosition>` and
 * `ElementBalance.counts: Map`, which Compose marks Unstable by
 * default. Without the annotation, `CelestialLineRow` would skip-check-
 * fail on every recomposition. Same lesson as Stage 4-C `GoshuinSeal`.
 */
@Immutable
data class CelestialSnapshot(
    val positions: List<PlanetaryPosition>,
    val planetaryHour: PlanetaryHour,
    val elementBalance: ElementBalance,
    val system: ZodiacSystem,
    val seasonalMarker: SeasonalMarker?,
) {
    fun position(planet: Planet): PlanetaryPosition? = positions.firstOrNull { it.planet == planet }

    /**
     * Resolved moon zodiac sign symbol for the active [system]. Returns
     * null when no moon position resolves OR the active zodiac isn't
     * populated on the position. Centralizes the chained traversal that
     * was previously inlined in `ExpandCardSheet` (where any rename in
     * the chain silently null-coalesced to empty string with no signal).
     */
    fun moonZodiacSymbol(): String? {
        val pos = position(Planet.Moon) ?: return null
        val zodiac = if (system == ZodiacSystem.Tropical) pos.tropical else pos.sidereal
        return zodiac?.sign?.symbol
    }
}
