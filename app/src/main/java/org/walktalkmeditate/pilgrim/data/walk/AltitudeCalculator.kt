// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

/**
 * Sums positive deltas (ascent) and negative deltas (descent) over
 * consecutive [AltitudeSample] points. Used by the `.pilgrim`
 * exporter (Stage 10-I) for `PilgrimStats.ascent` / `descent`.
 *
 * Returns `(ascent, descent)` as a positive-meters pair. iOS computes
 * the same partition via Walk's CoreData accessors — matched here for
 * cross-platform export consistency.
 */
object AltitudeCalculator {

    fun computeAscentDescent(samples: List<AltitudeSample>): Pair<Double, Double> {
        if (samples.size < 2) return 0.0 to 0.0
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until samples.size) {
            val delta = samples[i].altitudeMeters - samples[i - 1].altitudeMeters
            if (delta > 0) ascent += delta else descent += -delta
        }
        return ascent to descent
    }
}
