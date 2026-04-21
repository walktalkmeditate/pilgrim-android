// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import java.time.Instant

/**
 * Sunrise / sunset / solar-noon for a location on a given date (UTC).
 *
 * [sunrise] and [sunset] are null when the sun doesn't cross the
 * horizon on the requested date — polar day (always above) and polar
 * night (always below). [solarNoon] is always computable: it's the
 * instant the sun is highest, even if that peak is below the horizon.
 */
data class SunTimes(
    val sunrise: Instant?,
    val sunset: Instant?,
    val solarNoon: Instant,
)
