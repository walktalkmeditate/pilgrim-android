// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` torii branch — gateway with radial
 * glow (slow phase pulse), base shadow ellipse, two layered ToriiGate
 * shapes (ghost + main), shimenawa rope (quad-curve), and 3 fluttering
 * shide (zigzag white strips).
 *
 * The gate kind shapes the treatment: practice gates stand vermilion
 * (rust tint, resolved upstream via `SceneryPlacement.tintTokenName`);
 * seeking gates are weathered stone — a heavier 0.45 fill and moss
 * creeping up the pillars.
 */
@Composable
internal fun ToriiScenery(
    sizeDp: Dp,
    tintColor: Color,
    dawnColor: Color,
    gateKind: WalkThreshold? = null,
) {
    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        // Radial glow (slow phase pulse — 3.5 s easing in iOS).
        val glowPulse = ((sin(timeSec * (Math.PI / 3.5)) + 1.0) / 2.0).toFloat() // 0..1
        val glowAlpha = 0.5f + glowPulse * 0.5f
        translate(left = cx - s * 0.3f, top = cy - s * 0.4f - s * 0.05f) {
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        dawnColor.copy(alpha = 0.08f * glowAlpha),
                        Color.Transparent,
                    ),
                    center = Offset(s * 0.3f, s * 0.4f),
                    radius = s * 0.4f,
                ),
                topLeft = Offset.Zero,
                size = GeomSize(s * 0.6f, s * 0.8f),
            )
        }

        // Base shadow under the gate.
        drawOval(
            color = tintColor.copy(alpha = 0.06f),
            topLeft = Offset(cx - s * 0.45f, cy + s * 0.48f - s * 0.075f),
            size = GeomSize(s * 0.9f, s * 0.15f),
        )

        // Ghost layer — slight offset, low alpha
        translate(left = cx - s * 1.05f / 2f + 1.5f, top = cy - s * 1.05f / 2f + 2f) {
            drawPath(
                path = toriiGatePath(GeomSize(s * 1.05f, s * 1.05f)),
                color = tintColor.copy(alpha = 0.08f),
            )
        }

        // Main gate — seeking gates fill heavier, stone that has stood.
        translate(left = cx - s / 2f, top = cy - s / 2f) {
            drawPath(
                path = toriiGatePath(GeomSize(s, s)),
                color = tintColor.copy(alpha = toriiFillAlpha(gateKind)),
            )
        }

        // Weathered stone gates grow moss that creeps up the pillars —
        // the seeking thresholds have stood longer than memory. Heavier
        // on the left, the way weather leans on real stone.
        for (patch in mossPatchesFor(gateKind)) {
            drawOval(
                color = MOSS_GREEN.copy(alpha = patch.alpha),
                topLeft = Offset(
                    cx + s * patch.xFraction - s * patch.widthFraction / 2f,
                    cy + s * patch.yFraction - s * patch.heightFraction / 2f,
                ),
                size = GeomSize(s * patch.widthFraction, s * patch.heightFraction),
            )
        }

        // Rope and shide
        drawRopeAndShide(timeSec, cx, cy, s, tintColor)
    }
}

internal fun toriiFillAlpha(gateKind: WalkThreshold?): Float =
    if (gateKind == WalkThreshold.Seeking) 0.45f else 0.35f

internal fun mossPatchesFor(gateKind: WalkThreshold?): List<MossPatch> =
    if (gateKind == WalkThreshold.Seeking) SEEKING_MOSS_PATCHES else emptyList()

/** Center-relative ellipse geometry in gate-size fractions. */
internal data class MossPatch(
    val widthFraction: Float,
    val heightFraction: Float,
    val xFraction: Float,
    val yFraction: Float,
    val alpha: Float,
)

/** iOS `seekingMoss` verbatim (`SceneryItemView.swift:702-726@c1745e8`). */
internal val SEEKING_MOSS_PATCHES: List<MossPatch> = listOf(
    MossPatch(0.20f, 0.08f, -0.29f, 0.45f, 0.50f),
    MossPatch(0.09f, 0.14f, -0.24f, 0.36f, 0.38f),
    MossPatch(0.06f, 0.09f, -0.29f, 0.26f, 0.26f),
    MossPatch(0.15f, 0.06f, 0.31f, 0.46f, 0.44f),
    MossPatch(0.07f, 0.11f, 0.27f, 0.38f, 0.30f),
)

/** iOS literal Color(0.45, 0.52, 0.35) — fixed, not seasonal. */
internal val MOSS_GREEN = Color(0xFF738559)

/**
 * Shimenawa/shide geometry in top-left gate-frame fractions, pinned to
 * iOS's eed14d1 fix: the rope hangs UNDER the nuki crossbeam
 * (`toriiGatePath` draws the nuki at y 0.30–0.34, pillar inner edges
 * near 0.27/0.73) instead of floating off center-origin coordinates.
 */
internal object ShimenawaGeometry {
    const val ROPE_Y = 0.33f
    const val ROPE_LEFT_X = 0.28f
    const val ROPE_RIGHT_X = 0.72f
    const val ROPE_SAG = 0.06f
    val SHIDE_X = listOf(0.37f, 0.49f, 0.61f)
}

private fun DrawScope.drawRopeAndShide(
    timeSec: Float,
    cx: Float,
    cy: Float,
    s: Float,
    tintColor: Color,
) {
    val frameLeft = cx - s / 2f
    val frameTop = cy - s / 2f
    val ropeY = frameTop + s * ShimenawaGeometry.ROPE_Y
    val leftX = frameLeft + s * ShimenawaGeometry.ROPE_LEFT_X
    val rightX = frameLeft + s * ShimenawaGeometry.ROPE_RIGHT_X

    val rope = Path().apply {
        moveTo(leftX, ropeY)
        quadraticBezierTo(
            frameLeft + s * 0.5f,
            ropeY + s * ShimenawaGeometry.ROPE_SAG,
            rightX,
            ropeY,
        )
    }
    drawPath(
        path = rope,
        color = tintColor.copy(alpha = 0.20f),
        style = Stroke(width = 1f),
    )

    for ((i, xFraction) in ShimenawaGeometry.SHIDE_X.withIndex()) {
        val flutter = (sin(timeSec * 2.0 + i * 1.2) * 2.5).toFloat()
        val stripX = frameLeft + s * xFraction

        val shide = Path().apply {
            moveTo(stripX, ropeY + s * 0.03f)
            lineTo(stripX + flutter * 0.3f, ropeY + s * 0.08f)
            lineTo(stripX + s * 0.03f, ropeY + s * 0.08f)
            lineTo(stripX + s * 0.03f + flutter * 0.5f, ropeY + s * 0.14f)
            lineTo(stripX - s * 0.01f, ropeY + s * 0.14f)
            lineTo(stripX - s * 0.01f + flutter * 0.4f, ropeY + s * 0.19f)
        }
        drawPath(
            path = shide,
            color = Color.White.copy(alpha = 0.20f),
            style = Stroke(width = 0.8f),
        )
    }
}
