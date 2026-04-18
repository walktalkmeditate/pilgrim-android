// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

/**
 * Pre-formatted row DTO for the Home walk list. The VM computes the
 * text fields once per Flow emission so row recomposition stays cheap
 * — the composable is a pass-through of already-formatted strings,
 * not a formatter.
 */
data class HomeWalkRow(
    val walkId: Long,
    // Raw fields (Stage 3-E): the calligraphy journal thread uses these
    // to synthesize a CalligraphyStrokeSpec per row. The formatted text
    // fields below are still cached so card composition stays a
    // pass-through.
    val uuid: String,
    val startTimestamp: Long,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val relativeDate: String,
    val durationText: String,
    val distanceText: String,
    val recordingCountText: String?,
    val intention: String?,
)

/**
 * Three-state load model for the Home screen. Mirrors Stage 2-E's
 * [org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryUiState] pattern.
 */
sealed class HomeUiState {
    data object Loading : HomeUiState()
    data class Loaded(val rows: List<HomeWalkRow>) : HomeUiState()
    data object Empty : HomeUiState()
}
