// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

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
 * range — caller falls back to the full-route fit-bounds. iOS-faithful
 * port of `boundsForTimeRange` + `boundsForRoute`
 * (`WalkSummaryView.swift:911-931`):
 *   - 15% padding on each axis
 *   - +0.001 floor so a degenerate single-point range still produces a
 *     visible span (otherwise the camera fits to a zero-area rectangle
 *     and Mapbox returns the global default zoom).
 */
fun computeBoundsForTimeRange(
    samples: List<RouteDataSample>,
    startMs: Long,
    endMs: Long,
): MapCameraBounds? {
    val inRange = samples.filter { it.timestamp in startMs..endMs }
    if (inRange.isEmpty()) return null
    val lats = inRange.map { it.latitude }
    val lngs = inRange.map { it.longitude }
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
