// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao

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
        val meditation = WalkMetricsMath.computeMeditationSeconds(intervals, walk, events)
        walkDao.updateAggregates(walkId, distance, meditation)
    }
}
