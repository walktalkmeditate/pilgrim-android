// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Minimal lat/lon/time tuple for the prompt-context route projection
 * helpers. Decoupled from the project's Room route-sample entity so the
 * helpers can be tested without DB plumbing.
 */
data class RouteSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
)

object RouteSampleProjection {
    /**
     * Cumulative flat-earth distance summed up to the sample at-or-before
     * [targetMs]. Verbatim port of iOS `PromptListView.swift:222-231`.
     * Uses the 111 320 m/deg flat-earth approximation with cos-latitude
     * correction — NOT haversine — so Android matches iOS bit-for-bit
     * even though `domain.haversineMeters` is available here.
     *
     * Loop semantics from iOS: iterates `i in 1 until samples.size`, and
     * `if samples[i].timestampMs > targetMs: break` BEFORE adding the
     * segment. So a target between sample[0] and sample[1] returns 0.
     */
    fun distanceAtTimestamp(samples: List<RouteSample>, targetMs: Long): Double {
        var cum = 0.0
        for (i in 1 until samples.size) {
            if (samples[i].timestampMs > targetMs) break
            val dlat = (samples[i].latitude - samples[i - 1].latitude) * METERS_PER_DEG_LAT
            val dlon = (samples[i].longitude - samples[i - 1].longitude) *
                METERS_PER_DEG_LAT * cos(samples[i].latitude * PI / 180.0)
            cum += sqrt(dlat * dlat + dlon * dlon)
        }
        return cum
    }

    /**
     * Coordinate of the sample whose timestamp is closest to [targetMs]
     * (by absolute delta). Null when [samples] is empty. Verbatim port of
     * iOS `PromptListView.swift:272`.
     */
    fun closestCoordinate(samples: List<RouteSample>, targetMs: Long): LatLng? {
        if (samples.isEmpty()) return null
        val closest = samples.minBy { abs(it.timestampMs - targetMs) }
        return LatLng(closest.latitude, closest.longitude)
    }

    private const val METERS_PER_DEG_LAT = 111_320.0
}

/**
 * Truncates [this] string to at most [maxLength] characters, lopping at
 * the last whitespace boundary at-or-before [maxLength]. When no space
 * is found, hard-cuts at [maxLength]. Always appends `"..."` when the
 * string was truncated. Verbatim port of iOS
 * `String.truncatedAtWordBoundary(maxLength:)` in
 * `Pilgrim/Models/PromptGenerator.swift` (default `maxLength = 200`
 * matches iOS).
 */
fun String.truncatedAtWordBoundary(maxLength: Int = 200): String {
    if (length <= maxLength) return this
    val head = take(maxLength)
    val lastSpace = head.lastIndexOf(' ')
    val body = if (lastSpace >= 0) head.substring(0, lastSpace) else head
    return "$body..."
}
