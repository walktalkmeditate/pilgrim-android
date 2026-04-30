// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS-faithful streak flame: two stacked icons flicker at different
 * tempos via rememberInfiniteTransition. The faint outer glow scales
 * 0.9 ↔ 1.15 with alpha 0.3 ↔ 0.6 over 0.8s; the brighter core scales
 * 0.95 ↔ 1.05 with alpha 0.7 ↔ 1.0 over 1.1s. Mirrors iOS
 * `StreakFlameView`.
 */
@Composable
fun StreakFlame(days: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "streak-flame")
    val flicker1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flicker1",
    )
    val flicker2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flicker2",
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = pilgrimColors.rust.copy(alpha = 0.3f + flicker1 * 0.3f),
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        val scale = 0.9f + flicker1 * 0.25f
                        scaleX = scale
                        scaleY = scale
                    },
            )
            Icon(
                imageVector = Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = pilgrimColors.rust.copy(alpha = 0.7f + flicker2 * 0.3f),
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer {
                        val scale = 0.95f + flicker2 * 0.10f
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
        Text(
            text = stringResource(R.string.practice_summary_streak_days, days),
            style = pilgrimType.caption,
            color = pilgrimColors.stone,
        )
    }
}
