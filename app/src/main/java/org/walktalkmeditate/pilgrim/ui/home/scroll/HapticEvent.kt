// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

/** Single-shot haptic emitted as the viewport center crosses a dot or milestone. */
sealed class HapticEvent {
    data object None : HapticEvent()
    data class LightDot(val dotIndex: Int) : HapticEvent()
    data class HeavyDot(val dotIndex: Int) : HapticEvent()
    data class Milestone(val milestoneIndex: Int) : HapticEvent()
}
