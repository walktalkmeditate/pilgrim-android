// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.max
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * One point on the elevation sparkline path, both axes normalized
 * to `[0, 1]`. y is `1 - normalized-altitude` so the highest altitude
 * draws at the top (y=0) and the lowest at the bottom (y=1).
 */
@Immutable
internal data class ElevationSparklinePoint(
    val xFraction: Float,
    val yFraction: Float,
)

/**
 * Bucket-and-normalize altitude samples for the post-walk elevation
 * sparkline. Verbatim port of iOS `ElevationProfileView` sample-stride
 * + per-pixel-bucket pattern (`ElevationProfileView.swift:39-79`).
 *
 * Returns empty for fewer than 2 samples (cannot draw a path) OR when
 * `max - min` is zero (degenerate flat profile — caller guards on
 * range > 1m before calling, but this is defense-in-depth).
 */
internal fun computeElevationSparklinePoints(
    altitudes: List<Double>,
    targetWidthBuckets: Int,
): List<ElevationSparklinePoint> {
    if (altitudes.size < 2) return emptyList()
    val minAlt = altitudes.min()
    val maxAlt = altitudes.max()
    val range = maxAlt - minAlt
    if (range <= 0.0) return emptyList()

    val step = max(1, altitudes.size / targetWidthBuckets.coerceAtLeast(1))
    val sampled = mutableListOf<Double>()
    var i = 0
    while (i < altitudes.size) {
        sampled += altitudes[i]
        i += step
    }
    if (sampled.size < 2) return emptyList()
    val denom = (sampled.size - 1).toFloat()
    return sampled.mapIndexed { idx, alt ->
        ElevationSparklinePoint(
            xFraction = idx.toFloat() / denom,
            yFraction = (1.0 - (alt - minAlt) / range).toFloat(),
        )
    }
}

@Composable
fun ElevationProfile(
    altitudes: List<Double>,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    if (altitudes.size < 2) return
    val minAlt = altitudes.min()
    val maxAlt = altitudes.max()
    if (maxAlt - minAlt <= 1.0) return // iOS guard: range > 1m

    val stoneFill = pilgrimColors.stone
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val targetBuckets = size.width.toInt().coerceAtLeast(2)
                val points = computeElevationSparklinePoints(altitudes, targetBuckets)
                if (points.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val fillPath = Path().apply {
                    moveTo(points.first().xFraction * w, h)
                    for (p in points) lineTo(p.xFraction * w, p.yFraction * h)
                    lineTo(points.last().xFraction * w, h)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(stoneFill.copy(alpha = 0.3f), stoneFill.copy(alpha = 0.05f)),
                        startY = 0f,
                        endY = h,
                    ),
                )
                val strokePath = Path().apply {
                    moveTo(points.first().xFraction * w, points.first().yFraction * h)
                    for (p in points.drop(1)) lineTo(p.xFraction * w, p.yFraction * h)
                }
                drawPath(
                    path = strokePath,
                    color = stoneFill.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = WalkFormat.altitude(minAlt, units),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Icon(
                imageVector = Icons.Outlined.Terrain,
                contentDescription = null,
                tint = pilgrimColors.fog,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = WalkFormat.altitude(maxAlt, units),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}
