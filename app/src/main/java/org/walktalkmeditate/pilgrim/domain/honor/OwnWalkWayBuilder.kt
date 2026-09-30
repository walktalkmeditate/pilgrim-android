// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.honor

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.data.share.TourBuilder
import org.walktalkmeditate.pilgrim.data.share.TourRecordingKind
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsMath
import org.walktalkmeditate.pilgrim.data.walk.deriveActivityIntervals
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence

/**
 * A Way from one of the walker's own walks: a port of iOS
 * `Pilgrim/Models/Honor/OwnWalkWayBuilder.swift@7c200bf` (parity spec A
 * §9–§11). Nothing is copied: voices reference their recording files and
 * photos their `content://` URIs. Pure and synchronous; the caller reads
 * the rows (see [Input.fromRows]) and probes the recording files.
 */
object OwnWalkWayBuilder {

    const val MAX_ROUTE_POINTS = 4000
    const val MIN_REST_SECONDS = 180.0

    /** Below this a "Way" is jitter around one spot: every frac collapses and arrival is instant or unreachable. */
    const val MIN_LENGTH_METERS = 20.0

    /** iOS's own fallback glyph, for an Android waypoint saved without an icon (spec decision 4). */
    const val DEFAULT_WAYPOINT_ICON = "mappin"

    /** A closed stretch of the walk, epoch millis. */
    data class TimeSpan(val startMillis: Long, val endMillis: Long)

    /**
     * What the builder reads from a walk: iOS's `WalkInterface` members, in
     * Android's shapes. [pauses] are the walk's paused stretches and
     * [sittings] its meditations; [activeSeconds] is iOS's `activeDuration`
     * (wall clock less pauses, sittings counted).
     */
    data class Input(
        val uuid: String?,
        val startedAtMillis: Long,
        val intention: String?,
        val activeSeconds: Double,
        val weatherCondition: String?,
        val weatherTemperatureC: Double?,
        val samples: List<RouteDataSample>,
        val recordings: List<VoiceRecording>,
        val photos: List<WalkPhoto>,
        val waypoints: List<Waypoint>,
        val pauses: List<TimeSpan>,
        val sittings: List<TimeSpan>,
    ) {
        companion object {
            /**
             * From the walk's Room rows. Pauses pair PAUSED/RESUMED events
             * ([WalkMetricsMath.pauseSpans]) and sittings come from
             * `walk_events` ([deriveActivityIntervals], #223), both closing
             * an open stretch at the walk's end.
             */
            fun fromRows(
                walk: Walk,
                samples: List<RouteDataSample>,
                recordings: List<VoiceRecording>,
                photos: List<WalkPhoto>,
                waypoints: List<Waypoint>,
                events: List<WalkEvent>,
            ): Input {
                val pauses = WalkMetricsMath.pauseSpans(walk, events)
                val sittings = deriveActivityIntervals(events, walkId = walk.id, closeAt = walk.endTimestamp)
                return Input(
                    uuid = walk.uuid,
                    startedAtMillis = walk.startTimestamp,
                    intention = walk.intention,
                    activeSeconds = WalkMetricsMath.activeDurationMillis(walk, pauses) / MILLIS_PER_SECOND,
                    weatherCondition = walk.weatherCondition,
                    weatherTemperatureC = walk.weatherTemperature,
                    samples = samples,
                    recordings = recordings,
                    photos = photos,
                    waypoints = waypoints,
                    pauses = pauses.map { TimeSpan(it.startMs, it.startMs + it.durationMillis) },
                    sittings = sittings.map { TimeSpan(it.startTimestamp, it.endTimestamp) },
                )
            }
        }
    }

