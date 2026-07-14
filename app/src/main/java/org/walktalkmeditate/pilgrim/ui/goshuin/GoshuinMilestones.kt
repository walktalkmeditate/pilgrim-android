// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

/**
 * Per-walk fields needed by [GoshuinMilestones.detect]. A small DTO
 * (rather than the full `Walk` Room entity) so detection tests don't
 * need to instantiate Room. Android analogue of iOS `SealInput`.
 */
data class WalkMilestoneInput(
    val walkId: Long,
    val uuid: String,
    val startTimestamp: Long,
    val distanceMeters: Double,
    val meditateDurationMillis: Long = 0L,
    /**
     * Latitude of the walk's first GPS sample (0.0 if no route). The
     * walk's season is computed against THIS, matching iOS
     * `GoshuinMilestones` (`walk.routeData.first?.latitude`).
     */
    val latitude: Double = 0.0,
    /**
     * Seek arrivals recorded on this walk (reserved-icon waypoints),
     * for the seeking milestones. Mirrors iOS
     * `SealInput.foundPlaceCount`; populated from
     * [GoshuinMilestones.arrivalCounts]. Default 0 = wander walk or
     * caller that doesn't award seeking seals.
     */
    val foundPlaceCount: Int = 0,
)

/**
 * Pure milestone detector. Ported from iOS's `GoshuinMilestones.swift`.
 *
 * Returns the primary milestone for the walk at [walkIndex] (0-based,
 * most-recent-first within [allFinished]) — or `null` when no
 * milestone applies. Multiple simultaneous milestones (e.g., walk #10
 * is also the longest) are resolved by [primaryMilestone]'s
 * display-priority table (iOS `displayPriority`/`intraPriority`):
 * once-ever moments outrank threshold crossings outrank recurring and
 * transient records; within a parameterized tier the largest count is
 * the headline.
 *
 * Shape divergence from iOS: `detect` there returns the full
 * `Set<Milestone>` and every consumer immediately reduces it (halo on
 * `!isEmpty`, caption/share label via `primaryMilestone`). Android
 * returns the primary directly — same observable output on every
 * surface, no churn for `GoshuinSeal.milestone` consumers.
 *
 * **List-ordering note:** callers pass [allFinished] sorted by
 * `endTimestamp DESC` (walk completion order) because the goshuin
 * grid and summary paths both present walks most-recently-finished
 * first. The [walkIndex]-derived `walkNumber` for [FirstWalk] /
 * [NthWalk] therefore reflects that order. In contrast, the
 * [FirstOfSeason] check compares `startTimestamp` (walk beginning)
 * — the two orderings can diverge for a walk that was paused
 * overnight (started one day, ended the next). In practice the
 * disagreement only affects whether a rare overnight walk is the
 * "first of season"; all other milestone outputs are unaffected.
 */
object GoshuinMilestones {

    /**
     * Lifetime found-place counts that earn a seal. iOS
     * `unknownThresholds` (`GoshuinMilestones.swift:18@c1745e8`).
     */
    val unknownThresholds: List<Int> = listOf(10, 25, 50, 100)

