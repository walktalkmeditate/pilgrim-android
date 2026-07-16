// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.home.scenery.sceneryTimeSeconds
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

private const val HALO_PEAK_ALPHA = 0.15f
// iOS RadialGradient bounds on the halo: `startRadius: size * 0.5`,
// `endRadius: size * 1.8` (WalkDotView.swift @ c1745e8).
private const val HALO_GRADIENT_START_SCALE = 0.5f
private const val HALO_GRADIENT_END_SCALE = 1.8f
// Padding around the core's own Canvas for the blurred drop shadow, so the
// 2dp Gaussian blur + (1,2)dp offset render without clipping.
private const val SHADOW_PAD_DP = 4f
private const val ACTIVITY_RING_OFFSET_DP = 5f
private const val ACTIVITY_STROKE_DP = 2f
private const val SHARED_RING_OFFSET_DP = 12f

/**
 * iOS pins BOTH dot shadows to fixed black (3c8c443, in c1745e8 history):
 * "Fixed .black, not adaptive .ink: .ink inverts to near-white in dark
 * mode and renders as a light halo around every dot." Core shadow is
 * `.black.opacity(0.15)`, the favicon glyph shadow `.black.opacity(0.4)`.
 */
internal val DOT_SHADOW_COLOR = Color.Black.copy(alpha = 0.15f)
internal val FAVICON_SHADOW_TINT = Color.Black.copy(alpha = 0.4f)

