// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import kotlinx.serialization.Serializable

/**
 * Persisted widget state. Raw values only (no pre-formatted strings)
 * so render-time formatting picks up day-rollover for relative-date
 * labels AND mantra rotation correctly. The composable formats inline
 * via `LocalDate.now()`; the Worker only persists timestamps + numbers.
 *
 * Single `@Singleton WidgetStateRepository` shares this state across
 * all widget instances (home + lockscreen) — Worker's
 * `PilgrimWidget().updateAll(context)` triggers a synchronized
 * re-render after each write.
 */
@Serializable
sealed interface WidgetState {

    @Serializable
    data class LastWalk(
        val walkId: Long,
        val endTimestampMs: Long,
        val distanceMeters: Double,
        val activeDurationMs: Long,
    ) : WidgetState

    @Serializable
    data object Empty : WidgetState
}
