// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.runtime.Immutable

/**
 * Three-state load model for the Journal screen. Replaces `HomeUiState`.
 * Stage 13-XZ B5/B7 lesson: `@Immutable` cascade on every data class
 * with `List<>` field types — Loaded carries `List<WalkSnapshot>`.
 */
sealed class JournalUiState {
    data object Loading : JournalUiState()
    data object Empty : JournalUiState()

    @Immutable
    data class Loaded(
        val snapshots: List<WalkSnapshot>,
        val summary: JourneySummary,
        val celestialAwarenessEnabled: Boolean,
    ) : JournalUiState()
}
