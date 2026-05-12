// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.home.scenery.sceneryTimeSeconds
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

private const val HALO_SCALE = 3.5f
private const val HALO_PEAK_ALPHA = 0.15f
private const val ACTIVITY_RING_OFFSET_DP = 5f
private const val ACTIVITY_STROKE_DP = 2f
private const val SHARED_RING_OFFSET_DP = 12f

/**
 * Per-row dot — verbatim port of iOS WalkDotView.swift. Layer stack
 * (bottom → top):
 *  1. Animated ripple (newest only, Reduce-Motion-safe).
 *  2. Outer halo radial gradient at 3.5× core size.
 *  3. Core fill — radial gradient `color → color.copy(alpha=0.7)` from
 *     UnitPoint(0.4, 0.35) for the soft 3D feel.
 *  4. Favicon glyph (if set).
 *  5. Activity arcs — rust talk arc + dawn meditate arc (trimmed).
 *  6. Specular highlight — small white-30% radial offset upper-left.
 *  7. Shared-walk stone ring (if isShared).
 */
@Composable
fun WalkDot(
    snapshot: WalkSnapshot,
    sizeDp: Float,
    color: Color,
    talkColor: Color,
    meditateColor: Color,
    opacity: Float,
    isNewest: Boolean,
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haloSizeDp = sizeDp * HALO_SCALE
    val activityRingSizeDp = sizeDp + ACTIVITY_RING_OFFSET_DP
    val sharedRingSizeDp = sizeDp + SHARED_RING_OFFSET_DP
    Box(
        modifier = modifier
            .size(haloSizeDp.dp)
            .graphicsLayer { alpha = opacity }
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        // 1. Ripple — newest only.
        if (isNewest) {
            RippleEffect(color = color, dotSizeDp = sizeDp)
        }

        // 2. Outer halo radial gradient (3.5× core). iOS uses a SwiftUI
        // RadialGradient with `startRadius = size * 0.5` + `endRadius =
        // size * 1.8` so the peak alpha forms a soft donut: flat-peak
        // from r=0 to r=0.5×sizeDp, fading to clear at r=1.8×sizeDp.
        // Compose's Brush.radialGradient has no startRadius, so we use
        // colorStops normalized against the halo Canvas radius
        // (= sizeDp * 1.75): plateau 0..0.286, fade 0.286..1.0.
        Canvas(Modifier.size(haloSizeDp.dp)) {
            val r = size.minDimension / 2f
            val peak = color.copy(alpha = HALO_PEAK_ALPHA)
            val plateauStop = (0.5f / (HALO_SCALE / 2f)).coerceIn(0f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to peak,
                        plateauStop to peak,
                        1f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }

        // 3. Core dot — radial gradient from full color to 70% alpha,
        // origin biased upper-left to read as 3D. Drop shadow drawn
        // BEHIND the core matches iOS `.shadow(color: .ink.opacity(0.15),
        // radius: 2, x: 1, y: 2)` — Compose Modifier.shadow doesn't
        // apply to Canvas content directly, so we paint a soft ink
        // circle offset (1, 2) before the core.
        val shadowColor = pilgrimColors.ink.copy(alpha = 0.15f)
        Canvas(Modifier.size(sizeDp.dp)) {
            val coreR = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            // Drop shadow: opacity 0.15, offset (1, 2), soft 2px feather
            // approximated by drawing two slightly larger faded circles.
            val shadowCenter = Offset(center.x + 1.dp.toPx(), center.y + 2.dp.toPx())
            drawCircle(
                color = shadowColor.copy(alpha = 0.07f),
                radius = coreR + 1.dp.toPx(),
                center = shadowCenter,
            )
            drawCircle(
                color = shadowColor,
                radius = coreR,
                center = shadowCenter,
            )
            val biasedCenter = Offset(size.width * 0.4f, size.height * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color, color.copy(alpha = 0.7f)),
                    center = biasedCenter,
                    radius = coreR * 1.2f,
                ),
                radius = coreR,
                center = center,
            )
        }

        // 4. Favicon glyph. iOS `.font(.system(size: size * 0.4)).bold()
        // .foregroundColor(.parchment)`. The 0.5× ratio used previously
        // made glyphs crowd the dot interior — 0.4× matches iOS.
        snapshot.favicon?.let { faviconKey ->
            val favicon = WalkFavicon.entries.firstOrNull { it.rawValue == faviconKey }
            if (favicon != null) {
                Icon(
                    imageVector = favicon.icon,
                    contentDescription = null,
                    tint = pilgrimColors.parchment,
                    modifier = Modifier.size((sizeDp * 0.4f).dp),
                )
            }
        }

        // 5. Activity arcs — talk (rust) + meditate (dawn). Both
        // drawn around an `activityRingSizeDp` circle, trimmed to the
        // duration fraction. iOS rotateEffect(-90°) so 0deg starts at
        // top — Compose Canvas rotate() achieves the same.
        val totalSec = snapshot.durationSec
        if (totalSec > 0.0) {
            val talkFrac = (snapshot.talkDurationSec / totalSec).toFloat().coerceIn(0f, 1f)
            val meditateFrac = (snapshot.meditateDurationSec / totalSec).toFloat().coerceIn(0f, 1f)
            if (talkFrac > 0.01f || meditateFrac > 0.01f) {
                Canvas(Modifier.size(activityRingSizeDp.dp)) {
                    val arcRect = Size(size.width, size.height)
                    val topLeft = Offset(0f, 0f)
                    val strokeW = ACTIVITY_STROKE_DP.dp.toPx()
                    rotate(degrees = -90f, pivot = Offset(size.width / 2f, size.height / 2f)) {
                        if (talkFrac > 0.01f) {
                            drawArc(
                                color = talkColor.copy(alpha = 0.7f),
                                startAngle = 0f,
                                sweepAngle = 360f * talkFrac,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcRect,
                                style = Stroke(width = strokeW),
                            )
                        }
                        if (meditateFrac > 0.01f) {
                            drawArc(
                                color = meditateColor.copy(alpha = 0.7f),
                                startAngle = 360f * talkFrac,
                                sweepAngle = 360f * meditateFrac,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcRect,
                                style = Stroke(width = strokeW),
                            )
                        }
                    }
                }
            }
        }

        // 6. Specular highlight — small white-30% radial offset upper-left.
        // iOS `.opacity(opacity * 0.5)` so older dots fade their
        // catchlight with the core; the per-row `opacity` is already
        // multiplied at the root via `graphicsLayer { alpha = opacity }`,
        // so the inner 0.5 is the iOS multiplier.
        Canvas(
            Modifier
                .size((sizeDp * 0.7f).dp)
                .graphicsLayer { alpha = 0.5f },
        ) {
            val r = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
                    center = Offset(size.width * 0.3f, size.height * 0.3f),
                    radius = r,
                ),
                radius = r,
                center = Offset(size.width / 2f - sizeDp * 0.08f, size.height / 2f - sizeDp * 0.08f),
            )
        }

        // 7. Shared-walk stone ring. iOS uses a fixed
        // `Color.stone.opacity(0.5)` — earlier Android code reused the
        // per-dot seasonal `color`, so jade walks got a jade ring.
        if (snapshot.isShared) {
            val ringColor = pilgrimColors.stone.copy(alpha = 0.5f)
            Canvas(Modifier.size(sharedRingSizeDp.dp)) {
                val r = size.minDimension / 2f
                drawCircle(
                    color = ringColor,
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
    }
}

/**
 * Animated breathing-ripple around the newest dot. Two expanding rings
 * + a slowly pulsing glow disc behind. iOS WalkDotView.swift:188-241
 * verbatim — same 0.4 ring frequency, 0.5 phase offset, 1.2 breath
 * frequency, 0.04 + breath*0.04 glow alpha, 0.2 ring fade.
 *
 * Reduce-Motion fallback: a single static stroked Circle at dotSize+16.
 */
@Composable
private fun RippleEffect(color: Color, dotSizeDp: Float) {
    val reduceMotion = LocalReduceMotion.current
    val frameSizeDp = dotSizeDp * 4f
    val rippleColor = color
    if (reduceMotion) {
        Canvas(Modifier.size((dotSizeDp + 16f).dp)) {
            val r = size.minDimension / 2f
            drawCircle(
                color = rippleColor.copy(alpha = 0.15f),
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 1.5.dp.toPx()),
            )
        }
        return
    }

    val timeSec by sceneryTimeSeconds()
    Canvas(Modifier.size(frameSizeDp.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val dotPx = dotSizeDp.dp.toPx()

        // Two expanding rings — phase-offset by 0.5.
        for (i in 0..1) {
            val raw = (timeSec * 0.4 + i * 0.5).toFloat()
            val phase = raw - kotlin.math.floor(raw.toDouble()).toFloat() // mod 1
            val radius = dotPx * 0.5f + phase * dotPx * 1.2f
            val alpha = ((1f - phase) * 0.2f).coerceIn(0f, 1f)
            drawCircle(
                color = rippleColor.copy(alpha = alpha),
                radius = radius,
                center = center,
                style = Stroke(width = (1.5.dp.toPx()) * (1f - phase * 0.5f).coerceAtLeast(0.1f)),
            )
        }

        // Breathing glow disc.
        val breath = (sin(timeSec * 1.2) * 0.5f + 0.5f).toFloat()
        val glowR = dotPx * 1.5f
        drawCircle(
            color = rippleColor.copy(alpha = 0.04f + breath * 0.04f),
            radius = glowR,
            center = center,
        )
    }
}