/**
 * Per-row dot — verbatim port of iOS WalkDotView.swift @ c1745e8. Layer
 * stack (bottom → top):
 *  1. Animated ripple (newest only, Reduce-Motion-safe). No age fade.
 *  2. Outer halo radial gradient at 3.5× core size. No age fade.
 *  3. Blurred black drop shadow, then the core fill — radial gradient
 *     `color → color.copy(alpha=0.7)` from UnitPoint(0.4, 0.35) for the
 *     soft 3D feel. ×opacity.
 *  4. Favicon glyph + its black-40% shadow (if set). ×opacity.
 *  5. Activity arcs — rust talk arc + dawn meditate arc (trimmed). ×opacity.
 *  6. Specular highlight — small white-30% radial offset upper-left.
 *     ×(opacity × 0.5).
 *  7. Shared-walk stone ring (if isShared). ×opacity.
 *
 * The age fade (`opacity`, newest 1.0 → oldest 0.5) is applied per layer
 * exactly as iOS does — layers 1-2 stay constant while 3-7 fade.
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
    /**
     * iOS v1.6.0 — archived walks render as a thin hollow fog ring
     * (`stroke(Color.fog.opacity(0.5), lineWidth: 1)`) instead of the
     * full layered dot. Suppresses ripple / halo / favicon / activity
     * arcs / specular / shared-ring decoration so the released walk
     * reads as a quiet placeholder, not a live entry.
     */
    isArchived: Boolean = false,
    /**
     * iOS v1.6.0 — long-press on a dot triggers the same expand-card
     * overlay iOS shows via `previewSnapshot` + `previewPosition` state
     * in InkScrollView. On Android we route the long-press through
     * the same `onTap` handler so the existing ExpandCardSheet appears
     * — gesture parity with no need for a separate transient preview
     * surface that would duplicate the same content.
     */
    onLongPress: (() -> Unit)? = null,
) {
    val haloSizeDp = sizeDp * WalkDotMath.HALO_SCALE
    val activityRingSizeDp = sizeDp + ACTIVITY_RING_OFFSET_DP
    val sharedRingSizeDp = sizeDp + SHARED_RING_OFFSET_DP
    if (isArchived) {
        // iOS archived treatment (WalkDotView.swift @ c1745e8): a hollow
        // fog-50% ring at 0.6× the dot's nominal size inside a fixed
        // 44 pt touch frame, with NO age fade — the archived branch
        // ignores `opacity` entirely so released walks stay a constant
        // quiet placeholder.
        Box(
            modifier = modifier
                .size(WalkDotMath.MIN_TOUCH_DP.dp)
                .semantics {
                    this.contentDescription = contentDescription
                    // detectTapGestures (below) is invisible to TalkBack —
                    // declare the role + an activation action so the dot is
                    // announced as a button and openable via double-tap.
                    role = Role.Button
                    onClick { onTap(); true }
                }
                .pointerInput(onTap, onLongPress) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { (onLongPress ?: onTap)() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val ringColor = pilgrimColors.fog.copy(alpha = 0.5f)
            Canvas(Modifier.size((sizeDp * WalkDotMath.ARCHIVED_RING_SCALE).dp)) {
                val r = size.minDimension / 2f - 0.5f
                drawCircle(
                    color = ringColor,
                    radius = r,
                    center = Offset(size.width / 2f, size.height / 2f),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
        }
        return
    }
    // Age fade is applied PER LAYER exactly as iOS does — the ripple and
    // the outer halo carry NO `.opacity(opacity)` on iOS, so a root-level
    // alpha here would (and previously did) dim the aura of older dots.
    Box(
        modifier = modifier
            .size(WalkDotMath.dotBoxDp(sizeDp, isArchived = false).dp)
            .semantics {
                this.contentDescription = contentDescription
                // detectTapGestures (below) is invisible to TalkBack —
                // declare the role + an activation action so the dot is
                // announced as a button and openable via double-tap.
                role = Role.Button
                onClick { onTap(); true }
            }
            .pointerInput(onTap, onLongPress) {
                detectTapGestures(
                    onTap = { onTap() },
                    onLongPress = { (onLongPress ?: onTap)() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // 1. Ripple — newest only.
        if (isNewest) {
            RippleEffect(color = color, dotSizeDp = sizeDp)
        }

        // 2. Outer halo radial gradient (3.5× core). iOS uses a SwiftUI
        // RadialGradient with `startRadius = size * 0.5` + `endRadius =
        // size * 1.8` so the peak alpha forms a soft donut: flat-peak
        // from r=0 to r=0.5×sizeDp, fading to clear at r=1.8×sizeDp —
        // slightly past the 1.75×sizeDp shape edge, so the brush radius
        // deliberately exceeds the drawn circle. NOT age-faded: iOS
        // applies no `.opacity(opacity)` to this layer.
        Canvas(Modifier.size(haloSizeDp.dp)) {
            val r = size.minDimension / 2f
            val peak = color.copy(alpha = HALO_PEAK_ALPHA)
            val gradientEndPx = sizeDp.dp.toPx() * HALO_GRADIENT_END_SCALE
            val plateauStop =
                (HALO_GRADIENT_START_SCALE / HALO_GRADIENT_END_SCALE).coerceIn(0f, 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to peak,
                        plateauStop to peak,
                        1f to Color.Transparent,
                    ),
                    center = Offset(size.width / 2f, size.height / 2f),
                    radius = gradientEndPx,
                ),
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f),
            )
        }

        // 3a. Drop shadow — iOS `.shadow(color: .black.opacity(0.15),
        // radius: 2, x: 1, y: 2)`: a REAL Gaussian blur via
        // BlurMaskFilter, not the old two-hard-circle approximation
        // (which read as a crisp taupe disc behind the dot — the "ring"
        // — and made the dot look like a 3D sticker rather than ink on
        // paper). Fixed black per iOS 3c8c443 (adaptive ink flips to a
        // near-white halo in dark mode). iOS applies `.shadow` AFTER
        // `.opacity(opacity)`, so the shadow fades with the dot's age.
        // Drawn in its own slightly-larger Canvas so the blur + offset
        // aren't clipped by the core's bounds, and BEFORE the core so it
        // sits behind.
        val shadowArgb = DOT_SHADOW_COLOR
            .copy(alpha = DOT_SHADOW_COLOR.alpha * opacity)
            .toArgb()
        Canvas(Modifier.size((sizeDp + SHADOW_PAD_DP * 2f).dp)) {
            val coreR = sizeDp.dp.toPx() / 2f
            val cx = size.width / 2f + 1.dp.toPx()
            val cy = size.height / 2f + 2.dp.toPx()
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    setColor(shadowArgb)
                    maskFilter = android.graphics.BlurMaskFilter(
                        2.dp.toPx(),
                        android.graphics.BlurMaskFilter.Blur.NORMAL,
                    )
                }
                canvas.nativeCanvas.drawCircle(cx, cy, coreR, paint)
            }
        }
        // 3b. Core dot — radial gradient from full color to 70% alpha,
        // origin biased upper-left to read as a soft 3D bulge (iOS
        // parity). ×opacity = iOS `.opacity(opacity)` age fade on the
        // core circle.
        Canvas(Modifier.size(sizeDp.dp)) {
            val coreR = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val biasedCenter = Offset(size.width * 0.4f, size.height * 0.35f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        color.copy(alpha = color.alpha * opacity),
                        color.copy(alpha = color.alpha * 0.7f * opacity),
                    ),
                    center = biasedCenter,
                    radius = coreR * 1.2f,
                ),
                radius = coreR,
                center = center,
            )
        }

        // 4. Favicon glyph. iOS `.font(.system(size: size * 0.4)).bold()
        // .foregroundColor(.parchment).shadow(color: .black.opacity(0.4),
        // radius: 0.5, x: 0, y: 0.5).opacity(opacity)`. The 0.5× ratio
        // used previously made glyphs crowd the dot interior — 0.4×
        // matches iOS. The shadow is a black-40% copy nudged 0.5dp down
        // (the 0.5pt Gaussian blur is sub-pixel at glyph scale, omitted);
        // glyph + shadow fade together, as iOS composes the shadow before
        // the age fade.
        snapshot.favicon?.let { faviconKey ->
            val favicon = WalkFavicon.entries.firstOrNull { it.rawValue == faviconKey }
            if (favicon != null) {
                Box(Modifier.graphicsLayer { alpha = opacity }) {
                    Icon(
                        imageVector = favicon.icon,
                        contentDescription = null,
                        tint = FAVICON_SHADOW_TINT,
                        modifier = Modifier
                            .size((sizeDp * 0.4f).dp)
                            .graphicsLayer { translationY = 0.5.dp.toPx() },
                    )
                    Icon(
                        imageVector = favicon.icon,
                        contentDescription = null,
                        tint = pilgrimColors.parchment,
                        modifier = Modifier.size((sizeDp * 0.4f).dp),
                    )
                }
            }
        }

        // 5. Activity arcs — talk (rust) + meditate (dawn). Both
        // drawn around an `activityRingSizeDp` circle, trimmed to the
        // duration fraction. iOS rotateEffect(-90°) so 0deg starts at
        // top — Compose Canvas rotate() achieves the same. ×opacity =
        // iOS `.opacity(opacity)` on the arcs ZStack.
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
                                color = talkColor.copy(alpha = 0.7f * opacity),
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
                                color = meditateColor.copy(alpha = 0.7f * opacity),
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
        // catchlight with the core.
        Canvas(
            Modifier
                .size((sizeDp * 0.7f).dp)
                .graphicsLayer { alpha = 0.5f * opacity },
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
        // ×opacity = iOS `.opacity(opacity)` age fade on the ring.
        if (snapshot.isShared) {
            val ringColor = pilgrimColors.stone.copy(alpha = 0.5f * opacity)
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
