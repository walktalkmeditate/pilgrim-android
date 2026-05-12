// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.path

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * iOS parity `WalkStartView.swift:83-136@db4196e` — layered Path
 * background:
 *   1. parchment (handled by parent Box already)
 *   2. time-of-day tint at low alpha
 *   3. animated radial gradient at slightly higher alpha (skipped under
 *      reduce-motion)
 *   4. per-mode atmosphere overlay at 0.01 alpha
 *
 * The 15s easeInOut autoreverse animation drives the radial center
 * UnitPoint from (0.5, 0.5) → (0.65, 0.6); the iOS source uses
 * `ambientOffset = CGSize(width: 0.15, height: 0.1)`.
 *
 * Per-hour tint matches `WalkStartView.timeOfDay`:
 *   - 5-7   → orange α 0.03 (dawn)
 *   - 8-15  → yellow α 0.02 (midday)
 *   - 16-19 → orange α 0.04 (sunset)
 *   - else  → blue   α 0.02 (night)
 */
@Composable
internal fun PathBackgroundLayers(
    selectedMode: WalkMode,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    // Refresh hour on every ON_RESUME so a backgrounded app returning
    // after crossing an hour boundary picks up the new time-of-day
    // tint. `remember { ... }` alone would freeze at first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    var hour by remember { mutableStateOf(java.time.LocalTime.now().hour) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hour = java.time.LocalTime.now().hour
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val tod = remember(hour) { timeOfDayTint(hour) }

    // `targetValue = initialValue` under reduceMotion freezes the
    // ambient transition at 0f without changing the slot table or
    // ticking per-frame recomposes.
    val transition = rememberInfiniteTransition(label = "path-ambient")
    val ambientOffsetX by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion) 0f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-x",
    )
    val ambientOffsetY by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduceMotion) 0f else 0.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 15_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ambient-y",
    )

    Box(modifier = modifier.fillMaxSize()) {
        // Time-of-day tint flat-fill.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(tod.tint.copy(alpha = tod.opacity)),
        )

        // Animated radial gradient — skipped under reduce-motion.
        if (!reduceMotion) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width * (0.5f + ambientOffsetX)
                val cy = size.height * (0.5f + ambientOffsetY)
                val maxDim = maxOf(size.width, size.height)
                // iOS startRadius 50, endRadius 300 — both in points;
                // scaled to canvas px via maxDim so the radial behaves
                // consistently across phone widths.
                val startR = 50f / 300f * maxDim
                val endR = maxDim
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            tod.tint.copy(alpha = tod.opacity * 1.5f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = endR,
                    ),
                )
            }
        }

        // Per-mode atmosphere overlay (very subtle).
        val atmosphere = when (selectedMode) {
            WalkMode.Wander -> Color.Transparent
            WalkMode.Together -> pilgrimColors.dawn.copy(alpha = 0.01f)
            WalkMode.Seek -> pilgrimColors.fog.copy(alpha = 0.01f)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(atmosphere),
        )
    }
}

private data class TimeOfDayTint(val tint: Color, val opacity: Float)

private fun timeOfDayTint(hour: Int): TimeOfDayTint = when (hour) {
    in 5..7 -> TimeOfDayTint(Color(0xFFFFA500), 0.03f) // dawn orange
    in 8..15 -> TimeOfDayTint(Color(0xFFFFFF00), 0.02f) // midday yellow
    in 16..19 -> TimeOfDayTint(Color(0xFFFFA500), 0.04f) // sunset orange
    else -> TimeOfDayTint(Color(0xFF4A5FAA), 0.02f) // night blue
}
