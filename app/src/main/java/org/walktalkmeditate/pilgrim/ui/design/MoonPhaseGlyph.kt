// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.core.celestial.MoonPhase
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Lunar phase glyph + name label. Compose Canvas port of iOS
 * `MoonPhaseShape`. Renders the lit portion of the moon's disc as
 * a half-circle arc + cubic bezier terminator, with a soft radial
 * glow halo behind it.
 *
 * Path geometry is extracted into [moonPhasePath] (top-level
 * `internal fun`) so unit tests can assert on `Path.getBounds()`
 * without exercising the Canvas draw path (Robolectric's Canvas is
 * a stub per the Stage 3-C lesson).
 *
 * Light-mode silver-on-parchment rendering only for 9.5-A. Dark-mode
 * variant (ink color gradient, larger halo) deferred.
 */
@Composable
fun MoonPhaseGlyph(
    phase: MoonPhase,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    reducedMotion: Boolean = rememberReducedMotion(),
) {
    val pulse: Float = if (reducedMotion) {
        1.0f
    } else {
        val infinite = rememberInfiniteTransition(label = "moon-glow-pulse")
        val animated by infinite.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        animated
    }

    val silver = remember { Color(red = 0.55f, green = 0.58f, blue = 0.65f) }
    val fog = pilgrimColors.fog

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Canvas(modifier = Modifier.size(size * 3)) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val moonRadiusPx = size.toPx() / 2

            // Soft radial halo behind the moon.
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        silver.copy(alpha = 0.18f),
                        silver.copy(alpha = 0.06f),
                        Color.Transparent,
                    ),
                    center = center,
                    radius = moonRadiusPx * 1.4f * pulse,
                ),
                radius = moonRadiusPx * 1.4f * pulse,
                center = center,
            )

            // Lit portion of the moon's disc.
            val path = moonPhasePath(
                illumination = phase.illumination,
                isWaxing = phase.isWaxing,
                center = center,
                radius = moonRadiusPx,
            )
            drawPath(
                path = path,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        silver.copy(alpha = 0.5f),
                        fog.copy(alpha = 0.35f),
                    ),
                ),
            )
        }
        Text(
            text = phase.name,
            style = pilgrimType.annotation,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * Returns the lit portion of the moon's disc. Pure geometry — no
 * draw side effects. Test-friendly via [Path.getBounds].
 *
 * Algorithm (port of iOS `MoonPhaseShape.path(in:)`):
 *  - illumination > 0.95 → full circle (oval at center, radius=radius)
 *  - illumination < 0.05 → empty path
 *  - else: half-circle arc on the lit edge + cubic bezier curve as
 *    the terminator. The bezier control offsets are 4/3 of the
 *    curve radius (4/3 quarter-circle approximation).
 *
 * @param illumination fraction of the moon's disc visibly lit, in [0, 1]
 * @param isWaxing true for new→full half, false for full→new half
 * @param center geometric center of the moon in canvas coordinates
 * @param radius moon radius in pixels
 */
internal fun moonPhasePath(
    illumination: Double,
    isWaxing: Boolean,
    center: Offset,
    radius: Float,
): Path {
    if (illumination > 0.95) {
        return Path().apply {
            addOval(
                Rect(
                    left = center.x - radius,
                    top = center.y - radius,
                    right = center.x + radius,
                    bottom = center.y + radius,
                ),
            )
        }
    }
    if (illumination < 0.05) {
        return Path()
    }

    val path = Path()
    val rect = Rect(
        left = center.x - radius,
        top = center.y - radius,
        right = center.x + radius,
        bottom = center.y + radius,
    )
    // Half-circle arc on the lit edge. Waxing → arc on the right (lit
    // side faces sun on the right); waning → arc on the left.
    path.arcTo(
        rect = rect,
        startAngleDegrees = -90f,
        sweepAngleDegrees = if (isWaxing) 180f else -180f,
        forceMoveTo = false,
    )

    val fraction = abs(2 * illumination - 1).toFloat()
    val curveRadius = radius * fraction
    val controlOffset = curveRadius * (4.0f / 3.0f)
    val litHalf = illumination > 0.5
    val curveGoesRight = (isWaxing && litHalf) || (!isWaxing && !litHalf)
    val sign = if (curveGoesRight) 1f else -1f
    val top = Offset(center.x, center.y - radius)

    path.cubicTo(
        x1 = center.x + sign * controlOffset, y1 = center.y + radius * 0.55f,
        x2 = center.x + sign * controlOffset, y2 = center.y - radius * 0.55f,
        x3 = top.x, y3 = top.y,
    )
    return path
}

