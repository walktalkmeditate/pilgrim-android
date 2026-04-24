// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Stage 7-C: pure-bitmap etegami postcard renderer.
 *
 * Mirrors iOS's `EtegamiRenderer` layer-for-layer against a fixed
 * 1080×1920 ARGB_8888 canvas. `render` is a `suspend fun` running on
 * [Dispatchers.Default] — encoding 2.1M pixels is NOT a Main-thread
 * operation (Stage 2-E ANR lesson).
 *
 * Each layer is an `internal fun` for test-accessibility; the main
 * [render] composes them in z-order.
 */
object EtegamiBitmapRenderer {

    const val WIDTH_PX = 1080
    const val HEIGHT_PX = 1920
    internal const val GRAIN_SEED = 12345L
    internal const val GRAIN_ALPHA = 0.025f
    internal const val SEAL_SIZE_PX = 160f
    internal const val SEAL_CX = 160f
    internal const val SEAL_CY = 1200f
    internal const val MOON_CX = 960f
    internal const val MOON_CY = 200f
    internal const val MOON_RADIUS = 28f
    /** Synodic-period half — boundary between waxing and waning in days. */
    internal const val SYNODIC_PERIOD_HALF_DAYS = 14.765294385288
    /**
     * Cormorant Garamond ships as a variable-weight TTF. Paint's default
     * instance renders at the font's built-in default (~400 on this
     * build), so variable fonts need an explicit wght-axis pin. The
     * etegami design mandates weight 300 for the austere aesthetic — per
     * the Stage 3-B lesson, apply this via
     * `paint.fontVariationSettings = CORMORANT_WGHT_300` on every
     * Cormorant paint (seal distance + top text).
     */
    internal const val CORMORANT_WGHT_300 = "'wght' 300"

    suspend fun render(spec: EtegamiSpec, context: Context): Bitmap =
        withContext(Dispatchers.Default) {
            val bitmap = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val palette = EtegamiPalettes.forHour(spec.hourOfDay)
            val smoothed = EtegamiRouteGeometry.smooth(spec.routePoints)

            drawPaper(canvas, palette)
            drawRadialGradient(canvas, palette, smoothed)
            drawGrain(canvas, palette)
            drawInnerBorder(canvas, palette)
            if (spec.moonPhase != null) {
                drawMoonGlyph(
                    canvas = canvas,
                    palette = palette,
                    illumination = spec.moonPhase.illumination,
                    isWaxing = spec.moonPhase.ageInDays < SYNODIC_PERIOD_HALF_DAYS,
                )
            }
            if (smoothed.size >= 2) {
                drawRouteGlow(canvas, palette, smoothed)
                drawRouteCrisp(canvas, palette, smoothed)
                drawActivityMarkers(canvas, palette, smoothed, spec)
            }
            drawSealWithGlow(canvas, palette, spec.sealSpec, context)
            spec.topText?.takeIf { it.isNotBlank() }?.let {
                drawTopText(canvas, palette, it, context)
            }
            drawStatsWhisper(canvas, palette, spec, context)
            drawProvenance(canvas, palette, context)
            bitmap
        }

    // --- layer 1: paper fill --------------------------------------------

    internal fun drawPaper(canvas: Canvas, palette: EtegamiPalette) {
        canvas.drawColor(palette.paper.toArgb())
    }

    // --- layer 2: radial gradient from route center ---------------------

