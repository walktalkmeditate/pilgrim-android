// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.sin
import org.walktalkmeditate.pilgrim.R

/**
 * Renders a single goshuin seal from [spec]. Pure draw layer — seeding
 * and geometry are computed once per spec via [remember]; the seasonal
 * tint comes in via [SealSpec.ink].
 *
 * Five procedural layers (the first four drawn under a global
 * hash-derived rotation):
 *   1. Concentric rings (3..8, count/jitter/dash from hash)
 *   2. Radial spokes (4..12)
 *   3. Arc accent segments (2..4)
 *   4. Decorative dots (3..7)
 *   5. Center text (distance + unit — stays upright, unrotated)
 *
 * iOS ships a further 4 decorative layers (weather, ghost route,
 * elevation ring, curved outer text) which Stage 4-A explicitly
 * defers — see the design spec's "Non-goals" block.
 *
 * Size-agnostic. Caller chooses the Canvas size via [modifier]; the
 * renderer enforces 1:1 aspect internally.
 */
@Composable
fun SealRenderer(
    spec: SealSpec,
    modifier: Modifier = Modifier,
) {
    // Key on the hash-seed fields only: geometry is deterministic from
    // uuid + startMillis + distanceMeters. Including `ink` in the key
    // (the full `spec`) would recompute identical geometry on every
    // theme/hemisphere flip, since those update `ink` but not the seed.
    val geometry = remember(spec.uuid, spec.startMillis, spec.distanceMeters) {
        sealGeometry(spec)
    }
    val context = LocalContext.current
    val cormorantTypeface = remember {
        ResourcesCompat.getFont(context, R.font.cormorant_garamond_variable)
            ?: Typeface.DEFAULT
    }
    val latoTypeface = remember {
        ResourcesCompat.getFont(context, R.font.lato_regular)
            ?: Typeface.DEFAULT
    }
    // Stage 4-B polish: cache Paint instances so the 60fps reveal
    // animation doesn't allocate 4 Paint + 2 FontMetrics per frame.
    // `textSize` + `color` mutate per-draw (cheap field assignments);
    // `typeface` + `textAlign` + `isAntiAlias` are stable across the
    // composable's lifetime. Keyed on typeface so a font-resource
    // failover (→ Typeface.DEFAULT) rebuilds the paints.
    val distancePaint = remember(cormorantTypeface) {
        NativePaint().apply {
            typeface = cormorantTypeface
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }
    val unitPaint = remember(latoTypeface) {
        NativePaint().apply {
            typeface = latoTypeface
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val canvasSize = size.minDimension
        if (canvasSize <= 0f) return@Canvas

        val center = Offset(canvasSize / 2f, canvasSize / 2f)
        val outerR = canvasSize * 0.44f

        rotate(degrees = geometry.rotationDeg, pivot = center) {
            drawRings(geometry.rings, center, outerR, canvasSize, spec.ink)
            drawRadialLines(geometry.radialLines, center, outerR, canvasSize, spec.ink)
            drawArcs(geometry.arcs, center, outerR, canvasSize, spec.ink)
            drawDots(geometry.dots, center, outerR, canvasSize, spec.ink)
        }

        // Center text isn't rotated — it stays upright regardless of
        // the seal's hash-derived rotation.
        drawCenterText(
            center = center,
            canvasSize = canvasSize,
            distance = spec.displayDistance,
            unit = spec.unitLabel,
            ink = spec.ink,
            distancePaint = distancePaint,
            unitPaint = unitPaint,
        )
    }
}

// --- layer helpers ---------------------------------------------------

private fun DrawScope.drawRings(
    rings: List<Ring>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    rings.forEach { ring ->
        val radiusPx = outerR * ring.radiusFrac
        val strokePx = canvasSize * ring.strokeWidthFrac
        val pathEffect = ring.dashPattern?.let { pattern ->
            PathEffect.dashPathEffect(
                intervals = pattern.map { it * canvasSize }.toFloatArray(),
                phase = 0f,
            )
        }
        drawCircle(
            color = ink.copy(alpha = (ink.alpha * ring.opacity).coerceIn(0f, 1f)),
            radius = radiusPx,
            center = center,
            style = Stroke(width = strokePx, pathEffect = pathEffect),
        )
    }
}

private fun DrawScope.drawRadialLines(
    radials: List<Radial>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    radials.forEach { radial ->
        val rad = Math.toRadians(radial.angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val innerOffset = Offset(
            center.x + cosA * outerR * radial.innerFrac,
            center.y + sinA * outerR * radial.innerFrac,
        )
        val outerOffset = Offset(
            center.x + cosA * outerR * radial.outerFrac,
            center.y + sinA * outerR * radial.outerFrac,
        )
        drawLine(
            color = ink.copy(alpha = (ink.alpha * radial.opacity).coerceIn(0f, 1f)),
            start = innerOffset,
            end = outerOffset,
            strokeWidth = canvasSize * radial.strokeWidthFrac,
        )
    }
}

private fun DrawScope.drawArcs(
    arcs: List<ArcSegment>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    arcs.forEach { arc ->
        val radiusPx = outerR * arc.radiusFrac
        drawArc(
            color = ink.copy(alpha = (ink.alpha * arc.opacity).coerceIn(0f, 1f)),
            startAngle = arc.startAngleDeg,
            sweepAngle = arc.sweepDeg,
            useCenter = false,
            topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
            size = Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(width = canvasSize * arc.strokeWidthFrac),
        )
    }
}

private fun DrawScope.drawDots(
    dots: List<Dot>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    dots.forEach { dot ->
        val rad = Math.toRadians(dot.angleDeg.toDouble())
        val dotCenter = Offset(
            center.x + cos(rad).toFloat() * outerR * dot.distanceFrac,
            center.y + sin(rad).toFloat() * outerR * dot.distanceFrac,
        )
        drawCircle(
            color = ink.copy(alpha = (ink.alpha * dot.opacity).coerceIn(0f, 1f)),
            radius = canvasSize * dot.radiusFrac,
            center = dotCenter,
        )
    }
}

private fun DrawScope.drawCenterText(
    center: Offset,
    canvasSize: Float,
    distance: String,
    unit: String,
    ink: Color,
    distancePaint: NativePaint,
    unitPaint: NativePaint,
) {
    val distanceTextPx = canvasSize * 0.09f
    val unitTextPx = canvasSize * 0.032f
    val gapPx = canvasSize * 0.008f
    // Mutate the cached paints per-frame — no allocation, just field
    // assignments on the Paint already created at composition time.
    distancePaint.textSize = distanceTextPx
    distancePaint.color = ink.toArgb()
    unitPaint.textSize = unitTextPx
    unitPaint.color = ink.copy(alpha = (ink.alpha * 0.9f).coerceIn(0f, 1f)).toArgb()
    drawIntoCanvas { composeCanvas ->
        val native = composeCanvas.nativeCanvas
        // Visual-center the two-line block around `center.y`.
        // `Paint.drawText`'s y parameter is the glyph BASELINE, not the
        // visual center — naive `center.y` would place digits above
        // the center with the unit label sitting below, skewing the
        // block upward. Measure both lines' ascent/descent and offset
        // so their combined visual box straddles center.y evenly.
        val distanceMetrics = distancePaint.fontMetrics
        val unitMetrics = unitPaint.fontMetrics
        val distanceHeight = distanceMetrics.descent - distanceMetrics.ascent
        val unitHeight = unitMetrics.descent - unitMetrics.ascent
        val totalHeight = distanceHeight + gapPx + unitHeight
        val blockTop = center.y - totalHeight / 2f
        val distanceBaseline = blockTop - distanceMetrics.ascent
        val unitBaseline = distanceBaseline + distanceMetrics.descent + gapPx - unitMetrics.ascent
        native.drawText(distance, center.x, distanceBaseline, distancePaint)
        native.drawText(unit, center.x, unitBaseline, unitPaint)
    }
}
