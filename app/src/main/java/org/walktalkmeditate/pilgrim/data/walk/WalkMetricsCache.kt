// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

/**
 * Surface used by [WalkFinalizationObserver] (Stage 11-A) and tests so
 * the concrete [WalkMetricsCache] can be faked at the boundary without
 * subclassing the `@Singleton` implementation.
 *
 * Stage 11 splits walk-summary maths off the hot UI path: the cache
 * fills the `distance_meters` and `meditation_seconds` cache columns on
 * [Walk] when the walk transitions out of the active state, so reads
 * (Walk Summary, milestone trigger, package export fallback) never
 * traverse `RouteDataSamples`/`ActivityIntervals`/`WalkEvents` again.
 */
interface WalkMetricsCaching {
    suspend fun computeAndPersist(walkId: Long)
}

/**
 * Computes distance and meditation aggregates for a finished walk and
 * writes them to the cache columns on [Walk] via [WalkDao.updateAggregates].
 *
 * - Distance: cumulative haversine over [WalkRepository.locationSamplesFor],
 *   delegated to [WalkDistanceCalculator].
 * - Meditation: raw sum of MEDITATING [ActivityInterval] durations,
 *   clamped to the walk's active duration (wall-clock minus paused gaps).
 *   Mirrors iOS `NewWalk.swift:42` `min(rawMeditate, activeDuration)`.
 *
 * Skips in-progress walks (`endTimestamp == null`) — the finalize hook
 * only forwards finished walks, but the future backfill coordinator in
 * Task 7 reuses this method and depends on the same guard.
 */
@Singleton
class WalkMetricsCache @Inject constructor(
    private val walkRepository: WalkRepository,
    private val walkDao: WalkDao,
    private val walkEventDao: WalkEventDao,
) : WalkMetricsCaching {

    override suspend fun computeAndPersist(walkId: Long) {
        val walk = walkDao.getById(walkId) ?: return
        if (walk.endTimestamp == null) return

        val samples = walkRepository.locationSamplesFor(walkId)
        val intervals = walkRepository.activityIntervalsFor(walkId)
        val events = walkEventDao.getForWalk(walkId)

        val distance = WalkDistanceCalculator.computeDistanceMeters(samples)
        val meditation = computeMeditationSeconds(intervals, walk, events)
        walkDao.updateAggregates(walkId, distance, meditation)
    }

    /**
     * Sum of MEDITATING [ActivityInterval] durations, clamped to active
     * duration. Mirrors iOS `NewWalk.swift:42` `min(rawMeditate, activeDuration)`.
     *
     * `internal` so [PilgrimPackageConverter] (Task 8) can reuse this
     * exact clamp on the fallback path when the cache column is null
     * (legacy walks that pre-date the cache backfill).
     */
    internal fun computeMeditationSeconds(
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
     * walks (defense-in-depth — the public entry point already guards).
     */
    internal fun computeActiveDurationSeconds(walk: Walk, events: List<WalkEvent>): Long {
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
