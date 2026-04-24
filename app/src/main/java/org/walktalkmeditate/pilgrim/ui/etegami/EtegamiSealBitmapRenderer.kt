// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.sin
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.ArcSegment
import org.walktalkmeditate.pilgrim.ui.design.seals.Dot
import org.walktalkmeditate.pilgrim.ui.design.seals.Radial
import org.walktalkmeditate.pilgrim.ui.design.seals.Ring
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.design.seals.sealGeometry

/**
 * Stage 7-C: draws a goshuin seal to a [Bitmap] via `android.graphics.Canvas`.
 * Reuses [sealGeometry] from Stage 4-A so the geometry layer is
 * identical to the Compose `SealRenderer` — only the draw code differs
 * (Compose `DrawScope` → `android.graphics.Canvas`).
 *
 * Intended for off-screen rendering into an etegami postcard; the
 * Compose `SealRenderer` remains the on-screen path.
 */
internal object EtegamiSealBitmapRenderer {

    /**
     * Returns a new [Bitmap] of [sizePx] × [sizePx] containing the
     * seal composition for [spec] with [ink] as the tint. Callers pass
     * the tint explicitly (the spec's own `ink` may be
     * `Color.Transparent` — our convention is that Compose-layer tint
     * resolution happens in the caller, which then supplies a final
     * color to the bitmap renderer).
     */
    fun renderToBitmap(
        spec: SealSpec,
        ink: Color,
        sizePx: Int,
        context: Context,
    ): Bitmap {
        require(sizePx > 0) { "sizePx must be > 0 (got $sizePx)" }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawInto(canvas, spec, ink, sizePx.toFloat(), context)
        return bitmap
    }

    /**
     * Draw into an existing [canvas] at position ([cx], [cy]) with the
     * seal spanning [sizePx] on each side (centered). Used by the
     * etegami pipeline to place the seal inside the postcard without
     * allocating a separate bitmap.
     */
    fun drawCentered(
        canvas: Canvas,
        spec: SealSpec,
        ink: Color,
        cx: Float,
        cy: Float,
        sizePx: Float,
        context: Context,
    ) {
        canvas.save()
        canvas.translate(cx - sizePx / 2f, cy - sizePx / 2f)
        drawInto(canvas, spec, ink, sizePx, context)
        canvas.restore()
    }

    private fun drawInto(
        canvas: Canvas,
        spec: SealSpec,
        ink: Color,
        sizePx: Float,
        context: Context,
    ) {
        if (sizePx <= 0f) return
        val geometry = sealGeometry(spec)
        val centerX = sizePx / 2f
        val centerY = sizePx / 2f
        val outerR = sizePx * 0.44f

        canvas.save()
        canvas.rotate(geometry.rotationDeg, centerX, centerY)
        drawRings(canvas, geometry.rings, centerX, centerY, outerR, sizePx, ink)
        drawRadials(canvas, geometry.radialLines, centerX, centerY, outerR, sizePx, ink)
        drawArcs(canvas, geometry.arcs, centerX, centerY, outerR, sizePx, ink)
        drawDots(canvas, geometry.dots, centerX, centerY, outerR, sizePx, ink)
        canvas.restore()

        drawCenterText(canvas, centerX, centerY, sizePx, spec, ink, context)
    }

    private fun drawRings(
        canvas: Canvas,
        rings: List<Ring>,
        cx: Float,
        cy: Float,
        outerR: Float,
        sizePx: Float,
        ink: Color,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        rings.forEach { ring ->
            paint.color = ink.copy(
                alpha = (ink.alpha * ring.opacity).coerceIn(0f, 1f),
            ).toArgb()
            paint.strokeWidth = sizePx * ring.strokeWidthFrac
            paint.pathEffect = ring.dashPattern
                ?.map { it * sizePx }
                ?.toFloatArray()
                ?.let { DashPathEffect(it, 0f) }
            canvas.drawCircle(cx, cy, outerR * ring.radiusFrac, paint)
            paint.pathEffect = null
        }
    }

