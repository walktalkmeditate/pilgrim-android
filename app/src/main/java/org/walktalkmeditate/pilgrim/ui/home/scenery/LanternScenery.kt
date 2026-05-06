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
 * Port of `SceneryItemView.swift` lantern branch — three-frequency
 * flicker (3.7/5.3/7.1 Hz multipliers from iOS) layered over the static
 * lantern shape + warm window glow. Glow color shifts between dawn
 * (winter) and stone (other seasons).
 */
@Composable
internal fun LanternScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
    glowColor: Color,
) {
    val month = remember(walkDateMs) {
        Instant.ofEpochMilli(walkDateMs).atZone(ZoneId.systemDefault()).monthValue
    }
    @Suppress("UNUSED_VARIABLE")
    val isWinter = month == 12 || month <= 2

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        val flicker1 = (sin(timeSec * 3.7) * 0.15).toFloat()
        val flicker2 = (sin(timeSec * 5.3) * 0.08).toFloat()
        val flicker3 = (sin(timeSec * 7.1) * 0.05).toFloat()
        val glow = (0.35f + flicker1 + flicker2 + flicker3).coerceIn(0f, 1f)

        // Warm radial glow behind the lantern. Radial gradient fades
        // from center to transparent so it reads as light, not a
        // visible circle. User feedback: solid drawCircle showed as
        // a translucent disk; gradient gives the lighting feel.
        val glowCenter = Offset(cx, cy - s * 0.1f)
        val glowRadius = s * 1.0f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    glowColor.copy(alpha = (glow * 0.55f).coerceIn(0f, 1f)),
                    glowColor.copy(alpha = (glow * 0.2f).coerceIn(0f, 1f)),
                    Color.Transparent,
                ),
                center = glowCenter,
                radius = glowRadius,
            ),
            radius = glowRadius,
            center = glowCenter,
        )

        // Outer ghost layer — slight offset, low alpha (matches iOS blur(1.2)).
        translate(left = cx - s * 1.06f / 2f + 1.5f, top = cy - s * 1.06f / 2f + 1.5f) {
            drawPath(
                path = lanternPath(GeomSize(s * 1.06f, s * 1.06f)),
                color = tintColor.copy(alpha = 0.10f),
            )
        }

        // Main lantern body
        translate(left = cx - s / 2f, top = cy - s / 2f) {
            drawPath(
                path = lanternPath(GeomSize(s, s)),
                color = tintColor.copy(alpha = 0.35f),
            )
            // Window — warm flickering glow
            drawPath(
                path = lanternWindowPath(GeomSize(s, s)),
                color = glowColor.copy(alpha = glow.coerceIn(0f, 1f)),
            )
        }
    }
}
