// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import androidx.annotation.DrawableRes
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.cairn.CairnTier

/**
 * Rasterizes the U13 vector glyph masters into Mapbox-ready bitmaps —
 * iOS parity `MapGlyphImageBuilder.swift@9a418e4` (port spec
 * docs/parity/2026-07-27-port-map-glyphs-u14-u15.md). Mapbox stores
 * raster sprites, so everything draws at dp × display density (the
 * Android analogue of iOS R11's "display size × screen scale": the
 * annotation plugin registers icon bitmaps at the map's pixel ratio,
 * so a dp×density raster renders at exactly [sizeDp] on-screen) and
 * is cached — the key space is small and fixed: 8 whisper mood tints
 * and 7 cairn tiers.
 *
 * Cache keys carry exactly what the draw reads: mood tint + size +
 * density for the wisp, tier + size + density for the cairn — and
 * deliberately NO theme component. The wisp's tint is a fixed
 * `WhisperCategory` literal and cairn art is baked fixed-hex, so a
 * light/dark flip must hit the same cached bitmaps.
 *
 * Main-thread only, like all callers (composition-time `remember`) —
 * same discipline as the iOS builder.
 */
object MapGlyphBitmaps {

    private val cache = HashMap<String, Bitmap>()

    fun wisp(context: Context, tintArgb: Int, sizeDp: Float, density: Float): Bitmap? =
        rendered(
            context = context,
            drawableId = R.drawable.glyph_whisper_wisp,
            tintArgb = tintArgb,
            // RGB-only, like iOS's `whisper-RRGGBB`: tints are the fixed
            // opaque WhisperCategory literals, alpha never varies.
            key = String.format(
                Locale.US,
                "whisper-%06X-%s-%s",
                tintArgb and 0xFFFFFF,
                sizeDp,
                density,
            ),
            sizeDp = sizeDp,
            density = density,
        )

    fun cairn(context: Context, tier: CairnTier, sizeDp: Float, density: Float): Bitmap? =
        rendered(
            context = context,
            drawableId = tierDrawable(tier),
            tintArgb = null,
            key = "cairn-${tier.ordinal}-$sizeDp-$density",
            sizeDp = sizeDp,
            density = density,
        )

    @VisibleForTesting
    internal fun rendered(
        context: Context,
        @DrawableRes drawableId: Int,
        tintArgb: Int?,
        key: String,
        sizeDp: Float,
        density: Float,
    ): Bitmap? {
        cache[key]?.let { return it }
        val drawable = try {
            ContextCompat.getDrawable(context, drawableId)
        } catch (_: Resources.NotFoundException) {
            null
        } ?: return null
        if (tintArgb != null) {
            // mutate() before tinting so the tint never contaminates the
            // drawable's shared ConstantState (the untinted cairn path and
            // any other consumer of the same resource).
            drawable.mutate()
            drawable.setTintMode(PorterDuff.Mode.SRC_IN)
            drawable.setTint(tintArgb)
        }
        val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        // setBounds always — without it the vector rasterizes at its
        // cosmetic 24dp intrinsic size (iOS's draw(in:) vs draw(at:) trap).
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(bitmap))
        cache[key] = bitmap
        return bitmap
    }

    /**
     * Robolectric sandboxes share statics across test methods, so tests
     * reset here in `@Before` (iOS `_test_clearCache` analogue).
     */
    @VisibleForTesting
    internal fun clearCache() {
        cache.clear()
    }

    @DrawableRes
    private fun tierDrawable(tier: CairnTier): Int = when (tier) {
        CairnTier.Faint -> R.drawable.glyph_cairn_faint
        CairnTier.Small -> R.drawable.glyph_cairn_small
        CairnTier.Medium -> R.drawable.glyph_cairn_medium
        CairnTier.Large -> R.drawable.glyph_cairn_large
        CairnTier.Great -> R.drawable.glyph_cairn_great
        CairnTier.Sacred -> R.drawable.glyph_cairn_sacred
        CairnTier.Eternal -> R.drawable.glyph_cairn_eternal
    }
}

/**
 * Shipped iOS map raster size for the wisp — 28pt at `9a418e4`
 * (`PilgrimMapView.buildPoints`, post-#57 doubling in `525bf5b`).
 */
internal const val WHISPER_GLYPH_SIZE_DP = 28f

/**
 * Shipped iOS per-tier map raster size — `24 + tier.rawValue * 2`
 * (24–36pt) at `9a418e4`; the annotation's `iconSize` stays 1.0 and
 * all tier progression lives in the raster, exactly like iOS.
 */
internal fun cairnGlyphSizeDp(tier: CairnTier): Float = 24f + tier.ordinal * 2f
