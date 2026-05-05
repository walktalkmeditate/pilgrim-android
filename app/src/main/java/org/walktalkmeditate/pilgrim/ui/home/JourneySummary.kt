// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.runtime.Immutable

/**
 * Aggregate totals for the JourneySummaryHeader's 3 cycle states.
 * Built alongside the WalkSnapshot list in `HomeViewModel.buildSnapshots`
 * to avoid a second collect on the same Flow.
 */
@Immutable
data class JourneySummary(
    val totalDistanceM: Double,
    val totalTalkSec: Long,
    val totalMeditateSec: Long,
    val talkerCount: Int,
    val meditatorCount: Int,
    val walkCount: Int,
    val firstWalkStartMs: Long,
)
