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
    /** 0 = off, 1 = inside-cascade, 2 = guiding-visible (edge). */
    fun nearClearingsMode(): Int
}

object SeekQaOverrides {

    /** Clearing 0 lands this close to the origin. */
    const val QA_BASE_DISTANCE_METERS = 12.0

    /** Each later clearing steps this much farther out. */
    const val QA_STEP_METERS = 12.0

    /** Mode 2: clearing edge sits this far beyond the tester. */
    const val QA_EDGE_MARGIN_METERS = 15.0

    /** Mode 2: each later clearing steps this much farther out. */
    const val QA_EDGE_STEP_METERS = 25.0

    /**
     * Mode 1 (`inside`): clearing `i` lands at `12 + 12·i` m — inside
     * every shipped 40–60 m radius, so arrival/stillness/reveal cascade
     * without walking; the guiding phase lasts seconds. Mode 2 (`edge`):
     * clearing `i`'s EDGE lands `15 + 25·i` m away (center = own radius
     * + margin), so fog/crescent/pings stay observable indefinitely and
     * arrival is a short stroll. Original distances are discarded in
     * both (a long chain's far clearing sits kilometers out); bearings,
     * ordering, radii, and budget survive.
     */
    fun compressTowardOrigin(chain: SeekChain, origin: SeekPoint, mode: Int): SeekChain =
        SeekChain(
            clearings = chain.clearings.mapIndexed { index, clearing ->
                val bearing = SeekChainGenerator.bearingDegrees(origin, clearing.center)
                val compressed = when (mode) {
                    2 -> clearing.radiusMeters + QA_EDGE_MARGIN_METERS + QA_EDGE_STEP_METERS * index
                    else -> QA_BASE_DISTANCE_METERS + QA_STEP_METERS * index
                }
                SeekClearing(
                    center = SeekChainGenerator.destination(origin, bearing, compressed),
                    radiusMeters = clearing.radiusMeters,
                )
            },
            budgetMeters = chain.budgetMeters,
        )
}
