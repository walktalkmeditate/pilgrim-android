// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.walktalkmeditate.pilgrim.domain.honor.HonorDistance
import org.walktalkmeditate.pilgrim.domain.honor.HonorTuning
import org.walktalkmeditate.pilgrim.domain.honor.WGS84_HONOR_DISTANCE
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate

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
 * coordinator keeps it. [metersBetween] is the ruler, swapped only by a
 * test that needs exactly 200 m.
 */
internal class CameraReportThrottle(
    private val metersBetween: HonorDistance = WGS84_HONOR_DISTANCE,
) {

    private var lastLevel: Int? = null
    private var lastCenter: WayCoordinate? = null
    private var lastReportUptimeMillis = 0L

    /** Rule 1 alone, which reads neither centre nor zoom: true when the event is dropped by the window. */
    fun inWindow(throttled: Boolean, nowUptimeMillis: Long): Boolean =
        throttled && nowUptimeMillis - lastReportUptimeMillis < MIN_INTERVAL_MILLIS

    /** True when this event is reported, with the camera's [center] and raw [zoom]. */
    fun report(center: WayCoordinate, zoom: Double, throttled: Boolean, nowUptimeMillis: Long): Boolean {
        if (inWindow(throttled, nowUptimeMillis)) return false
        if (!zoom.isFinite()) return false
        val level = zoom.toInt()
        val movedFar = lastCenter?.let { last ->
            metersBetween(last, center) > HonorTuning.MARK_PIN_REFRESH_METERS
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
