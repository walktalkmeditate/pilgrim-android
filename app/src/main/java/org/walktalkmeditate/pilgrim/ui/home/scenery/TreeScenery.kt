// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` tree branch — winter bare branches in
 * Dec/Jan/Feb, full canopy otherwise, with falling-leaf flecks in autumn
 * (rust) and spring (pink). Sway driven by three layered sines.
 *
 * Reduce-Motion: when `LocalReduceMotion = true`, the time source freezes
 * at 0 so the sway / falling-leaf positions collapse to a static frame.
 */
@Composable
internal fun TreeScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
) {
    val month = remember(walkDateMs) {
        Instant.ofEpochMilli(walkDateMs).atZone(ZoneId.systemDefault()).monthValue
    }
    val isWinter = month == 12 || month <= 2
    val isAutumn = month in 9..11
    val isSpring = month in 3..5

    val autumnLeafColor = Color(0xFFA0634B) // rust at Full intensity
    val springLeafColor = Color(0xFFFFB3CC) // (1.0, 0.7, 0.8) iOS verbatim

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        val sway1 = (sin(timeSec * 0.6) * 1.5).toFloat()
        val sway2 = (sin(timeSec * 1.3) * 0.8).toFloat()
        val gust = (sin(timeSec * 0.2) * sin(timeSec * 0.2) * 2.5).toFloat()
        val totalSway = sway1 + sway2 + gust

        if (isWinter) {
            translate(left = cx - s / 2f, top = cy - s / 2f) {
                rotate(totalSway * 0.3f, pivot = Offset(s / 2f, s)) {
                    drawPath(
                        path = winterTreePath(GeomSize(s, s)),
                        color = tintColor.copy(alpha = 0.25f),
                    )
                }
            }
        } else {
            // Outer blurred ghost
            translate(
                left = cx - s * 1.08f / 2f + totalSway * 0.4f + 1.5f,
                top = cy - s * 1.08f / 2f + 1f,
            ) {
                drawPath(
                    path = treePath(GeomSize(s * 1.08f, s * 1.08f)),
                    color = tintColor.copy(alpha = 0.12f),
                )
            }
            // Main canopy
            translate(left = cx - s / 2f, top = cy - s / 2f) {
                rotate(totalSway * 0.5f, pivot = Offset(s / 2f, s)) {
                    drawPath(
                        path = treePath(GeomSize(s, s)),
                        color = tintColor.copy(alpha = 0.30f),
                    )
                }
            }
            // Inner softer layer
            translate(left = cx - s * 0.88f / 2f - 1f, top = cy - s * 0.88f / 2f + 1f) {
                rotate(totalSway * 0.3f, pivot = Offset(s * 0.88f / 2f, s * 0.88f)) {
                    drawPath(
                        path = treePath(GeomSize(s * 0.88f, s * 0.88f)),
                        color = tintColor.copy(alpha = 0.12f),
                    )
                }
            }
        }

        if (isAutumn) drawFallingLeaves(timeSec, cx, cy, s, autumnLeafColor)
        if (isSpring) drawFallingLeaves(timeSec, cx, cy, s, springLeafColor)
    }
}

private fun DrawScope.drawFallingLeaves(
    timeSec: Float,
    cx: Float,
    cy: Float,
    s: Float,
    color: Color,
) {
    val leaves = listOf(
        Triple(0.0f, 0.7f, -0.3f),
        Triple(1.5f, 0.9f, 0.2f),
        Triple(3.0f, 0.6f, 0.1f),
        Triple(4.2f, 0.8f, -0.15f),
    )
    for ((phase, speed, xOff) in leaves) {
        var t = (timeSec * speed + phase) % 5f
        if (t < 0) t += 5f
        val progress = t / 5f
        val leafX = sin(t * 2.0).toFloat() * s * 0.2f + s * xOff
        val leafY = -s * 0.3f + progress * s * 0.9f
        val opacity = when {
            progress < 0.1f -> progress / 0.1f
            progress > 0.7f -> (1f - progress) / 0.3f
            else -> 1f
        }.coerceIn(0f, 1f)
        drawCircle(
            color = color.copy(alpha = 0.4f * opacity),
            radius = s * 0.03f,
            center = Offset(cx + leafX, cy + leafY),
        )
    }
}

/**
 * Shared infinite-transition driver — animates a Float (seconds) from 0
 * to a long period (300 s) on a linear easing curve, then loops. All
 * scenery types call this so individual sway/flicker/drift derivations
 * stay phase-locked and battery-cheap.
 *
 * `LocalReduceMotion = true` returns a constant t=0 — sub-effects collapse
 * to a static frame. `derivedStateOf` would be redundant since the State
 * is the underlying animation State.
 */
@Composable
internal fun sceneryTimeSeconds(): androidx.compose.runtime.State<Float> {
    if (LocalReduceMotion.current) {
        return remember { androidx.compose.runtime.mutableStateOf(0f) }
    }
    val transition = rememberInfiniteTransition(label = "scenery-clock")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 300f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300_000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scenery-time",
    )
}

@Suppress("unused")
private fun warmCos() = cos(0.0)
