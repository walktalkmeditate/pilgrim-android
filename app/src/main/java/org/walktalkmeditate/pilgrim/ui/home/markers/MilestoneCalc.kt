// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class MilestonePosition(val distanceM: Double, val yPx: Float)

/**
 * Returns [100k, 500k, 1M, 2M, 3M, ..., 100M] — the first three
 * discrete thresholds, then 1M-step from 2M to 100M.
 */
fun milestoneThresholds(): List<Double> = buildList {
    add(100_000.0)
    add(500_000.0)
    add(1_000_000.0)
    var next = 2_000_000.0
    while (next <= 100_000_000.0) {
        add(next)
        next += 1_000_000.0
    }
}

/**
 * Compute milestone bar positions.
 *
 * INTENTIONAL iOS divergence (Stage 14-A Documented iOS deviation #11).
 * iOS iterates `snapshots` in display order (newest-first) computing
 * `prevCumulative = i > 0 ? snapshots[i-1].cumulativeDistance : 0`.
 * Because snapshots are newest-first, cumulative distance is
 * decreasing with `i`, so `prev < threshold && curr >= threshold` only
 * ever satisfies at `i = 0` — every milestone collapses onto the
 * newest walk (latent iOS bug).
 *
 * Android iterates oldest-first and emits the marker on the first walk
 * whose cumulative crosses the threshold. The regression test
 * (4 walks × 30 km ⇒ single 100 km marker on the 4th-oldest) is
 * the ground truth.
 */
fun computeMilestonePositions(
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
): List<MilestonePosition> {
    if (snapshots.size < 2 || dotPositions.size < 2) return emptyList()
    val oldestFirstSnaps = snapshots.asReversed()
    val oldestFirstPositions = dotPositions.asReversed()
    val totalCumulative = oldestFirstSnaps.last().cumulativeDistanceM
    val results = mutableListOf<MilestonePosition>()
    for (threshold in milestoneThresholds()) {
        if (threshold > totalCumulative) break
        for (i in oldestFirstSnaps.indices) {
            val prev = if (i == 0) 0.0 else oldestFirstSnaps[i - 1].cumulativeDistanceM
            val curr = oldestFirstSnaps[i].cumulativeDistanceM
            if (prev < threshold && threshold <= curr) {
                if (i < oldestFirstPositions.size) {
                    results += MilestonePosition(
                        distanceM = threshold,
                        yPx = oldestFirstPositions[i].yPx,
                    )
                }
                break
            }
        }
    }
    return results
}
