// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

internal enum class TimelineSegmentType { Talking, Meditating }

/**
 * Pure data for one segment on the activity timeline bar. iOS-faithful
 * port of `ActivityTimelineBar.Segment` (`ActivityTimelineBar.swift:195-262`).
 *
 * `startFraction` and `widthFraction` are clamped to `[0, 1]` so segments
 * that started before the walk OR ended after it lay out within the bar's
 * visible bounds.
 */
@Immutable
internal data class TimelineSegment(
    val id: Int,
    val type: TimelineSegmentType,
    val startFraction: Float,
    val widthFraction: Float,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Build the timeline-bar segments. Meditations + voice recordings combined,
 * sorted by start timestamp. Out-of-range intervals get clamped to the bar's
 * span. Pure function — no Compose dependency.
 */
internal fun computeTimelineSegments(
    startMs: Long,
    endMs: Long,
    meditations: List<ActivityInterval>,
    recordings: List<VoiceRecording>,
): List<TimelineSegment> {
    val totalMs = (endMs - startMs).coerceAtLeast(1L).toFloat()
    val out = mutableListOf<TimelineSegment>()
    var nextId = 0

    for (m in meditations) {
        if (m.activityType != ActivityType.MEDITATING) continue
        val (sf, wf) = clampFraction(m.startTimestamp, m.endTimestamp, startMs, totalMs)
        if (wf <= 0f) continue
        out += TimelineSegment(
            id = nextId++,
            type = TimelineSegmentType.Meditating,
            startFraction = sf,
            widthFraction = wf,
            startMillis = m.startTimestamp,
            endMillis = m.endTimestamp,
        )
    }
    for (r in recordings) {
        val (sf, wf) = clampFraction(r.startTimestamp, r.endTimestamp, startMs, totalMs)
        if (wf <= 0f) continue
        out += TimelineSegment(
            id = nextId++,
            type = TimelineSegmentType.Talking,
            startFraction = sf,
            widthFraction = wf,
            startMillis = r.startTimestamp,
            endMillis = r.endTimestamp,
        )
    }

    return out.sortedBy { it.startFraction }
}

private fun clampFraction(
    startTs: Long,
    endTs: Long,
    walkStartMs: Long,
    totalMs: Float,
): Pair<Float, Float> {
    val rawStart = (startTs - walkStartMs) / totalMs
    val rawEnd = (endTs - walkStartMs) / totalMs
    val sf = rawStart.coerceIn(0f, 1f)
    val ef = rawEnd.coerceIn(0f, 1f)
    val wf = (ef - sf).coerceAtLeast(0f)
    return sf to wf
}
