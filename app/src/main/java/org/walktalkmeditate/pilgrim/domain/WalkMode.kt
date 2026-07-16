// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * The contemplative posture for a walk session, ported from iOS.
 * [Wander] and [Seek] are functional; [Together] surfaces as
 * "coming soon" in the UI and has [isAvailable] = false. Seek
 * shipped with Phase 14 (U13 flip — iOS 42563b8 equivalent).
 */
enum class WalkMode {
    Wander, Together, Seek;

    val isAvailable: Boolean get() = this == Wander || this == Seek

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
