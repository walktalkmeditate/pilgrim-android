// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Pure math shared by the cache writer ([WalkMetricsCache]), the
 * cache-fallback reader ([org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageConverter]),
 * and the Walk Summary and share totals.
 *
 * Stage 11-A spec review CRITICAL #2 mandate: live-compute and cached
 * paths must produce byte-identical meditation values so that
 * `meditationSeconds == null` rows export the same number a populated
 * cache row would.
 *
 * Every meditation path applies the iOS clamp `min(rawMeditate, activeDuration)`
 * (NewWalk.swift:42) so corrupt walks (a 50-min sitting on an 18-min
 * active wall clock) cannot inflate time beyond what the user actually
 * walked. iOS's summary card and share payload read that same clamped
 * `meditateDuration`.
 */
internal object WalkMetricsMath {

    /**
     * Total of the walk's sittings — derived from [events] by
     * [deriveActivityIntervals], closing an open sitting at the walk's
     * end — in whole seconds, clamped to the walk's active duration.
     * `activity_intervals` rows are never read: `walk_events` is the one
     * source of sittings (#223).
     */
    fun computeMeditationSeconds(walk: Walk, events: List<WalkEvent>): Long {
        val sittings = deriveActivityIntervals(events, walkId = walk.id, closeAt = walk.endTimestamp)
        val rawSeconds = sittings.sumOf { it.endTimestamp - it.startTimestamp } / 1_000L
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
