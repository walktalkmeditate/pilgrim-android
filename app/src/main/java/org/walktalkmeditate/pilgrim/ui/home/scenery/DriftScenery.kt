// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` drift branch — the season's breath.
 * One type, four faces: petals in spring, fireflies on summer evenings,
 * red dragonflies in autumn, a sparse snow flurry in winter. The only
 * scenery that moves through the landscape instead of standing in it.
 *
 * Fireflies glow only when the walk met the dark ([walkMetTheDark]);
 * daylight summer walks get dim static motes in the type tint.
 */
@Composable
internal fun DriftScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
) {
    val zonedDate = remember(walkDateMs) {
        Instant.ofEpochMilli(walkDateMs).atZone(ZoneId.systemDefault())
    }
    val face = driftFace(zonedDate.monthValue)
    val lit = walkMetTheDark(zonedDate.hour)

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        when (face) {
            DriftFace.SpringPetals -> drawPetalDrift(timeSec, cx, cy, s)
            DriftFace.SummerFireflies -> drawFireflies(timeSec, cx, cy, s, lit, tintColor)
            DriftFace.AutumnDragonflies -> drawDragonflies(timeSec, cx, cy, s)
            DriftFace.WinterFlurry -> drawSnowFlurry(timeSec, cx, cy, s)
        }
    }
}

internal enum class DriftFace { SpringPetals, SummerFireflies, AutumnDragonflies, WinterFlurry }

internal fun driftFace(month: Int): DriftFace = when (month) {
    in 3..5 -> DriftFace.SpringPetals
    in 6..8 -> DriftFace.SummerFireflies
    in 9..11 -> DriftFace.AutumnDragonflies
    else -> DriftFace.WinterFlurry
}

// Per-particle tables, U15-spec verbatim — pinned by DriftSceneryTest
// the way CairnScenery pins cairnStoneRects and ToriiScenery pins
// ShimenawaGeometry.

/** (phase, speed, radius fraction) per petal. */
internal val PETAL_PARTICLES: List<Triple<Float, Float, Float>> = listOf(
    Triple(0.0f, 0.09f, 0.055f),
    Triple(2.1f, 0.13f, 0.045f),
    Triple(4.0f, 0.07f, 0.06f),
    Triple(1.2f, 0.11f, 0.04f),
    Triple(5.3f, 0.15f, 0.05f),
)

/** (phase, x wander frequency, y wander frequency) per mote. */
internal val FIREFLY_MOTES: List<Triple<Float, Float, Float>> = listOf(
    Triple(0.0f, 0.31f, 0.23f),
    Triple(2.4f, 0.19f, 0.37f),
    Triple(4.7f, 0.27f, 0.17f),
)

/** Hover phase per dragonfly. */
internal val DRAGONFLY_PHASES: List<Double> = listOf(0.0, 2.6)

internal val SNOW_FLAKES: List<Flake> = listOf(
    Flake(0.0f, 0.10f, -0.30f, 0.030f),
    Flake(1.7f, 0.14f, 0.10f, 0.022f),
    Flake(3.2f, 0.08f, 0.35f, 0.026f),
    Flake(4.5f, 0.12f, -0.12f, 0.020f),
    Flake(2.6f, 0.09f, 0.24f, 0.028f),
    Flake(5.5f, 0.13f, -0.38f, 0.018f),
)

internal data class Flake(val phase: Float, val speed: Float, val x: Float, val r: Float)

private fun DrawScope.drawPetalDrift(timeSec: Float, cx: Float, cy: Float, s: Float) {
    for ((phase, speed, r) in PETAL_PARTICLES) {
        val progress = ((timeSec * speed + phase) % 1.6f) / 1.6f
        val px = cx + s * (-0.45f + progress * 0.9f) +
            (sin(timeSec * 0.8 + phase) * 3.0).toFloat()
        val py = cy + s * (-0.35f + progress * 0.75f)
        val w = s * r * 2f
        val h = s * r * 1.3f
        rotate(progress * 220f + phase * 40f, pivot = Offset(px, py)) {
            drawOval(
                color = PETAL_PINK.copy(alpha = 0.35f * (1f - progress * 0.5f)),
                topLeft = Offset(px - w / 2f, py - h / 2f),
                size = GeomSize(w, h),
            )
        }
    }
}

