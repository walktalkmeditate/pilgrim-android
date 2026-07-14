// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Point
import com.mapbox.maps.GeoJSONSourceData
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.extension.style.image.addImage
import com.mapbox.maps.extension.style.image.image
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.SymbolLayer
import com.mapbox.maps.extension.style.layers.generated.symbolLayer
import com.mapbox.maps.extension.style.layers.properties.generated.IconRotationAlignment
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.types.StyleTransition
import org.walktalkmeditate.pilgrim.domain.seek.SeekCrescentModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekCrescentVisibilityModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import org.walktalkmeditate.pilgrim.domain.seek.SeekSkyLight
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

// The crescent: which-way affordance on the puck's rim. It breathes with
// the seek's pulse clock (a flare per pulse, GPU-eased — no standing
// timers) and releases whenever the fog itself is visible in the viewport,
// exhaling into the thing it pointed at. Pure decisions live in
// domain/seek/SeekCrescentModel.kt; this file is the Mapbox side. Port spec
// docs/parity/2026-07-14-port-seek-crescent-u7.md
// (PilgrimMapView+SeekWisp.swift@c1745e8 — iOS names this "wisp").

/** Fixed ids + geometry + timing (iOS `SeekWispRendering@c1745e8`). */
internal object SeekCrescentRendering {
    const val LAYER_ID = "seek-crescent"
    const val SOURCE_ID = "seek-crescent-source"

    /**
     * One shared transition eases every opacity write — swell, settle,
     * exhale, and return all breathe at the same pace.
     */
    const val BREATH_MILLIS = 1_000L

    /** The settle write lands just after the swell completes. */
    const val FLARE_HOLD_MILLIS = 1_050L

    /**
     * Crescent geometry (dp): the arc hugs the puck's rim just inside the
     * pulsing halo, drawn pointing north and rotated by bearing at render
     * time. The span itself is distance-keyed — one image per span bucket.
     */
    const val IMAGE_SIZE_DP = 84.0
    const val ARC_RADIUS_DP = 30.0

    /**
     * Camera-change events arrive per frame during gestures; the
     * visibility check runs at most this often, with map-idle providing
     * the authoritative trailing check.
     */
    const val VISIBILITY_THROTTLE_MILLIS = 120L

    /** One pre-rendered arc image per (span bucket, light token). */
    fun imageId(spanDegrees: Double, lightToken: String): String =
        "seek-crescent-${spanDegrees.roundToInt()}-$lightToken"
}

/** A projected screen position in logical (density-independent) pixels. */
internal data class ScreenPointPx(val x: Double, val y: Double)

/** Viewport dimensions in logical (density-independent) pixels. */
internal data class ViewportPx(val width: Double, val height: Double)

/**
 * The Mapbox writes + reads the crescent needs, extracted so
 * [SeekCrescentRenderer]'s state machine (image cache, flare generations,
 * viewport release, throttle) is JVM-testable against a fake;
 * [MapboxSeekCrescentStyle] is the real surface, device-verified at the
 * mid-phase smoke check.
 */
internal interface SeekCrescentStyle {
    fun isStyleLoaded(): Boolean
    fun crescentLayerExists(): Boolean
    fun crescentSourceExists(): Boolean
    fun hasImage(imageId: String): Boolean
    fun addCrescentImage(imageId: String, spanDegrees: Double, colorArgb: Int)
    fun installCrescentLayer(
        imageId: String,
        position: SeekPoint,
        bearingDegrees: Double,
        breathTransitionMillis: Long,
    )

    fun updateCrescentGeometry(position: SeekPoint, bearingDegrees: Double)
    fun setCrescentImage(imageId: String)
    fun setCrescentOpacity(opacity: Double)
    fun removeCrescent()

    /**
     * The active fog center projected to logical screen pixels, or null
     * when it cannot be projected — including Mapbox's off-view clamp
     * (spec B10): `pixelForCoordinate` collapses every coordinate whose
     * projection lands outside the view to `(-1, -1)` (verified against
     * mapbox-maps-android v11.11.0 `MapboxMap.clampScreenCoordinate`), so
     * any negative component means "off screen", never "a circle just
     * past the top-left corner" (iOS regression 174e9e0).
     */
    fun projectedFogCenter(latitude: Double, longitude: Double): ScreenPointPx?
    fun viewportSize(): ViewportPx
    fun cameraZoom(): Double
    fun postDelayed(delayMillis: Long, action: () -> Unit)
}