    fun detect(
        walkIndex: Int,
        walk: WalkMilestoneInput,
        allFinished: List<WalkMilestoneInput>,
    ): GoshuinMilestone? {
        // Defensive guard: the function's production callers already
        // filter to non-empty `finished` lists, but keeping this check
        // at the entry point means a future caller (test, preview, new
        // feature) can pass `emptyList()` without crashing on
        // `allFinished.maxOf` below.
        if (allFinished.isEmpty()) return null

        val milestones = mutableSetOf<GoshuinMilestone>()

        // walkNumber is 1-based, where walkIndex 0 = newest = highest
        // walkNumber. iOS computed walkNumber = walkIndex + 1 from the
        // OLDEST-first page-view loop; same effective number expressed
        // via the most-recent-first list this codebase uses.
        val walkNumber = allFinished.size - walkIndex

        if (walkNumber == 1) milestones += GoshuinMilestone.FirstWalk

        if (walkNumber > 0 && walkNumber % 10 == 0) {
            milestones += GoshuinMilestone.NthWalk(walkNumber)
        }

        // LongestWalk tie-break: when two walks share the same max
        // distance, the most recent (lower index) wins via
        // `maxByOrNull`'s stable first-match semantics.
        //
        // The `maxDistance > 0.0` guard prevents a spurious "Longest
        // Walk" award when every finished walk has distance = 0 (e.g.,
        // a user with 2 short indoor sessions / GPS denied — without
        // the guard, walk #2 would win the all-zero tie-break and get
        // a celebration halo for a 0-meter walk).
        val maxDistance = allFinished.maxOf { it.distanceMeters }
        val longestId = allFinished.maxByOrNull { it.distanceMeters }?.walkId
        if (longestId == walk.walkId && allFinished.size > 1 && maxDistance > 0.0) {
            milestones += GoshuinMilestone.LongestWalk
        }

        // LongestMeditation: walk has the global max meditate duration AND
        // some walk has meditation > 0. Mirrors iOS goshuin-grid rule:
        // Set<Milestone>.insert when current walk == filter-positive max.
        // Distinct from the Walk Summary callout rule (which adds strict-
        // improvement-over-nonzero gate via WalkSummaryCalloutProse).
        val meditationCandidates = allFinished.filter { it.meditateDurationMillis > 0L }
        if (meditationCandidates.isNotEmpty()) {
            val maxMeditation = meditationCandidates.maxOf { it.meditateDurationMillis }
            val longestMedId = meditationCandidates.maxByOrNull { it.meditateDurationMillis }?.walkId
            if (longestMedId == walk.walkId && walk.meditateDurationMillis > 0L && walk.meditateDurationMillis == maxMeditation) {
                milestones += GoshuinMilestone.LongestMeditation
            }
        }

        // FirstOfSeason: no other walk in the same season+year came
        // before this one. iOS's `Calendar.current.component` uses the
        // local-time year; we mirror with `ZoneId.systemDefault()`.
        val zone = ZoneId.systemDefault()
        val walkSeason = seasonFor(walk.startTimestamp, walk.latitude)
        val walkYear = Instant.ofEpochMilli(walk.startTimestamp).atZone(zone).year
        val hasEarlierInSeason = allFinished.any { other ->
            other.walkId != walk.walkId &&
                other.startTimestamp < walk.startTimestamp &&
                seasonFor(other.startTimestamp, other.latitude) == walkSeason &&
                Instant.ofEpochMilli(other.startTimestamp).atZone(zone).year == walkYear
        }
        if (!hasEarlierInSeason) {
            milestones += GoshuinMilestone.FirstOfSeason(walkSeason)
        }

        // Seeking milestones — lifetime prior is the sum of arrivals on
        // walks strictly before this one, self excluded. Verbatim port
        // of the iOS SealInput-overload aggregation
        // (`GoshuinMilestones.swift:218-227@c1745e8`).
        if (walk.foundPlaceCount > 0) {
            val arrivalsBefore = allFinished
                .filter {
                    it.walkId != walk.walkId &&
                        isOrderedBefore(it.startTimestamp, it.uuid, walk.startTimestamp, walk.uuid)
                }
                .sumOf { it.foundPlaceCount }
            milestones += seekingMilestones(
                arrivalsInWalk = walk.foundPlaceCount,
                arrivalsBefore = arrivalsBefore,
            )
        }

        return primaryMilestone(milestones)
    }

    /**
     * Seeking milestones for a walk, from its own arrivals and the
     * lifetime count before it. Awarded to the walk that crosses the
     * threshold; a walk with no arrivals never earns one — including
     * FirstUnknown on a 0/0 tie (the Stage 4-D equal-value guard).
     * Verbatim port of iOS `seekingMilestones`
     * (`GoshuinMilestones.swift:75-89@c1745e8`).
     */
    fun seekingMilestones(arrivalsInWalk: Int, arrivalsBefore: Int): Set<GoshuinMilestone> {
        if (arrivalsInWalk <= 0) return emptySet()
        val milestones = mutableSetOf<GoshuinMilestone>()
        if (arrivalsBefore == 0) {
            milestones += GoshuinMilestone.FirstUnknown
        }
        val total = arrivalsBefore + arrivalsInWalk
        unknownThresholds
            .filter { arrivalsBefore < it && total >= it }
            .forEach { milestones += GoshuinMilestone.UnknownsFound(it) }
        return milestones
    }

