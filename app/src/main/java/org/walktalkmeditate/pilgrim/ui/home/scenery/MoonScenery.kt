// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * Port of `SceneryItemView.swift` moon branch — phase-scaled crescent
 * disc (size varies daily), 6 white moonlight rays, 6 stars (each with
 * its own twinkle speed), 2 drifting parchment-ish clouds, and a halo
 * glow that pulses every 3 s.
 */
@Composable
internal fun MoonScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
    parchmentColor: Color,
) {
    val phaseScale = remember(walkDateMs) {
        val daysSinceEpoch = (walkDateMs / 86_400_000L).toInt()
        // iOS used `timeIntervalSinceReferenceDate` (2001 epoch) but the
        // 2001-vs-1970 offset is a fixed integer so the phase pattern
        // collapses to (abs(days) % 30) / 100 + 0.85.
        0.85f + (abs(daysSinceEpoch % 30)) / 100f
    }

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        // Moonlight rays
        val rayPulse = ((sin(timeSec * 0.3) + 1.0) / 2.0 * 0.04 + 0.02).toFloat()
        for (i in 0 until 6) {
            val angle = (i * 60.0 + sin(timeSec * 0.2) * 5.0).toFloat()
            translate(left = cx, top = cy) {
                rotate(angle, pivot = Offset.Zero) {
                    drawRect(
                        color = Color.White.copy(alpha = rayPulse.coerceIn(0f, 1f)),
                        topLeft = Offset(-s * 0.01f, -s * 0.6f - s * 0.15f),
                        size = GeomSize(s * 0.02f, s * 0.6f),
                    )
                }
            }
        }

        // Halo glow — pulses every 3 s.
        val haloPulse = ((sin(timeSec * (PI / 3.0)) + 1.0) / 2.0).toFloat()
        val haloAlpha = 0.05f * (0.4f + haloPulse * 0.4f)
        drawCircle(
            color = tintColor.copy(alpha = haloAlpha),
            radius = s * 0.9f,
            center = Offset(cx, cy),
        )

        // Crescent ghost (1.06 × phaseScale, low alpha, slight offset).
        val mSize = s * phaseScale
        translate(left = cx - mSize * 1.06f / 2f + 1f, top = cy - mSize * 1.06f / 2f + 1f) {
            drawCrescent(GeomSize(mSize * 1.06f, mSize * 1.06f), tintColor.copy(alpha = 0.10f))
        }

        // Main crescent
        translate(left = cx - mSize / 2f, top = cy - mSize / 2f) {
            drawCrescent(GeomSize(mSize, mSize), tintColor.copy(alpha = 0.35f))
        }

        // Inner highlight (0.92×, white-ish)
        translate(left = cx - mSize * 0.92f / 2f - 1f, top = cy - mSize * 0.92f / 2f - 1f) {
            drawCrescent(GeomSize(mSize * 0.92f, mSize * 0.92f), Color.White.copy(alpha = 0.10f))
        }

        // Stars
        val starData = listOf(
            Triple(-0.35f, -0.30f, 2.1),
            Triple(0.40f, -0.35f, 3.0),
            Triple(-0.25f, 0.30f, 1.7),
            Triple(0.35f, 0.25f, 2.5),
            Triple(-0.40f, 0.05f, 1.9),
            Triple(0.15f, -0.40f, 2.8),
        )
        for ((x, y, speed) in starData) {
            val twinkle = ((sin(timeSec * speed) + 1.0) / 2.0).toFloat()
            drawCircle(
                color = Color.White.copy(alpha = (0.15f + twinkle * 0.25f).coerceIn(0f, 1f)),
                radius = s * 0.02f,
                center = Offset(cx + s * x, cy + s * y),
            )
        }

        // Drifting clouds — parchment-ish ellipses
        val clouds = listOf(
            Triple(-0.05f, 0.15, 0.5f),
            Triple(0.10f, 0.10, 0.35f),
        )
        for ((yOff, speed, width) in clouds) {
            val drift = (sin(timeSec * speed) * s * 0.3).toFloat()
            val fadeEdge = ((cos(timeSec * speed * 0.8) + 1.0) / 2.0 * 0.06 + 0.03).toFloat()
            drawOval(
                color = parchmentColor.copy(alpha = fadeEdge.coerceIn(0f, 1f)),
                topLeft = Offset(cx - s * width / 2f + drift, cy + s * yOff - s * 0.06f),
                size = GeomSize(s * width, s * 0.12f),
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrescent(
    size: GeomSize,
    color: Color,
) {
    val (outer, inner) = moonOuterAndInner(size)
    val crescent = Path().apply { op(outer, inner, PathOperation.Difference) }
    drawPath(path = crescent, color = color)
}
