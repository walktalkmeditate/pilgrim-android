// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import java.time.Instant
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SunCalc
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight
import kotlin.math.abs

/**
 * The walk summary's seek story: which clearings were reached and which
 * signs (photos, voice notes, marks) belong to each. `null` when the walk
 * is not a seek or no clearing was reached — zero-arrival seeks render the
 * standard summary untouched. Port spec
 * `docs/parity/2026-07-14-port-seek-summary-u11.md`
 * (iOS `SeekSummaryData`, `SeekSummarySection.swift:7-41@c1745e8`).
 */
@Immutable
data class SeekSummaryData(
    val groups: List<ClearingGroup>,
    val alongTheWay: AlongTheWay,
    /**
     * The gateway moment — the SEEK_MODE event's timestamp, written at
     * recording start. Together with the walk's intention this is the
     * seed's whole provenance. Null when no SEEK_MODE timestamp exists
     * (defensive; the detection guard already requires the event).
     */
    val seededAtEpochMs: Long?,
    val intentionWasVoiced: Boolean,
) {

    @Immutable
    data class ClearingGroup(
        val ordinal: Int,
        val label: String,
        val center: SeekPoint,
        val arrivedAtEpochMs: Long,
        /**
         * The sky's light at the arrival — golden hour, broad daylight,
         * or night — computed from the sun's real elevation there and then.
         */
        val foundUnder: SeekSkyLight.Daypart,
        val photoIds: List<String>,
        val voiceRecordingIds: List<String>,
        val waypointIds: List<String>,
    )

    @Immutable
    data class AlongTheWay(
        val photoIds: List<String>,
        val voiceRecordingIds: List<String>,
        val waypointIds: List<String>,
    ) {
        val isEmpty: Boolean
            get() = photoIds.isEmpty() && voiceRecordingIds.isEmpty() && waypointIds.isEmpty()
    }
}

/**
 * Pure assembly for the summary seek story: detection, sign grouping,
 * found-under captions, and provenance inputs. iOS `SeekSummaryModel`
 * (`SeekSummarySection.swift:43-241@c1745e8`).
 */
object SeekSummaryModel {

    /**
     * Half the maximum region diameter (120 m) plus GPS slack, so a sign
     * marked anywhere inside a clearing groups to it even when the fix
     * wandered past the region edge.
     */
    const val GROUPING_RADIUS_METERS = 80.0

    /**
     * Signs without any coordinate attribute to the preceding arrival only
     * when marked within this window of the arrival itself.
     */
    const val TIMESTAMP_FALLBACK_WINDOW_MS = 5 * 60 * 1_000L

    data class Arrival(
        val label: String,
        val center: SeekPoint,
        val arrivedAtEpochMs: Long,
    )

    data class Sign(
        val kind: Kind,
        val id: String,
        val coordinate: SeekPoint?,
        val timestampEpochMs: Long,
    ) {
        enum class Kind { PHOTO, VOICE_RECORDING, WAYPOINT }
    }

    fun isSeekWalk(events: List<WalkEventType>): Boolean =
        WalkEventType.SEEK_MODE in events

    /** A sign exactly on the radius boundary belongs to the clearing. */
    fun belongsToClearing(distanceMeters: Double): Boolean =
        distanceMeters <= GROUPING_RADIUS_METERS

    fun summaryData(
        events: List<WalkEventType>,
        arrivals: List<Arrival>,
        signs: List<Sign>,
        seededAtEpochMs: Long? = null,
        intentionWasVoiced: Boolean = false,
    ): SeekSummaryData? {
        if (!isSeekWalk(events) || arrivals.isEmpty()) return null

        val ordered = arrivals.sortedBy { it.arrivedAtEpochMs }
        val grouped = List(ordered.size) { mutableListOf<Sign>() }
        val strays = mutableListOf<Sign>()

        for (sign in signs) {
            val index = clearingIndex(sign, ordered)
            if (index != null) grouped[index] += sign else strays += sign
        }

        val groups = ordered.mapIndexed { index, arrival ->
            SeekSummaryData.ClearingGroup(
                ordinal = index + 1,
                label = arrival.label,
                center = arrival.center,
                arrivedAtEpochMs = arrival.arrivedAtEpochMs,
                foundUnder = foundUnderDaypart(arrival.center, arrival.arrivedAtEpochMs),
                photoIds = ids(Sign.Kind.PHOTO, grouped[index]),
                voiceRecordingIds = ids(Sign.Kind.VOICE_RECORDING, grouped[index]),
                waypointIds = ids(Sign.Kind.WAYPOINT, grouped[index]),
            )
        }

        return SeekSummaryData(
            groups = groups,
            alongTheWay = SeekSummaryData.AlongTheWay(
                photoIds = ids(Sign.Kind.PHOTO, strays),
                voiceRecordingIds = ids(Sign.Kind.VOICE_RECORDING, strays),
                waypointIds = ids(Sign.Kind.WAYPOINT, strays),
            ),
            seededAtEpochMs = seededAtEpochMs,
            intentionWasVoiced = intentionWasVoiced,
        )
    }