    internal fun drawRadialGradient(
        canvas: Canvas,
        palette: EtegamiPalette,
        smoothed: List<SmoothedSegment>,
    ) {
        val (cx, cy) = routeCenter(smoothed)
        val radius = max(WIDTH_PX, HEIGHT_PX) * 0.7f
        // Slightly lighter paper color at the center fading to paper
        // edges. Lighten by +0.03 on each RGB channel, clamped.
        val center = lightenedPaper(palette.paper, delta = 0.03f)
        val edge = palette.paper
        val shader = RadialGradient(
            cx, cy, radius,
            intArrayOf(center.toArgb(), edge.toArgb()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader }
        canvas.drawRect(0f, 0f, WIDTH_PX.toFloat(), HEIGHT_PX.toFloat(), paint)
    }

    // --- layer 3: seeded paper grain -----------------------------------

    internal fun drawGrain(canvas: Canvas, palette: EtegamiPalette) {
        val dots = EtegamiGrain.dots(
            seed = GRAIN_SEED,
            count = EtegamiGrain.DEFAULT_COUNT,
            width = WIDTH_PX,
            height = HEIGHT_PX,
        )
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.ink.copy(
                alpha = (palette.ink.alpha * GRAIN_ALPHA).coerceIn(0f, 1f),
            ).toArgb()
        }
        dots.forEach { d -> canvas.drawCircle(d.x, d.y, d.radius, paint) }
    }

    // --- layer 4: inner border inset ------------------------------------

