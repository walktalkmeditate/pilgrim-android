// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.sqrt

/**
 * One ripple ring spawned at end-of-inhale during meditation.
 *
 * iOS parity `MeditationView.swift:867-871@db4196e`:
 * ```
 * struct RippleRing: Identifiable {
 *     let id: UUID
 *     var size: CGFloat
 *     var opacity: Double
 * }
 * ```
 *
 * Android port: `id` is a monotonic Long instead of UUID (cheap, stable
 * for `removeAll { it.id == ring.id }`) and the `size`/`opacity` fields
 * are NOT carried on the struct — they're derived at draw time from
 * `(frameTime - spawnedAtMs) / RIPPLE_LIFESPAN_MS` progress so we don't
 * need per-ring `Animatable` bookkeeping.
 */
internal data class RippleRing(val id: Long, val spawnedAtMs: Long)

/**
 * iOS parity `MeditationView.swift:48-52, 187-195@db4196e`. Renders a
 * stack of [RippleRing]s as moss-stroked circles that grow + fade
 * over [RIPPLE_LIFESPAN_MS] (3.0s).
 *
 * Sized to [MAX_RING_SIZE_DP] — the rings extend beyond the
 * BreathingCircle's 320dp footprint by design. Caller is expected to
 * layer this UNDER the BreathingCircle in a centered Box.
 *
 * A single `withFrameMillis` LaunchedEffect drives per-frame redraws.
 * Reading `frameTimeMs.longValue` inside Canvas registers a snapshot
 * read, so the DrawScope re-runs on every tick. No per-ring
 * `Animatable` is needed.
 */
@Composable
internal fun MeditationRippleRings(
    rings: List<RippleRing>,
    mossColor: Color,
    modifier: Modifier = Modifier,
) {
    val frameTimeMs = remember { mutableLongStateOf(0L) }
    val hasRings = rings.isNotEmpty()
    // Frame ticker only runs while there are rings on-screen. Without
    // this guard, the `withFrameMillis` loop ticks forever and
    // Robolectric's Compose idling never settles (AppNotIdleException
    // after 60s on every MeditationScreen test). Production also wins:
    // when meditation is paused or in `isNone` mode the rings array
    // stays empty, so no frame work at all.
    LaunchedEffect(hasRings) {
        if (!hasRings) return@LaunchedEffect
        while (isActive) {
            withFrameMillis { frameTimeMs.longValue = it }
        }
    }
    if (!hasRings) return
    Canvas(modifier = modifier.size(MAX_RING_SIZE_DP.dp)) {
        val now = frameTimeMs.longValue
        val center = Offset(size.width / 2f, size.height / 2f)
        val strokeWidthPx = RING_STROKE_DP.dp.toPx()
        rings.forEach { ring ->
            val elapsedMs = (now - ring.spawnedAtMs).coerceAtLeast(0L)
            val progress = (elapsedMs / RIPPLE_LIFESPAN_MS.toFloat()).coerceIn(0f, 1f)
            // SwiftUI `.easeOut` is a CubicBezier(0, 0, 0.58, 1) curve;
            // `sqrt(progress)` approximates it well enough for a
            // 3-second decorative fade. Real Bezier evaluation per
            // frame would be ~20 ops vs sqrt — sqrt wins.
            val eased = sqrt(progress)
            val sizeDp = RING_START_SIZE_DP + (RING_END_SIZE_DP - RING_START_SIZE_DP) * eased
            val sizePx = sizeDp.dp.toPx()
            val alpha = (RING_START_ALPHA * (1f - eased)).coerceAtLeast(0f)
            if (alpha <= 0f) return@forEach
            drawCircle(
                color = mossColor.copy(alpha = alpha),
                radius = sizePx / 2f,
                center = center,
                style = Stroke(width = strokeWidthPx),
            )
        }
    }
}

/**
 * iOS parity `MeditationView.swift:189-230@db4196e` constants:
 *   `Color.moss.opacity(ring.opacity)` — start opacity 0.3, ends at 0
 *   `lineWidth: 0.5` — stroke width (SwiftUI points = Android dp)
 *   `ring.size = 160 * circleScale` at spawn (we use 160 fixed because
 *      emission happens at end-of-inhale when circleScale ≈ 1.0)
 *   `withAnimation(.easeOut(duration: 3.0)) { ring.size = 400 }`
 */
internal const val RIPPLE_LIFESPAN_MS = 3_000L
internal const val RIPPLE_RING_CAP = 3
private const val RING_START_SIZE_DP = 160f
private const val RING_END_SIZE_DP = 400f
private const val RING_START_ALPHA = 0.3f
private const val RING_STROKE_DP = 0.5f
private const val MAX_RING_SIZE_DP = 400
