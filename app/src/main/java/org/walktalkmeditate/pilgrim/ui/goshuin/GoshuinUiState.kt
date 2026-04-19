// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

/**
 * Three-state load for the goshuin collection. Mirrors
 * [org.walktalkmeditate.pilgrim.ui.home.HomeUiState].
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
    ) : GoshuinUiState()
}
