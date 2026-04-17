// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

sealed class WalkState {
    data object Idle : WalkState()

    data class Active(val walk: WalkAccumulator) : WalkState()

    data class Paused(val walk: WalkAccumulator, val pausedAt: Long) : WalkState()

    data class Meditating(val walk: WalkAccumulator, val meditationStartedAt: Long) : WalkState()

    data class Finished(val walk: WalkAccumulator, val endedAt: Long) : WalkState()
}

/**
 * True when a walk is being tracked right now (Active, Paused, or
 * Meditating). Used by UI surfaces to decide whether to route to
 * ActiveWalkScreen even when the user arrived at another route via
 * back navigation or a restored session.
 */
val WalkState.isInProgress: Boolean
    get() = this is WalkState.Active ||
        this is WalkState.Paused ||
        this is WalkState.Meditating
