// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

sealed class WalkState {
    data object Idle : WalkState()

    data class Active(val walk: WalkAccumulator) : WalkState()

    data class Paused(val walk: WalkAccumulator, val pausedAt: Long) : WalkState()

    data class Meditating(val walk: WalkAccumulator, val meditationStartedAt: Long) : WalkState()

    data class Finished(val walk: WalkAccumulator, val endedAt: Long) : WalkState()
}