    /**
     * The hour's light at an arrival, from the sun's real elevation at
     * that place and moment. Shared by the summary captions and the
     * summary map's halo tint (iOS `foundUnderDaypart`,
     * `SeekSummarySection.swift:130-136@c1745e8`).
     */
    fun foundUnderDaypart(center: SeekPoint, arrivedAtEpochMs: Long): SeekSkyLight.Daypart =
        SeekSkyLight.daypart(
            SunCalc.solarElevationDegrees(
                latitude = center.latitude,
                longitude = center.longitude,
                instant = Instant.ofEpochMilli(arrivedAtEpochMs),
            ),
        )

    /**
     * The unknowns-found note. Counts only reached clearings — never
     * totals, never "X of Y" (unreached clearings stay hidden). Dedicated
     * 1/2/3 phrasings, `%d` fallback beyond (iOS `unknownsFoundText`,
     * `SeekSummarySection.swift:138-145@c1745e8`).
     */
    fun unknownsFoundText(resources: Resources, arrivalCount: Int): String =
        when (arrivalCount) {
            1 -> resources.getString(R.string.seek_summary_found_one)
            2 -> resources.getString(R.string.seek_summary_found_two)
            3 -> resources.getString(R.string.seek_summary_found_three)
            else -> resources.getString(R.string.seek_summary_found_many, arrivalCount)
        }

    /**
     * Maps stored walk rows onto plain model inputs. Coordinate support
     * per sign type: photos carry their capture fix (null EXIF fix falls
     * back to timestamp grouping — spec D3), waypoints were marked at the
     * walker's position, and voice recordings store no location — their
     * coordinate resolves to the route sample nearest the recording start
     * (the same rule that places their map pin), falling back to timestamp
     * grouping when the walk has no route data. iOS walk adapter
     * (`SeekSummarySection.swift:173-241@c1745e8`).
     */
    fun summaryData(
        events: List<WalkEvent>,
        waypoints: List<Waypoint>,
        photos: List<WalkPhoto>,
        voiceRecordings: List<VoiceRecording>,
        routeSamples: List<RouteDataSample>,
        intention: String?,
    ): SeekSummaryData? {
        val eventTypes = events.map { it.eventType }
        if (!isSeekWalk(eventTypes)) return null

        val arrivals = waypoints
            .filter { SeekPersistence.isArrivalWaypoint(it.icon) }
            .map { waypoint ->
                Arrival(
                    label = waypoint.label.orEmpty(),
                    center = SeekPoint(waypoint.latitude, waypoint.longitude),
                    arrivedAtEpochMs = waypoint.timestamp,
                )
            }

        val signs = buildList {
            photos.mapTo(this) { photo ->
                val lat = photo.capturedLat
                val lng = photo.capturedLng
                Sign(
                    kind = Sign.Kind.PHOTO,
                    id = photo.uuid,
                    coordinate = if (lat != null && lng != null) SeekPoint(lat, lng) else null,
                    timestampEpochMs = photo.takenAt ?: photo.pinnedAt,
                )
            }
            voiceRecordings.mapTo(this) { recording ->
                Sign(
                    kind = Sign.Kind.VOICE_RECORDING,
                    id = recording.uuid,
                    coordinate = nearestRouteCoordinate(recording.startTimestamp, routeSamples),
                    timestampEpochMs = recording.startTimestamp,
                )
            }
            waypoints
                .filterNot { SeekPersistence.isArrivalWaypoint(it.icon) }
                .mapTo(this) { waypoint ->
                    Sign(
                        kind = Sign.Kind.WAYPOINT,
                        id = waypoint.uuid,
                        coordinate = SeekPoint(waypoint.latitude, waypoint.longitude),
                        timestampEpochMs = waypoint.timestamp,
                    )
                }
        }

        return summaryData(
            events = eventTypes,
            arrivals = arrivals,
            signs = signs,
            seededAtEpochMs = events
                .firstOrNull { it.eventType == WalkEventType.SEEK_MODE }
                ?.timestamp,
            // iOS: !(walk.comment?.isEmpty ?? true) — exact isEmpty, no trim.
            intentionWasVoiced = !intention.isNullOrEmpty(),
        )
    }

    private fun clearingIndex(sign: Sign, arrivals: List<Arrival>): Int? {
        val coordinate = sign.coordinate
            ?: return timestampFallbackIndex(sign.timestampEpochMs, arrivals)
        val nearest = arrivals.withIndex().minByOrNull { (_, arrival) ->
            SeekChainGenerator.distance(coordinate, arrival.center)
        } ?: return null
        val distance = SeekChainGenerator.distance(coordinate, nearest.value.center)
        return if (belongsToClearing(distance)) nearest.index else null
    }

    private fun timestampFallbackIndex(timestampEpochMs: Long, arrivals: List<Arrival>): Int? {
        val preceding = arrivals.indexOfLast { it.arrivedAtEpochMs <= timestampEpochMs }
        if (preceding < 0) return null
        val sinceArrival = timestampEpochMs - arrivals[preceding].arrivedAtEpochMs
        return if (sinceArrival <= TIMESTAMP_FALLBACK_WINDOW_MS) preceding else null
    }

    private fun nearestRouteCoordinate(
        timestampEpochMs: Long,
        samples: List<RouteDataSample>,
    ): SeekPoint? = samples
        .minByOrNull { abs(it.timestamp - timestampEpochMs) }
        ?.let { SeekPoint(it.latitude, it.longitude) }

    private fun ids(kind: Sign.Kind, signs: List<Sign>): List<String> =
        signs.filter { it.kind == kind }.map { it.id }
}
