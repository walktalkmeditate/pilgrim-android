// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.walktalkmeditate.pilgrim.domain.honor.HonorTuning
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.wgs84MidLatitudeMeters

/**
 * iOS `reportCamera(on:coordinator:throttled:)`
 * (`PilgrimMapView+CameraReport.swift:34-52@7c200bf`, pilgrimage-stage spec
 * P5 §4): which of the map's camera events reach the marks, in iOS's order.
 *
 * 1. A camera change less than [MIN_INTERVAL_MILLIS] after the last report
 *    is dropped; an idle ([throttled] false) skips only this rule.
 * 2. A NaN or infinite zoom is dropped, and the clock doesn't move.
 * 3. Either kind reports only on a new whole zoom level (truncated, as
 *    Swift's `Int(_:)`) or a centre more than 200 m from the last report's;
 *    the first event always does. So an idle with neither sends nothing:
 *    iOS's "idle is the one report that must always get through" overstates
 *    its code (noted in pilgrim-ios #122).
 * 4. A report keeps its time, level and centre.
 *
 * One per map, kept across style reloads and lost with the map, as iOS's
 * coordinator keeps it.
 */
internal class CameraReportThrottle {

    private var lastLevel: Int? = null
    private var lastCenter: WayCoordinate? = null
    private var lastReportUptimeMillis = 0L

    /** True when this event is reported, with the camera's [center] and raw [zoom]. */
    fun report(center: WayCoordinate, zoom: Double, throttled: Boolean, nowUptimeMillis: Long): Boolean {
        if (throttled && nowUptimeMillis - lastReportUptimeMillis < MIN_INTERVAL_MILLIS) return false
        if (!zoom.isFinite()) return false
        val level = zoom.toInt()
        val movedFar = lastCenter?.let { last ->
            wgs84MidLatitudeMeters(last.lat, last.lon, center.lat, center.lon) > HonorTuning.MARK_PIN_REFRESH_METERS
        } ?: true
        if (level == lastLevel && !movedFar) return false
        lastReportUptimeMillis = nowUptimeMillis
        lastLevel = level
        lastCenter = center
        return true
    }

    companion object {
        /** Four reports a second at most. */
        const val MIN_INTERVAL_MILLIS = 250L
    }
}