    /**
     * Null when the walk has fewer than two route samples, no uuid, or a
     * route under [MIN_LENGTH_METERS]. [recordingIsPresent] answers whether
     * a recording's file exists with a size above zero, as iOS probes
     * (`OwnWalkWayBuilder.swift:38-45@7c200bf`); recordings are deletable
     * while their rows stay. The title's date and the Way's `tzIdentifier`
     * use [zone] and [locale].
     */
    fun make(
        walk: Input,
        recordingIsPresent: (VoiceRecording) -> Boolean,
        zone: ZoneId,
        locale: Locale,
    ): Way? {
        val samples = walk.samples.sortedBy { it.timestamp }
        val uuid = walk.uuid?.takeIf { it.isNotEmpty() }
        if (samples.size < 2 || uuid == null) return null
        val t0 = samples.first().timestamp
        val full = samples.map {
            WayPoint(
                lat = it.latitude,
                lon = it.longitude,
                alt = it.altitudeMeters,
                t = secondsBetween(t0, it.timestamp),
            )
        }
        val fullGeometry = WayGeometry(full)
        if (fullGeometry.totalMeters < MIN_LENGTH_METERS) return null
        val route = if (full.size > MAX_ROUTE_POINTS) strideSample(full, MAX_ROUTE_POINTS) else full

        // Positions come from the full-resolution samples, before any
        // downsampling, and a moment's frac is taken at its nearest
        // sample's time, never by projecting onto the line.
        fun nearestSample(millis: Long): RouteDataSample = samples.minBy { abs(it.timestamp - millis) }
        fun fracAt(millis: Long): Double =
            fullGeometry.frac(atElapsed = secondsBetween(t0, nearestSample(millis).timestamp))
        fun coordinateAt(millis: Long): WayCoordinate =
            nearestSample(millis).let { WayCoordinate(lat = it.latitude, lon = it.longitude) }

        val moments = mutableListOf<WayMoment>()

        val present = walk.recordings
            .filter { it.fileRelativePath.isNotEmpty() }
            .filter(recordingIsPresent)
            .sortedBy { it.startTimestamp }
        present.forEachIndexed { n, recording ->
            val startFrac = fracAt(recording.startTimestamp)
            val voiceKind = when (TourBuilder.classify(recording.transcription)) {
                TourRecordingKind.SPOKEN -> VoiceKind.SPOKEN
                TourRecordingKind.AMBIENT -> VoiceKind.AMBIENT
            }
            moments += WayMoment(
                id = "voice-${n + 1}",
                frac = startFrac,
                at = coordinateAt(recording.startTimestamp),
                kind = WayMomentKind.Voice(
                    endFrac = max(fracAt(recording.endTimestamp), startFrac),
                    duration = recording.durationMillis / MILLIS_PER_SECOND,
                    kind = voiceKind,
                    media = WayMedia.Recording(relativePath = recording.fileRelativePath),
                ),
                transcript = WayMoment.trimmedTranscript(recording.transcription),
            )
        }

        // Android photos can lack a capture time or a place, which iOS's
        // never do (spec decision 4): skip the first, and place the second
        // at the route sample nearest its time.
        walk.photos
            .mapNotNull { photo -> photo.takenAt?.let { takenAt -> photo to takenAt } }
            .sortedBy { (_, takenAt) -> takenAt }
            .forEachIndexed { n, (photo, takenAt) ->
                val lat = photo.capturedLat
                val lng = photo.capturedLng
                moments += WayMoment(
                    id = "photo-${n + 1}",
                    frac = fracAt(takenAt),
                    at = if (lat != null && lng != null) WayCoordinate(lat = lat, lon = lng) else coordinateAt(takenAt),
                    kind = WayMomentKind.Photo(media = WayMedia.PhotoAsset(localIdentifier = photo.photoUri)),
                )
            }

        walk.waypoints
            .filterNot { SeekPersistence.isArrivalWaypoint(it.icon) || HonorPersistence.isArrivalWaypoint(it.icon) }
            .sortedBy { it.timestamp }
            .forEachIndexed { n, waypoint ->
                moments += WayMoment(
                    id = "waypoint-${n + 1}",
                    frac = fracAt(waypoint.timestamp),
                    at = WayCoordinate(lat = waypoint.latitude, lon = waypoint.longitude),
                    kind = WayMomentKind.Waypoint(
                        label = waypoint.label.orEmpty(),
                        icon = waypoint.icon ?: DEFAULT_WAYPOINT_ICON,
                    ),
                )
            }

        walk.pauses
            .filter { secondsBetween(it.startMillis, it.endMillis) >= MIN_REST_SECONDS }
            .sortedBy { it.startMillis }
            .forEachIndexed { n, pause ->
                moments += WayMoment(
                    id = "rest-${n + 1}",
                    frac = fracAt(pause.startMillis),
                    at = coordinateAt(pause.startMillis),
                    kind = WayMomentKind.Rest(minutes = pause.roundedMinutes()),
                )
            }

        val sittings = walk.sittings.sortedBy { it.startMillis }
        sittings.forEachIndexed { n, sitting ->
            moments += WayMoment(
                id = "sit-${n + 1}",
                frac = fracAt(sitting.startMillis),
                at = coordinateAt(sitting.startMillis),
                kind = WayMomentKind.Meditation(minutes = sitting.roundedMinutes(), isEstimate = false),
            )
        }

        // The same stretches the walk's own route colors: a recording is a
        // talking span, a sitting a meditating span. Kept only with length.
        val talking = present.map { TimeSpan(it.startTimestamp, it.endTimestamp) }
        val spans = (spans(talking, WaySpanKind.TALKING, ::fracAt) + spans(sittings, WaySpanKind.MEDITATING, ::fracAt))
            .sortedBy { it.startFrac }

        return Way(
            id = "walk:$uuid",
            source = WaySource.OwnWalk(uuid = uuid),
            title = title(walk, zone, locale),
            departedAt = Instant.ofEpochMilli(walk.startedAtMillis),
            tzIdentifier = zone.id,
            expires = null,
            route = route,
            totalDistanceMeters = fullGeometry.totalMeters,
            theirActiveSeconds = walk.activeSeconds,
            moments = moments.sortedWith(compareBy<WayMoment> { it.frac }.thenBy { it.id }),
            weather = walk.weatherCondition?.let { condition ->
                WayWeather(condition = condition, temperatureC = walk.weatherTemperatureC)
            },
            spans = spans,
        )
    }

