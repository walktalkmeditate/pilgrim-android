// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stage 8-A: wire format for `POST /api/share`. Field names use
 * @SerialName to match the backend's snake_case schema
 * (`pilgrim-worker/src/types.ts`). Fields Android doesn't populate
 * yet (place_start/end) are nullable so the serializer omits them
 * when `NetworkModule.provideJson().explicitNulls = false`.
 *
 * Integer timestamps are epoch-SECONDS (matches iOS
 * `Int(Date.timeIntervalSince1970)`), NOT millis. Conversion happens
 * in [SharePayloadBuilder].
 *
 * Phase 19 (interactive share, iOS pin `3f9f9e8`): [tour] and
 * [pauses] are Interactive-only additions, both `null` (omitted) on a
 * classic share — see `TourBuilder`/`RouteTrimmer` and
 * [SharePayloadBuilder.build]'s `interactive` option.
 */
@Serializable
data class SharePayload(
    val stats: Stats,
    val route: List<RoutePoint>,
    @SerialName("activity_intervals") val activityIntervals: List<ActivityIntervalPayload>,
    val journal: String? = null,
    @SerialName("expiry_days") val expiryDays: Int,
    val units: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("tz_identifier") val tzIdentifier: String? = null,
    @SerialName("toggled_stats") val toggledStats: List<String>,
    @SerialName("place_start") val placeStart: String? = null,
    @SerialName("place_end") val placeEnd: String? = null,
    val mark: String? = null,
    val waypoints: List<Waypoint>? = null,
    val photos: List<Photo>? = null,
    @SerialName("turning_day") val turningDay: String? = null,
    val tour: Tour? = null,
    val pauses: List<Pause>? = null,
) {
    @Serializable
    data class Stats(
        val distance: Double? = null,
        @SerialName("active_duration") val activeDuration: Double? = null,
        @SerialName("elevation_ascent") val elevationAscent: Double? = null,
        @SerialName("elevation_descent") val elevationDescent: Double? = null,
        val steps: Int? = null,
        @SerialName("meditate_duration") val meditateDuration: Double? = null,
        @SerialName("talk_duration") val talkDuration: Double? = null,
        @SerialName("weather_condition") val weatherCondition: String? = null,
        @SerialName("weather_temperature") val weatherTemperature: Double? = null,
    )

    @Serializable
    data class RoutePoint(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val ts: Long,
    )

    @Serializable
    data class ActivityIntervalPayload(
        val type: String,
        @SerialName("start_ts") val startTs: Long,
        @SerialName("end_ts") val endTs: Long,
    )

    @Serializable
    data class Waypoint(
        val lat: Double,
        val lon: Double,
        val label: String,
        val icon: String,
        val ts: Long,
    )

    @Serializable
    data class Photo(
        val lat: Double,
        val lon: Double,
        val ts: Long,
        // Nullable (Phase 19): an interactive-share tour photo's bytes
        // travel via a separate PUT (a later unit) — its payload entry
        // is metadata-only and omits this key entirely. Classic shares
        // keep embedding a base64 JPEG here, unchanged.
        val data: String? = null,
    )

    /** One paused stretch of the walk. iOS `SharePayload.Pause` (`SharePayload.swift:79-87`). */
    @Serializable
    data class Pause(
        @SerialName("start_ts") val startTs: Long,
        @SerialName("end_ts") val endTs: Long,
    )

    /** iOS `SharePayload.Tour` (`SharePayload.swift:89-97`). */
    @Serializable
    data class Tour(
        val recordings: List<TourRecording>,
        @SerialName("trim_m") val trimM: Int,
    )

    /**
     * One tour recording entry. `transcription` is structurally present
     * but contractually always null — transcripts never leave the
     * device (iOS `TourBuilder.swift:105-108`). iOS
     * `SharePayload.TourRecording` (`SharePayload.swift:99-115`).
     */
    @Serializable
    data class TourRecording(
        val n: Int,
        @SerialName("start_ts") val startTs: Long,
        @SerialName("end_ts") val endTs: Long,
        val duration: Double,
        val kind: String,
        val transcription: String? = null,
        val wpm: Double? = null,
        @SerialName("size_bytes") val sizeBytes: Long,
    )
}
