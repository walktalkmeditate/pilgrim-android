// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Live pace sparkline for the Active Walk minimized-stats region.
 *
 * Port of iOS `LivePaceSparklineView` (`ActiveWalkSubviews.swift:29-55`)
 * and the `paceHistory` build in `ActiveWalkViewModel`
 * (`ActiveWalkViewModel.swift:205-210`): an ambient, accessibility-hidden
 * trend line of recent walking pace.
 */

internal const val PACE_SPEED_THRESHOLD_MPS = 0.3f
internal const val PACE_HISTORY_CAP = 60
private const val PACE_RANGE_FLOOR = 0.5

/**
 * Maps a stream of per-sample speeds (m/s, newest last) to pace in
 * minutes/km, keeping only the most recent [PACE_HISTORY_CAP] entries.
 *
 * iOS clamps speed at 0 and treats anything at or below 0.3 m/s (standing
 * / GPS noise) as 0 pace so the sparkline flattens instead of spiking to
 * absurd min/km when the walker stops.
 */
internal fun livePaceHistory(speedsMps: List<Float?>): List<Double> {
    val paced = speedsMps.map { raw ->
        val speed = max(0f, raw ?: 0f)
        if (speed > PACE_SPEED_THRESHOLD_MPS) (1000.0 / speed) / 60.0 else 0.0
    }
    return if (paced.size > PACE_HISTORY_CAP) paced.takeLast(PACE_HISTORY_CAP) else paced
}

/**
 * Resolves pace values to draw points within a [width]×[height] box.
 *
 * Mirrors iOS: drop non-positive values, render only when at least two
 * positive samples remain, normalize y against the value range with a
 * 0.5 floor (prevents divide-by-zero on a flat trace), and spread x
 * evenly across the full width.
 */
internal fun livePaceSparklineOffsets(
    values: List<Double>,
    width: Float,
    height: Float,
): List<Offset> {
    val filtered = values.filter { it > 0.0 }
    if (filtered.size <= 1) return emptyList()
    val minVal = filtered.min()
    val maxVal = filtered.max()
    val range = max(maxVal - minVal, PACE_RANGE_FLOOR)
    val lastIndex = filtered.size - 1
    return filtered.mapIndexed { i, v ->
        val x = width * i / lastIndex
        val normalized = (v - minVal) / range
        val y = height * (1f - normalized.toFloat())
        Offset(x, y)
    }
}

@Composable
fun LivePaceSparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
) {
    val strokeColor = pilgrimColors.stone.copy(alpha = 0.4f)
    val path = remember { Path() }
    val density = LocalDensity.current
    val stroke = remember(density) {
        Stroke(
            width = with(density) { 1.5.dp.toPx() },
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
    }
    Canvas(modifier = modifier) {
        val points = livePaceSparklineOffsets(values, size.width, size.height)
        if (points.size <= 1) return@Canvas
        path.rewind()
        path.moveTo(points.first().x, points.first().y)
        for (p in points.drop(1)) path.lineTo(p.x, p.y)
        drawPath(
            path = path,
            color = strokeColor,
            style = stroke,
        )
    }
}
