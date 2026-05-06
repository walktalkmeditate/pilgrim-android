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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` mountain branch — 3 layered mountain
 * ranges (back/middle/front) + drifting low-mist ellipse + alpenglow
 * (radial gradient, morning hours only) + snow triangles (winter only).
 *
 * Snow uses synthesized phase-animated opacity since iOS uses
 * `phaseAnimator([false, true])` which has no Compose equivalent — we
 * approximate with a slow sine.
 */
@Composable
internal fun MountainScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
    dawnColor: Color,
) {
    val zonedDate = remember(walkDateMs) {
        Instant.ofEpochMilli(walkDateMs).atZone(ZoneId.systemDefault())
    }
    val month = zonedDate.monthValue
    val hour = zonedDate.hour
    val hasSnow = month <= 3 || month >= 11
    val isMorning = hour in 5..9

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        val mistDrift = (sin(timeSec * 0.3) * s * 0.15).toFloat()
        val mistOpacity = ((sin(timeSec * 0.2) + 1.0) / 2.0 * 0.12 + 0.04).toFloat()

        // Alpenglow — radial gradient for morning hours.
        if (isMorning) {
            translate(left = cx - s * 0.7f, top = cy - s * 0.5f - s * 0.2f) {
                drawOval(
                    brush = Brush.radialGradient(
                        colors = listOf(dawnColor.copy(alpha = 0.15f), Color.Transparent),
                        center = Offset(s * 0.7f, s * 0.5f),
                        radius = s * 0.7f,
                    ),
                    topLeft = Offset.Zero,
                    size = GeomSize(s * 1.4f, s * 1.0f),
                )
            }
        }

        // Background range — slightly larger, soft alpha.
        translate(left = cx - s * 1.2f / 2f - s * 0.1f, top = cy - s * 1.1f / 2f + s * 0.05f) {
            drawPath(
                path = mountainPath(GeomSize(s * 1.2f, s * 1.1f)),
                color = tintColor.copy(alpha = 0.08f),
            )
        }

        // Middle range — medium alpha.
        translate(left = cx - s * 1.05f / 2f + s * 0.08f, top = cy - s * 1.02f / 2f + s * 0.02f) {
            drawPath(
                path = mountainPath(GeomSize(s * 1.05f, s * 1.02f)),
                color = tintColor.copy(alpha = 0.15f),
            )
        }

        // Foreground range — main silhouette.
        translate(left = cx - s / 2f, top = cy - s / 2f) {
            drawPath(
                path = mountainPath(GeomSize(s, s)),
                color = tintColor.copy(alpha = 0.30f),
            )
        }

        // Drifting low mist
        drawOval(
            color = tintColor.copy(alpha = mistOpacity.coerceIn(0f, 1f)),
            topLeft = Offset(cx - s * 0.7f / 2f + mistDrift, cy + s * 0.1f - s * 0.05f),
            size = GeomSize(s * 0.7f, s * 0.1f),
        )

        if (hasSnow) {
            val snowPulse1 = ((sin(timeSec * 1.5) + 1.0) / 2.0).toFloat()
            val snowPulse2 = ((sin(timeSec * 1.2 + 0.5) + 1.0) / 2.0).toFloat()
            // Big snow cap
            translate(left = cx + s * 0.1f - s * 0.11f, top = cy - s * 0.38f - s * 0.065f) {
                drawPath(
                    path = trianglePath(GeomSize(s * 0.22f, s * 0.13f)),
                    color = Color.White.copy(alpha = 0.15f + snowPulse1 * 0.15f),
                )
            }
            // Small snow cap
            translate(left = cx - s * 0.08f - s * 0.07f, top = cy - s * 0.28f - s * 0.045f) {
                drawPath(
                    path = trianglePath(GeomSize(s * 0.14f, s * 0.09f)),
                    color = Color.White.copy(alpha = 0.08f + snowPulse2 * 0.12f),
                )
            }
        }
    }
}
