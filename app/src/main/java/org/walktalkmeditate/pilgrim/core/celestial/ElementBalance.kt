// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.compose.runtime.Immutable

/**
 * Tally of element counts across all 7 planetary positions.
 * `dominant` is null when there's a tie at the top — UI gracefully
 * skips the element line in that case (matches iOS behavior).
 *
 * `@Immutable` for Compose stability — `Map` field would otherwise
 * mark this Unstable.
 */
@Immutable
data class ElementBalance(
    val counts: Map<ZodiacSign.Element, Int>,
    val dominant: ZodiacSign.Element?,
)
