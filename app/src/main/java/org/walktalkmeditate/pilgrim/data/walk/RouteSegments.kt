// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Activity classification for a continuous run of route samples on the
 * Walk Summary map. Each segment renders as one colored polyline.
 *
 * Priority on overlap (matches iOS `WalkSummaryView.activityType`,
 * `WalkSummaryView.swift:893-902`):
 *   Meditating > Talking > Walking
 *
 * I.e., a sample whose timestamp falls inside both a meditation interval
 * and a voice recording is classified as Meditating.
 */
enum class RouteActivity { Walking, Talking, Meditating }

@Immutable
data class RouteSegment(
    val activity: RouteActivity,
    val points: List<LocationPoint>,
)

/**
 * Walk the [samples] in timestamp order and group consecutive runs of
 * identical activity into [RouteSegment]s. Boundary points (the sample
 * where the activity changes) are duplicated across the two adjacent
 * segments so the rendered polylines connect seamlessly.
 *
 * Returns an empty list when fewer than 2 samples exist (single point
 * cannot draw a polyline; matches iOS `computeSegments` guard).
 *
 * Pure function — caller is responsible for ordering samples by
 * `timestamp` if needed (Room's `getForWalk` already does this via the
 * DAO's `ORDER BY timestamp` clause).
 */
fun computeRouteSegments(
    samples: List<RouteDataSample>,
    intervals: List<ActivityInterval>,
    recordings: List<VoiceRecording>,
): List<RouteSegment> {
    if (samples.size < 2) return emptyList()

    val meditationIntervals = intervals.filter { it.activityType == ActivityType.MEDITATING }
    val classified = samples.map { classify(it.timestamp, meditationIntervals, recordings) }

    val segments = mutableListOf<RouteSegment>()
    var currentActivity = classified[0]
    var currentIndices = mutableListOf(0)

    for (i in 1 until samples.size) {
        val activity = classified[i]
        if (activity == currentActivity) {
            currentIndices.add(i)
        } else {
            // Boundary point sits in BOTH segments so the rendered
            // polylines connect rather than leaving a visible gap at
            // the activity transition.
            currentIndices.add(i)
            segments.add(buildSegment(currentActivity, currentIndices, samples))
            currentActivity = activity
            currentIndices = mutableListOf(i)
        }
    }
    segments.add(buildSegment(currentActivity, currentIndices, samples))

    return segments
}

/**
 * Reorders [segments] (as returned by [computeRouteSegments], in
 * chronological order) for polyline PAINT order: every Walking segment
 * first, then every Talking segment, then every Meditating segment.
 * `sortedBy` is stable, so segments sharing a priority keep their
 * relative (chronological) order.
 *
 * Callers still need [computeRouteSegments]'s chronological order for
 * everything else (the activity timeline bar, segment-tap-zoom) — this
 * function exists only for the renderer, which creates one polyline
 * annotation per segment and paints later-created annotations on top.
 *
 * A route that doubles back on itself (an out-and-back, a loop, a
 * meandering path) can have a chronologically-LATER Walking stretch
 * retrace the exact GPS coordinates of an EARLIER Talking or Meditating
 * stretch. Painting strictly in chronological order then lets that
 * later Walking polyline visually bury the earlier rust/dawn tint at
 * the overlap, even though [classify] tagged every sample correctly.
 * Round-2 QA (2026-08-18), device walk id=3: a 43s talk survived
 * classification (confirmed by a dedicated fixture test) but rendered
 * as plain "moss" because the walker re-crossed those coordinates ~29
 * minutes later on the way back. Applying [classify]'s existing overlap
 * priority (Meditating > Talking > Walking) to paint order — instead of
 * only to same-timestamp classification — guarantees the
 * higher-priority tint always wins the overlap, regardless of which
 * segment is chronologically later.
 */
fun routeSegmentsInPaintOrder(segments: List<RouteSegment>): List<RouteSegment> =
    segments.sortedBy { it.activity.paintPriority }

private val RouteActivity.paintPriority: Int
    get() = when (this) {
        RouteActivity.Walking -> 0
        RouteActivity.Talking -> 1
        RouteActivity.Meditating -> 2
    }

private fun classify(
    timestampMs: Long,
    meditationIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): RouteActivity = when {
    meditationIntervals.any { iv ->
        timestampMs in iv.startTimestamp..iv.endTimestamp
    } -> RouteActivity.Meditating
    voiceRecordings.any { rec ->
        timestampMs in rec.startTimestamp..rec.endTimestamp
    } -> RouteActivity.Talking
    else -> RouteActivity.Walking
}

private fun buildSegment(
    activity: RouteActivity,
    indices: List<Int>,
    samples: List<RouteDataSample>,
): RouteSegment = RouteSegment(
    activity = activity,
    points = indices.map { i ->
        LocationPoint(
            timestamp = samples[i].timestamp,
            latitude = samples[i].latitude,
            longitude = samples[i].longitude,
        )
    },
)
