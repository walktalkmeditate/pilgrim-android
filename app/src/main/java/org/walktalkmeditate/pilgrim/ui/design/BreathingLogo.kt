// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * The breathing Pilgrim logo for the Path tab. Animates a subtle
 * 1.0 → 1.02 scale with a 4-second period, matching iOS's
 * PilgrimLogoView breath cadence.
 *
 * iOS parity `WalkStartView.swift:153-160@db4196e` — when [pulseActive]
 * is true (collective counter shows another walker active in the last
 * hour) the logo layers a 1.2s scale 1.0→1.03 + shadow alpha 0→0.3 +
 * radius 0→12 pulse on top of the steady breath. Pulse combines
 * multiplicatively with breath so both animations stay alive.
 *
 * Uses the lambda form of [graphicsLayer] (`graphicsLayer { scaleX = ... }`)
 * per the Stage 5-A memory: `Modifier.scale(value)` would force
 * composition-phase reads on every animation frame; the lambda form
 * keeps the read in the draw phase.
 *
 * Source asset: `R.drawable.ic_pilgrim_logo`, ported edge-to-edge from
 * `pilgrim-ios/Pilgrim/Support Files/Assets.xcassets/pilgrimLogo.imageset/`.
 * The dark variant lives in `drawable-night-nodpi/`; system picks the
 * correct one based on the night-mode configuration.
 */
@Composable
fun BreathingLogo(
    modifier: Modifier = Modifier,
    size: Dp = 100.dp,
    reducedMotion: Boolean = rememberReducedMotion(),
    pulseActive: Boolean = false,
) {
    // Always call rememberInfiniteTransition — switching between
    // animated and 0f via the result value, never via a conditional
    // remember*() call (Compose forbids that, and a runtime toggle of
    // reducedMotion would otherwise throw at recompose).
    val breathTransition = rememberInfiniteTransition(label = "logo-breath")
    val animatedBreath by breathTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath-scale",
    )
    val breath = if (reducedMotion) 1.0f else animatedBreath

    val pulseTransition = rememberInfiniteTransition(label = "logo-pulse")
    val animatedPulse by pulseTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    val pulseShadow by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-shadow",
    )
    val pulse = if (pulseActive && !reducedMotion) animatedPulse else 1.0f
    val shadowElevation = if (pulseActive && !reducedMotion) pulseShadow else 0f

    val finalScale = breath * pulse
    val stoneColor = pilgrimColors.stone

    Image(
        painter = painterResource(R.drawable.ic_pilgrim_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .shadow(
                elevation = shadowElevation.dp,
                shape = CircleShape,
                ambientColor = stoneColor,
                spotColor = stoneColor,
            )
            .clip(RoundedCornerShape(size * 0.18f))
            .graphicsLayer {
                scaleX = finalScale
                scaleY = finalScale
            },
    )
}
