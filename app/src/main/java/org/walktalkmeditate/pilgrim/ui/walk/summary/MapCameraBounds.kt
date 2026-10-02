// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Geographic bounds for a Mapbox camera fit. Verbatim port of iOS
 * `MapCameraBounds` (`PilgrimAnnotation.swift:21-31`).
 */
@Immutable
data class MapCameraBounds(
    val swLat: Double,
    val swLng: Double,
    val neLat: Double,
    val neLng: Double,
)

/**
 * Compute camera bounds covering all GPS samples whose timestamp falls
 * inside `[startMs, endMs]`. Returns null when no samples land in the
 * range; the caller keeps the current framing. iOS-faithful
 * port of `boundsForTimeRange` (`WalkSummaryView.swift:841-846@7c200bf`),
 * which pads through [boundsForRoute].
 */
fun computeBoundsForTimeRange(
    samples: List<RouteDataSample>,
    startMs: Long,
    endMs: Long,
): MapCameraBounds? {
    val inRange = samples.filter { it.timestamp in startMs..endMs }
    if (inRange.isEmpty()) return null
    return paddedBounds(inRange.map { it.latitude }, inRange.map { it.longitude })
}

/**
 * iOS `boundsForRoute` (`WalkSummaryView.swift:848-861@7c200bf`): the
 * route's extent padded by 15% of its span plus 0.001° on each axis. The
 * 0.001° floor gives a one-point or tiny route a visible span, so an
 * unclamped fit stays off street zoom. Null for an empty route (every iOS
 * caller guards that case before calling).
 */
fun boundsForRoute(points: List<LocationPoint>): MapCameraBounds? {
    if (points.isEmpty()) return null
    return paddedBounds(points.map { it.latitude }, points.map { it.longitude })
}

private fun paddedBounds(lats: List<Double>, lngs: List<Double>): MapCameraBounds {
    val minLat = lats.min()
    val maxLat = lats.max()
    val minLng = lngs.min()
    val maxLng = lngs.max()
    val latPad = (maxLat - minLat) * 0.15 + 0.001
    val lngPad = (maxLng - minLng) * 0.15 + 0.001
    return MapCameraBounds(
        swLat = minLat - latPad,
        swLng = minLng - lngPad,
        neLat = maxLat + latPad,
        neLng = maxLng + lngPad,
    )
}
