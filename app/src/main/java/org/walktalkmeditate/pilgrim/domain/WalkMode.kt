// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * The contemplative posture for a walk session, ported from iOS.
 * Only [Wander] is functional today; [Together] and [Seek] surface
 * as "coming soon" in the UI and have [isAvailable] = false. Future
 * stages may differentiate behavior per mode (group walks for
 * Together, exploration prompts for Seek). For now the enum exists
 * solely to drive the Path-tab mode selector's affordances.
 */
enum class WalkMode {
    Wander, Together, Seek;

    val isAvailable: Boolean get() = this == Wander

    companion object {
        /**
         * Forgiving parse for wire values (nav arguments, service intent
         * extras). Unknown or absent values collapse to [Wander] so a
         * stale intent from a future binary can never crash the start
         * path — mirrors the UNKNOWN convention in
         * [org.walktalkmeditate.pilgrim.domain.WalkEventType].
         */
        fun fromWire(value: String?): WalkMode =
            entries.firstOrNull { it.name == value } ?: Wander
    }
}
