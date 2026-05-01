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
