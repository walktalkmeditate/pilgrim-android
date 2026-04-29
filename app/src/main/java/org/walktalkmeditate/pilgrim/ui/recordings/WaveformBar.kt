// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Compose Canvas waveform with continuous tap+drag seek.
 *
 * iOS parity: matches `WaveformBarView.swift`'s `.onChanged { ... }` semantics —
 * `onSeek` fires continuously during drag, NOT only on drag-end. Tap-to-seek
 * fires once on tap-up.
 *
 * Active bars (index ≤ progress * sampleCount) draw in [activeColor]; the rest
 * draw in [inactiveColor]. The caller passes the theme-tinted colors —
 * typically `pilgrimColors.stone` for active and `pilgrimColors.fog.copy(alpha = 0.4f)`
 * for inactive. Empty [samples] composes to an empty Canvas (no draw, no crash).
 */
@Composable
fun WaveformBar(
    samples: FloatArray,
    progress: Float,
    inactiveColor: Color,
    activeColor: Color,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .pointerInput(samples) {
                detectTapGestures { offset ->
                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                    onSeek(frac)
                }
            }
            .pointerInput(samples) {
                detectDragGestures(onDrag = { change, _ ->
                    val frac = (change.position.x / size.width).coerceIn(0f, 1f)
                    onSeek(frac)
                })
            },
    ) {
        if (samples.isEmpty()) return@Canvas
        val barWidth = size.width / samples.size
        val centerY = size.height / 2f
        // Half-open boundary: at progress = 0f, activeUntil = 0 and NO bar
        // is active. At progress = 1f, activeUntil = samples.size and every
        // bar is active. Using `i < activeUntil` (not `<=`) preserves that
        // semantic at both endpoints.
        val activeUntil = (progress.coerceIn(0f, 1f) * samples.size).toInt()
        for (i in samples.indices) {
            val mag = samples[i].coerceIn(0f, 1f)
            val barHalfHeight = (mag * size.height / 2f).coerceAtLeast(1f)
            drawLine(
                color = if (i < activeUntil) activeColor else inactiveColor,
                start = Offset(i * barWidth + barWidth / 2f, centerY - barHalfHeight),
                end = Offset(i * barWidth + barWidth / 2f, centerY + barHalfHeight),
                strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f),
            )
        }
    }
}
