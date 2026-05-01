// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import kotlin.math.max
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

@Immutable
internal data class PaceSparklinePoint(
    val xFraction: Float,
    val yFraction: Float,
)

/**
 * Pure port of iOS `PaceSparklineView.sparklinePoints`
 * (`PaceSparklineView.swift:53-80`). Filters route samples by speed
 * threshold (0.3 m/s — drops standing or near-stop noise), buckets up to 50
 * windows, computes a normalized fraction in `[0, 1]` for x (timestamp
 * within the walk's span) and y (1 minus 0.85× normalized speed; iOS
 * leaves 15% top headroom).
 *
 * Returns empty when fewer than 3 above-threshold samples exist OR when
 * the max bucketed speed is zero.
 */
internal fun computePaceSparklinePoints(
    samples: List<RouteDataSample>,
    walkStartMs: Long,
    walkEndMs: Long,
): List<PaceSparklinePoint> {
    val filtered = samples
        .filter { (it.speedMetersPerSecond ?: 0f) > SPEED_THRESHOLD_MPS }
        .sortedBy { it.timestamp }
    if (filtered.size < 3) return emptyList()

    val totalMs = max(1f, (walkEndMs - walkStartMs).toFloat())
    val step = max(1, filtered.size / TARGET_BUCKETS)

    data class Bucket(val xFraction: Float, val avgSpeed: Float)
    val buckets = mutableListOf<Bucket>()
    var i = 0
    while (i < filtered.size) {
        val end = (i + step).coerceAtMost(filtered.size)
        val window = filtered.subList(i, end)
        val avg = window.sumOf { (it.speedMetersPerSecond ?: 0f).toDouble() }.toFloat() / window.size
        val mid = window[window.size / 2]
        val frac = ((mid.timestamp - walkStartMs) / totalMs).coerceIn(0f, 1f)
        buckets += Bucket(frac, avg)
        i += step
    }

    val maxSpeed = buckets.maxOfOrNull { it.avgSpeed } ?: 0f
    if (maxSpeed <= 0f) return emptyList()

    return buckets.map { b ->
        PaceSparklinePoint(
            xFraction = b.xFraction,
            yFraction = 1f - (b.avgSpeed / maxSpeed) * Y_FILL_FRACTION,
        )
    }
}

private const val SPEED_THRESHOLD_MPS = 0.3f
private const val TARGET_BUCKETS = 50
private const val Y_FILL_FRACTION = 0.85f
