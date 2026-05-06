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
    emptyMode: Boolean = false,
) {
    // Empty-state branch — verbatim port of iOS InkScrollView.swift:714.
    // 120 dp tall: half-width 1 dp top → 0.2 dp midpoint → 1 dp bottom.
    // Tapered single calligraphy stroke shown when no walks exist.
    if (emptyMode) {
        Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
            val centerX = size.width / 2f
            val topY = 0f
            val midY = size.height / 2f
            val bottomY = size.height
            val topHalf = 1f * density
            val midHalf = 0.2f * density
            val bottomHalf = 1f * density
            val strokeColor = Color(0xFF8B7355)  // stone
            val tail = Path().apply {
                moveTo(centerX - topHalf, topY)
                cubicTo(
                    centerX - topHalf, midY * 0.6f,
                    centerX - midHalf, midY * 0.9f,
                    centerX - midHalf, midY,
                )
                cubicTo(
                    centerX - midHalf, midY * 1.1f,
                    centerX - bottomHalf, midY * 1.5f,
                    centerX - bottomHalf, bottomY,
                )
                lineTo(centerX + bottomHalf, bottomY)
                cubicTo(
                    centerX + bottomHalf, midY * 1.5f,
                    centerX + midHalf, midY * 1.1f,
                    centerX + midHalf, midY,
                )
                cubicTo(
                    centerX + midHalf, midY * 0.9f,
                    centerX + topHalf, midY * 0.6f,
                    centerX + topHalf, topY,
                )
                close()
            }
            drawPath(path = tail, color = strokeColor.copy(alpha = 0.5f))
        }
        return
    }
    // Fewer than 2 strokes = no segments to connect = nothing to draw.
    // Collapse to 0.dp so a fresh-install device with 0 or 1 finished
    // walks doesn't reserve blank parchment below the header. Stage
    // 3-E may want to render a single dot in this case; for now the
    // renderer is a connector.
    //
    // N strokes → N dots at y = topInset + (i + 0.5) * verticalSpacing,
    // so the minimum bounding height is topInset + N * verticalSpacing
    // (half a stride of breathing room above the first dot + below
    // the last). An earlier version reserved an extra full stride
    // below the last dot; Stage 3-E's Box-layered HomeScreen exposed
    // that as a visible gap beneath the final card. (Initial-review
    // catch.)
    val totalHeight = if (strokes.size < 2) 0.dp else topInset + verticalSpacing * strokes.size
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

            // Antisymmetric control points produce a gentle S-curve
            // between the two dots: cp1 leans the same direction as the
            // seed, cp2 leans the opposite. Matches the iOS renderer
            // and is what makes the thread feel hand-drawn rather than
            // mechanically offset.
            val cpOffset = meanderSeed(strokes[i]) * maxMeanderPx * 0.4f

            val path = buildRibbonPath(
                startX = sx, startY = sy,
                endX = ex, endY = ey,
                halfWidth = halfWidth,
                cpOffsetX = cpOffset,
                verticalSpacingPx = verticalSpacingPx,
            )
            drawInkRibbon(path, strokes[i].ink, segmentOpacity(i, strokes.size))
        }
    }
}

/**
 * Builds the filled-polygon [Path] for a single ribbon segment between
 * `(startX, startY)` and `(endX, endY)`, with [halfWidth] offset on
 * each side and antisymmetric control points scaled by [cpOffsetX].
 *
 * Extracted out of the Canvas draw lambda so a Robolectric test can
 * actually exercise `Path` + `cubicTo` + `close` directly — without
 * having to rely on Compose's draw pipeline firing under a stub
 * rendering backend. (Closing-review catch.)
 */
internal fun buildRibbonPath(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    halfWidth: Float,
    cpOffsetX: Float,
    verticalSpacingPx: Float,
): Path {
    val midY = (startY + endY) / 2f
    val cp1X = startX + cpOffsetX
    val cp1Y = midY - verticalSpacingPx * 0.2f
    val cp2X = endX - cpOffsetX
    val cp2Y = midY + verticalSpacingPx * 0.2f
    return Path().apply {
        moveTo(startX - halfWidth, startY)
        cubicTo(
            cp1X - halfWidth, cp1Y,
            cp2X - halfWidth, cp2Y,
            endX - halfWidth, endY,
        )
        lineTo(endX + halfWidth, endY)
        cubicTo(
            cp2X + halfWidth, cp2Y,
            cp1X + halfWidth, cp1Y,
            startX + halfWidth, startY,
        )
        close()
    }
}

private fun DrawScope.drawInkRibbon(
    path: Path,
    ink: Color,
    opacity: Float,
) {
    drawPath(path = path, color = ink.copy(alpha = opacity))
}

/**
 * Per-row dot center coordinate emitted alongside the existing ribbon
 * draw. Stage 14 promotes the renderer's internal dot-Y math to a pure
 * top-level helper so the Journal screen's scroll-haptic state machine
 * + lunar / milestone / date-divider overlays can read positions without
 * redrawing the path.
 */
data class DotPosition(val centerXPx: Float, val yPx: Float)

/**
 * Public helper exposing the same `(x, y)` formula the renderer uses
 * internally. Pure function — no Canvas dependency, no Compose state.
 *
 * - x: `xOffsetPx(spec, centerXPx, maxMeanderPx)` — verbatim with the
 *   in-renderer call, so the dots a downstream consumer expects to
 *   align with line up byte-for-byte with what the path actually drew.
 * - y: `topInsetPx + verticalSpacingPx * index + verticalSpacingPx / 2f`
 *   — the renderer's `i * verticalSpacingPx + verticalSpacingPx / 2f`
 *   centering, plus `topInsetPx`.
 *
 * Stage 3-C kept this math private; Stage 14 makes it additive without
 * touching the existing draw lambda.
 */
fun dotPositions(
    strokes: List<CalligraphyStrokeSpec>,
    widthPx: Float,
    verticalSpacingPx: Float,
    topInsetPx: Float,
    maxMeanderPx: Float,
): List<DotPosition> {
    val centerX = widthPx / 2f
    return strokes.mapIndexed { index, spec ->
        DotPosition(
            centerXPx = xOffsetPx(spec, centerX, maxMeanderPx),
            yPx = topInsetPx + verticalSpacingPx * index + verticalSpacingPx / 2f,
        )
    }
}
