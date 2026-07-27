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
     * paused gaps ([pauseSpans]). Returns 0 for in-progress walks.
     */
    fun computeActiveDurationSeconds(walk: Walk, events: List<WalkEvent>): Long {
        val end = walk.endTimestamp ?: return 0L
        val wallClockMs = (end - walk.startTimestamp).coerceAtLeast(0L)
        val pausedTotalMs = pauseSpans(walk, events).sumOf { it.durationMillis }
        return ((wallClockMs - pausedTotalMs).coerceAtLeast(0L)) / 1_000L
    }

    /** One paused stretch, in epoch millis. Negative spans coerce to 0. */
    data class PauseSpan(val startMs: Long, val durationMillis: Long)

    /**
     * The single PAUSED/RESUMED pairing automaton: the first PAUSED
     * opens a span, its RESUMED closes it, an unmatched RESUMED is
     * ignored, and an unpaired trailing PAUSED closes at the walk's
     * `endTimestamp` — dropped entirely while the walk is still open
     * (closed pairs are still returned for open walks). Shared by
     * [computeActiveDurationSeconds] and the prompt pipeline's
     * pause-context builder so the two can never drift.
     */
    fun pauseSpans(walk: Walk, events: List<WalkEvent>): List<PauseSpan> {
        val spans = mutableListOf<PauseSpan>()
        var pausedSinceMs: Long? = null
        for (event in events.sortedBy { it.timestamp }) {
            when (event.eventType) {
                WalkEventType.PAUSED -> if (pausedSinceMs == null) pausedSinceMs = event.timestamp
                WalkEventType.RESUMED -> {
                    val pausedAt = pausedSinceMs ?: continue
                    spans += PauseSpan(
                        startMs = pausedAt,
                        durationMillis = (event.timestamp - pausedAt).coerceAtLeast(0L),
                    )
                    pausedSinceMs = null
                }
                else -> Unit
            }
        }
        val end = walk.endTimestamp
        val pausedAt = pausedSinceMs
        if (end != null && pausedAt != null) {
            spans += PauseSpan(
                startMs = pausedAt,
                durationMillis = (end - pausedAt).coerceAtLeast(0L),
            )
        }
        return spans
    }
}
