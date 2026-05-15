// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * iOS parity `MeditationView.swift:673-684` (voice-ring layer).
 * Renders four concentric circles at 300/350/400/450dp with
 * decreasing opacity and a slow pulse, shown only while a
 * voice-guide prompt is playing.
 *
 * Single Canvas draws every ring so we allocate one composable
 * regardless of ring count. Pulse drives radius + alpha together
 * over a 2.4s cycle (iOS approximates the same with its
 * `voiceRingPulse` animation).
 */
@Composable
internal fun VoiceGuideRings(
    mossColor: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "voice-rings")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "voice-rings-pulse",
    )
    Canvas(modifier = modifier.size(MAX_RING_DP.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokePx = 1.dp.toPx()
        RING_SIZES_DP.forEachIndexed { index, baseSizeDp ->
            // 0..1 pulse adds ~10% radius and toggles alpha 0.6→1.0.
            val pulsedRadius = (baseSizeDp * (1f + pulse * 0.10f)).dp.toPx() / 2f
            val baseAlpha = RING_BASE_ALPHA - index * RING_ALPHA_STEP
            val alpha = (baseAlpha * (0.6f + 0.4f * pulse)).coerceIn(0f, 1f)
            drawCircle(
                color = mossColor.copy(alpha = alpha),
                radius = pulsedRadius,
                center = center,
                style = Stroke(width = strokePx),
            )
        }
    }
}

// iOS sizes 300/350/400/450 (CGFloat = pt) target their ~200pt breath
// circle. Android's breath circle is 320dp; scaled-down ring sizes
// (250/290/330/370) extend visibly past the breath halo while staying
// inside the 400dp Box slot (which is locked by the ripple-ring layer).
private val RING_SIZES_DP = listOf(250f, 290f, 330f, 370f)
private const val MAX_RING_DP = 400
private const val RING_BASE_ALPHA = 0.12f
private const val RING_ALPHA_STEP = 0.02f
