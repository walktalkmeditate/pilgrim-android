// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * The threshold into a seek — it teaches the seek language before the
 * walk begins: mist gathers (the fog to come), two sonar rings sound
 * silently outward (the pulse to come), and the mode's own line holds
 * the center. Every animation is a one-shot value change; the whole
 * sequence runs once and completes. iOS `SeekGatewayView`
 * (`SeekGatewayView.swift:8-104@c1745e8`).
 *
 * Timeline (non reduce-motion, `runGateway` @c1745e8):
 *   t=0     mist 0 → 0.5 opacity / 0.85 → 1.0 scale over 1.4 s
 *   t=0.8   quote fades in over 1.6 s
 *   t=2.0   ONE breath-in haptic ([onBreathMoment]) + ring one expands
 *           0.25 → 1.7 / 0.5 → 0 over 1.8 s — rings are SILENT
 *   t=3.1   ring two, opacity 0.4 → 0, same expansion, no second haptic
 *   t=4.9   everything fades out over 1.2 s (mist drifts to 1.06)
 *   t=6.2   [onComplete]
 * Reduce motion: text-only — quote in 0.4 s, out at 2.2 s, complete at
 * 2.6 s. No mist, no rings, no haptic.
 *
 * @param celestialLineRes a turning / full-moon override for the center
 *   line ([org.walktalkmeditate.pilgrim.domain.seek.SeekTint.gatewayLineRes]);
 *   null falls back to the mode's own first quote (iOS `Seek.Quote.1`).
 */
@Composable
fun SeekGatewayOverlay(
    celestialLineRes: Int?,
    onBreathMoment: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = LocalReduceMotion.current
    // Stage 4-B rule: LaunchedEffect-scoped delayed callbacks must read
    // the freshest lambda or a recompose with a new lambda fires stale
    // closures at the end of the 6.2 s run.
    val currentOnComplete by rememberUpdatedState(onComplete)
    val currentOnBreathMoment by rememberUpdatedState(onBreathMoment)

    val mistAlpha = remember { Animatable(0f) }
    val mistScale = remember { Animatable(0.85f) }
    val quoteAlpha = remember { Animatable(0f) }
    val ringOneScale = remember { Animatable(0.25f) }
    val ringOneAlpha = remember { Animatable(0f) }
    val ringTwoScale = remember { Animatable(0.25f) }
    val ringTwoAlpha = remember { Animatable(0f) }

    LaunchedEffect(reduceMotion) {
        if (reduceMotion) {
            quoteAlpha.animateTo(1f, tween(durationMillis = 400, easing = EaseIn))
            delay(2_200L - 400L)
            launch { quoteAlpha.animateTo(0f, tween(durationMillis = 300, easing = EaseOut)) }
            delay(400L)
            currentOnComplete()
            return@LaunchedEffect
        }

        launch { mistAlpha.animateTo(0.5f, tween(durationMillis = 1_400, easing = EaseInOut)) }
        launch { mistScale.animateTo(1f, tween(durationMillis = 1_400, easing = EaseInOut)) }
        delay(800L)
        launch { quoteAlpha.animateTo(1f, tween(durationMillis = 1_600, easing = EaseIn)) }
        delay(1_200L) // t = 2.0 s
        currentOnBreathMoment()
        ringOneAlpha.snapTo(0.5f)
        launch { ringOneScale.animateTo(1.7f, tween(durationMillis = 1_800, easing = EaseOut)) }
        launch { ringOneAlpha.animateTo(0f, tween(durationMillis = 1_800, easing = EaseOut)) }
        delay(1_100L) // t = 3.1 s
        ringTwoAlpha.snapTo(0.4f)
        launch { ringTwoScale.animateTo(1.7f, tween(durationMillis = 1_800, easing = EaseOut)) }
        launch { ringTwoAlpha.animateTo(0f, tween(durationMillis = 1_800, easing = EaseOut)) }
        delay(1_800L) // t = 4.9 s
        launch { mistAlpha.animateTo(0f, tween(durationMillis = 1_200, easing = EaseOut)) }
        launch { mistScale.animateTo(1.06f, tween(durationMillis = 1_200, easing = EaseOut)) }
        launch { quoteAlpha.animateTo(0f, tween(durationMillis = 1_200, easing = EaseOut)) }
        delay(1_300L) // t = 6.2 s
        currentOnComplete()
    }

    val fog = pilgrimColors.fog
    val stone = pilgrimColors.stone
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
        contentAlignment = Alignment.Center,
    ) {
        // iOS blurs a solid fog circle (blur radius 42); Compose blur is a
        // no-op below API 31, so the same soft-edged mist is drawn as a
        // radial fade instead (BreathingCircle house pattern) — identical
        // read at every API level.
        Canvas(
            modifier = Modifier
                .size(MIST_DIAMETER)
                .graphicsLayer {
                    alpha = mistAlpha.value
                    scaleX = mistScale.value
                    scaleY = mistScale.value
                },
        ) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(fog, fog.copy(alpha = 0f)),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
            )
        }
        GatewayRing(scale = ringOneScale.value, alpha = ringOneAlpha.value, color = stone)
        GatewayRing(scale = ringTwoScale.value, alpha = ringTwoAlpha.value, color = stone)

        androidx.compose.material3.Text(
            text = celestialLineRes?.let { stringResource(it) }
                // iOS falls back to LS["Seek.Quote.1"]; the Android home of
                // that string is the first entry of the seek quote array.
                ?: stringArrayResource(R.array.path_quotes_seek).first(),
            style = pilgrimType.displayMedium,
            color = fog,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = PilgrimSpacing.big * 2)
                .graphicsLayer { alpha = quoteAlpha.value },
        )
    }
}

@Composable
private fun GatewayRing(scale: Float, alpha: Float, color: Color) {
    Canvas(
        modifier = Modifier
            .size(RING_DIAMETER)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            },
    ) {
        drawCircle(
            color = color.copy(alpha = 0.6f),
            radius = size.minDimension / 2f,
            style = Stroke(width = RING_STROKE.toPx()),
        )
    }
}

private val MIST_DIAMETER = 260.dp
private val RING_DIAMETER = 220.dp
private val RING_STROKE = 1.5.dp