/**
 * Style bookkeeping for the crescent: released state, flare generations,
 * the applied image id, and the visibility throttle. One instance per
 * [com.mapbox.maps.MapView], owned by [SeekFogRenderer] so every hook
 * (sync, flare, remove, reload, visibility) fires at the same points iOS
 * fires them from `PilgrimMapView+SeekFog.swift`.
 *
 * The hour's light is read through [daypart]/[starlight] at write time
 * (iOS reads `currentSeekDaypart` per sync/fire), so the image and the
 * pulse ring can never disagree about the hour.
 */
internal class SeekCrescentRenderer(
    private val style: SeekCrescentStyle,
    private val daypart: () -> SeekSkyLight.Daypart,
    private val starlight: () -> Boolean,
    private val uptimeMillis: () -> Long,
) {
    /**
     * True while the fog is visible in the viewport and the crescent has
     * released. Survives style reloads on purpose: reinstalling a released
     * crescent at zero must not replay the handoff exhale (spec D8 — iOS's
     * reinstall path resets this by accident; the documented intent wins).
     */
    private var released = false

    /**
     * Cancels in-flight flare/exhale settle closures: any new opacity
     * sequence bumps this, turning stale delayed bodies into no-ops.
     */
    private var flareGeneration = 0

    /**
     * The crescent image currently on the layer (distance-keyed span +
     * light token), null when no layer exists (or the style was reloaded,
     * wiping its images).
     */
    private var appliedImageId: String? = null
    private var appliedCrescent: SeekFogState.Crescent? = null
    private var lastVisibilityCheckUptimeMillis = Long.MIN_VALUE / 2

    /**
     * A style reload wipes layers and images; forget what was applied so
     * the next sync reinstalls from scratch. [released] survives (the
     * handoff exhale must not replay).
     */
    fun onStyleReloaded() {
        flareGeneration++
        appliedImageId = null
        appliedCrescent = null
    }

    /**
     * The crescent rides the walker's own coordinate and only rotates.
     * Screen geometry is constant across zooms (it is an affordance, not
     * geography). Opacity is owned by the flare/visibility writes; this
     * only manages existence, position, rotation, and the image.
     */
    fun sync(crescent: SeekFogState.Crescent?, spanDegrees: Double, reduceMotion: Boolean) {
        val desiredImageId =
            SeekCrescentRendering.imageId(spanDegrees, SeekSkyLight.token(daypart(), starlight()))
        if (crescent == appliedCrescent && desiredImageId == appliedImageId) return
        if (crescent == null) {
            remove()
            return
        }
        if (style.crescentLayerExists() && style.crescentSourceExists()) {
            style.updateCrescentGeometry(crescent.position, crescent.bearingDegrees)
            if (desiredImageId != appliedImageId) {
                ensureImage(desiredImageId, spanDegrees)
                style.setCrescentImage(desiredImageId)
                appliedImageId = desiredImageId
            }
            appliedCrescent = crescent
            return
        }
        // From-scratch install (fresh style, or a partial strip). Clears
        // layer bookkeeping but NOT `released` — a released crescent
        // reinstalls at zero so the handoff exhale never replays.
        flareGeneration++
        appliedImageId = null
        style.removeCrescent()

        ensureImage(desiredImageId, spanDegrees)
        style.installCrescentLayer(
            imageId = desiredImageId,
            position = crescent.position,
            bearingDegrees = crescent.bearingDegrees,
            breathTransitionMillis = if (reduceMotion) 0L else SeekCrescentRendering.BREATH_MILLIS,
        )
        appliedImageId = desiredImageId
        appliedCrescent = crescent
        style.setCrescentOpacity(
            if (released) 0.0 else SeekCrescentModel.restingOpacity(reduceMotion),
        )
    }

    /**
     * One breath per pulse: swell to a peak shaped by closeness (warmer
     * still when aligned), then settle back to rest. Both writes ride the
     * layer's single opacity transition — the only bookkeeping is the
     * generation guard that turns a superseded settle into a no-op.
     */
    fun flare(pulse: SeekPulseVisual, reduceMotion: Boolean) {
        if (reduceMotion || released || !style.isStyleLoaded() || !style.crescentLayerExists()) {
            return
        }
        flareGeneration++
        val generation = flareGeneration
        style.setCrescentOpacity(SeekCrescentModel.flarePeak(pulse.aligned, pulse.closeness))
        style.postDelayed(SeekCrescentRendering.FLARE_HOLD_MILLIS) {
            if (flareGeneration == generation && !released && style.crescentLayerExists()) {
                style.setCrescentOpacity(SeekCrescentModel.REST_OPACITY)
            }
        }
    }

    /**
     * Re-decides whether the crescent should be shown, from the active
     * fog's screen-space footprint. Called from camera changes (throttled),
     * map idle, and every fog apply. Cheap guards first: wander maps and
     * seek maps without a crescent exit before touching any projection.
     */
    fun evaluateVisibility(state: SeekFogState?, throttled: Boolean, reduceMotion: Boolean) {
        if (state?.crescent == null) return
        val fog = state.circles.firstOrNull { !it.isHalo } ?: return
        val now = uptimeMillis()
        if (throttled &&
            now - lastVisibilityCheckUptimeMillis < SeekCrescentRendering.VISIBILITY_THROTTLE_MILLIS
        ) {
            return
        }
        lastVisibilityCheckUptimeMillis = now
        if (!style.isStyleLoaded()) return

        val projected = style.projectedFogCenter(fog.center.latitude, fog.center.longitude)
        val viewport = style.viewportSize()
        val metersPerPixel = SeekFogRendering.METERS_PER_PIXEL_EQUATOR_Z0 *
            cos(fog.center.latitude * PI / 180.0) / 2.0.pow(style.cameraZoom())
        if (metersPerPixel <= 0.0) return

        val newReleased = SeekCrescentVisibilityModel.shouldRelease(
            wasReleased = released,
            fogCenterX = projected?.x,
            fogCenterY = projected?.y,
            fogRadiusPx = fog.radiusMeters / metersPerPixel,
            viewWidthPx = viewport.width,
            viewHeightPx = viewport.height,
        )
        if (newReleased == released || !style.crescentLayerExists()) return
        released = newReleased
        flareGeneration++
        if (newReleased) {
            fireHandoffExhale(reduceMotion)
        } else {
            style.setCrescentOpacity(SeekCrescentModel.restingOpacity(reduceMotion))
        }
    }

    /** Hide path (crescent gone / walk over): everything resets. */
    fun remove() {
        released = false
        flareGeneration++
        appliedImageId = null
        appliedCrescent = null
        style.removeCrescent()
    }

    /**
     * The handoff: the fog just entered view, so the crescent gives one
     * final full flare and dissolves into the thing it pointed at.
     */
    private fun fireHandoffExhale(reduceMotion: Boolean) {
        if (reduceMotion) {
            style.setCrescentOpacity(0.0)
            return
        }
        val generation = flareGeneration
        style.setCrescentOpacity(SeekCrescentModel.ALIGNED_FLARE_PEAK)
        style.postDelayed(SeekCrescentRendering.FLARE_HOLD_MILLIS) {
            if (flareGeneration == generation && released && style.crescentLayerExists()) {
                style.setCrescentOpacity(0.0)
            }
        }
    }

    private fun ensureImage(imageId: String, spanDegrees: Double) {
        if (style.hasImage(imageId)) return
        style.addCrescentImage(
            imageId,
            spanDegrees,
            hexToColorArgb(SeekSkyLight.hex(daypart(), starlight())),
        )
    }
}

