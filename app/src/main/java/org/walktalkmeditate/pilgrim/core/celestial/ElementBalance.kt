// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * Tally of element counts across all 7 planetary positions.
 * `dominant` is null when there's a tie at the top — UI gracefully
 * skips the element line in that case (matches iOS behavior).
 */
data class ElementBalance(
    val counts: Map<ZodiacSign.Element, Int>,
    val dominant: ZodiacSign.Element?,
)
