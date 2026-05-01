// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

/**
 * Pin marker on the post-walk map. iOS-faithful port of
 * `PilgrimAnnotation.Kind` (subset — photo / whisper / cairn pins
 * deferred to later stages).
 *
 * `@Immutable` on the sealed parent + each data subkind so Compose's
 * stability inference walks the `WalkMapAnnotation.kind` field through
 * — without it, `WalkMapAnnotation`'s own `@Immutable` annotation is a
 * lie that would silently mask a real stability regression if a future
 * subkind added a `List`/`Map` field. Stage 4-C / 7-C cascade lesson.
 */
@Immutable
sealed class WalkMapAnnotationKind {
    @Immutable data object StartPoint : WalkMapAnnotationKind()
    @Immutable data object EndPoint : WalkMapAnnotationKind()
    @Immutable data class Meditation(val durationMillis: Long) : WalkMapAnnotationKind()
    @Immutable data class VoiceRecording(val durationMillis: Long) : WalkMapAnnotationKind()
}

@Immutable
data class WalkMapAnnotation(
    val kind: WalkMapAnnotationKind,
    val latitude: Double,
    val longitude: Double,
)

/**
 * Build the Walk Summary map's pin set. Verbatim port of iOS
 * `WalkSummaryView.computeAnnotations` (`WalkSummaryView.swift:863-891`):
 *   - Start pin at first GPS sample.
 *   - End pin at last GPS sample (only when route has > 1 sample).
 *   - Meditation pin at the GPS sample closest in time to each
 *     meditation interval's start.
 *   - Voice recording pin at the GPS sample closest in time to each
 *     recording's start.
 *
 * Returns empty when the route is empty (cannot place start/end without
 * GPS). Pure function — caller is responsible for ordering samples by
 * timestamp (Room's DAO already does).
 */
fun computeWalkMapAnnotations(
    routeSamples: List<RouteDataSample>,
    meditationIntervals: List<ActivityInterval>,
    voiceRecordings: List<VoiceRecording>,
): List<WalkMapAnnotation> {
    if (routeSamples.isEmpty()) return emptyList()
    val out = mutableListOf<WalkMapAnnotation>()

    val first = routeSamples.first()
    out += WalkMapAnnotation(WalkMapAnnotationKind.StartPoint, first.latitude, first.longitude)

    if (routeSamples.size > 1) {
        val last = routeSamples.last()
        out += WalkMapAnnotation(WalkMapAnnotationKind.EndPoint, last.latitude, last.longitude)
    }

    for (m in meditationIntervals) {
        if (m.activityType != ActivityType.MEDITATING) continue
        val closest = routeSamples.minByOrNull { abs(it.timestamp - m.startTimestamp) }
            ?: continue
        out += WalkMapAnnotation(
            kind = WalkMapAnnotationKind.Meditation(m.endTimestamp - m.startTimestamp),
            latitude = closest.latitude,
            longitude = closest.longitude,
        )
    }

    for (r in voiceRecordings) {
        val closest = routeSamples.minByOrNull { abs(it.timestamp - r.startTimestamp) }
            ?: continue
        out += WalkMapAnnotation(
            kind = WalkMapAnnotationKind.VoiceRecording(r.durationMillis),
            latitude = closest.latitude,
            longitude = closest.longitude,
        )
    }

    return out
}