/**
 * The real Mapbox surface. Every mutation is caught-and-logged — the
 * crescent is decorative and a failed style write must never crash a walk.
 * Screen-space reads are converted to logical pixels (÷[pixelRatio]) so
 * the release insets match iOS's 24 pt on every density.
 */
internal class MapboxSeekCrescentStyle(
    private val map: MapboxMap,
    private val pixelRatio: Float,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : SeekCrescentStyle {

    override fun isStyleLoaded(): Boolean = map.isStyleLoaded()

    override fun crescentLayerExists(): Boolean =
        map.styleLayerExists(SeekCrescentRendering.LAYER_ID)

    override fun crescentSourceExists(): Boolean =
        map.styleSourceExists(SeekCrescentRendering.SOURCE_ID)

    override fun hasImage(imageId: String): Boolean = map.hasStyleImage(imageId)

    override fun addCrescentImage(imageId: String, spanDegrees: Double, colorArgb: Int) {
        try {
            val bitmap = renderCrescentBitmap(spanDegrees, colorArgb, pixelRatio)
            // scale = pixelRatio registers the bitmap's native density so
            // the crescent draws IMAGE_SIZE_DP dp on every screen.
            map.addImage(image(imageId, bitmap) { scale(pixelRatio) })
        } catch (e: Exception) {
            Log.w(TAG, "seek crescent image add failed for $imageId", e)
        }
    }

    override fun installCrescentLayer(
        imageId: String,
        position: SeekPoint,
        bearingDegrees: Double,
        breathTransitionMillis: Long,
    ) {
        try {
            map.addSource(buildCrescentSource(position))
            map.addLayer(buildCrescentLayer(imageId, bearingDegrees, breathTransitionMillis))
        } catch (e: Exception) {
            Log.w(TAG, "seek crescent install failed", e)
        }
    }

    override fun updateCrescentGeometry(position: SeekPoint, bearingDegrees: Double) {
        try {
            map.setStyleGeoJSONSourceData(
                SeekCrescentRendering.SOURCE_ID,
                "",
                GeoJSONSourceData.valueOf(
                    Point.fromLngLat(position.longitude, position.latitude),
                ),
            ).error?.let { Log.w(TAG, "seek crescent source update failed: $it") }
            map.setStyleLayerProperty(
                SeekCrescentRendering.LAYER_ID,
                "icon-rotate",
                Value.valueOf(bearingDegrees),
            ).error?.let { Log.w(TAG, "seek crescent rotate failed: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "seek crescent geometry update failed", e)
        }
    }

    override fun setCrescentImage(imageId: String) {
        map.setStyleLayerProperty(
            SeekCrescentRendering.LAYER_ID,
            "icon-image",
            Value.valueOf(imageId),
        ).error?.let { Log.w(TAG, "seek crescent image swap failed: $it") }
    }

    override fun setCrescentOpacity(opacity: Double) {
        map.setStyleLayerProperty(
            SeekCrescentRendering.LAYER_ID,
            "icon-opacity",
            Value.valueOf(opacity),
        ).error?.let { Log.w(TAG, "seek crescent opacity write failed: $it") }
    }

    override fun removeCrescent() {
        try {
            if (map.styleLayerExists(SeekCrescentRendering.LAYER_ID)) {
                map.removeStyleLayer(SeekCrescentRendering.LAYER_ID)
            }
            if (map.styleSourceExists(SeekCrescentRendering.SOURCE_ID)) {
                map.removeStyleSource(SeekCrescentRendering.SOURCE_ID)
            }
        } catch (e: Exception) {
            Log.w(TAG, "seek crescent removal failed", e)
        }
    }

    override fun projectedFogCenter(latitude: Double, longitude: Double): ScreenPointPx? {
        val projected = try {
            map.pixelForCoordinate(Point.fromLngLat(longitude, latitude))
        } catch (e: Exception) {
            Log.w(TAG, "seek crescent projection failed", e)
            return null
        }
        // Off-view clamp sentinel (spec B10): the SDK returns (-1, -1) for
        // any projection outside the view bounds. Map it to null so the
        // model reads "off screen", not "a circle just past the top-left
        // corner" — which would release the crescent forever (iOS 174e9e0).
        if (projected.x < 0.0 || projected.y < 0.0) return null
        return ScreenPointPx(projected.x / pixelRatio, projected.y / pixelRatio)
    }

    override fun viewportSize(): ViewportPx {
        val size = map.getSize()
        return ViewportPx(
            width = size.width.toDouble() / pixelRatio,
            height = size.height.toDouble() / pixelRatio,
        )
    }

    override fun cameraZoom(): Double = map.cameraState.zoom

    override fun postDelayed(delayMillis: Long, action: () -> Unit) {
        handler.postDelayed(action, delayMillis)
    }

    private companion object {
        const val TAG = "PilgrimMap"
    }
}

internal fun buildCrescentSource(position: SeekPoint): GeoJsonSource =
    geoJsonSource(SeekCrescentRendering.SOURCE_ID) {
        geometry(Point.fromLngLat(position.longitude, position.latitude))
    }

/**
 * Icon opacity is DECLARED 0 with the single breath transition set once at
 * creation; every later opacity write (swell, settle, exhale, return) is
 * GPU-eased by it — no animators. Map-aligned rotation keeps the arc aimed
 * at the clearing when the user rotates; overlap/ignore-placement keep the
 * puck's own layer from collision-culling it.
 */
internal fun buildCrescentLayer(
    imageId: String,
    bearingDegrees: Double,
    breathTransitionMillis: Long,
): SymbolLayer = symbolLayer(SeekCrescentRendering.LAYER_ID, SeekCrescentRendering.SOURCE_ID) {
    iconImage(imageId)
    iconRotate(bearingDegrees)
    iconRotationAlignment(IconRotationAlignment.MAP)
    iconAllowOverlap(true)
    iconIgnorePlacement(true)
    iconOpacity(0.0)
    iconOpacityTransition(
        StyleTransition.Builder().duration(breathTransitionMillis).delay(0).build(),
    )
}

/**
 * One arc segment of the pre-rendered crescent image, in the dp-space of
 * an [SeekCrescentRendering.IMAGE_SIZE_DP] canvas. Angles follow Android's
 * `drawArc` convention (0° at 3 o'clock, positive clockwise) — identical
 * to UIKit's flipped arc space, so the port is angle-for-angle.
 */
internal data class CrescentSegment(
    val startAngleDegrees: Double,
    val sweepAngleDegrees: Double,
    val widthDp: Double,
    val alpha: Double,
)

/**
 * An arc of dawn light drawn pointing north; `icon-rotate` aims it at the
 * clearing. Short segments whose width and alpha peak at the apex and
 * taper to nothing at the tips, in three stacked passes (wide/faint under
 * narrow/bright) so brightness falls off smoothly — light, not a band with
 * a casing. iOS `wispCrescentImage`
 * (`PilgrimMapView+SeekWisp.swift:317-353@c1745e8`).
 */
internal fun crescentSegments(spanDegrees: Double): List<CrescentSegment> {
    val baseDegrees = -90.0 - spanDegrees / 2.0
    // Tiny angular overlap hides antialiasing seams between segments
    // (0.008 rad on iOS, converted once to degrees).
    val seamCoverDegrees = Math.toDegrees(0.008)
    val segments = 24
    val passes = listOf(3.0 to 0.10, 1.9 to 0.22, 1.0 to 1.0)
    return passes.flatMap { (widthScale, alphaScale) ->
        (0 until segments).map { segment ->
            val fractionStart = segment / segments.toDouble()
            val fractionEnd = (segment + 1) / segments.toDouble()
            // 0 at the apex (the point that aims at the clearing), 1 at
            // either tip.
            val offApex = abs((fractionStart + fractionEnd) / 2.0 - 0.5) * 2.0
            val fade = cos(offApex * PI / 2.0).pow(1.4)
            CrescentSegment(
                startAngleDegrees = baseDegrees + spanDegrees * fractionStart,
                sweepAngleDegrees = spanDegrees * (fractionEnd - fractionStart) + seamCoverDegrees,
                widthDp = (1.5 + 3.0 * fade) * widthScale,
                alpha = min((0.05 + 0.95 * fade) * alphaScale, 1.0),
            )
        }
    }
}

/**
 * Rasterizes [crescentSegments] at [pixelRatio] so the bitmap is crisp at
 * the device density; registered with the same ratio as the style image's
 * scale (see [MapboxSeekCrescentStyle.addCrescentImage]).
 */
internal fun renderCrescentBitmap(
    spanDegrees: Double,
    colorArgb: Int,
    pixelRatio: Float,
): Bitmap {
    val sizePx = (SeekCrescentRendering.IMAGE_SIZE_DP * pixelRatio).roundToInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val radius = (SeekCrescentRendering.ARC_RADIUS_DP * pixelRatio).toFloat()
    val bounds = RectF(center - radius, center - radius, center + radius, center + radius)
    val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    for (segment in crescentSegments(spanDegrees)) {
        paint.color = colorArgb
        paint.alpha = (segment.alpha * 255.0).roundToInt().coerceIn(0, 255)
        paint.strokeWidth = (segment.widthDp * pixelRatio).toFloat()
        canvas.drawArc(
            bounds,
            segment.startAngleDegrees.toFloat(),
            segment.sweepAngleDegrees.toFloat(),
            false,
            paint,
        )
    }
    return bitmap
}
