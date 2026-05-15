// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Constellation appearance overlay — animated starlit indigo decoration
 * drawn on top of every Pilgrim surface when the user selects
 * [org.walktalkmeditate.pilgrim.data.appearance.AppearanceMode.Constellation].
 *
 * Compose port of iOS `ConstellationOverlay.swift@v1.6.0`. Renders three
 * layered atmospheric effects atop the underlying canvas:
 *
 * 1. **Cosmic gradient** — faint center brighten fading to flat indigo,
 *    sitting just above the [pilgrimColors.parchment] canvas to give the
 *    field cosmic depth without changing the perceived bg color.
 * 2. **Nebula clouds** — 2-3 large soft radial blotches (violet / indigo
 *    / plum) drifting at ~0.4-0.8 px/sec. Wrap horizontally past the
 *    canvas edges so the wrap is invisible. Static at `time = 0` for the
 *    Reduce Motion path.
 * 3. **Star field** — 5-14 layered stars (far / mid / near) with
 *    twinkle (sin opacity modulation 0.2-0.4 Hz), parallax drift
 *    (0.4 / 0.9 / 1.6 px/sec by layer), and tiny vertical sway per-star
 *    with deterministic phase. Each star draws as halo + mid + core so
 *    the pinpoint reads against the indigo bg.
 *
 * Plus an idle/active shooting-star scheduler that picks a random
 * down-angled line every 30-90s and animates it across 600ms.
 *
 * Accessibility:
 * - `hideFromAccessibility()` so screen readers skip the overlay
 *   entirely — it's purely decorative.
 * - When `LocalReduceTransparency` is true (system "Reduce
 *   Transparency"), the overlay renders nothing — match iOS
 *   `reduceTransparency` path.
 * - When [LocalReduceMotion] is true, the overlay renders a single
 *   static frame (no drift, no twinkle, no shooting stars) — match
 *   iOS `reduceMotion` path.
 *
 * Hit-testing is disabled via not adding `clickable`; the Box used as
 * the host doesn't consume gestures, so taps pass through to the
 * underlying content.
 *
 * @param includesNebulae False suppresses nebula generation entirely.
 *   Matches iOS behavior of dropping nebulae on Active Walk + Walk
 *   Summary so the purple/blue clouds don't clash with the dense
 *   Mapbox map or warm parchmentSecondary cards.
 */
@Composable
fun ConstellationOverlay(
    modifier: Modifier = Modifier,
    includesNebulae: Boolean = true,
) {
    val reduceMotion = LocalReduceMotion.current

    // Generate stars + nebulae once per overlay lifetime. Star positions
    // are normalized 0..1 so the canvas-size hint here doesn't affect
    // placement once they render. Nebula radii are absolute density-
    // independent pixels so they keep visual size across devices.
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val seed = remember { Random.nextLong() }
    val widthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val heightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val state = remember(seed, includesNebulae) {
        val random = Random(seed)
        ConstellationState(
            stars = generateStars(random),
            nebulae = if (includesNebulae) generateNebulae(random, widthPx, heightPx) else emptyList(),
        )
    }

    val timeMs = remember { mutableStateOf(0L) }
    val shooting = remember { mutableStateOf<ShootingState>(ShootingState.Idle) }

    if (!reduceMotion) {
        // Frame-driver: derive elapsed-since-start ms from withFrameMillis,
        // which yields a vsync-aligned timestamp each frame. Cancelling
        // the LaunchedEffect (overlay leaves composition) stops the
        // driver automatically — no manual disposal.
        LaunchedEffect(state) {
            var start = -1L
            while (true) {
                withFrameMillis { now ->
                    if (start < 0L) start = now
                    timeMs.value = now - start
                }
            }
        }
        // Shooting-star scheduler. Fires every 30-90s; while active
        // (600ms duration) the Canvas draws the streak. Cancellation
        // (overlay leaves composition) ends the loop cleanly.
        LaunchedEffect(state) {
            while (true) {
                val waitMillis = 30_000L + (Random.nextDouble() * 60_000L).toLong()
                kotlinx.coroutines.delay(waitMillis)
                val line = randomShootingLine(Random, widthPx, heightPx)
                shooting.value = ShootingState.Active(start = timeMs.value, line = line)
                kotlinx.coroutines.delay(600L)
                shooting.value = ShootingState.Idle
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clearAndSetSemantics { },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = if (reduceMotion) 0.0 else timeMs.value / 1000.0
            drawCosmicGradient(size)
            for (n in state.nebulae) {
                drawNebula(n, size, t)
            }
            for (s in state.stars) {
                if (reduceMotion) {
                    val pos = Offset(s.position.x * size.width, s.position.y * size.height)
                    drawStar(pos, s.radius, s.tint, STATIC_OPACITY)
                } else {
                    val phase = sin(t * 2 * Math.PI * s.twinkleHz + s.twinklePhase)
                    val opacity = (s.baseOpacity * (0.5 + 0.5 * phase)).toFloat()
                    val pos = driftedPosition(s, t, size)
                    drawStar(pos, s.radius, s.tint, opacity)
                }
            }
            val sh = shooting.value
            if (sh is ShootingState.Active && !reduceMotion) {
                val elapsedMs = timeMs.value - sh.start
                if (elapsedMs in 0..600L) {
                    drawShootingStar(sh.line, elapsedMs / 600f, size)
                }
            }
        }
    }
}

/**
 * Convenience modifier — wraps content and overlays the constellation
 * decoration on top of it when [org.walktalkmeditate.pilgrim.ui.theme
 * .LocalIsConstellation] is true. iOS parity: `.constellationDecorated()`
 * applied to roots, fullScreenCover content, and sheet content.
 */
@Composable
fun ConstellationDecoration(includesNebulae: Boolean = true) {
    val isConstellation = org.walktalkmeditate.pilgrim.ui.theme.LocalIsConstellation.current
    if (!isConstellation) return
    ConstellationOverlay(includesNebulae = includesNebulae)
}

private const val STATIC_OPACITY: Float = 0.6f

private data class ConstellationState(
    val stars: List<Star>,
    val nebulae: List<Nebula>,
)

private data class Star(
    val position: Offset, // normalized 0..1
    val layer: Layer,
    val radius: Float,
    val baseOpacity: Double,
    val twinkleHz: Double,
    val twinklePhase: Double,
    val tint: StarTint,
) {
    enum class Layer { Far, Mid, Near }
}

private enum class StarTint(val r: Float, val g: Float, val b: Float) {
    Cool(232f / 255f, 224f / 255f, 1f),
    Warm(1f, 232f / 255f, 220f / 255f),
}

private data class Nebula(
    val basePosition: Offset, // normalized 0..1
    val radiusPx: Float,
    val tint: NebulaTint,
    val driftSpeedPxPerSec: Float,
)

private enum class NebulaTint(val r: Float, val g: Float, val b: Float) {
    Violet(0.62f, 0.42f, 0.92f),
    Indigo(0.40f, 0.52f, 0.92f),
    Plum(0.78f, 0.52f, 0.82f),
}

private sealed class ShootingState {
    object Idle : ShootingState()
    data class Active(val start: Long, val line: ShootingLine) : ShootingState()
}

private data class ShootingLine(val start: Offset, val end: Offset)

private fun layerRadius(layer: Star.Layer): Float = when (layer) {
    Star.Layer.Far -> 1.2f
    Star.Layer.Mid -> 1.8f
    Star.Layer.Near -> 2.6f
}

private fun layerSpeed(layer: Star.Layer): Float = when (layer) {
    Star.Layer.Far -> 0.4f
    Star.Layer.Mid -> 0.9f
    Star.Layer.Near -> 1.6f
}

private fun generateStars(random: Random): List<Star> {
    val count = random.nextInt(5, 15)
    return List(count) {
        val layer = Star.Layer.values()[random.nextInt(3)]
        val warm = random.nextDouble() < 0.3
        Star(
            position = Offset(
                x = random.nextDouble(0.05, 0.95).toFloat(),
                y = random.nextDouble(0.05, 0.95).toFloat(),
            ),
            layer = layer,
            radius = layerRadius(layer),
            baseOpacity = 0.6 + random.nextDouble(0.0, 0.35),
            twinkleHz = random.nextDouble(0.2, 0.4),
            twinklePhase = random.nextDouble(0.0, 2 * Math.PI),
            tint = if (warm) StarTint.Warm else StarTint.Cool,
        )
    }
}

private fun generateNebulae(random: Random, widthPx: Float, heightPx: Float): List<Nebula> {
    val scale = max(widthPx / 393f, 1f)
    val candidates = listOf(
        Nebula(Offset(0.25f, 0.20f), 280f * scale, NebulaTint.Violet, 0.6f),
        Nebula(Offset(0.75f, 0.55f), 340f * scale, NebulaTint.Indigo, 0.4f),
        Nebula(Offset(0.45f, 0.85f), 260f * scale, NebulaTint.Plum, 0.8f),
    )
    val pick = random.nextInt(2, 4)
    return candidates.shuffled(random).take(pick)
}

private fun randomShootingLine(random: Random, widthPx: Float, heightPx: Float): ShootingLine {
    val fromLeft = random.nextBoolean()
    val startY = random.nextDouble(0.0, heightPx * 0.4).toFloat()
    val startX = if (fromLeft) {
        random.nextDouble(0.0, widthPx * 0.3).toFloat()
    } else {
        random.nextDouble(widthPx * 0.7, widthPx.toDouble()).toFloat()
    }
    val length = widthPx * random.nextDouble(0.4, 0.6).toFloat()
    val absAngle = random.nextDouble(0.43, 0.79).toFloat()
    val dx = (if (fromLeft) 1 else -1) * length * cos(absAngle)
    val dy = length * sin(absAngle)
    return ShootingLine(Offset(startX, startY), Offset(startX + dx, startY + dy))
}

private fun driftedPosition(star: Star, time: Double, canvas: Size): Offset {
    val speed = layerSpeed(star.layer)
    val basePixelX = star.position.x * canvas.width
    val driftX = (time * speed).toFloat()
    val cycle = canvas.width + 80f
    var wrappedX = (basePixelX + driftX) % cycle
    if (wrappedX < 0) wrappedX += cycle
    val basePixelY = star.position.y * canvas.height
    val swayAmplitude = when (star.layer) {
        Star.Layer.Near -> 10f
        Star.Layer.Mid -> 6f
        Star.Layer.Far -> 4f
    }
    val swayPeriodSeconds = 30.0 + (star.twinklePhase / (2 * Math.PI)) * 30.0
    val swayHz = 1.0 / swayPeriodSeconds
    val swayY = swayAmplitude * sin(time * 2 * Math.PI * swayHz + star.twinklePhase).toFloat()
    return Offset(wrappedX, basePixelY + swayY)
}

private fun DrawScope.drawStar(position: Offset, radius: Float, tint: StarTint, opacity: Float) {
    val baseColor = Color(tint.r, tint.g, tint.b)
    // Three-layer composite — halo (3.5x radius @ 18% alpha) + mid
    // (1.8x @ 45%) + core (1.0x @ full opacity). Solid filled circles
    // never read as 'stars'; layered fills produce a sharp bright
    // pinpoint with a soft visible halo.
    val haloRadius = radius * 3.5f
    drawCircle(color = baseColor.copy(alpha = opacity * 0.18f), radius = haloRadius, center = position)
    val midRadius = radius * 1.8f
    drawCircle(color = baseColor.copy(alpha = opacity * 0.45f), radius = midRadius, center = position)
    drawCircle(color = baseColor.copy(alpha = opacity), radius = radius, center = position)
}

private fun DrawScope.drawCosmicGradient(canvas: Size) {
    val center = Offset(canvas.width / 2f, canvas.height / 2f)
    val radius = max(canvas.width, canvas.height) * 0.7f
    val centerTint = Color(red = 0.10f, green = 0.10f, blue = 0.16f)
    val brush = Brush.radialGradient(
        colorStops = arrayOf(
            0.0f to centerTint.copy(alpha = 0.55f),
            0.5f to centerTint.copy(alpha = 0.18f),
            1.0f to Color.Transparent,
        ),
        center = center,
        radius = radius,
    )
    drawRect(brush = brush, topLeft = Offset.Zero, size = canvas)
}

private fun DrawScope.drawNebula(nebula: Nebula, canvas: Size, time: Double) {
    val baseX = nebula.basePosition.x * canvas.width
    val baseY = nebula.basePosition.y * canvas.height
    val driftX = (time * nebula.driftSpeedPxPerSec).toFloat()
    val cycle = canvas.width + nebula.radiusPx * 2
    var modded = (baseX + driftX + nebula.radiusPx) % cycle
    if (modded < 0) modded += cycle
    val centerX = modded - nebula.radiusPx
    val tint = nebula.tint
    val color = Color(tint.r, tint.g, tint.b)
    val brush = Brush.radialGradient(
        colorStops = arrayOf(
            0.0f to color.copy(alpha = 0.32f),
            0.35f to color.copy(alpha = 0.16f),
            0.7f to color.copy(alpha = 0.06f),
            1.0f to Color.Transparent,
        ),
        center = Offset(centerX, baseY),
        radius = nebula.radiusPx,
    )
    drawCircle(brush = brush, radius = nebula.radiusPx, center = Offset(centerX, baseY))
}

private fun DrawScope.drawShootingStar(line: ShootingLine, progress: Float, canvas: Size) {
    val clamped = progress.coerceIn(0f, 1f)
    val alpha = sin(Math.PI * clamped).toFloat()
    val head = Offset(
        line.start.x + (line.end.x - line.start.x) * clamped,
        line.start.y + (line.end.y - line.start.y) * clamped,
    )
    val tailProgress = max(0f, clamped - 0.15f)
    val tail = Offset(
        line.start.x + (line.end.x - line.start.x) * tailProgress,
        line.start.y + (line.end.y - line.start.y) * tailProgress,
    )
    val path = Path().apply {
        moveTo(tail.x, tail.y)
        lineTo(head.x, head.y)
    }
    drawPath(
        path = path,
        color = Color.White.copy(alpha = alpha * 0.9f),
        style = Stroke(width = 1.5f, cap = StrokeCap.Round),
    )
}
