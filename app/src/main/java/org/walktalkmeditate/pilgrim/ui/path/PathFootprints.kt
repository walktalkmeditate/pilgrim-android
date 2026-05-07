// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.design.scenery.footprintPath
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

private val FRAME_WIDTH = 60.dp
private val FRAME_HEIGHT = 40.dp

/**
 * Per-mode footstep glyph above each `ModeButton` label — verbatim port
 * of iOS `WalkStartView.footprintForMode`. Wander = two prints rotated
 * outward; Together = three pairs orbiting; Seek = single print + a
 * stack of dissolving dots. Frame is 60×40 dp to match iOS.
 *
 * Active mode renders fully opaque + a subtle 1.01× breath scale; the
 * inactive modes render at 0 opacity (slot reserved so the underline
 * stays vertically aligned).
 */
@Composable
fun PathFootprints(
    mode: WalkMode,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    val breathScale by animateFloatAsState(
        targetValue = when {
            !isActive -> 0.92f
            reduceMotion -> 1.0f
            else -> 1.01f
        },
        animationSpec = tween(durationMillis = 300),
        label = "breath",
    )
    val opacity by animateFloatAsState(
        targetValue = if (isActive) 1.0f else 0.0f,
        animationSpec = tween(durationMillis = 300),
        label = "opacity",
    )
    Box(
        modifier = modifier
            .size(width = FRAME_WIDTH, height = FRAME_HEIGHT)
            .graphicsLayer {
                alpha = opacity
                scaleX = breathScale
                scaleY = breathScale
            },
        contentAlignment = Alignment.Center,
    ) {
        when (mode) {
            WalkMode.Wander -> WanderFootprints()
            WalkMode.Together -> TogetherFootprints(reduceMotion = reduceMotion)
            WalkMode.Seek -> SeekFootprints(reduceMotion = reduceMotion)
        }
    }
}

@Composable
private fun WanderFootprints() {
    val ink = pilgrimColors.ink
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        FootprintGlyph(
            width = 16.dp, height = 26.dp,
            color = ink.copy(alpha = 0.08f),
            rotationDegrees = -12f,
            mirror = true,
        )
        FootprintGlyph(
            width = 16.dp, height = 26.dp,
            color = ink.copy(alpha = 0.06f),
            rotationDegrees = 12f,
        )
    }
}

@Composable
private fun TogetherFootprints(reduceMotion: Boolean) {
    val ink = pilgrimColors.ink
    val drift by if (reduceMotion) {
        animateFloatAsState(targetValue = 0f, label = "together-static")
    } else {
        rememberInfiniteTransition(label = "together-drift").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "drift",
        )
    }
    Box(modifier = Modifier.size(width = FRAME_WIDTH, height = FRAME_HEIGHT)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = (-14f - drift * 1f) * density
                    translationY = (-10f + drift * 0.5f) * density
                    alpha = 0.7f
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FootprintGlyph(14.dp, 22.dp, ink.copy(alpha = 0.06f), -18f, mirror = true)
                FootprintGlyph(14.dp, 22.dp, ink.copy(alpha = 0.05f), 6f)
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = (12f + drift * 1f) * density
                    translationY = (-8f - drift * 0.5f) * density
                    alpha = 0.7f
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FootprintGlyph(14.dp, 22.dp, ink.copy(alpha = 0.05f), 8f, mirror = true)
                FootprintGlyph(14.dp, 22.dp, ink.copy(alpha = 0.04f), -16f)
            }
        }
        Box(modifier = Modifier.align(Alignment.Center)) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                FootprintGlyph(16.dp, 26.dp, ink.copy(alpha = 0.10f), -12f, mirror = true)
                FootprintGlyph(16.dp, 26.dp, ink.copy(alpha = 0.08f), 12f)
            }
        }
    }
}

@Composable
private fun SeekFootprints(reduceMotion: Boolean) {
    val ink = pilgrimColors.ink
    val float by if (reduceMotion) {
        animateFloatAsState(targetValue = 0f, label = "seek-static")
    } else {
        rememberInfiniteTransition(label = "seek-float").animateFloat(
            initialValue = -2f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "float",
        )
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        FootprintGlyph(16.dp, 26.dp, ink.copy(alpha = 0.10f), -12f, mirror = true)
        DissolvingDots(
            ink = ink,
            modifier = Modifier
                .size(width = 16.dp, height = 30.dp)
                .rotate(12f)
                .graphicsLayer { translationY = float * density },
        )
    }
}

@Composable
private fun FootprintGlyph(
    width: Dp,
    height: Dp,
    color: Color,
    rotationDegrees: Float,
    mirror: Boolean = false,
) {
    Canvas(
        modifier = Modifier
            .size(width = width, height = height)
            .rotate(rotationDegrees)
            .scale(scaleX = if (mirror) -1f else 1f, scaleY = 1f),
    ) {
        drawPath(
            path = footprintPath(Size(size.width, size.height)),
            color = color,
        )
    }
}

@Composable
private fun DissolvingDots(
    ink: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val dots = listOf(
            Triple(Offset(w * 0.5f, h * 0.85f), w * 0.18f, 0.10f),
            Triple(Offset(w * 0.5f, h * 0.65f), w * 0.16f, 0.08f),
            Triple(Offset(w * 0.5f, h * 0.45f), w * 0.13f, 0.06f),
            Triple(Offset(w * 0.5f, h * 0.25f), w * 0.10f, 0.04f),
            Triple(Offset(w * 0.5f, h * 0.05f), w * 0.06f, 0.02f),
        )
        dots.forEach { (center, radius, alpha) ->
            drawCircle(
                color = ink.copy(alpha = alpha),
                radius = radius,
                center = center,
            )
        }
    }
}
