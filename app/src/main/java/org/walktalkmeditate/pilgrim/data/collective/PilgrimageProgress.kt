// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

/**
 * Motivational message based on the collective's total walking distance.
 * Mirrors iOS [PilgrimageProgress] — sacred routes table verbatim.
 */
data class PilgrimageProgress(
    val message: String,
    val distanceKm: Double,
) {
    companion object {
        /**
         * Sacred routes for the "together, the X walked N times" message.
         * iOS-verbatim, ordered ascending by km. The factory walks them
         * in REVERSED order (largest first) and picks the first match.
         */
        private val routes: List<Pair<String, Double>> = listOf(
            "Kumano Kodo" to 40.0,
            "Via Francigena stage" to 100.0,
            "Camino de Santiago" to 800.0,
            "Shikoku 88 Temples" to 1_200.0,
            "Te Araroa" to 3_000.0,
            "Appalachian Trail" to 3_500.0,
            "the Moon" to 384_400.0,
        )

        fun from(distanceKm: Double): PilgrimageProgress {
            if (distanceKm < 10.0) {
                return PilgrimageProgress("The path is beginning.", distanceKm)
            }
            if (distanceKm < 40.0) {
                return PilgrimageProgress("The first steps, taken together.", distanceKm)
            }
            for ((name, km) in routes.reversed()) {
                if (distanceKm >= km) {
                    val times = (distanceKm / km).toInt()
                    val message = if (times >= 2) {
                        "Together, the $name walked $times times."
                    } else {
                        "Together, one $name complete."
                    }
                    return PilgrimageProgress(message, distanceKm)
                }
            }
            return PilgrimageProgress("The first steps, taken together.", distanceKm)
        }
    }
}
