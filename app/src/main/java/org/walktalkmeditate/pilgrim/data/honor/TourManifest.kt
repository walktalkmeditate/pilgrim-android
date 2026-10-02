// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A share's `tour.json`, as iOS's `TourManifest` declares it
 * (`TourManifest.swift:3-82@7c200bf`, shared-walk spec S1 §2): only the
 * fields the importer reads, every one the worker may omit nullable.
 *
 * A required field has no default, so a missing key or a JSON `null`
 * fails the whole decode, as Swift's synthesized `Decodable` does; a
 * default would quietly turn it into a value (S1 §9 trap 1). Unknown keys
 * are ignored. `v` is required and never compared: there is no version
 * gate (S1 §2.5). Swift's `Int` is 64-bit, so the integers are [Long].
 */
@Serializable
internal data class TourManifest(
    val v: Long,
    @SerialName("place_start") val placeStart: String? = null,
    @SerialName("place_end") val placeEnd: String? = null,
    @SerialName("weather_condition") val weatherCondition: String? = null,
    @SerialName("weather_temperature") val weatherTemperature: Double? = null,
    @SerialName("start_date") val startDate: String,
    @SerialName("tz_identifier") val tzIdentifier: String? = null,
    val expires: String,
    val route: List<RoutePoint>,
    val encounters: List<Encounter>,
    val meditation: List<Sitting>,
    @SerialName("activity_segments") val activitySegments: List<ActivitySegment>? = null,
    val stats: Stats? = null,
) {

    @Serializable
    data class RoutePoint(
        val lat: Double,
        val lon: Double,
        val alt: Double,
        val ts: Long,
    )

    /** [type] is a plain string, so a kind the worker adds later decodes and is skipped. */
    @Serializable
    data class Encounter(
        val type: String,
        val frac: Double,
        @SerialName("end_frac") val endFrac: Double? = null,
        val n: Long? = null,
        val duration: Double? = null,
        val label: String? = null,
        val icon: String? = null,
        val minutes: Long? = null,
        val lat: Double? = null,
        val lon: Double? = null,
        val place: String? = null,
        val transcript: String? = null,
    )

    @Serializable
    data class Sitting(
        @SerialName("start_frac") val startFrac: Double,
        @SerialName("end_frac") val endFrac: Double,
        val duration: Double? = null,
    )

    @Serializable
    data class Stats(
        @SerialName("active_duration") val activeDuration: Double? = null,
    )

    /** [kind] is a plain string for the same reason [Encounter.type] is. */
    @Serializable
    data class ActivitySegment(
        val kind: String,
        @SerialName("start_frac") val startFrac: Double,
        @SerialName("end_frac") val endFrac: Double,
    )
}
