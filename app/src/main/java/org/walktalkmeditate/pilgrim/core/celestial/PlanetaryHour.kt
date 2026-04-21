// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.compose.runtime.Immutable

/**
 * Planetary-hour snapshot using the Chaldean order.
 *
 * [dayRuler] is the day-of-week's ruling planet (Sunday=Sun,
 * Monday=Moon, Tuesday=Mars, Wednesday=Mercury, Thursday=Jupiter,
 * Friday=Venus, Saturday=Saturn). [planet] is the specific hour's
 * ruler, derived from the day ruler's position in the Chaldean
 * sequence plus the hour index since sunrise (0–11 daytime, 12–23
 * nighttime).
 */
@Immutable
data class PlanetaryHour(
    val planet: Planet,
    val dayRuler: Planet,
)
