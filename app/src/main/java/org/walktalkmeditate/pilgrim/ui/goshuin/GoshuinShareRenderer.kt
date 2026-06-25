// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealColorPalette
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSealBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.settings.about.AboutSeasonHelpers
import org.walktalkmeditate.pilgrim.ui.settings.about.Season

/**
 * v1.6.0 iOS-parity port of `GoshuinShareRenderer.swift`. Draws the
 * full goshuin collection to a 1080×1920 [Bitmap] for `ACTION_SEND`,
 * mirroring the iOS `UIGraphicsImageRenderer` layout:
 *
 *  - parchment fill + patina overlay scaled by lifetime walk count
 *  - seeded paper-grain speckle + thin inner border
 *  - header: "My Goshuin" + "N walks · X km|mi" + "Season YYYY"
 *  - up to 12 seals (milestones-first by date, then most-recent fill),
 *    each with a favicon tint circle, optional milestone ring, slight
 *    seeded jitter/rotation, drawn via [EtegamiSealBitmapRenderer]
 *    (identical geometry to the on-screen [GoshuinSealCell])
 *  - footprint glyph + italic tagline + "pilgrimapp.org" provenance
 *
 * Pure: no Compose-theme reads (uses the light-theme color literals,
 * same as the iOS share image which renders against the named asset
 * colours). Off-screen Canvas work — call from `Dispatchers.Default`.
 *
 * iOS uses a platform `SeededRNG`; the per-seal scatter here uses a
 * small deterministic LCG seeded the same way (by seal count / a
 * fixed grain seed). Pixel-identical RNG output is not required —
 * the scatter is decorative and the goshuin.populated parity bar is
 * "the Share affordance exists and produces the iOS layout", not a
 * byte-identical RNG.
 */
internal object GoshuinShareRenderer {

    const val CANVAS_W = 1080
    const val CANVAS_H = 1920
    private const val BORDER_INSET = 40f
    private const val MAX_SEALS = 12

    // Light-theme literals (PilgrimLightColors) — the share image is a
    // standalone artifact, rendered light like iOS's.
    private const val INK = 0xFF2C2416.toInt()
    private const val PARCHMENT = 0xFFF5F0E8.toInt()
    private const val DAWN = 0xFFC4956A.toInt()
    // AF68: WCAG-contrast fog. Always-light render tracks the light fog asset
    // (iOS GoshuinShareRenderer: UIColor(named: "fog")); post-fix @ 3c8c443.
    // `internal` so a test pins it to PilgrimPaletteLight.fog.
    internal const val FOG = 0xFF8A8175.toInt()

    /** iOS `tintColor(for:)` — favicon → seal tint. */
    private fun tintArgb(favicon: org.walktalkmeditate.pilgrim.data.entity.WalkFavicon?): Int =
        when (favicon?.rawValue) {
            "flame" -> 0xFFA0634B.toInt()
            "leaf" -> 0xFF7A8B6F.toInt()
            "star" -> 0xFF4B5A78.toInt()
            else -> 0xFF8B7355.toInt()
        }

    /**
     * @param selected the favicon-filtered view (iOS `input.walks`).
     * @param totalWalkCount lifetime finished walks incl. archived
     *        (iOS `input.allWalks.count`) — drives the patina.
     * @param isImperial distance-unit preference.
     */
    fun render(
        context: Context,
        selected: List<GoshuinSeal>,
        totalWalkCount: Int,
        isImperial: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(CANVAS_W, CANVAS_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas, totalWalkCount)
        drawPaperGrain(canvas)
        drawInnerBorder(canvas)
        drawHeader(canvas, context, selected, isImperial)

        val sealsForGrid = selectSeals(selected)
        drawSeals(canvas, context, sealsForGrid)

        drawFootprint(canvas, CANVAS_W / 2f, CANVAS_H - 240f, 24f)
        drawTagline(canvas, context, CANVAS_W / 2f, CANVAS_H - 210f)
        drawProvenance(canvas, context)
        return bitmap
    }

    // MARK: - Seal selection (iOS selectSeals)

    private fun selectSeals(walks: List<GoshuinSeal>): List<GoshuinSeal> {
        val result = LinkedHashMap<String, GoshuinSeal>()
        fun key(s: GoshuinSeal) = s.uuid.ifEmpty { "id:${s.walkId}" }

        walks.sortedBy { it.startMillis }.forEach { s ->
            if (result.size >= MAX_SEALS) return@forEach
            if (s.milestone != null) result.putIfAbsent(key(s), s)
        }
        walks.sortedByDescending { it.startMillis }.forEach { s ->
            if (result.size >= MAX_SEALS) return@forEach
            result.putIfAbsent(key(s), s)
        }
        return result.values.toList()
    }

    // MARK: - Background

