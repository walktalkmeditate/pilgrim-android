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
}
