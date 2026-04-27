// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

internal const val WAVEFORM_BAR_TEST_TAG = "audio-waveform-bar"

private val BAR_WEIGHTS = listOf(0.6f, 0.8f, 1.0f, 0.8f, 0.6f)
private const val BAR_MIN_DP = 4f
private const val BAR_RANGE_DP = 20f

/**
 * Direct port of iOS [AudioWaveformView] (ActiveWalkSubviews.swift:58-86).
 * 5 vertical rust-colored bars in HStack; bar heights animate to [level]
 * (0..1) over 80ms with the symmetric weights `[0.6, 0.8, 1.0, 0.8, 0.6]`.
 *
 * VoiceRecorder publishes audioLevel ~10×/sec (100ms RMS buffer);
 * 80ms tweens complete just before the next sample, so transitions
 * read as continuous motion rather than discrete jumps.
 */
@Composable
fun AudioWaveformView(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val weights = remember { BAR_WEIGHTS }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weights.forEach { weight ->
            val targetHeightDp = audioWaveformBarHeightDp(level = level, weight = weight)
            val animatedHeight by animateDpAsState(
                targetValue = targetHeightDp.dp,
                animationSpec = tween(durationMillis = 80, easing = FastOutLinearInEasing),
                label = "bar-height",
            )
            Box(
                modifier = Modifier
                    .testTag(WAVEFORM_BAR_TEST_TAG)
                    .width(4.dp)
                    .height(animatedHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(pilgrimColors.rust),
            )
        }
    }
}

internal fun audioWaveformBarHeightDp(level: Float, weight: Float): Float {
    val clampedLevel = level.coerceIn(0f, 1f)
    return BAR_MIN_DP + clampedLevel * weight * BAR_RANGE_DP
}
