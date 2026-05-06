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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` torii branch — gateway with radial
 * glow (slow phase pulse), base shadow ellipse, two layered ToriiGate
 * shapes (ghost + main), shimenawa rope (quad-curve), and 3 fluttering
 * shide (zigzag white strips).
 */
@Composable
internal fun ToriiScenery(
    sizeDp: Dp,
    tintColor: Color,
    dawnColor: Color,
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

        // Main gate
        translate(left = cx - s / 2f, top = cy - s / 2f) {
            drawPath(
                path = toriiGatePath(GeomSize(s, s)),
                color = tintColor.copy(alpha = 0.35f),
            )
        }

        // Rope and shide
        drawRopeAndShide(timeSec, cx, cy, s, tintColor)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRopeAndShide(
    timeSec: Float,
    cx: Float,
    cy: Float,
    s: Float,
    tintColor: Color,
) {
    val ropeY = cy - s * 0.5f + s * 0.28f
    val leftX = cx - s * 0.22f
    val rightX = cx + s * 0.22f

    val rope = Path().apply {
        moveTo(leftX, ropeY)
        quadraticBezierTo(cx, ropeY + s * 0.06f, rightX, ropeY)
    }
    drawPath(
        path = rope,
        color = tintColor.copy(alpha = 0.20f),
        style = Stroke(width = 1f),
    )

    val shidePositions = listOf(-0.12f, 0.0f, 0.12f)
    for ((i, xPos) in shidePositions.withIndex()) {
        val flutter = (sin(timeSec * 2.0 + i * 1.2) * 2.5).toFloat()
        val stripX = cx + s * xPos

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
