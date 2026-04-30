// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Pure math used by both the cache writer ([WalkMetricsCache]) and the
 * cache-fallback reader ([org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageConverter]).
 *
 * Stage 11-A spec review CRITICAL #2 mandate: live-compute and cached
 * paths must produce byte-identical meditation values so that
 * `meditationSeconds == null` rows export the same number a populated
 * cache row would.
 *
 * Both meditation paths apply the iOS clamp `min(rawMeditate, activeDuration)`
 * (NewWalk.swift:42) so corrupt walks (a 50-min MEDITATING interval on
 * an 18-min active wall clock) cannot inflate exported time beyond what
 * the user actually walked.
 */
internal object WalkMetricsMath {

    /**
     * Sum of MEDITATING [ActivityInterval] durations, clamped to the
     * walk's active duration. Negative interval spans are coerced to 0.
     */
    fun computeMeditationSeconds(
        intervals: List<ActivityInterval>,
        walk: Walk,
        events: List<WalkEvent>,
    ): Long {
        val rawMillis = intervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .sumOf { (it.endTimestamp - it.startTimestamp).coerceAtLeast(0L) }
        val rawSeconds = rawMillis / 1_000L
        val activeDurationSeconds = computeActiveDurationSeconds(walk, events)
        return rawSeconds.coerceAtMost(activeDurationSeconds).coerceAtLeast(0L)
    }

    /**
     * Active duration in seconds = wall-clock duration minus the sum of
     * paused gaps. A paused gap is the elapsed time between a PAUSED
     * event and its matching RESUMED event; an unpaired trailing PAUSED
     * is closed at the walk's `endTimestamp`. Returns 0 for in-progress
     * walks.
     */
    fun computeActiveDurationSeconds(walk: Walk, events: List<WalkEvent>): Long {
        val end = walk.endTimestamp ?: return 0L
        val wallClockMs = (end - walk.startTimestamp).coerceAtLeast(0L)
        var pausedSinceMs: Long? = null
        var pausedTotalMs = 0L
        for (event in events.sortedBy { it.timestamp }) {
            when (event.eventType) {
                WalkEventType.PAUSED -> if (pausedSinceMs == null) pausedSinceMs = event.timestamp
                WalkEventType.RESUMED -> {
                    val pausedAt = pausedSinceMs ?: continue
                    pausedTotalMs += (event.timestamp - pausedAt).coerceAtLeast(0L)
                    pausedSinceMs = null
                }
                else -> Unit
            }
        }
        pausedSinceMs?.let { pausedTotalMs += (end - it).coerceAtLeast(0L) }
        return ((wallClockMs - pausedTotalMs).coerceAtLeast(0L)) / 1_000L
    }
}
