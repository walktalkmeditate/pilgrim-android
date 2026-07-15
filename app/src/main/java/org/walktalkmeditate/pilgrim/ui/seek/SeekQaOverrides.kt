// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import org.walktalkmeditate.pilgrim.domain.seek.SeekChain
import org.walktalkmeditate.pilgrim.domain.seek.SeekClearing
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint

/**
 * Device-QA seam: when enabled, a freshly generated chain is compressed
 * to hug the start fix so arrival → stillness → reveal → completion are
 * exercisable without walking. Production binding is active only in
 * debug builds AND when the tester has opted in on the device:
 *
 * ```
 * adb shell settings put global pilgrim_seek_qa_near 1
 * ```
 *
 * Never active in release builds; the compression itself is pure and
 * test-pinned.
 */
fun interface SeekQaFlags {
    fun nearClearings(): Boolean
}

object SeekQaOverrides {

    /** Clearing 0 lands this close to the origin. */
    const val QA_BASE_DISTANCE_METERS = 12.0

    /** Each later clearing steps this much farther out. */
    const val QA_STEP_METERS = 12.0

    /**
     * Re-places clearing `i` at `12 + 12·i` meters from [origin] along
     * its original bearing — original distances are discarded entirely
     * (a long chain's far clearing sits kilometers out; no scale factor
     * serves both short and long chains). Bearings, ordering, radii,
     * and budget survive, and the shipped 40–60 m radii mean every
     * compressed clearing overlaps the tester standing at the origin,
     * so the whole chain cascades to completion without walking.
     */
    fun compressTowardOrigin(chain: SeekChain, origin: SeekPoint): SeekChain =
        SeekChain(
            clearings = chain.clearings.mapIndexed { index, clearing ->
                val bearing = SeekChainGenerator.bearingDegrees(origin, clearing.center)
                val compressed = QA_BASE_DISTANCE_METERS + QA_STEP_METERS * index
                SeekClearing(
                    center = SeekChainGenerator.destination(origin, bearing, compressed),
                    radiusMeters = clearing.radiusMeters,
                )
            },
            budgetMeters = chain.budgetMeters,
        )
}
