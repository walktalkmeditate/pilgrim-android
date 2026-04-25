// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Stage 8-A: wire format for `POST /api/share`. Field names use
 * @SerialName to match the backend's snake_case schema
 * (`pilgrim-worker/src/types.ts`). Fields Android doesn't populate
 * yet (photos, mark, turning_day, weather, place_start/end) are
 * nullable so the serializer omits them when
 * `NetworkModule.provideJson().explicitNulls = false`.
 *
 * Integer timestamps are epoch-SECONDS (matches iOS
 * `Int(Date.timeIntervalSince1970)`), NOT millis. Conversion happens
 * in [SharePayloadBuilder].
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
        val data: String,
    )
}
