// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

/**
 * Three-state load for the goshuin collection. Mirrors
 * [org.walktalkmeditate.pilgrim.ui.home.JournalUiState].
 *
 * [Loaded.totalCount] equals `seals.size` in Stage 4-C (no filtering
 * yet) but is a separate field so a future favicon filter can render
 * a subset while the parchment patina continues to reflect *lifetime*
 * practice, not the current view.
 */
sealed class GoshuinUiState {
    data object Loading : GoshuinUiState()
    data object Empty : GoshuinUiState()
    data class Loaded(
        val seals: List<GoshuinSeal>,
        val totalCount: Int,
        /**
         * iOS v1.6.0 — stats header counts ALL finished walks INCLUDING
         * archived ones. `seals` excludes archived, so this lives as a
         * separate field. iOS [Goshuin] header reads "N walks · X km · Y min".
         */
        val totalIncludingArchived: Int = totalCount,
        val totalDistanceMeters: Double = 0.0,
        val totalMeditationSeconds: Long = 0L,
    ) : GoshuinUiState()
}
