// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.runtime.Immutable

/**
 * A walk-history milestone. Detected by [GoshuinMilestones.detect]
 * from the per-walk index + the full finished-walks snapshot. Surfaced
 * on the goshuin grid (halo + label) and on the seal-reveal overlay
 * (2-pulse haptic + extra hold).
 *
 * Ports all 9 of iOS's `GoshuinMilestones.Milestone` cases
 * (`GoshuinMilestones.swift:5-19@7c200bf`).
 *
 * `@Immutable` for Compose stability — the class hierarchy contains
 * only stable types ([Season] enum, [Int]) but the Compose compiler
 * doesn't auto-infer stability for sealed-class hierarchies without
 * the explicit annotation. Same lesson as Stage 4-C `GoshuinSeal`.
 */
@Immutable
sealed class GoshuinMilestone {
    data object FirstWalk : GoshuinMilestone()
    data object LongestWalk : GoshuinMilestone()
    data object LongestMeditation : GoshuinMilestone()
    data class NthWalk(val n: Int) : GoshuinMilestone()
    data class FirstOfSeason(val season: Season) : GoshuinMilestone()

    /**
     * Seeking thresholds (iOS `firstUnknown` / `unknownsFound(Int)`):
     * the walk that carried the first found place, and the walks whose
     * arrivals crossed a lifetime count in
     * [GoshuinMilestones.unknownThresholds].
     */
    data object FirstUnknown : GoshuinMilestone()
    data class UnknownsFound(val count: Int) : GoshuinMilestone()

    /**
     * Honor thresholds (iOS `firstHonor` / `honorsWalked(Int)`): the walk
     * that carried the first Way walked to its end, and the walks whose
     * Way arrivals crossed a lifetime count in
     * [GoshuinMilestones.honorThresholds].
     */
    data object FirstHonor : GoshuinMilestone()
    data class HonorsWalked(val count: Int) : GoshuinMilestone()
}

/**
 * Hemisphere-aware season. Computed from the walk's start month + the
 * walk's own first-route-coordinate latitude via
 * [GoshuinMilestones.seasonFor]. Mirrors iOS's
 * `SealTimeHelpers.season(for:latitude:)`.
 */
enum class Season { Spring, Summer, Autumn, Winter }
