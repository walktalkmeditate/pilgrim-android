// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

sealed class WalkEffect {
    data object None : WalkEffect()

    data class PersistLocation(
        val walkId: Long,
        val point: LocationPoint,
    ) : WalkEffect()

    data class PersistEvent(
        val walkId: Long,
        val eventType: WalkEventType,
        val timestamp: Long,
    ) : WalkEffect()

    data class FinalizeWalk(
        val walkId: Long,
        val endTimestamp: Long,
    ) : WalkEffect()

    data class PurgeWalk(
        val walkId: Long,
    ) : WalkEffect()
}
