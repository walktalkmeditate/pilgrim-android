// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * A closed activity span, in epoch millis, inclusive at both ends —
 * matching iOS's `timestamp >= start && timestamp <= end` comparisons in
 * `ActiveWalkViewModel.activityType(at:)@2ee1185:585,593`.
 *
 * An activity still in progress is NOT expressed as a window; it comes in
 * through the dedicated `live*StartMillis` parameters, because iOS closes
 * the in-progress case with an open-ended `timestamp >= start` test rather
 * than an interval containment test.
 */
@Immutable
data class ActivityWindow(val startTimestamp: Long, val endTimestamp: Long)

/**
 * Segment the live route so the Active Walk map can tint the stretch a
 * walker is talking or meditating through, in real time.
 *
 * Verbatim port of iOS `ActiveWalkViewModel.activityType(at:)@2ee1185:583-602`:
 * ```swift
 * for interval in meditationIntervals {
 *     if timestamp >= interval.startDate && timestamp <= interval.endDate { return "meditating" }
 * }
 * if let start = meditationStartDate, timestamp >= start { return "meditating" }
 * for recording in completedRecordings {
 *     if timestamp >= recording.startDate && timestamp <= recording.endDate { return "talking" }
 * }
 * if voiceRecordingManagement.isRecording,
 *    let recStart = voiceRecordingManagement.recordingStartDate,
 *    timestamp >= recStart { return "talking" }
 * return "walking"
 * ```
 * Meditation is tested before talking in both the completed and the live
 * case, which is the same Meditating > Talking > Walking precedence
 * [computeRouteSegments] applies on the summary map.
 *
 * Grouping — including duplicating the boundary fix into both adjacent
 * segments and the "fewer than two points draws nothing" guard — is shared
 * with the summary segmenter via [groupIntoSegments], so a stretch
 * classified live comes back identical once the walk is over.
 *
 * Pure function. Renderer-facing invariant: a segment that has closed
 * (anything but the last one) must be structurally identical on every
 * subsequent call as the route grows, because the renderer keeps its
 * polyline alive by equality rather than rebuilding the whole set per GPS
 * fix. Closing a live window preserves that — a fix classified `talking`
 * by `>= liveTalkStartMillis` still falls inside the closed window the
 * finished recording writes, since the recording ends after its last fix.
 */
fun computeLiveRouteSegments(
    points: List<LocationPoint>,
    meditationWindows: List<ActivityWindow>,
    liveMeditationStartMillis: Long?,
    talkWindows: List<ActivityWindow>,
    liveTalkStartMillis: Long?,
): List<RouteSegment> {
    if (points.size < 2) return emptyList()

    return groupIntoSegments(
        points = points,
        classified = points.map { point ->
            classifyLive(
                timestampMs = point.timestamp,
                meditationWindows = meditationWindows,
                liveMeditationStartMillis = liveMeditationStartMillis,
                talkWindows = talkWindows,
                liveTalkStartMillis = liveTalkStartMillis,
            )
        },
    )
}

private fun classifyLive(
    timestampMs: Long,
    meditationWindows: List<ActivityWindow>,
    liveMeditationStartMillis: Long?,
    talkWindows: List<ActivityWindow>,
    liveTalkStartMillis: Long?,
): RouteActivity = when {
    meditationWindows.any { timestampMs in it.startTimestamp..it.endTimestamp } ->
        RouteActivity.Meditating
    liveMeditationStartMillis != null && timestampMs >= liveMeditationStartMillis ->
        RouteActivity.Meditating
    talkWindows.any { timestampMs in it.startTimestamp..it.endTimestamp } ->
        RouteActivity.Talking
    liveTalkStartMillis != null && timestampMs >= liveTalkStartMillis ->
        RouteActivity.Talking
    else -> RouteActivity.Walking
}
