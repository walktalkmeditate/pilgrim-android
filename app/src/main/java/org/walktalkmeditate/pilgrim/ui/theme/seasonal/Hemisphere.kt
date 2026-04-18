// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme.seasonal

/**
 * Which half of the globe this device is in. Drives the seasonal
 * color engine's day-of-year shift so southern-hemisphere users see
 * summer palette in January rather than winter.
 *
 * Resolved lazily by [HemisphereRepository] — see that class for the
 * "infer from first location, cache to DataStore" flow.
 */
enum class Hemisphere {
    Northern, Southern;

    companion object {
        /**
         * Negative latitude → Southern. Zero (equator) and positive
         * latitudes → Northern by convention. Matches the iOS engine.
         */
        fun fromLatitude(latitude: Double): Hemisphere =
            if (latitude < 0.0) Southern else Northern
    }
}