    internal fun drawInnerBorder(canvas: Canvas, palette: EtegamiPalette) {
        val inset = 40f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = palette.ink.copy(alpha = 0.08f).toArgb()
        }
        canvas.drawRect(inset, inset, WIDTH_PX - inset, HEIGHT_PX - inset, paint)
    }

    // --- layer 5: moon terminator glyph ---------------------------------

    internal fun drawMoonGlyph(
        canvas: Canvas,
        palette: EtegamiPalette,
        illumination: Double,
        isWaxing: Boolean,
    ) {
        // Skip the new-moon glyph — the degenerate path renders as an
        // invisible sliver on the right edge, which just wastes a
        // Path + 2 fills. Callers can still rely on drawMoonGlyph
        // being safe to call at any illumination; this is a draw-no-op.
        if (illumination < 0.02) return
        val path = EtegamiMoonGlyph.terminatorPath(
            illumination = illumination,
            isWaxing = isWaxing,
            cx = MOON_CX,
            cy = MOON_CY,
            radius = MOON_RADIUS,
        )
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.ink.copy(alpha = 0.13f).toArgb()
        }
        canvas.drawPath(path, fillPaint)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = palette.ink.copy(alpha = 0.08f).toArgb()
        }
        canvas.drawPath(path, strokePaint)
    }

    // --- layer 6: route glow pass ---------------------------------------

    internal fun drawRouteGlow(
        canvas: Canvas,
        palette: EtegamiPalette,
        smoothed: List<SmoothedSegment>,
    ) {
        val path = Path().apply {
            smoothed.forEachIndexed { i, s ->
                if (i == 0) moveTo(s.x, s.y) else lineTo(s.x, s.y)
            }
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 24f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.ink.copy(alpha = 0.12f).toArgb()
        }
        canvas.drawPath(path, paint)
    }

    // --- layer 7: route crisp pass + variable width + taper -------------

    internal fun drawRouteCrisp(
        canvas: Canvas,
        palette: EtegamiPalette,
        smoothed: List<SmoothedSegment>,
    ) {
        // Defensive: internal fun can be called by tests with sparse
        // inputs. Caller in render() already guards size >= 2.
        if (smoothed.isEmpty()) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (i in 0 until smoothed.size - 1) {
            val a = smoothed[i]
            val b = smoothed[i + 1]
            val width = 8f * a.widthMultiplier
            val opacity = 0.9f * (0.6f + a.taper * 0.4f)
            paint.strokeWidth = width
            paint.color = palette.ink.copy(
                alpha = (palette.ink.alpha * opacity).coerceIn(0f, 1f),
            ).toArgb()
            canvas.drawLine(a.x, a.y, b.x, b.y, paint)
        }
        // Start dot (filled) + end ring (stroked)
        val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = palette.ink.copy(alpha = 0.5f).toArgb()
        }
        val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
            color = palette.ink.copy(alpha = 0.9f).toArgb()
        }
        val first = smoothed.first()
        val last = smoothed.last()
        canvas.drawCircle(first.x, first.y, 8f, startPaint)
        canvas.drawCircle(last.x, last.y, 8f, endPaint)
    }

    // --- activity markers (meditation rings / voice waveform) ----------

    internal fun drawActivityMarkers(
        canvas: Canvas,
        palette: EtegamiPalette,
        smoothed: List<SmoothedSegment>,
        spec: EtegamiSpec,
    ) {
        if (spec.routePoints.size < 2 || smoothed.isEmpty()) return
        val meditationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
            color = palette.ink.copy(alpha = 0.55f).toArgb()
        }
        val voicePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            strokeCap = Paint.Cap.ROUND
            color = palette.ink.copy(alpha = 0.7f).toArgb()
        }
        val voiceBarHeights = floatArrayOf(4f, 10f, 16f, 10f, 4f)
        spec.activityMarkers.forEach { marker ->
            val smoothedIndex = indexAtTimestamp(spec, smoothed, marker.timestampMs)
            val p = smoothed.getOrNull(smoothedIndex) ?: return@forEach
            when (marker.kind) {
                ActivityMarker.Kind.Meditation -> {
                    // Three concentric rings at radius 14, 28, 42.
                    listOf(14f, 28f, 42f).forEach { r ->
                        canvas.drawCircle(p.x, p.y, r, meditationPaint)
                    }
                }
                ActivityMarker.Kind.Voice -> {
                    val offset = 30f
                    val baseY = p.y - 20f
                    val barSpacing = 6f
                    val baseX = p.x + offset - (voiceBarHeights.size - 1) * barSpacing / 2f
                    voiceBarHeights.forEachIndexed { i, h ->
                        val x = baseX + i * barSpacing
                        canvas.drawLine(x, baseY - h / 2f, x, baseY + h / 2f, voicePaint)
                    }
                }
            }
        }
    }

    // --- layer 8+9: seal with glow + drop shadow -----------------------

    internal fun drawSealWithGlow(
        canvas: Canvas,
        palette: EtegamiPalette,
        sealSpec: org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec,
        context: Context,
    ) {
        // Glow behind the seal — radial gradient ink → transparent.
        val glowRadius = 140f
        val glowShader = RadialGradient(
            SEAL_CX, SEAL_CY, glowRadius,
            intArrayOf(
                palette.ink.copy(alpha = 0.08f).toArgb(),
                palette.ink.copy(alpha = 0f).toArgb(),
            ),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = glowShader }
        canvas.drawCircle(SEAL_CX, SEAL_CY, glowRadius, glowPaint)

        // The seal bitmap — drawn with a shadow layer for depth. The
        // shadow layer requires software rendering; our bitmap-backed
        // Canvas is inherently software, so `setShadowLayer` is fine.
        val sealBitmap = EtegamiSealBitmapRenderer.renderToBitmap(
            spec = sealSpec,
            ink = palette.ink,
            sizePx = SEAL_SIZE_PX.roundToInt(),
            context = context,
        )
        val sealPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            setShadowLayer(12f, 0f, 4f, 0x1A000000)
        }
        canvas.drawBitmap(
            sealBitmap,
            SEAL_CX - SEAL_SIZE_PX / 2f,
            SEAL_CY - SEAL_SIZE_PX / 2f,
            sealPaint,
        )
        sealBitmap.recycle()
    }

    // --- layer 10: intention / notes / haiku text ----------------------

    internal fun drawTopText(
        canvas: Canvas,
        palette: EtegamiPalette,
        text: String,
        context: Context,
    ) {
        val cormorant = ResourcesCompat.getFont(context, R.font.cormorant_garamond_variable)
            ?: Typeface.DEFAULT
        val paint = TextPaint().apply {
            typeface = cormorant
            fontVariationSettings = CORMORANT_WGHT_300
            isAntiAlias = true
            textSize = 46f
            color = palette.ink.copy(alpha = 0.85f).toArgb()
        }
        val width = 920
        // Bound height so an unusually long intention/notes doesn't
        // overflow into the stats whisper region (y=1700). iOS caps
        // at a 360 px rect ≈ 6 lines of Cormorant Garamond at 46 px
        // with 8 px line-spacing. Ellipsize the tail rather than
        // crop mid-glyph.
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(8f, 1f)
            .setMaxLines(6)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(80f, 1320f)
        layout.draw(canvas)
        canvas.restore()
    }

    // --- layer 11: stats whisper ---------------------------------------

    internal fun drawStatsWhisper(
        canvas: Canvas,
        palette: EtegamiPalette,
        spec: EtegamiSpec,
        context: Context,
    ) {
        val lato = ResourcesCompat.getFont(context, R.font.lato_regular) ?: Typeface.DEFAULT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = lato
            textAlign = Paint.Align.CENTER
            textSize = 16f
            color = palette.ink.copy(alpha = 0.4f).toArgb()
        }
        val parts = buildList {
            add(WalkFormat.distance(spec.distanceMeters))
            add(WalkFormat.duration(spec.durationMillis))
            // `isFinite()` guard is redundant with `elevationGain()`'s
            // source-side NaN filter, but cheap defense-in-depth —
            // `Double.roundToInt()` throws on NaN / ±∞, and a throw
            // here silently erases the whole postcard via
            // WalkEtegamiCard's Throwable catch.
            if (spec.elevationGainMeters > 1.0 && spec.elevationGainMeters.isFinite()) {
                add("${spec.elevationGainMeters.roundToInt()}m ↑")
            }
        }
        val text = parts.joinToString(" · ")
        canvas.drawText(text, WIDTH_PX / 2f, 1700f, paint)
    }

    // --- layer 12: provenance ------------------------------------------

    internal fun drawProvenance(canvas: Canvas, palette: EtegamiPalette, context: Context) {
        val lato = ResourcesCompat.getFont(context, R.font.lato_regular) ?: Typeface.DEFAULT
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = lato
            textAlign = Paint.Align.RIGHT
            textSize = 14f
            color = palette.ink.copy(alpha = 0.4f).toArgb()
        }
        canvas.drawText("pilgrimapp.org", WIDTH_PX - 60f, HEIGHT_PX - 60f, paint)
    }

    // --- helpers --------------------------------------------------------

    private fun routeCenter(smoothed: List<SmoothedSegment>): Pair<Float, Float> {
        if (smoothed.isEmpty()) return WIDTH_PX / 2f to HEIGHT_PX / 2f
        val cx = smoothed.map { it.x }.average().toFloat()
        val cy = smoothed.map { it.y }.average().toFloat()
        return cx to cy
    }

    private fun lightenedPaper(base: Color, delta: Float): Color = Color(
        red = (base.red + delta).coerceIn(0f, 1f),
        green = (base.green + delta).coerceIn(0f, 1f),
        blue = (base.blue + delta).coerceIn(0f, 1f),
        alpha = base.alpha,
    )

    /**
     * Locate the smoothed-array index that corresponds to [timestampMs]
     * along the walk's route. iOS uses `closestIndex(timestamp,
     * routeTimestamps) × 8` — we mirror that, mapping the original
     * route index to the 8×-expanded smoothed index.
     *
     * `internal` so the last-point clamp boundary can be asserted
     * directly in unit tests (the `coerceIn` is doing real work for
     * the last route index, and a silent drift here would only surface
     * as misplaced activity glyphs at scale).
     */
    internal fun indexAtTimestamp(
        spec: EtegamiSpec,
        smoothed: List<SmoothedSegment>,
        timestampMs: Long,
    ): Int {
        if (spec.routePoints.isEmpty()) return 0
        val closestOrig = spec.routePoints.indices.minByOrNull { i ->
            kotlin.math.abs(spec.routePoints[i].timestamp - timestampMs)
        } ?: 0
        return (closestOrig * EtegamiRouteGeometry.DEFAULT_SUBDIVISIONS)
            .coerceIn(0, smoothed.size - 1)
    }

}
