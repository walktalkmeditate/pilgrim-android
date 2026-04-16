// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.walktalkmeditate.pilgrim.data.entity.WalkEventType

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
}
