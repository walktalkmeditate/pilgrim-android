// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import kotlin.math.abs

/**
 * Viewport-center crossing detector. Tracks `lastTriggeredDot` and
 * `lastTriggeredMilestone` to dedup; rearms when the center exits the
 * detection window (Stage 3-C closing-review pattern). Reduce-motion
 * gating happens downstream in `JournalHapticDispatcher`.
 *
 * Thresholds default to verbatim iOS values (20 px dot, 25 px milestone,
 * 15 px large-dot cutoff).
 */
class ScrollHapticState(
    private val dotPositionsPx: List<Float>,
    private val dotSizesPx: List<Float>,
    private val milestonePositionsPx: List<Float>,
    private val largeDotCutoffPx: Float = 15f,
    private val dotThresholdPx: Float = 20f,
    private val milestoneThresholdPx: Float = 25f,
) {
    private var lastTriggeredDot: Int? = null
    private var lastTriggeredMilestone: Int? = null

    fun handleViewportCenterPx(centerPx: Float): HapticEvent {
        // Milestone first — they're more important.
        var milestoneIndex: Int? = null
        for (i in milestonePositionsPx.indices) {
            if (abs(milestonePositionsPx[i] - centerPx) <= milestoneThresholdPx) {
                milestoneIndex = i
                break
            }
        }
        if (milestoneIndex != null) {
            if (milestoneIndex != lastTriggeredMilestone) {
                lastTriggeredMilestone = milestoneIndex
                return HapticEvent.Milestone(milestoneIndex)
            }
        } else {
            lastTriggeredMilestone = null
        }

        var dotIndex: Int? = null
        for (i in dotPositionsPx.indices) {
            if (abs(dotPositionsPx[i] - centerPx) <= dotThresholdPx) {
                dotIndex = i
                break
            }
        }
        if (dotIndex == null) {
            lastTriggeredDot = null
            return HapticEvent.None
        }
        if (dotIndex == lastTriggeredDot) return HapticEvent.None
        lastTriggeredDot = dotIndex
        val isLarge = dotSizesPx.getOrNull(dotIndex)?.let { it > largeDotCutoffPx } == true
        return if (isLarge) HapticEvent.HeavyDot(dotIndex) else HapticEvent.LightDot(dotIndex)
    }
}