    private fun drawRadials(
        canvas: Canvas,
        radials: List<Radial>,
        cx: Float,
        cy: Float,
        outerR: Float,
        sizePx: Float,
        ink: Color,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        radials.forEach { radial ->
            val rad = Math.toRadians(radial.angleDeg.toDouble())
            val cosA = cos(rad).toFloat()
            val sinA = sin(rad).toFloat()
            paint.color = ink.copy(
                alpha = (ink.alpha * radial.opacity).coerceIn(0f, 1f),
            ).toArgb()
            paint.strokeWidth = sizePx * radial.strokeWidthFrac
            canvas.drawLine(
                cx + cosA * outerR * radial.innerFrac,
                cy + sinA * outerR * radial.innerFrac,
                cx + cosA * outerR * radial.outerFrac,
                cy + sinA * outerR * radial.outerFrac,
                paint,
            )
        }
    }

    private fun drawArcs(
        canvas: Canvas,
        arcs: List<ArcSegment>,
        cx: Float,
        cy: Float,
        outerR: Float,
        sizePx: Float,
        ink: Color,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val rect = RectF()
        arcs.forEach { arc ->
            val radiusPx = outerR * arc.radiusFrac
            rect.set(cx - radiusPx, cy - radiusPx, cx + radiusPx, cy + radiusPx)
            paint.color = ink.copy(
                alpha = (ink.alpha * arc.opacity).coerceIn(0f, 1f),
            ).toArgb()
            paint.strokeWidth = sizePx * arc.strokeWidthFrac
            canvas.drawArc(rect, arc.startAngleDeg, arc.sweepDeg, false, paint)
        }
    }

    private fun drawDots(
        canvas: Canvas,
        dots: List<Dot>,
        cx: Float,
        cy: Float,
        outerR: Float,
        sizePx: Float,
        ink: Color,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        dots.forEach { dot ->
            val rad = Math.toRadians(dot.angleDeg.toDouble())
            val dx = cx + cos(rad).toFloat() * outerR * dot.distanceFrac
            val dy = cy + sin(rad).toFloat() * outerR * dot.distanceFrac
            paint.color = ink.copy(
                alpha = (ink.alpha * dot.opacity).coerceIn(0f, 1f),
            ).toArgb()
            canvas.drawCircle(dx, dy, sizePx * dot.radiusFrac, paint)
        }
    }

    private fun drawCenterText(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        sizePx: Float,
        spec: SealSpec,
        ink: Color,
        context: Context,
    ) {
        val cormorant = ResourcesCompat.getFont(context, R.font.cormorant_garamond_variable)
            ?: Typeface.DEFAULT
        val lato = ResourcesCompat.getFont(context, R.font.lato_regular)
            ?: Typeface.DEFAULT
        val distancePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = cormorant
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.09f
            color = ink.toArgb()
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = lato
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.032f
            color = ink.copy(alpha = (ink.alpha * 0.9f).coerceIn(0f, 1f)).toArgb()
        }
        val gapPx = sizePx * 0.008f
        val distanceMetrics = distancePaint.fontMetrics
        val unitMetrics = unitPaint.fontMetrics
        val distanceHeight = distanceMetrics.descent - distanceMetrics.ascent
        val unitHeight = unitMetrics.descent - unitMetrics.ascent
        val totalHeight = distanceHeight + gapPx + unitHeight
        val blockTop = cy - totalHeight / 2f
        val distanceBaseline = blockTop - distanceMetrics.ascent
        val unitBaseline = distanceBaseline + distanceMetrics.descent + gapPx - unitMetrics.ascent
        canvas.drawText(spec.displayDistance, cx, distanceBaseline, distancePaint)
        canvas.drawText(spec.unitLabel, cx, unitBaseline, unitPaint)
    }
}
