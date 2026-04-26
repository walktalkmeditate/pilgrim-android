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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R

/**
 * The breathing Pilgrim logo for the Path tab. Animates a subtle
 * 1.0 → 1.02 scale with a 4-second period, matching iOS's
 * PilgrimLogoView breath cadence.
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
) {
    val scale: Float = if (reducedMotion) {
        1.0f
    } else {
        val infinite = rememberInfiniteTransition(label = "logo-breath")
        val animated by infinite.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scale",
        )
        animated
    }
    Image(
        painter = painterResource(R.drawable.ic_pilgrim_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.18f))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    )
}
