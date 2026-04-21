// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.compose.runtime.Immutable

/**
 * A short contemplative saying. [attribution] is null for
 * unattributed proverbs; otherwise it's the bare attribution
 * text (e.g., "Rumi") without a leading em-dash — the UI layer
 * decides how to render the dash / styling.
 */
@Immutable
data class Koan(
    val text: String,
    val attribution: String?,
)
