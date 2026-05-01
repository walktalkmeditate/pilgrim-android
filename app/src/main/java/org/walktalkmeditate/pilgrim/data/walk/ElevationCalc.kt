// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

/**
 * Sum of positive altitude deltas across consecutive samples. Returns
 * 0.0 for fewer than 2 samples and for purely descending routes.
 *
 * Matches iOS `walk.ascend` semantics on the Walk Summary screen,
 * where elevation = total ascent (gain), not net change. A walk that
 * climbs 100m and descends 100m back reports 100m, not 0m.
 *
 * Pure function — caller responsible for ordering samples by
 * `timestamp` if needed (Room's `getForWalk` already does this via
 * an `ORDER BY timestamp` clause on the DAO query).
 */
fun computeAscend(samples: List<AltitudeSample>): Double =
    if (samples.size < 2) 0.0
    else samples.zipWithNext().sumOf { (a, b) ->
        (b.altitudeMeters - a.altitudeMeters).coerceAtLeast(0.0)
    }