private fun DrawScope.drawFireflies(
    timeSec: Float,
    cx: Float,
    cy: Float,
    s: Float,
    lit: Boolean,
    tintColor: Color,
) {
    for ((phase, fx, fy) in FIREFLY_MOTES) {
        val pulse = if (lit) ((sin(timeSec * 1.7 + phase * 2.0) + 1.0) / 2.0).toFloat() else 0f
        val color = if (lit) {
            FIREFLY_GLOW.copy(alpha = 0.12f + pulse * 0.38f)
        } else {
            tintColor.copy(alpha = 0.14f)
        }
        drawCircle(
            color = color,
            radius = s * 0.035f,
            center = Offset(
                cx + (sin(timeSec * fx + phase) * s * 0.4).toFloat(),
                cy + (cos(timeSec * fy + phase * 1.3) * s * 0.32).toFloat(),
            ),
        )
    }
}

private fun DrawScope.drawDragonflies(timeSec: Float, cx: Float, cy: Float, s: Float) {
    for (phase in DRAGONFLY_PHASES) {
        // Hover with the occasional sideways dart.
        val x = (sin(timeSec * 0.4 + phase) * s * 0.32 + sin(timeSec * 2.3 + phase) * s * 0.06).toFloat()
        val y = (cos(timeSec * 0.7 + phase) * s * 0.2 + sin(timeSec * 3.1 + phase) * 2.0).toFloat()
        val center = Offset(cx + x, cy + y)
        val tilt = (sin(timeSec * 0.9 + phase) * 14.0).toFloat()

        rotate(tilt, pivot = center) {
            drawRoundRect(
                color = DRAGONFLY_BODY.copy(alpha = 0.4f),
                topLeft = Offset(center.x - s * 0.08f, center.y - s * 0.014f),
                size = GeomSize(s * 0.16f, s * 0.028f),
                cornerRadius = CornerRadius(s * 0.014f, s * 0.014f),
            )
            drawDragonflyWing(center, s, angleDeg = -24f, yOffset = -s * 0.02f)
            drawDragonflyWing(center, s, angleDeg = 24f, yOffset = s * 0.02f)
        }
    }
}

private fun DrawScope.drawDragonflyWing(
    bodyCenter: Offset,
    s: Float,
    angleDeg: Float,
    yOffset: Float,
) {
    val wingCenter = Offset(bodyCenter.x - s * 0.01f, bodyCenter.y + yOffset)
    val w = s * 0.09f
    val h = s * 0.03f
    rotate(angleDeg, pivot = wingCenter) {
        drawOval(
            color = Color.White.copy(alpha = 0.25f),
            topLeft = Offset(wingCenter.x - w / 2f, wingCenter.y - h / 2f),
            size = GeomSize(w, h),
        )
    }
}

private fun DrawScope.drawSnowFlurry(timeSec: Float, cx: Float, cy: Float, s: Float) {
    for (flake in SNOW_FLAKES) {
        val progress = ((timeSec * flake.speed + flake.phase) % 1.4f) / 1.4f
        drawCircle(
            color = Color.White.copy(alpha = 0.32f * (1f - progress * 0.35f)),
            radius = s * flake.r,
            center = Offset(
                cx + s * flake.x + (sin(timeSec * 0.6 + flake.phase) * 2.5).toFloat(),
                cy + s * (-0.4f + progress * 0.85f),
            ),
        )
    }
}

/** iOS literal Color(1.0, 0.75, 0.82). */
private val PETAL_PINK = Color(0xFFFFBFD1)

/** iOS literal Color(0.95, 0.87, 0.55). */
private val FIREFLY_GLOW = Color(0xFFF2DE8C)

/** iOS literal Color(0.72, 0.30, 0.22). */
private val DRAGONFLY_BODY = Color(0xFFB84D38)
