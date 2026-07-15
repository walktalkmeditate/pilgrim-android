// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import androidx.compose.runtime.Immutable
import java.time.Instant
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.core.celestial.SunCalc
import org.walktalkmeditate.pilgrim.data.cairn.CachedCairn
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight

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

    /**
     * iOS parity `PilgrimAnnotation.Kind.photo(localIdentifier:)`
     * (`WalkSummaryView+Map.swift:34-46@db4196e`). Renders a circular
     * photo thumbnail at the photo's EXIF GPS coordinates. Tapping a
     * photo pin scrolls the carousel to the matching thumbnail (does
     * NOT open the preview sheet — iOS comment: "map pin taps focus
     * the carousel rather than preview").
     */
    @Immutable data class Photo(
        val walkPhotoId: Long,
        val photoUri: String,
    ) : WalkMapAnnotationKind()

    /**
     * iOS parity `PilgrimAnnotation.Kind.waypoint(label:icon:)`. User-
     * dropped pin during the walk via the Options sheet. Label is the
     * user-provided text (defaults to "Waypoint"); icon is the
     * favicon-style glyph key (sf-symbol name on iOS, normalised to
     * Material icon mapping on Android).
     */
    @Immutable data class Waypoint(
        val label: String,
        val iconKey: String? = null,
    ) : WalkMapAnnotationKind()

    /**
     * iOS parity `PilgrimAnnotation.Kind.seekArrival(label:lightHex:)`
     * (`PilgrimAnnotation.swift:13-17@c1745e8`). A seek arrival on the
     * summary map: a dawn halo in the hour's light it was found under
     * (fixed hex — the record keeps the sky palette), not a pin. Live
     * walks keep the waypoint path — their halo comes from the fog layer
     * (U6). Label carries identity only; the halo is the whole marker.
     */
    @Immutable data class SeekArrival(
        val label: String,
        val lightHex: String,
    ) : WalkMapAnnotationKind()

    /**
     * iOS parity `PilgrimAnnotation.Kind.whisper(categoryColor:isNearby:)`.
     * Server-side pinned whisper at a chosen point along the walk. Rendered
     * as a glyph at `categoryColor` with a soft halo when `isNearby == true`.
     */
    @Immutable data class Whisper(
        val whisperId: String,
        val categoryColor: Long,
        val isNearby: Boolean = false,
    ) : WalkMapAnnotationKind()

    /**
     * iOS parity `PilgrimAnnotation.Kind.cairn(stoneCount:tier:)`. Pile of
     * stones placed during meditation; tier is the visual tier index
     * (1..3) and stoneCount drives the silhouette layering.
     */
    @Immutable data class Cairn(
        val cairnId: String,
        val stoneCount: Int,
        val tier: Int,
    ) : WalkMapAnnotationKind()
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
    waypoints: List<Waypoint> = emptyList(),
    nearbyWhispers: List<CachedWhisper> = emptyList(),
    nearbyCairns: List<CachedCairn> = emptyList(),
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

    // iOS v1.5 parity — user-dropped waypoints live at their own
    // captured lat/lon (no GPS-sample alignment needed). Seek arrivals
    // (reserved-icon waypoints) render as two-part hour-lit halos instead
    // of pins (iOS `WalkSummaryView.computeAnnotations`,
    // `WalkSummaryView.swift:693-710@c1745e8`); the hex is the dawn
    // family regardless of appearance — the record keeps the sky palette.
    for (w in waypoints) {
        if (SeekPersistence.isArrivalWaypoint(w.icon)) {
            val daypart = SeekSkyLight.daypart(
                SunCalc.solarElevationDegrees(
                    latitude = w.latitude,
                    longitude = w.longitude,
                    instant = Instant.ofEpochMilli(w.timestamp),
                ),
            )
            out += WalkMapAnnotation(
                kind = WalkMapAnnotationKind.SeekArrival(
                    label = w.label.orEmpty(),
                    lightHex = SeekSkyLight.hex(daypart, starlight = false),
                ),
                latitude = w.latitude,
                longitude = w.longitude,
            )
        } else {
            out += WalkMapAnnotation(
                kind = WalkMapAnnotationKind.Waypoint(
                    label = w.label ?: "Waypoint",
                    iconKey = w.icon,
                ),
                latitude = w.latitude,
                longitude = w.longitude,
            )
        }
    }

    // Whispers + cairns come from the server geo-cache (CachedWhisper /
    // CachedCairn). They are NOT walk-scoped; the caller passes the
    // subset the user encountered along this walk (filtered by walk
    // route + radius in the repository layer).
    for (w in nearbyWhispers) {
        val color = w.resolvedCategory?.borderColor?.let { c ->
            // Pack Compose Color → ARGB long for stability + serialisation.
            val a = (c.alpha * 255f).toInt() and 0xFF
            val r = (c.red * 255f).toInt() and 0xFF
            val g = (c.green * 255f).toInt() and 0xFF
            val b = (c.blue * 255f).toInt() and 0xFF
            ((a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong())
        } ?: 0xFF8B7355L
        out += WalkMapAnnotation(
            kind = WalkMapAnnotationKind.Whisper(
                whisperId = w.whisperId,
                categoryColor = color,
                isNearby = false,
            ),
            latitude = w.latitude,
            longitude = w.longitude,
        )
    }
    for (c in nearbyCairns) {
        out += WalkMapAnnotation(
            kind = WalkMapAnnotationKind.Cairn(
                cairnId = c.id,
                stoneCount = c.stoneCount,
                tier = c.tier.ordinal + 1,
            ),
            latitude = c.latitude,
            longitude = c.longitude,
        )
    }

    return out
}