    private fun drawBackground(canvas: Canvas, walkCount: Int) {
        canvas.drawColor(PARCHMENT)
        val patina = when {
            walkCount <= 10 -> 0f
            walkCount <= 30 -> 0.03f
            walkCount <= 70 -> 0.07f
            else -> 0.12f
        }
        if (patina > 0f) {
            val p = Paint().apply {
                color = DAWN
                alpha = (patina * 255f).toInt()
            }
            canvas.drawRect(0f, 0f, CANVAS_W.toFloat(), CANVAS_H.toFloat(), p)
        }
    }

    private fun drawPaperGrain(canvas: Canvas) {
        val rng = Lcg(12345L)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            alpha = (0.025f * 255f).toInt()
        }
        repeat(3000) {
            val x = rng.nextFloat() * CANVAS_W
            val y = rng.nextFloat() * CANVAS_H
            val r = 0.5f + rng.nextFloat() * 1.0f
            canvas.drawCircle(x, y, r, p)
        }
    }

    private fun drawInnerBorder(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 0.5f
            color = INK
            alpha = (0.08f * 255f).toInt()
        }
        canvas.drawRect(
            BORDER_INSET,
            BORDER_INSET,
            CANVAS_W - BORDER_INSET,
            CANVAS_H - BORDER_INSET,
            p,
        )
    }

    // MARK: - Header

    private fun drawHeader(
        canvas: Canvas,
        context: Context,
        selected: List<GoshuinSeal>,
        isImperial: Boolean,
    ) {
        val cormorant = font(context, R.font.cormorant_garamond_variable, Typeface.SERIF)
        val lato = font(context, R.font.lato_regular, Typeface.SANS_SERIF)
        val centerX = CANVAS_W / 2f

        val totalMeters = selected.sumOf { it.distanceMeters }
        val distVal = if (isImperial) totalMeters / 1000.0 * 0.621371 else totalMeters / 1000.0
        val unit = if (isImperial) "mi" else "km"
        val distStr = if (distVal >= 100) {
            String.format(Locale.US, "%.0f", distVal)
        } else {
            String.format(Locale.US, "%.1f", distVal)
        }
        val statsLine = "${selected.size} walks · $distStr $unit"

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = cormorant
            textSize = 48f
            textAlign = Paint.Align.CENTER
            color = INK
            alpha = (0.85f * 255f).toInt()
        }
        canvas.drawText(context.getString(R.string.goshuin_share_title), centerX, 145f, title)

        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = lato
            textSize = 16f
            textAlign = Paint.Align.CENTER
            color = FOG
            alpha = (0.7f * 255f).toInt()
        }
        canvas.drawText(statsLine, centerX, 185f, sub)

        val seasonStr = seasonLabel(context, selected)
        if (seasonStr.isNotEmpty()) {
            val seasonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = lato
                textSize = 14f
                textAlign = Paint.Align.CENTER
                color = FOG
                alpha = (0.5f * 255f).toInt()
            }
            canvas.drawText(seasonStr, centerX, 218f, seasonPaint)
        }
    }

    private fun seasonLabel(context: Context, selected: List<GoshuinSeal>): String {
        val latest = selected.maxByOrNull { it.startMillis } ?: return ""
        val instant = Instant.ofEpochMilli(latest.startMillis)
        // iOS `deriveSeasonLabel` keys the season off the latest walk's
        // first route coordinate (`routePoints.first?.lat`), so a southern
        // walk reads "Summer" in December.
        val season = AboutSeasonHelpers.season(instant, latitude = latest.firstRouteLatitude)
        val year = instant.atZone(ZoneId.systemDefault()).year
        val res = when (season) {
            Season.Spring -> R.string.practice_summary_season_spring
            Season.Summer -> R.string.practice_summary_season_summer
            Season.Autumn -> R.string.practice_summary_season_autumn
            Season.Winter -> R.string.practice_summary_season_winter
        }
        return context.getString(res, year)
    }

    // MARK: - Seals

    private fun drawSeals(canvas: Canvas, context: Context, selected: List<GoshuinSeal>) {
        if (selected.isEmpty()) return

        val (sealSize, columns) = when (selected.size) {
            in 1..3 -> 280f to minOf(selected.size, 3)
            in 4..6 -> 250f to 3
            else -> 220f to 3
        }
        val spacing = 30f
        val rowSpacing = 40f
        val gridOriginY = 275f
        val rng = Lcg(selected.size.toLong())

        val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context, R.font.lato_regular, Typeface.SANS_SERIF)
            textSize = 12f
            textAlign = Paint.Align.CENTER
            color = FOG
            alpha = (0.6f * 255f).toInt()
        }
        val totalRows = (selected.size + columns - 1) / columns

        selected.forEachIndexed { index, seal ->
            val col = index % columns
            val row = index / columns
            val itemsInThisRow =
                if (row == totalRows - 1) selected.size - row * columns else columns
            val rowWidth =
                itemsInThisRow * sealSize + max(itemsInThisRow - 1, 0) * spacing
            val rowOriginX = (CANVAS_W - rowWidth) / 2f

            val baseX = rowOriginX + col * (sealSize + spacing)
            val baseY = gridOriginY + row * (sealSize + rowSpacing)
            val offsetX = (rng.nextFloat() * 16f) - 8f
            val offsetY = (rng.nextFloat() * 16f) - 8f
            val rotationDeg = ((rng.nextFloat() * 6f) - 3f)

            val centerX = baseX + sealSize / 2f + offsetX
            val centerY = baseY + sealSize / 2f + offsetY
            val tint = tintArgb(seal.favicon)

            canvas.save()
            canvas.rotate(rotationDeg, centerX, centerY)

            val tintRect = RectF(
                centerX - sealSize / 2f - 4f,
                centerY - sealSize / 2f - 4f,
                centerX + sealSize / 2f + 4f,
                centerY + sealSize / 2f + 4f,
            )
            val tintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = tint
                alpha = (0.08f * 255f).toInt()
            }
            canvas.drawOval(tintRect, tintFill)

            if (seal.milestone != null) {
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f
                    color = DAWN
                    alpha = (0.4f * 255f).toInt()
                }
                canvas.drawOval(tintRect, ring)
            }

            // The decorative oval behind the seal uses the representative
            // family color (`tint`, iOS `tintColor(for:)`), but the seal
            // ink itself comes from the full favicon-family palette +
            // turning override (iOS `SealGenerator` → `uiColor(for:)`).
            // Share images render on a light card, so resolve light.
            EtegamiSealBitmapRenderer.drawCentered(
                canvas = canvas,
                spec = seal.sealSpec,
                ink = SealColorPalette.sealInk(seal.sealSpec, isDark = false),
                cx = centerX,
                cy = centerY,
                sizePx = sealSize,
                context = context,
            )
            canvas.restore()

            seal.milestone?.let { ms ->
                canvas.drawText(
                    GoshuinMilestones.label(ms),
                    centerX,
                    centerY + sealSize / 2f + 18f,
                    captionPaint,
                )
            }
        }
    }

    // MARK: - Colophon

    private fun drawFootprint(canvas: Canvas, cx: Float, cy: Float, height: Float) {
        val w = height * 0.6f
        val h = height
        val ox = cx - w / 2f
        val oy = cy - h / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            alpha = (0.15f * 255f).toInt()
        }
        fun ell(fx: Float, fy: Float, fw: Float, fh: Float) {
            canvas.drawOval(
                RectF(ox + w * fx, oy + h * fy, ox + w * fx + w * fw, oy + h * fy + h * fh),
                p,
            )
        }
        ell(0.22f, 0.75f, 0.50f, 0.25f)
        ell(0.50f, 0.48f, 0.22f, 0.34f)
        ell(0.08f, 0.38f, 0.62f, 0.22f)
        ell(0.10f, 0.18f, 0.24f, 0.24f)
        ell(0.32f, 0.10f, 0.18f, 0.22f)
        ell(0.48f, 0.06f, 0.16f, 0.20f)
        ell(0.62f, 0.10f, 0.14f, 0.18f)
        ell(0.72f, 0.18f, 0.12f, 0.14f)
    }

    private fun drawTagline(canvas: Canvas, context: Context, cx: Float, y: Float) {
        val italic = font(
            context,
            R.font.cormorant_garamond_italic_variable,
            Typeface.create(Typeface.SERIF, Typeface.ITALIC),
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = italic
            textSize = 24f
            textAlign = Paint.Align.CENTER
            color = INK
            alpha = (0.25f * 255f).toInt()
        }
        canvas.drawText(context.getString(R.string.goshuin_share_tagline), cx, y, p)
    }

    private fun drawProvenance(canvas: Canvas, context: Context) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = font(context, R.font.lato_regular, Typeface.SANS_SERIF)
            textSize = 14f
            textAlign = Paint.Align.RIGHT
            color = INK
            alpha = (0.4f * 255f).toInt()
        }
        canvas.drawText(
            context.getString(R.string.goshuin_share_provenance),
            CANVAS_W - 60f,
            CANVAS_H - 60f,
            p,
        )
    }

    private fun font(context: Context, resId: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(context, resId) }.getOrNull() ?: fallback

    /**
     * Minimal deterministic LCG (glibc constants). Replaces iOS's
     * platform `SeededRNG` for the decorative paper-grain + seal
     * scatter — same seeding scheme, visually-equivalent output.
     */
    private class Lcg(seed: Long) {
        private var state = seed
        fun nextFloat(): Float {
            state = (state * 1103515245L + 12345L) and 0x7FFFFFFFL
            return state.toFloat() / 0x7FFFFFFFL.toFloat()
        }
    }
}
