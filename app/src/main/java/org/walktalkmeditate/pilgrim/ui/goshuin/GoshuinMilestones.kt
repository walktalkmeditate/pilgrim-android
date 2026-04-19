// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

/**
 * Per-walk fields needed by [GoshuinMilestones.detect]. A small DTO
 * (rather than the full `Walk` Room entity) so detection tests don't
 * need to instantiate Room.
 */
data class WalkMilestoneInput(
    val walkId: Long,
    val uuid: String,
    val startTimestamp: Long,
    val distanceMeters: Double,
)

/**
 * Pure milestone detector. Ported from iOS's `GoshuinMilestones.swift`.
 *
 * Returns the highest-precedence milestone for the walk at [walkIndex]
 * (0-based, most-recent-first within [allFinished]) — or `null` when
 * no milestone applies. Multiple simultaneous milestones (e.g., walk
 * #10 is also the longest) are resolved by explicit precedence:
 *
 *   1. [GoshuinMilestone.FirstWalk]
 *   2. [GoshuinMilestone.LongestWalk]
 *   3. [GoshuinMilestone.NthWalk]
 *   4. [GoshuinMilestone.FirstOfSeason]
 *
 * iOS uses `Set<Milestone>.first` which depends on Swift's hash-based
 * iteration order — non-deterministic across processes. Android fixes
 * this with explicit precedence above.
 */
object GoshuinMilestones {

    fun detect(
        walkIndex: Int,
        walk: WalkMilestoneInput,
        allFinished: List<WalkMilestoneInput>,
        hemisphere: Hemisphere,
    ): GoshuinMilestone? {
        // walkNumber is 1-based, where walkIndex 0 = newest = highest
        // walkNumber. iOS computed walkNumber = walkIndex + 1 from the
        // OLDEST-first page-view loop; same effective number expressed
        // via the most-recent-first list this codebase uses.
        val walkNumber = allFinished.size - walkIndex

        if (walkNumber == 1) return GoshuinMilestone.FirstWalk

        // LongestWalk precedes NthWalk so a walk that hits both shows
        // the more meaningful "Longest Walk" label. Tie-break: when two
        // walks share the same max distance, the most recent (lower
        // index) wins via `maxByOrNull`'s stable first-match semantics.
        //
        // The `maxDistance > 0.0` guard prevents a spurious "Longest
        // Walk" award when every finished walk has distance = 0 (e.g.,
        // a user with 2 short indoor sessions / GPS denied — without
        // the guard, walk #2 would win the all-zero tie-break and get
        // a celebration halo for a 0-meter walk).
        val maxDistance = allFinished.maxOf { it.distanceMeters }
        val longestId = allFinished.maxByOrNull { it.distanceMeters }?.walkId
        if (longestId == walk.walkId && allFinished.size > 1 && maxDistance > 0.0) {
            return GoshuinMilestone.LongestWalk
        }

        if (walkNumber > 0 && walkNumber % 10 == 0) {
            return GoshuinMilestone.NthWalk(walkNumber)
        }

        // FirstOfSeason: no other walk in the same season+year came
        // before this one. iOS's `Calendar.current.component` uses the
        // local-time year; we mirror with `ZoneId.systemDefault()`.
        val zone = ZoneId.systemDefault()
        val walkSeason = seasonFor(walk.startTimestamp, hemisphere)
        val walkYear = Instant.ofEpochMilli(walk.startTimestamp).atZone(zone).year
        val hasEarlierInSeason = allFinished.any { other ->
            other.walkId != walk.walkId &&
                other.startTimestamp < walk.startTimestamp &&
                seasonFor(other.startTimestamp, hemisphere) == walkSeason &&
                Instant.ofEpochMilli(other.startTimestamp).atZone(zone).year == walkYear
        }
        if (!hasEarlierInSeason) {
            return GoshuinMilestone.FirstOfSeason(walkSeason)
        }

        return null
    }

    /**
     * Month-based season selector. Mirrors iOS's
     * `SealTimeHelpers.season(for:latitude:)`. Uses the device-level
     * [hemisphere] (not the walk's own latitude) so a walk that
     * happened on the user's vacation in Sydney still computes its
     * season against the user's home hemisphere — matches the rest of
     * this app's seasonal-color and journal-tinting behavior.
     */
    fun seasonFor(timestampMs: Long, hemisphere: Hemisphere): Season {
        val month = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .monthValue
        val northern = hemisphere == Hemisphere.Northern
        return when (month) {
            3, 4, 5 -> if (northern) Season.Spring else Season.Autumn
            6, 7, 8 -> if (northern) Season.Summer else Season.Winter
            9, 10, 11 -> if (northern) Season.Autumn else Season.Spring
            else -> if (northern) Season.Winter else Season.Summer
        }
    }

    /**
     * Stable English label for the cell + reveal-overlay surfaces.
     * CLAUDE.md specifies English-only baseline; localization
     * (Stage 10) will re-route through `R.string.*` resources.
     */
    fun label(milestone: GoshuinMilestone): String = when (milestone) {
        GoshuinMilestone.FirstWalk -> "First Walk"
        GoshuinMilestone.LongestWalk -> "Longest Walk"
        is GoshuinMilestone.NthWalk -> "${ordinal(milestone.n)} Walk"
        is GoshuinMilestone.FirstOfSeason -> "First of ${seasonLabel(milestone.season)}"
    }

    private fun seasonLabel(season: Season): String = when (season) {
        Season.Spring -> "Spring"
        Season.Summer -> "Summer"
        Season.Autumn -> "Autumn"
        Season.Winter -> "Winter"
    }

    /**
     * 1 → "1st", 2 → "2nd", 3 → "3rd", 4 → "4th", 11 → "11th",
     * 21 → "21st", 100 → "100th". Matches iOS's `ordinal(_:)`.
     */
    internal fun ordinal(n: Int): String {
        val tens = (n / 10) % 10
        val ones = n % 10
        val suffix = if (tens == 1) {
            "th"
        } else when (ones) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }
}
