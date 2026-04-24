// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.Waypoint
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Stage 8-A: inputs collected by `WalkShareViewModel` from the repo
 * (pulls match Stage 7-C `composeEtegamiSpec` pattern). Passed to
 * [SharePayloadBuilder.build] as a single aggregate so the builder
 * stays pure + independently testable.
 */
data class ShareInputs(
    val walk: Walk,
    val routePoints: List<LocationPoint>,
    val altitudeSamples: List<AltitudeSample>,
    val activityIntervals: List<ActivityInterval>,
    val voiceRecordings: List<VoiceRecording>,
    val waypoints: List<Waypoint>,
    val distanceMeters: Double,
    val activeDurationSeconds: Double,
    val meditateDurationSeconds: Double,
    val talkDurationSeconds: Double,
    val elevationAscentMeters: Double,
    val elevationDescentMeters: Double,
    val steps: Int?,
)

/** User-selected share options surfaced by the modal. */
data class WalkShareOptions(
    val expiry: ExpiryOption,
    val journal: String,
    val includeDistance: Boolean,
    val includeDuration: Boolean,
    val includeElevation: Boolean,
    val includeActivityBreakdown: Boolean,
    val includeSteps: Boolean,
    val includeWaypoints: Boolean,
)

internal object SharePayloadBuilder {

    /**
     * Pure mapper. Thread-safe (no shared state). Callers run this
     * on `Dispatchers.Default` since CPU cost is list mapping + RDP +
     * JSON encoding downstream.
     */
    fun build(
        inputs: ShareInputs,
        options: WalkShareOptions,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): SharePayload {
        val altitudeByTs = inputs.altitudeSamples.associateBy { it.timestamp }
        val projected = inputs.routePoints.map { p ->
            SharePayload.RoutePoint(
                lat = p.latitude,
                lon = p.longitude,
                alt = altitudeByTs[p.timestamp]?.altitudeMeters ?: 0.0,
                // Epoch-MILLIS → epoch-SECONDS per iOS wire parity.
                ts = p.timestamp / MILLIS_PER_SECOND,
            )
        }
        val downsampled = RouteDownsampler.downsample(projected)

        val intervals = buildList {
            inputs.activityIntervals
                .filter { it.activityType == ActivityType.MEDITATING }
                .forEach {
                    add(
                        SharePayload.ActivityIntervalPayload(
                            type = "meditation",
                            startTs = it.startTimestamp / MILLIS_PER_SECOND,
                            endTs = it.endTimestamp / MILLIS_PER_SECOND,
                        ),
                    )
                }
            inputs.voiceRecordings.forEach {
                add(
                    SharePayload.ActivityIntervalPayload(
                        type = "talk",
                        startTs = it.startTimestamp / MILLIS_PER_SECOND,
                        endTs = it.endTimestamp / MILLIS_PER_SECOND,
                    ),
                )
            }
        }

        val toggledStats = buildList {
            if (options.includeDistance) add("distance")
            if (options.includeDuration) add("duration")
            if (options.includeElevation) add("elevation")
            if (options.includeActivityBreakdown) add("activity_breakdown")
            if (options.includeSteps) add("steps")
        }

        val stats = SharePayload.Stats(
            distance = inputs.distanceMeters.takeIf { it > 0.0 },
            activeDuration = inputs.activeDurationSeconds.takeIf { it > 0.0 },
            elevationAscent = if (options.includeElevation) {
                inputs.elevationAscentMeters.takeIf { it > 1.0 }
            } else null,
            elevationDescent = if (options.includeElevation) {
                inputs.elevationDescentMeters.takeIf { it > 1.0 }
            } else null,
            steps = if (options.includeSteps) inputs.steps?.takeIf { it > 0 } else null,
            meditateDuration = inputs.meditateDurationSeconds.takeIf { it > 0.0 },
            talkDuration = inputs.talkDurationSeconds.takeIf { it > 0.0 },
            weatherCondition = null,
            weatherTemperature = null,
        )

        val waypointsPayload = if (options.includeWaypoints && inputs.waypoints.isNotEmpty()) {
            inputs.waypoints.map {
                SharePayload.Waypoint(
                    lat = it.latitude,
                    lon = it.longitude,
                    // Room entity allows null label/icon; backend
                    // rejects non-strings. Empty string is safe (≤
                    // MAX_WAYPOINT_LABEL_LEN on the server).
                    label = it.label.orEmpty(),
                    icon = it.icon.orEmpty(),
                    ts = it.timestamp / MILLIS_PER_SECOND,
                )
            }
        } else null

        return SharePayload(
            stats = stats,
            route = downsampled,
            activityIntervals = intervals,
            journal = options.journal.takeIf { it.isNotBlank() },
            expiryDays = options.expiry.days,
            units = ShareConfig.DEFAULT_UNITS,
            // `ISO_OFFSET_DATE_TIME` with `Locale.ROOT` — avoids the
            // Arabic/Persian/Hindi non-ASCII-digit trap (Stage 6-B
            // lesson) and matches iOS `ISO8601DateFormatter`'s default
            // output shape `yyyy-MM-ddTHH:mm:ssZ`.
            startDate = ISO.format(Instant.ofEpochMilli(inputs.walk.startTimestamp).atZone(zoneId)),
            tzIdentifier = zoneId.id,
            toggledStats = toggledStats,
            placeStart = null,
            placeEnd = null,
            mark = null,
            waypoints = waypointsPayload,
            photos = null,
            turningDay = null,
        )
    }

    private val ISO: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withLocale(Locale.ROOT)

    private const val MILLIS_PER_SECOND = 1_000L
}
