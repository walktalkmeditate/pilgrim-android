// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

private const val DOWNSAMPLE_TARGET = 200
private const val STROKE_WIDTH_DP = 3
private const val ENDPOINT_DIAMETER_DP = 8
private const val ENDPOINT_STROKE_DP = 1.5f

/**
 * Route-line-only preview — no map tiles. iOS parity with
 * `RouteShapeView.swift@v1.6.0`: a stone-colored normalized polyline
 * with a filled moss start dot and an outlined moss end dot, drawn on
 * the caller's [pilgrimColors.parchmentSecondary] card. The whole
 * shape is inset by [PilgrimSpacing.big] (iOS `.padding(big)`).
 */
@Composable
internal fun RouteShapeView(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
) {
    val stone = pilgrimColors.stone
    val moss = pilgrimColors.moss
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(PilgrimSpacing.big),
    ) {
        val projected = projectRoute(points, size.width, size.height)
        if (projected.size < 2) return@Canvas

        val path = Path().apply {
            moveTo(projected[0].x, projected[0].y)
            for (i in 1 until projected.size) lineTo(projected[i].x, projected[i].y)
        }
        drawPath(
            path = path,
            color = stone,
            style = Stroke(
                width = STROKE_WIDTH_DP.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        val radius = ENDPOINT_DIAMETER_DP.dp.toPx() / 2f
        drawCircle(color = moss, radius = radius, center = projected.first())
        drawCircle(
            color = moss,
            radius = radius,
            center = projected.last(),
            style = Stroke(width = ENDPOINT_STROKE_DP.dp.toPx()),
        )
    }
}

/**
 * Normalize lat/lng samples into canvas-space [Offset]s that fit
 * [width] × [height] while preserving aspect ratio and centering the
 * shape. Mirrors iOS `RouteShapeView.projectRoute` — including the
 * `count / 200` downsample stride and the explicit re-append of the
 * final sample so the route always terminates at the true endpoint.
 *
 * Returns an empty list when there are fewer than two points or the
 * route has zero spatial extent (a single stationary fix repeated).
 */
internal fun projectRoute(
    points: List<LocationPoint>,
    width: Float,
    height: Float,
): List<Offset> {
    if (points.size < 2) return emptyList()

    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var minLon = Double.POSITIVE_INFINITY
    var maxLon = Double.NEGATIVE_INFINITY
    for (p in points) {
        if (p.latitude < minLat) minLat = p.latitude
        if (p.latitude > maxLat) maxLat = p.latitude
        if (p.longitude < minLon) minLon = p.longitude
        if (p.longitude > maxLon) maxLon = p.longitude
    }

    val latRange = maxLat - minLat
    val lonRange = maxLon - minLon
    if (latRange <= 0.0 && lonRange <= 0.0) return emptyList()

    val scale = minOf(
        if (lonRange > 0.0) width / lonRange else Double.POSITIVE_INFINITY,
        if (latRange > 0.0) height / latRange else Double.POSITIVE_INFINITY,
    )
    val routeW = lonRange * scale
    val routeH = latRange * scale
    val offsetX = (width - routeW) / 2.0
    val offsetY = (height - routeH) / 2.0

    fun project(p: LocationPoint) = Offset(
        x = (offsetX + (p.longitude - minLon) * scale).toFloat(),
        y = (offsetY + (maxLat - p.latitude) * scale).toFloat(),
    )

    val step = maxOf(1, points.size / DOWNSAMPLE_TARGET)
    val result = ArrayList<Offset>(points.size / step + 2)
    var i = 0
    while (i < points.size) {
        result.add(project(points[i]))
        i += step
    }
    val lastPoint = project(points.last())
    if (result.last() != lastPoint) result.add(lastPoint)
    return result
}
