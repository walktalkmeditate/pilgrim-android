// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import org.walktalkmeditate.pilgrim.domain.WalkMode

/**
 * Everything a walk start carries to the `:tracker` controller.
 *
 * [walkUuid] is the uuid the Honor Begin use case minted for the new walk,
 * so the staged Way, the link file, and the marker all key by the same
 * string as the walk row; null lets the repository mint one, as every
 * non-Honor start does. It is also the replay guard for a redelivered
 * start: a uuid already in Room is never inserted twice.
 */
data class WalkStartRequest(
    val intention: String? = null,
    val mode: WalkMode = WalkMode.Wander,
    val walkUuid: String? = null,
    val honor: HonorStart? = null,
)

/** The Way an honor walk follows, and the preferences frozen for it at Start. */
data class HonorStart(
    val wayId: String,
    val settings: HonorSettings,
)

/**
 * The three Honor preferences, read once at Start and frozen for the walk
 * (parity spec D §11; iOS reads them in `startHonorEngineIfNeeded`,
 * `ActiveWalkViewModel+Honor.swift:53-59@7c200bf`).
 */
data class HonorSettings(
    val voicesEnabled: Boolean,
    val softTapEnabled: Boolean,
) {
    companion object {
        /**
         * The master Sounds switch silences a Way's voices too. The soft tap
         * has no switch on iOS (pilgrim-ios #109), so it stays off.
         */
        fun atStart(
            honorVoicesEnabled: Boolean,
            soundsEnabled: Boolean,
            honorSoftTapEnabled: Boolean = false,
        ) = HonorSettings(
            voicesEnabled = honorVoicesEnabled && soundsEnabled,
            softTapEnabled = honorSoftTapEnabled,
        )
    }
}
