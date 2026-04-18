// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders the Pilgrim "calligraphy path" — a thread of variable-width
 * ink ribbons connecting per-walk dot positions, drawn as filled
 * polygons between two parallel cubic Béziers.
 *
 * Pure draw layer. Caller supplies pre-resolved [strokes] (see
 * [CalligraphyStrokeSpec] + `Walk.toStrokeSpec`). No coroutines, no
 * state, no side effects.
 *
 * See `docs/superpowers/specs/2026-04-18-stage-3c-calligraphy-path-design.md`.
 */
@Composable
fun CalligraphyPath(
    strokes: List<CalligraphyStrokeSpec>,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 90.dp,
    topInset: Dp = 40.dp,
    maxMeander: Dp = 100.dp,
    baseWidth: Dp = 1.5.dp,
    maxWidth: Dp = 4.5.dp,
) {
    val totalHeight = topInset + verticalSpacing * (strokes.size + 1)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight),
    ) {
        if (strokes.size < 2) return@Canvas
        val verticalSpacingPx = verticalSpacing.toPx()
        val topInsetPx = topInset.toPx()
        val maxMeanderPx = maxMeander.toPx()
        val baseWidthPx = baseWidth.toPx()
        val maxWidthPx = maxWidth.toPx()
        val centerX = size.width / 2f

        val positions: List<Pair<Float, Float>> = strokes.mapIndexed { i, spec ->
            val x = xOffsetPx(spec, centerX, maxMeanderPx)
            val y = topInsetPx + i * verticalSpacingPx + verticalSpacingPx / 2f
            x to y
        }

        for (i in 0 until positions.size - 1) {
            val (sx, sy) = positions[i]
            val (ex, ey) = positions[i + 1]

            val strokeWidth = paceDrivenWidth(
                averagePaceSecPerKm = strokes[i].averagePaceSecPerKm,
                baseWidthPx = baseWidthPx,
                maxWidthPx = maxWidthPx,
            ) * taperFactor(i, strokes.size)
            val halfWidth = strokeWidth / 2f

            val midY = (sy + ey) / 2f
            val cpOffset = meanderSeed(strokes[i]) * maxMeanderPx * 0.4f
            val cp1X = sx + cpOffset
            val cp1Y = midY - verticalSpacingPx * 0.2f
            val cp2X = ex - cpOffset
            val cp2Y = midY + verticalSpacingPx * 0.2f

            val path = Path().apply {
                moveTo(sx - halfWidth, sy)
                cubicTo(
                    cp1X - halfWidth, cp1Y,
                    cp2X - halfWidth, cp2Y,
                    ex - halfWidth, ey,
                )
                lineTo(ex + halfWidth, ey)
                cubicTo(
                    cp2X + halfWidth, cp2Y,
                    cp1X + halfWidth, cp1Y,
                    sx + halfWidth, sy,
                )
                close()
            }
            drawInkRibbon(path, strokes[i].ink, segmentOpacity(i, strokes.size))
        }
    }
}

private fun DrawScope.drawInkRibbon(
    path: Path,
    ink: Color,
    opacity: Float,
) {
    drawPath(path = path, color = ink.copy(alpha = opacity))
}
