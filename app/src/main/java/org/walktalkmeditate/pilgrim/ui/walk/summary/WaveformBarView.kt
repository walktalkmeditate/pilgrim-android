// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * iOS parity `WaveformBarView.swift@db4196e`. Renders [samples] as
 * vertical bars (centered on the row's mid-line), with bars before
 * `progress * count` painted in [activeColor] and bars after in
 * [inactiveColor]. Tap-to-seek converts the tap's x-fraction into the
 * normalized [onSeek] argument.
 */
@Composable
internal fun WaveformBarView(
    samples: FloatArray,
    playbackPositionMillisFlow: StateFlow<Long>?,
    durationMillis: Long,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = pilgrimColors.stone
    val inactive = pilgrimColors.fog.copy(alpha = 0.35f)
    // collectAsStateWithLifecycle is gated on a non-null flow so
    // non-active rows pay zero subscription cost — only the playing
    // row's bar wakes on the 100ms tick.
    val progress: Float = if (playbackPositionMillisFlow != null && durationMillis > 0L) {
        val pos by playbackPositionMillisFlow.collectAsStateWithLifecycle()
        (pos.toFloat() / durationMillis.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(samples) {
                detectTapGestures { offset ->
                    val w = size.width.toFloat()
                    if (w > 0f) {
                        onSeek((offset.x / w).coerceIn(0f, 1f))
                    }
                }
            },
    ) {
        if (samples.isEmpty()) return@Canvas
        drawBars(
            samples = samples,
            progress = progress,
            active = active,
            inactive = inactive,
            canvasSize = this.size,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBars(
    samples: FloatArray,
    progress: Float,
    active: Color,
    inactive: Color,
    canvasSize: Size,
) {
    val n = samples.size
    if (n == 0) return
    val barWidth = canvasSize.width / (n * 1.6f)
    val gap = barWidth * 0.6f
    val stride = barWidth + gap
    val maxHeight = canvasSize.height
    val midY = maxHeight / 2f
    val progressBoundary = canvasSize.width * progress
    val minBarHeight = 2f
    for (i in 0 until n) {
        val x = i * stride + gap / 2f
        val sample = samples[i].coerceIn(0f, 1f)
        val h = (sample * maxHeight).coerceAtLeast(minBarHeight)
        val color = if (x + barWidth / 2f <= progressBoundary) active else inactive
        drawRoundRect(
            color = color,
            topLeft = Offset(x, midY - h / 2f),
            size = Size(barWidth, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f),
        )
    }
}
