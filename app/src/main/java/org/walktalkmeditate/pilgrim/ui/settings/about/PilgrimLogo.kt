// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R

/**
 * Pilgrim logo. iOS scales 1.0 → 1.02 over 4s easeInOut when [breathing]
 * is true; we use the same envelope. Stage 9-A's `ic_pilgrim_logo`
 * resource is the source asset (day + night variants in
 * `drawable-nodpi` / `drawable-night-nodpi`).
 *
 * `graphicsLayer { scaleX = scale; scaleY = scale }` keeps the
 * animated read in the layout phase, not the composition phase
 * (Stage 5-A regression memory: `Modifier.scale(Float)` value form
 * forces composition-phase recomposition on every frame).
 */
@Composable
fun PilgrimLogo(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    breathing: Boolean = false,
) {
    val scale: Float = if (breathing) {
        val transition = rememberInfiniteTransition(label = "pilgrim-logo-breath")
        val value by transition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scale",
        )
        value
    } else {
        1.0f
    }
    Image(
        painter = painterResource(R.drawable.ic_pilgrim_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 18))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentScale = ContentScale.Fit,
    )
}
