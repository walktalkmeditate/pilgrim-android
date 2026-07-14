// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import kotlin.math.max
import kotlin.random.Random

/**
 * A plain coordinate for seek geometry — free of map-SDK and pipeline
 * types so chains ride checkpoints unchanged.
 */
data class SeekPoint(
    val latitude: Double,
    val longitude: Double,
)

/**
 * A single fogged destination region within a seek. The center coordinate
 * lives only in the engine and map layers — it is never shown as a pin.
 */
data class SeekClearing(
    val center: SeekPoint,
    val radiusMeters: Double,
)

/**
 * The ordered destination list a seek walks through. This shape is the
 * seam for a future pilgrimage mode: real route stages can feed the same
 * engine without rework, so nothing here may assume randomness.
 */
data class SeekChain(
    val clearings: List<SeekClearing>,
    val budgetMeters: Double,
) {

    /**
     * A reroll replaces the active clearing and regenerates everything
     * downstream as a fresh outbound wander from the walker's current
     * position, under the remaining one-way budget.
     */
    fun regeneratingRemainder(
        fromActiveIndex: Int,
        current: SeekPoint,
        remainingBudgetMeters: Double,
        rng: Random,
    ): SeekChain {
        if (fromActiveIndex !in clearings.indices) return this
        val kept = clearings.take(fromActiveIndex)
        val regenerated = SeekChainGenerator.placeChain(
            count = clearings.size - fromActiveIndex,
            budgetMeters = max(remainingBudgetMeters, SeekTuning.REROLL_MIN_BUDGET_METERS),
            from = current,
            rng = rng,
        )
        return SeekChain(clearings = kept + regenerated, budgetMeters = budgetMeters)
    }
}
