// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.util.Log
import com.mapbox.bindgen.Value
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxStyleManager
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.properties.generated.CirclePitchAlignment
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.types.StyleTransition
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogModel
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow

/**
 * Seek fog circle + pulse ring rendering over `PilgrimMap`'s Mapbox style.
 * The pure state model lives in `domain/seek/SeekFogModel.kt`; the crescent
 * renderer arrives with U7. Port spec
 * `docs/parity/2026-07-14-port-seek-fog-u6.md`
 * (PilgrimMapView+SeekFog.swift@c1745e8).
 */

/** Fixed palette + geometry values (iOS `SeekFogRendering@c1745e8`). */
internal object SeekFogRendering {
    // Fixed hexes on purpose — adaptive theme colors invert in dark mode
    // and would become bright halos on the map.
    const val FOG_COLOR_HEX = "#8A8175"
    const val HALO_COLOR_HEX = "#C4956A"
    const val FOG_TRANSITION_MILLIS = 1_500L
    const val FOG_BLUR = 1.0
    const val RING_LAYER_ID = "seek-pulse-ring"
    const val RING_SOURCE_ID = "seek-pulse-ring-source"
    const val RING_TRANSITION_MILLIS = 1_200L
    const val RING_START_RADIUS_PX = 12.0
    const val RING_END_RADIUS_PX = 80.0
    const val RING_START_OPACITY = 0.45
    const val RING_BLUR = 0.6
    const val METERS_PER_PIXEL_EQUATOR_Z0 = 78271.517

    /** Golden `#C4956A` — the seek's home light, until U7's SeekSkyLight. */
    val DEFAULT_LIGHT_COLOR_ARGB = hexToColorArgb(HALO_COLOR_HEX)
}

internal fun fogSourceId(circleId: String): String = "$circleId-source"

/** `"#RRGGBB"` → opaque ARGB color int; framework-free for JVM tests. */
internal fun hexToColorArgb(hex: String): Int =
    0xFF000000.toInt() or hex.removePrefix("#").toInt(16)

/**
 * Geographic sizing: circle-radius is in screen pixels, so derive the pixel
 * radius at zoom 0 from the meters-per-pixel scale there (78271.517·cos(lat))
 * and let the layer expression double it per zoom level.
 */
internal fun fogRadiusPixelsAtZoomZero(radiusMeters: Double, latitudeDegrees: Double): Double =
    radiusMeters / (SeekFogRendering.METERS_PER_PIXEL_EQUATOR_Z0 * cos(latitudeDegrees * PI / 180.0))

/**
 * The Mapbox writes the fog needs, extracted so [SeekFogRenderer]'s state
 * machine (equality gate, deferred queue, self-heal, pulse swallow) is
 * JVM-testable against a fake; [MapboxSeekFogStyle] is the real surface,
 * verified at the device smoke check.
 */
internal interface SeekFogStyle {
    fun isStyleLoaded(): Boolean
    fun fogLayerExists(layerId: String): Boolean
    fun installFogCircle(circle: SeekFogState.FogCircle, tintHex: String?, transitionMillis: Long)
    fun setFogOpacity(layerId: String, opacity: Double)
    fun removeFogCircle(layerId: String)
    fun firePulseRing(lightColorArgb: Int)
    fun removePulseRing()
}

/**
 * Style bookkeeping for the seek fog: which circles are installed, what
 * whole-state was last applied, what is queued while the style is not ready,
 * and which pulse token last fired. One instance per [com.mapbox.maps.MapView],
 * created by `PilgrimMap` alongside the map.
 */
