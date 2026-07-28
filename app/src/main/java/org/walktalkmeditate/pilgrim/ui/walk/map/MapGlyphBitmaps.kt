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
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight
import org.walktalkmeditate.pilgrim.ui.walk.glyphRes

/**
 * Rasterizes the U13 vector glyph masters into Mapbox-ready bitmaps —
 * iOS parity `MapGlyphImageBuilder.swift@9a418e4` plus the clearing
 * branch `@b4decad` (port specs
 * docs/parity/2026-07-27-port-map-glyphs-u14-u15.md and
 * docs/parity/2026-07-28-port-seek-clearing-glyph.md). Mapbox stores
 * raster sprites, so everything draws at dp × display density (the
 * Android analogue of iOS R11's "display size × screen scale": the
 * annotation plugin registers icon bitmaps at the map's pixel ratio,
 * so a dp×density raster renders at exactly [sizeDp] on-screen) and
 * is cached — the key space is small and fixed: 9 whisper tints (8 moods + the unresolved-category stone),
 * 7 cairn tiers, the 6 daypart hexes a clearing can be found under,
 * and the live stone the mid-walk clearing wears.
 *
 * Cache keys carry exactly what the draw reads: mood tint + size +
 * density for the wisp, tier + size + density for the cairn, light
 * tint + size + density for the clearing — and deliberately NO theme
 * component. The wisp's tint is a fixed `WhisperCategory` literal and
 * cairn art is baked fixed-hex, so a light/dark flip must hit the
 * same cached bitmaps; the clearing's tints are the fixed SeekSkyLight
 * hexes plus the theme-resolved stone, whose changed RGB simply keys a
 * fresh entry.
 *
 * Main-thread only, like all callers (composition-time `remember`) —
 * same discipline as the iOS builder.
 */
object MapGlyphBitmaps {

    private val cache = HashMap<String, Bitmap>()

    /**
     * Every cache key embeds a density, so a density change (display
     * move, foldable posture) strands the old density's entries as
     * unreachable Bitmaps. Realistic density count per process is 1-2
     * and the full set rebuilds in milliseconds, so wholesale clear on
     * change beats per-key eviction.
     */
    private var lastDensity: Float? = null

    fun wisp(context: Context, tintArgb: Int, sizeDp: Float, density: Float): Bitmap? {
        require(tintArgb ushr 24 == 0xFF) {
            "wisp tint must be opaque: cache keys are RGB-only (the documented " +
                "fixed-opaque WhisperCategory assumption), so translucent tints " +
                "would collide with their opaque counterparts"
        }
        return rendered(
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
    }

    fun cairn(context: Context, tier: CairnTier, sizeDp: Float, density: Float): Bitmap? =
        rendered(
            context = context,
            drawableId = tier.glyphRes,
            tintArgb = null,
            key = "cairn-${tier.ordinal}-$sizeDp-$density",
            sizeDp = sizeDp,
            density = density,
        )

    /**
     * The clearing's tree — a template asset like the wisp, taking the
     * hour's light (or the live map's stone) at render time rather than
     * carrying its own colour (iOS `MapGlyph.seekClearing@b4decad`).
     */
    fun seekClearing(context: Context, tintArgb: Int, sizeDp: Float, density: Float): Bitmap? {
        require(tintArgb ushr 24 == 0xFF) {
            "clearing tint must be opaque: cache keys are RGB-only (tints are " +
                "the fixed SeekSkyLight hexes or the opaque theme stone), so " +
                "translucent tints would collide with their opaque counterparts"
        }
        return rendered(
            context = context,
            drawableId = R.drawable.glyph_seek_clearing,
            tintArgb = tintArgb,
            // `clearing-RRGGBB` like iOS, so a wisp and a clearing handed
            // the same colour can never share a cache slot.
            key = String.format(
                Locale.US,
                "clearing-%06X-%s-%s",
                tintArgb and 0xFFFFFF,
                sizeDp,
                density,
            ),
            sizeDp = sizeDp,
            density = density,
        )
    }

    @VisibleForTesting
    internal fun rendered(
        context: Context,
        @DrawableRes drawableId: Int,
        tintArgb: Int?,
        key: String,
        sizeDp: Float,
        density: Float,
    ): Bitmap? {
        if (lastDensity != density) {
            if (lastDensity != null) cache.clear()
            lastDensity = density
        }
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
        lastDensity = null
    }

    @VisibleForTesting
    internal fun cacheSize(): Int = cache.size

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

/**
 * Shipped iOS summary-map raster size for the clearing's tree — 30pt
 * at `b4decad` (`PilgrimMapView.buildPoints`, `.seekArrival` branch):
 * sized against the tightened 20pt halo so it reads as light around
 * the tree.
 */
internal const val SEEK_CLEARING_GLYPH_SIZE_DP = 30f

/**
 * Shipped iOS live-map raster size for a clearing reached mid-walk —
 * 22pt tinted stone at `b4decad` (`PilgrimMapView.buildPoints`,
 * arrival-waypoint branch). No daypart on live; the hour's light
 * belongs to the record.
 */
internal const val SEEK_CLEARING_LIVE_GLYPH_SIZE_DP = 22f

/**
 * The clearing's full tint key space — the 6 daypart hexes it can be
 * found under (3 dayparts × dawn/starlight families). The prebuilt
 * summary bitmap map spans all of these so the `lightHex` lookup can
 * never miss, even though the annotation site pins the dawn family.
 */
internal fun seekClearingLightHexes(): List<String> =
    listOf(false, true).flatMap { starlight ->
        SeekSkyLight.Daypart.entries.map { daypart ->
            SeekSkyLight.hex(daypart, starlight)
        }
    }