    private fun spans(intervals: List<TimeSpan>, kind: WaySpanKind, fracAt: (Long) -> Double): List<WaySpan> =
        intervals.mapNotNull { interval ->
            val start = fracAt(interval.startMillis)
            val end = fracAt(interval.endMillis)
            if (end > start) WaySpan(startFrac = start, endFrac = end, kind = kind) else null
        }

    private fun title(walk: Input, zone: ZoneId, locale: Locale): String {
        val intention = walk.intention?.trim()
        if (!intention.isNullOrEmpty()) return intention
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(walk.startedAtMillis))
    }

    /** Indices `round(i × step)`, then the last point (`OwnWalkWayBuilder.swift:130-135@7c200bf`). */
    private fun strideSample(points: List<WayPoint>, target: Int): List<WayPoint> {
        val step = (points.size - 1).toDouble() / (target - 1)
        return List(target - 1) { i -> points[(i * step).roundToInt()] } + points.last()
    }

    private fun secondsBetween(fromMillis: Long, toMillis: Long): Double = (toMillis - fromMillis) / MILLIS_PER_SECOND

    /**
     * Swift's `.rounded()` is half away from zero; for these non-negative
     * values `roundToInt` agrees (270 s is 5 minutes), where
     * `kotlin.math.round` is half-even and gives 4.
     */
    private fun TimeSpan.roundedMinutes(): Int = (secondsBetween(startMillis, endMillis) / 60).roundToInt()

    private const val MILLIS_PER_SECOND = 1_000.0
}
