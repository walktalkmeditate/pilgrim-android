// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import kotlin.math.max

/**
 * One point on the elevation sparkline path, both axes normalized
 * to `[0, 1]`. y is `1 - normalized-altitude` so the highest altitude
 * draws at the top (y=0) and the lowest at the bottom (y=1).
 */
@Immutable
internal data class ElevationSparklinePoint(
    val xFraction: Float,
    val yFraction: Float,
)

/**
 * Bucket-and-normalize altitude samples for the post-walk elevation
 * sparkline. Verbatim port of iOS `ElevationProfileView` sample-stride
 * + per-pixel-bucket pattern (`ElevationProfileView.swift:39-79`).
 *
 * Returns empty for fewer than 2 samples (cannot draw a path) OR when
 * `max - min` is zero (degenerate flat profile — caller guards on
 * range > 1m before calling, but this is defense-in-depth).
 */
internal fun computeElevationSparklinePoints(
    altitudes: List<Double>,
    targetWidthBuckets: Int,
): List<ElevationSparklinePoint> {
    if (altitudes.size < 2) return emptyList()
    val minAlt = altitudes.min()
    val maxAlt = altitudes.max()
    val range = maxAlt - minAlt
    if (range <= 0.0) return emptyList()

    val step = max(1, altitudes.size / targetWidthBuckets.coerceAtLeast(1))
    val sampled = mutableListOf<Double>()
    var i = 0
    while (i < altitudes.size) {
        sampled += altitudes[i]
        i += step
    }
    if (sampled.size < 2) return emptyList()
    val denom = (sampled.size - 1).toFloat()
    return sampled.mapIndexed { idx, alt ->
        ElevationSparklinePoint(
            xFraction = idx.toFloat() / denom,
            yFraction = (1.0 - (alt - minAlt) / range).toFloat(),
        )
    }
}