    /**
     * One pure counting pass for the whole book: arrival-waypoint
     * counts per walk id, zero-count walks omitted. iOS
     * `arrivalCounts(for:)` walks the CoreData waypoint relationships
     * (`GoshuinMilestones.swift:51-63@c1745e8`); Android takes the
     * icons from `WalkRepository.waypointIconsByWalk()` (one query, no
     * per-walk faulting) and applies the same
     * [SeekPersistence.isArrivalWaypoint] predicate.
     */
    fun arrivalCounts(waypointIconsByWalk: Map<Long, List<String?>>): Map<Long, Int> =
        waypointIconsByWalk
            .mapValues { (_, icons) -> icons.count(SeekPersistence::isArrivalWaypoint) }
            .filterValues { it > 0 }

    /**
     * Strictly-before ordering with a stable uuid tie-break, so two
     * walks sharing a startTimestamp never both count as "before" each
     * other (a crossing seal would double-award) nor neither (it would
     * vanish). Port of iOS `isOrderedBefore`
     * (`GoshuinMilestones.swift:65-73@c1745e8`); uuids are non-null on
     * Android so the `?? ""` fallback drops out.
     */
    fun isOrderedBefore(
        lhsStartMs: Long,
        lhsUuid: String,
        rhsStartMs: Long,
        rhsUuid: String,
    ): Boolean {
        if (lhsStartMs != rhsStartMs) return lhsStartMs < rhsStartMs
        return lhsUuid < rhsUuid
    }

    /**
     * Deterministic caption selection when a walk earns several
     * milestones at once — Set iteration order must never pick the
     * displayed seal. Once-ever moments outrank threshold crossings
     * outrank recurring and transient records; within a parameterized
     * tier the largest count is the headline. Port of iOS
     * `primaryMilestone` (`GoshuinMilestones.swift:20-49@c1745e8`).
     */
    fun primaryMilestone(milestones: Set<GoshuinMilestone>): GoshuinMilestone? =
        milestones.minWithOrNull(
            compareBy({ displayPriority(it) }, { -intraPriority(it) }),
        )

    private fun displayPriority(milestone: GoshuinMilestone): Int = when (milestone) {
        GoshuinMilestone.FirstWalk -> 0
        GoshuinMilestone.FirstUnknown -> 1
        is GoshuinMilestone.UnknownsFound -> 2
        is GoshuinMilestone.NthWalk -> 3
        is GoshuinMilestone.FirstOfSeason -> 4
        GoshuinMilestone.LongestWalk -> 5
        GoshuinMilestone.LongestMeditation -> 6
    }

    private fun intraPriority(milestone: GoshuinMilestone): Int = when (milestone) {
        is GoshuinMilestone.NthWalk -> milestone.n
        is GoshuinMilestone.UnknownsFound -> milestone.count
        else -> 0
    }

    /**
     * Month-based season selector. Mirrors iOS's
     * `SealTimeHelpers.season(for:latitude:)`: the season is computed
     * against the walk's OWN [latitude] (its first route coordinate), so a
     * walk recorded in Sydney reads as summer in December even when the
     * user's home device is northern — full iOS parity. The device/home
     * hemisphere still drives the journal dots, summary kanji, and palette.
     */
    fun seasonFor(timestampMs: Long, latitude: Double): Season {
        val month = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .monthValue
        val northern = Hemisphere.fromLatitude(latitude) == Hemisphere.Northern
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
        GoshuinMilestone.LongestMeditation -> "Longest Meditation"
        is GoshuinMilestone.NthWalk -> "${ordinal(milestone.n)} Walk"
        is GoshuinMilestone.FirstOfSeason -> "First of ${seasonLabel(milestone.season)}"
        GoshuinMilestone.FirstUnknown -> "First Unknown"
        is GoshuinMilestone.UnknownsFound -> "${milestone.count} Unknowns"
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