class SeekFogRenderer internal constructor(
    private val style: SeekFogStyle,
) {
    private var pendingState: SeekFogState? = null
    private var lastAppliedState: SeekFogState? = null
    private var appliedCircles: Map<String, SeekFogState.FogCircle> = emptyMap()
    private var hasDeferredUpdate = false
    private var lastHandledPulseToken = 0

    /**
     * A style reload wipes every layer; forget what was applied so the next
     * pass reinstalls from scratch.
     */
    private fun resetForStyleReload() {
        lastAppliedState = null
        appliedCircles = emptyMap()
    }

    fun apply(
        state: SeekFogState?,
        pulse: SeekPulseVisual,
        reduceMotion: Boolean,
        lightColorArgb: Int,
    ) {
        pendingState = state
        if (!style.isStyleLoaded()) {
            // Pulses are moments, not state: swallow tokens seen while the
            // style isn't ready so stale rings never fire on flush. Fog
            // state is queued and flushed instead.
            lastHandledPulseToken = pulse.token
            if (lastAppliedState != state) hasDeferredUpdate = true
            return
        }
        applyNow(state, reduceMotion)
        if (pulse.token != lastHandledPulseToken) {
            lastHandledPulseToken = pulse.token
            if (state != null && !reduceMotion) {
                style.firePulseRing(lightColorArgb)
            }
        }
    }

    /**
     * Called from `loadStyle`'s success callback: the fresh style has no
     * seek layers, so reinstall from the pending state.
     */
    fun onStyleReloaded(reduceMotion: Boolean) {
        resetForStyleReload()
        if (!style.isStyleLoaded()) {
            if (pendingState != null) hasDeferredUpdate = true
            return
        }
        applyNow(pendingState, reduceMotion)
    }

    fun flushDeferred(reduceMotion: Boolean) {
        if (!hasDeferredUpdate) return
        // Keep the flag while the style is still loading — clearing it
        // before a guaranteed apply silently drops the deferred update.
        if (!style.isStyleLoaded()) return
        hasDeferredUpdate = false
        applyNow(pendingState, reduceMotion)
    }

    private fun applyNow(state: SeekFogState?, reduceMotion: Boolean) {
        if (!style.isStyleLoaded()) return
        // Equality early-return: the update lambda re-fires on every
        // recomposition; fog rarely changes. `null == null` also keeps the
        // whole seek path from ever touching the style on wander walks.
        if (lastAppliedState == state) {
            // Trust, but verify: a lock/unlock cycle can strip runtime
            // layers without any style event (field-confirmed on iOS,
            // a0624d0; assume the same class of risk here), leaving the
            // bookkeeping claiming fog that no longer exists. One layer
            // probe per pass keeps the map self-healing.
            val firstCircle = state?.circles?.firstOrNull() ?: return
            if (style.fogLayerExists(firstCircle.id)) return
            resetForStyleReload()
        }
        if (state == null) {
            appliedCircles.keys.forEach(style::removeFogCircle)
            style.removePulseRing()
            appliedCircles = emptyMap()
            lastAppliedState = null
            return
        }

        val transitionMillis = if (reduceMotion) 0L else SeekFogRendering.FOG_TRANSITION_MILLIS
        val applied = mutableMapOf<String, SeekFogState.FogCircle>()
        for (circle in state.circles) {
            syncFogCircle(circle, appliedCircles[circle.id], state.tintHex, transitionMillis)
            applied[circle.id] = circle
        }
        for (id in appliedCircles.keys) {
            if (id !in applied) style.removeFogCircle(id)
        }
        appliedCircles = applied
        lastAppliedState = state
    }

    private fun syncFogCircle(
        circle: SeekFogState.FogCircle,
        previous: SeekFogState.FogCircle?,
        tintHex: String?,
        transitionMillis: Long,
    ) {
        if (previous == null) {
            style.installFogCircle(circle, tintHex, transitionMillis)
            return
        }
        if (previous == circle) return
        if (previous.center == circle.center &&
            previous.radiusMeters == circle.radiusMeters &&
            previous.isHalo == circle.isHalo
        ) {
            style.setFogOpacity(
                circle.id,
                SeekFogModel.opacity(circle.opacityBucket, circle.isHalo),
            )
        } else {
            // Geometry or role changed (reroll, fog → halo): recreate so
            // the entrance-at-zero write inside install fades the new
            // circle in.
            style.removeFogCircle(circle.id)
            style.installFogCircle(circle, tintHex, transitionMillis)
        }
    }
}

/**
 * The real Mapbox surface. Every mutation is caught-and-logged — fog is
 * decorative and a failed style write must never crash a walk.
 */
