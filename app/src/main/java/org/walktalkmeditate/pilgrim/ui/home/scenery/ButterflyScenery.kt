// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` butterfly branch — 4 wing ellipses
 * (upper/lower × left/right) + body capsule, with wing flap (sin time
 * × 3.8) scaling Y-axis, plus drift + wobble + small rotate.
 *
 * Wing color is provided by caller — varies seasonally (spring pink,
 * summer dawn, autumn rust, winter near-white).
 */
@Composable
internal fun ButterflyScenery(
    sizeDp: Dp,
    wingColor: Color,
) {
    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        val wingFlap = ((sin(timeSec * 3.8) + 1.0) / 2.0).toFloat()
        val drift = (sin(timeSec * 0.4) * 3.0).toFloat()
        val wobble = (sin(timeSec * 0.7) * 4.0).toFloat()

        translate(left = wobble, top = drift) {
            rotate(wobble * 0.5f, pivot = Offset(cx, cy)) {
                // Left upper wing
                drawWing(
                    cx - s * 0.22f, cy - s * 0.05f,
                    s * 0.45f, s * 0.35f,
                    wingColor.copy(alpha = 0.20f),
                    yScale = 0.3f + wingFlap * 0.7f,
                )
                // Left lower wing
                drawWing(
                    cx - s * 0.18f, cy + s * 0.12f,
                    s * 0.35f, s * 0.25f,
                    wingColor.copy(alpha = 0.15f),
                    yScale = 0.4f + wingFlap * 0.6f,
                )
                // Right upper wing
                drawWing(
                    cx + s * 0.22f, cy - s * 0.05f,
                    s * 0.45f, s * 0.35f,
                    wingColor.copy(alpha = 0.20f),
                    yScale = 0.3f + wingFlap * 0.7f,
                )
                // Right lower wing
                drawWing(
                    cx + s * 0.18f, cy + s * 0.12f,
                    s * 0.35f, s * 0.25f,
                    wingColor.copy(alpha = 0.15f),
                    yScale = 0.4f + wingFlap * 0.6f,
                )
                // Body — small dark capsule centered
                drawOval(
                    color = wingColor.copy(alpha = 0.30f),
                    topLeft = Offset(cx - s * 0.025f, cy - s * 0.15f),
                    size = GeomSize(s * 0.05f, s * 0.30f),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWing(
    centerX: Float,
    centerY: Float,
    width: Float,
    height: Float,
    color: Color,
    yScale: Float,
) {
    scale(scaleX = 1f, scaleY = yScale, pivot = Offset(centerX, centerY)) {
        drawOval(
            color = color,
            topLeft = Offset(centerX - width / 2f, centerY - height / 2f),
            size = GeomSize(width, height),
        )
    }
}
