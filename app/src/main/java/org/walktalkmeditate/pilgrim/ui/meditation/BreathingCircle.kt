// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A soft moss-glow circle that breathes — scales 0.45 → 1.0 and back
 * over a 6-second cycle (3s inhale + 3s exhale) via
 * [rememberInfiniteTransition] with [FastOutSlowInEasing] on each
 * half-cycle.
 *
 * Pure visual composable: takes the moss color as a parameter (no
 * theme read) so previews and tests don't need a `PilgrimTheme`
 * wrapper. Stage 5-A's single delight — no particles, no rings, no
 * milestone flashes (iOS's `MeditationView.swift` has all of those;
 * we defer to later sub-stages).
 */
@Composable
internal fun BreathingCircle(
    moss: Color,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "breath")
    val scale by transition.animateFloat(
        initialValue = SCALE_EXHALED,
        targetValue = SCALE_INHALED,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = HALF_CYCLE_MS,
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathScale",
    )

    Canvas(modifier = modifier.size(CIRCLE_SIZE_DP.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val outerRadius = (size.minDimension / 2f) * scale

        // Outer halo — moss at 50% fading to 0%, broad and soft.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    moss.copy(alpha = 0.5f),
                    moss.copy(alpha = 0.15f),
                    moss.copy(alpha = 0f),
                ),
                center = center,
                radius = outerRadius,
            ),
            radius = outerRadius,
            center = center,
        )
        // Inner core — moss at 70% fading to 30%, denser focal point.
        val innerRadius = outerRadius * INNER_CORE_FRACTION
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    moss.copy(alpha = 0.7f),
                    moss.copy(alpha = 0.3f),
                ),
                center = center,
                radius = innerRadius,
            ),
            radius = innerRadius,
            center = center,
        )
    }
}

private const val SCALE_EXHALED = 0.45f
private const val SCALE_INHALED = 1.0f
private const val HALF_CYCLE_MS = 3_000
private const val CIRCLE_SIZE_DP = 320
private const val INNER_CORE_FRACTION = 0.5f