internal class MapboxSeekFogStyle(
    private val map: MapboxStyleManager,
    private val routeLayerId: () -> String?,
) : SeekFogStyle {

    /**
     * Latest puck point, fed by `PilgrimMap`'s indicator-position listener
     * while fog is active. iOS reads `location.latestLocation` at fire time;
     * Android's location component has no synchronous getter (spec D3).
     */
    var latestPuckPoint: Point? = null

    override fun isStyleLoaded(): Boolean = map.isStyleLoaded()

    override fun fogLayerExists(layerId: String): Boolean = map.styleLayerExists(layerId)

    override fun installFogCircle(
        circle: SeekFogState.FogCircle,
        tintHex: String?,
        transitionMillis: Long,
    ) {
        // Idempotent (spec D8): apply can legally run between the style
        // finishing loading and the reload callback's reset, and a second
        // addSource with a live id is a hard error on Android.
        removeFogCircle(circle.id)
        try {
            map.addSource(buildFogSource(circle))
            val layer = buildFogCircleLayer(circle, tintHex, transitionMillis)
            val below = routeLayerId()?.takeIf(map::styleLayerExists)
            if (below != null) {
                // Fog sits under the route line so the walked path stays
                // legible (iOS: below "pilgrim-route-casing").
                map.addLayerBelow(layer, below)
            } else {
                map.addLayer(layer)
            }
            // Entrance: created at opacity 0, target written in the same
            // update pass — the opacity transition renders the fade-in.
            map.setStyleLayerProperty(
                circle.id,
                "circle-opacity",
                Value.valueOf(SeekFogModel.opacity(circle.opacityBucket, circle.isHalo)),
            )
        } catch (e: Exception) {
            Log.w(TAG, "seek fog install failed for ${circle.id}", e)
        }
    }

    override fun setFogOpacity(layerId: String, opacity: Double) {
        map.setStyleLayerProperty(layerId, "circle-opacity", Value.valueOf(opacity))
            .error?.let { Log.w(TAG, "seek fog opacity write failed for $layerId: $it") }
    }

    override fun removeFogCircle(layerId: String) {
        try {
            if (map.styleLayerExists(layerId)) map.removeStyleLayer(layerId)
            val sourceId = fogSourceId(layerId)
            if (map.styleSourceExists(sourceId)) map.removeStyleSource(sourceId)
        } catch (e: Exception) {
            Log.w(TAG, "seek fog removal failed for $layerId", e)
        }
    }

    /**
     * One-shot: recreate the ring at the puck with small/visible initial
     * paint (initial values don't transition), then immediately write
     * large/transparent — the layer's StyleTransitions ease it out on the
     * GPU. No timers, no animators. Recreated per pulse so it picks up the
     * hour's light on the very next heartbeat.
     */
    override fun firePulseRing(lightColorArgb: Int) {
        val coordinate = latestPuckPoint ?: return
        removePulseRing()
        try {
            map.addSource(
                geoJsonSource(SeekFogRendering.RING_SOURCE_ID) { geometry(coordinate) },
            )
            map.addLayer(buildPulseRingLayer(lightColorArgb))
            map.setStyleLayerProperty(
                SeekFogRendering.RING_LAYER_ID,
                "circle-radius",
                Value.valueOf(SeekFogRendering.RING_END_RADIUS_PX),
            )
            map.setStyleLayerProperty(
                SeekFogRendering.RING_LAYER_ID,
                "circle-opacity",
                Value.valueOf(0.0),
            )
        } catch (e: Exception) {
            Log.w(TAG, "seek pulse ring failed", e)
        }
    }

    override fun removePulseRing() {
        try {
            if (map.styleLayerExists(SeekFogRendering.RING_LAYER_ID)) {
                map.removeStyleLayer(SeekFogRendering.RING_LAYER_ID)
            }
            if (map.styleSourceExists(SeekFogRendering.RING_SOURCE_ID)) {
                map.removeStyleSource(SeekFogRendering.RING_SOURCE_ID)
            }
        } catch (e: Exception) {
            Log.w(TAG, "seek pulse ring removal failed", e)
        }
    }

    private companion object {
        const val TAG = "PilgrimMap"
    }
}

internal fun buildFogSource(circle: SeekFogState.FogCircle): GeoJsonSource =
    geoJsonSource(fogSourceId(circle.id)) {
        geometry(Point.fromLngLat(circle.center.longitude, circle.center.latitude))
    }

/**
 * Transitions are set once at creation; every later opacity write is
 * GPU-eased by them — no timers. Reduce motion passes 0 for instant writes.
 */
internal fun buildFogCircleLayer(
    circle: SeekFogState.FogCircle,
    tintHex: String?,
    transitionMillis: Long,
): CircleLayer = circleLayer(circle.id, fogSourceId(circle.id)) {
    val colorHex = if (circle.isHalo) {
        SeekFogRendering.HALO_COLOR_HEX
    } else {
        tintHex ?: SeekFogRendering.FOG_COLOR_HEX
    }
    circleColor(hexToColorArgb(colorHex))
    circleBlur(SeekFogRendering.FOG_BLUR)
    circlePitchAlignment(CirclePitchAlignment.MAP)
    circleStrokeWidth(0.0)
    circleRadius(fogRadiusExpression(circle.radiusMeters, circle.center.latitude))
    circleOpacity(0.0)
    circleOpacityTransition(
        StyleTransition.Builder().duration(transitionMillis).delay(0).build(),
    )
    circleBlurTransition(
        StyleTransition.Builder().duration(transitionMillis).delay(0).build(),
    )
}

internal fun buildPulseRingLayer(lightColorArgb: Int): CircleLayer =
    circleLayer(SeekFogRendering.RING_LAYER_ID, SeekFogRendering.RING_SOURCE_ID) {
        circleColor(lightColorArgb)
        circleBlur(SeekFogRendering.RING_BLUR)
        circleStrokeWidth(0.0)
        circleRadius(SeekFogRendering.RING_START_RADIUS_PX)
        circleOpacity(SeekFogRendering.RING_START_OPACITY)
        circleRadiusTransition(
            StyleTransition.Builder()
                .duration(SeekFogRendering.RING_TRANSITION_MILLIS)
                .delay(0)
                .build(),
        )
        circleOpacityTransition(
            StyleTransition.Builder()
                .duration(SeekFogRendering.RING_TRANSITION_MILLIS)
                .delay(0)
                .build(),
        )
    }

/**
 * Interpolate exponentially (base 2) over zoom from the z0 pixel radius up
 * to z20 at ×2²⁰ so the circle covers a fixed ground area at every zoom.
 */
internal fun fogRadiusExpression(radiusMeters: Double, latitude: Double): Expression {
    val radiusPixelsAtZ0 = fogRadiusPixelsAtZoomZero(radiusMeters, latitude)
    return Expression.interpolate {
        exponential(2.0)
        zoom()
        stop {
            literal(0.0)
            literal(radiusPixelsAtZ0)
        }
        stop {
            literal(20.0)
            literal(radiusPixelsAtZ0 * 2.0.pow(20))
        }
    }
}
