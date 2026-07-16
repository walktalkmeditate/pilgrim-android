// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestones

/**
 * Which kind of gate a walk stands at. Port of iOS `WalkThreshold`
 * (`HomeViewModel.swift:34-38@c1745e8`). Practice gates (first walk,
 * every tenth) stand vermilion; seeking gates (first unknown, unknown
 * milestones) stand weathered stone.
 */
enum class WalkThreshold { Practice, Seeking }

/**
 * Pure chronological threshold computation — the accumulator loop iOS
 * runs inline in `HomeViewModel.buildSnapshots`
 * (`HomeViewModel.swift:85-113@c1745e8`), extracted so it's testable
 * without Room. Thresholds are recomputed from history on every
 * snapshot build, never stored.
 */
object WalkThresholds {

    /** The identity + ordering fields the computation needs per walk. */
    data class WalkRef(
        val walkId: Long,
        val uuid: String,
        val startMs: Long,
    )

    /**
     * Total order induced by [GoshuinMilestones.isOrderedBefore]:
     * chronological with the stable uuid tie-break, so the journal's
     * `arrivalsBefore` can never disagree with the goshuin seals'
     * on two walks sharing a start timestamp.
     */
    private val chronological = Comparator<WalkRef> { a, b ->
        when {
            GoshuinMilestones.isOrderedBefore(a.startMs, a.uuid, b.startMs, b.uuid) -> -1
            GoshuinMilestones.isOrderedBefore(b.startMs, b.uuid, a.startMs, a.uuid) -> 1
            else -> 0
        }
    }

    /**
     * Per-walk gate kinds, keyed by walk id; walks without a gate are
     * absent. [walks] may arrive in any order — ordering is pinned
     * internally. [foundPlacesByWalkId] is the U12
     * [GoshuinMilestones.arrivalCounts] output (zero counts omitted).
     *
     * `Seeking` when the walk's own arrivals produce any seeking
     * milestone against the arrivals accumulated over strictly-earlier
     * walks (self excluded — a walk's own count joins the accumulator
     * only after its threshold is decided). Else `Practice` for walk #1
     * and every 10th. Mystery outranks routine: a tenth walk that also
     * found its first unknown stands at a seeking gate.
     */
    fun compute(
        walks: List<WalkRef>,
        foundPlacesByWalkId: Map<Long, Int>,
    ): Map<Long, WalkThreshold> {
        val thresholds = mutableMapOf<Long, WalkThreshold>()
        var arrivalsBefore = 0
        walks.sortedWith(chronological).forEachIndexed { index, walk ->
            val walkNumber = index + 1
            val foundPlaces = foundPlacesByWalkId[walk.walkId] ?: 0
            val crossesSeekingMilestone = GoshuinMilestones.seekingMilestones(
                arrivalsInWalk = foundPlaces,
                arrivalsBefore = arrivalsBefore,
            ).isNotEmpty()
            when {
                crossesSeekingMilestone -> thresholds[walk.walkId] = WalkThreshold.Seeking
                walkNumber == 1 || walkNumber % 10 == 0 ->
                    thresholds[walk.walkId] = WalkThreshold.Practice
            }
            arrivalsBefore += foundPlaces
        }
        return thresholds
    }
}
