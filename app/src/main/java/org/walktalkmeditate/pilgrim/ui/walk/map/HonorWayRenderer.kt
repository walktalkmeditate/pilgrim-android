// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.GeoJSONSourceData
import com.mapbox.maps.MapboxStyleManager
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.Layer
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerAbove
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.CircleLayer
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.CirclePitchAlignment
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySpan
import org.walktalkmeditate.pilgrim.domain.honor.WaySpanKind
import org.walktalkmeditate.pilgrim.ui.walk.ROUTE_CASING_LAYER_ID
import org.walktalkmeditate.pilgrim.ui.walk.ROUTE_LINE_LAYER_ID
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors

/*
 * The Way on a map: the faded ghost line and, on the walk, the companion
 * dot. iOS `HonorWayState`, `applyGhostLine`, and `applyCompanion`
 * (`PilgrimMapView+HonorWay.swift:28-266@7c200bf`, parity spec E §2, §3,
 * §5). The overview draws the line (U21); the walk map adds the companion.
 */

/** One stretch of the ghost, named as the walker's route source names its activities. */
@Immutable
data class HonorWaySegment(val kind: String, val coordinates: List<WayCoordinate>)

/** iOS `HonorWayState`: built once per Way, and equal when the id, the point count, and the segments are. */
@Immutable
data class HonorWayLine(
    val wayId: String,
    val routePointCount: Int,
    val segments: List<HonorWaySegment>,
) {
    companion object {
        fun of(way: Way): HonorWayLine = HonorWayLine(
            wayId = way.id,
            routePointCount = way.route.size,
            segments = HonorWayRendering.segments(way.route, way.spans.orEmpty()),
        )
    }
}

/** iOS `GhostStyle`: the companion's fill and both layers' opacities for one map style. */
internal data class GhostStyle(
    val companionArgb: Int,
    val lineOpacity: Double,
    val companionOpacity: Double,
)

/** Where a runtime layer goes in the stack: below or above a named layer, or on top. */
internal sealed interface LayerSlot {
    data class Below(val layerId: String) : LayerSlot
    data class Above(val layerId: String) : LayerSlot
    data object Top : LayerSlot
}

internal object HonorWayRendering {
    const val SOURCE_ID = "honor-way-source"
    const val LINE_LAYER_ID = "honor-way-line"
    const val COMPANION_SOURCE_ID = "honor-companion-source"
    const val COMPANION_LAYER_ID = "honor-companion"
    const val LINE_WIDTH = 4.0
    const val COMPANION_RADIUS = 6.0
    const val COMPANION_STROKE_WIDTH = 1.5
    const val COMPANION_STROKE_ARGB = 0xFFFFFFFF.toInt()
    const val ACTIVITY_PROPERTY = "activityType"
    const val WALKING = "walking"
    const val TALKING = "talking"
    const val MEDITATING = "meditating"

    /**
     * iOS `ghostStyle(dark:)` (`PilgrimMapView+HonorWay.swift:75-84@7c200bf`),
     * read at install time; a theme flip reloads the style, which reinstalls
     * both layers with the other values. The line's colour is the walk
     * palette by span, not this one.
     */
    fun ghostStyle(dark: Boolean): GhostStyle = if (dark) {
        GhostStyle(companionArgb = 0xFFD9CFBF.toInt(), lineOpacity = 0.4, companionOpacity = 0.85)
    } else {
        GhostStyle(companionArgb = 0xFF8A8175.toInt(), lineOpacity = 0.22, companionOpacity = 0.6)
    }

    /**
     * iOS `ghostLinePosition` (`PilgrimMapView+HonorWay.swift:212-227@7c200bf`):
     * below the route casing, else below the route line, else on top, so the
     * walker's own route stays the legible one.
     */
    fun ghostLineSlot(layerExists: (String) -> Boolean): LayerSlot =
        listOf(ROUTE_CASING_LAYER_ID, ROUTE_LINE_LAYER_ID).firstOrNull(layerExists)
            ?.let { LayerSlot.Below(it) } ?: LayerSlot.Top

    /**
     * Directly above the route line, iOS's `pilgrim-route-layer`
     * (`PilgrimMapView+HonorWay.swift:259-260@7c200bf`), else on top.
     * Installed after the annotation managers, as every reload here is,
     * the companion lands under every pin (owner decision 7).
     */
    fun companionSlot(layerExists: (String) -> Boolean): LayerSlot =
        if (layerExists(ROUTE_LINE_LAYER_ID)) LayerSlot.Above(ROUTE_LINE_LAYER_ID) else LayerSlot.Top

    /**
     * The route cut at every span boundary, gaps walking, with a forward
     * cursor so no span reaches back over one already drawn
     * (`PilgrimMapView+HonorWay.swift:94-117@7c200bf`).
     */
    fun segments(route: List<WayPoint>, spans: List<WaySpan>): List<HonorWaySegment> {
        val geometry = WayGeometry(route)
        if (route.size <= 1 || geometry.totalMeters <= 0) {
            return listOf(HonorWaySegment(WALKING, route.map { WayCoordinate(lat = it.lat, lon = it.lon) }))
        }
        val pieces = mutableListOf<HonorWaySegment>()
        var cursor = 0.0
        fun add(kind: String, start: Double, end: Double) {
            if (end > start) pieces += HonorWaySegment(kind, geometry.slice(fromFrac = start, toFrac = end))
        }
        for (span in spans.sortedBy { it.startFrac }) {
            val start = maxOf(span.startFrac.coerceIn(0.0, 1.0), cursor)
            val end = span.endFrac.coerceIn(0.0, 1.0)
            if (end <= start) continue
            add(WALKING, cursor, start)
            add(if (span.kind == WaySpanKind.MEDITATING) MEDITATING else TALKING, start, end)
            cursor = end
        }
        add(WALKING, cursor, 1.0)
        return pieces
    }
}

/**
 * The style writes the Honor layers need, so [HonorWayRenderer]'s
 * bookkeeping is JVM-testable against a fake; [MapboxHonorWayStyle] is the
 * real one. Each install places its layer by [HonorWayRendering]'s slots.
 */
internal interface HonorWayStyle {
    fun isStyleLoaded(): Boolean
    fun ghostLineExists(): Boolean

    /** Returns whether the line went in; a failure is retried on the next pass, as iOS's is. */
    fun installGhostLine(line: HonorWayLine, opacity: Double): Boolean
    fun removeGhostLine()
    fun companionExists(): Boolean

    /** Returns whether the dot went in; a failure is retried on the next pass. */
    fun installCompanion(at: WayCoordinate, style: GhostStyle): Boolean
    fun moveCompanion(to: WayCoordinate)
    fun removeCompanion()
}

/**
 * The Honor layers' bookkeeping for one map. The ghost line installs once
 * per Way id and the companion once, then moves; a null input removes its
 * layer; both reinstall after a style reload; and each self-heals when its
 * layer vanishes without a style event (`PilgrimMapView+HonorWay.swift:140-266@7c200bf`).
 * [dark] is read at install time, as iOS reads the map's trait. The
 * companion moves wherever it is handed: its 2 s cadence is the walk's
 * view model's (`HonorWalkViewModel`).
 *
 * Once our style's load callback has run, the layers go in whatever
 * `isStyleLoaded` says, as iOS's `styleHasLoaded` latch lets them
 * (`PilgrimMapView.swift:166-172,679-682@7c200bf`): the annotation managers'
 * new sources flip it back to false while they settle, and the overview has
 * no later pass to try again on.
 */
internal class HonorWayRenderer(
    private val style: HonorWayStyle,
    private val dark: () -> Boolean,
) {
    private var pendingLine: HonorWayLine? = null
    private var pendingCompanion: WayCoordinate? = null
    private var appliedWayId: String? = null

    /** Where the installed dot stands; null while none is installed. */
    private var appliedCompanion: WayCoordinate? = null

    private var styleHasLoaded = false

    fun apply(line: HonorWayLine?, companion: WayCoordinate? = null) {
        pendingLine = line
        pendingCompanion = companion
        if (!styleHasLoaded && !style.isStyleLoaded()) return
        applyGhostLine(line)
        applyCompanion(companion)
    }

    /** Just before a style load: the layers wait for its callback. */
    fun onStyleLoadStarted() {
        styleHasLoaded = false
    }

    /**
     * From the style-load callback, after the annotation managers: the fresh
     * style has no Honor layers, and with the route line already in place
     * the companion goes straight above it (owner decision 7).
     */
    fun onStyleReloaded() {
        styleHasLoaded = true
        appliedWayId = null
        appliedCompanion = null
        apply(pendingLine, pendingCompanion)
    }

    private fun applyGhostLine(line: HonorWayLine?) {
        if (appliedWayId != null && !style.ghostLineExists()) appliedWayId = null
        if (line == null) {
            // Non-honor maps pass here on every update pass: touch the style
            // only when something was installed.
            if (appliedWayId == null) return
            style.removeGhostLine()
            appliedWayId = null
            return
        }
        if (appliedWayId == line.wayId) return
        style.removeGhostLine()
        if (style.installGhostLine(line, HonorWayRendering.ghostStyle(dark()).lineOpacity)) {
            appliedWayId = line.wayId
        }
    }

    private fun applyCompanion(at: WayCoordinate?) {
        if (appliedCompanion != null && !style.companionExists()) appliedCompanion = null
        val installed = appliedCompanion
        when {
            at == null -> if (installed != null) {
                style.removeCompanion()
                appliedCompanion = null
            }
            installed == null -> {
                style.removeCompanion()
                if (style.installCompanion(at, HonorWayRendering.ghostStyle(dark()))) appliedCompanion = at
            }
            installed != at -> {
                style.moveCompanion(at)
                appliedCompanion = at
            }
        }
    }
}

/** The real surface: every write is caught and logged, as iOS's are; the Honor layers are decorative. */
internal class MapboxHonorWayStyle(
    private val map: MapboxStyleManager,
    private val colors: RouteSegmentColors = RouteSegmentColors.Fixed,
) : HonorWayStyle {

    override fun isStyleLoaded(): Boolean = map.isStyleLoaded()

    override fun ghostLineExists(): Boolean = map.styleLayerExists(HonorWayRendering.LINE_LAYER_ID)

    override fun installGhostLine(line: HonorWayLine, opacity: Double): Boolean =
        try {
            map.addSource(
                geoJsonSource(HonorWayRendering.SOURCE_ID) {
                    featureCollection(ghostFeatures(line))
                },
            )
            addLayer(ghostLineLayer(opacity, colors), HonorWayRendering.ghostLineSlot(map::styleLayerExists))
            true
        } catch (e: Exception) {
            Log.w(TAG, "honor way install failed", e)
            removeGhostLine()
            false
        }

    override fun removeGhostLine() {
        remove(HonorWayRendering.LINE_LAYER_ID, HonorWayRendering.SOURCE_ID, "honor way")
    }

    override fun companionExists(): Boolean = map.styleLayerExists(HonorWayRendering.COMPANION_LAYER_ID)

    override fun installCompanion(at: WayCoordinate, style: GhostStyle): Boolean =
        try {
            map.addSource(companionSource(at))
            addLayer(companionLayer(style), HonorWayRendering.companionSlot(map::styleLayerExists))
            true
        } catch (e: Exception) {
            Log.w(TAG, "companion install failed", e)
            removeCompanion()
            false
        }

    override fun moveCompanion(to: WayCoordinate) {
        map.setStyleGeoJSONSourceData(
            HonorWayRendering.COMPANION_SOURCE_ID,
            "",
            GeoJSONSourceData.valueOf(Point.fromLngLat(to.lon, to.lat)),
        ).error?.let { Log.w(TAG, "companion move failed: $it") }
    }

    override fun removeCompanion() {
        remove(HonorWayRendering.COMPANION_LAYER_ID, HonorWayRendering.COMPANION_SOURCE_ID, "companion")
    }

    private fun addLayer(layer: Layer, slot: LayerSlot) {
        when (slot) {
            is LayerSlot.Below -> map.addLayerBelow(layer, slot.layerId)
            is LayerSlot.Above -> map.addLayerAbove(layer, slot.layerId)
            LayerSlot.Top -> map.addLayer(layer)
        }
    }

    private fun remove(layerId: String, sourceId: String, what: String) {
        try {
            if (map.styleLayerExists(layerId)) map.removeStyleLayer(layerId)
            if (map.styleSourceExists(sourceId)) map.removeStyleSource(sourceId)
        } catch (e: Exception) {
            Log.w(TAG, "$what removal failed", e)
        }
    }

    private companion object {
        const val TAG = "PilgrimMap"
    }
}

/** One LineString per segment, ids `honor-way-<index>`, each carrying its activity. */
internal fun ghostFeatures(line: HonorWayLine): FeatureCollection =
    FeatureCollection.fromFeatures(
        line.segments.mapIndexed { index, segment ->
            Feature.fromGeometry(
                LineString.fromLngLats(segment.coordinates.map { Point.fromLngLat(it.lon, it.lat) }),
                null,
                "honor-way-$index",
            ).apply { addStringProperty(HonorWayRendering.ACTIVITY_PROPERTY, segment.kind) }
        },
    )

/** Width 4, round cap and join, no dash; the walk palette by span, faded by [opacity]. */
internal fun ghostLineLayer(opacity: Double, colors: RouteSegmentColors): LineLayer =
    lineLayer(HonorWayRendering.LINE_LAYER_ID, HonorWayRendering.SOURCE_ID) {
        lineWidth(HonorWayRendering.LINE_WIDTH)
        lineCap(LineCap.ROUND)
        lineJoin(LineJoin.ROUND)
        lineOpacity(opacity)
        lineColor(
            Expression.match {
                get(HonorWayRendering.ACTIVITY_PROPERTY)
                literal(HonorWayRendering.MEDITATING)
                color(colors.meditating.toArgb())
                literal(HonorWayRendering.TALKING)
                color(colors.talking.toArgb())
                color(colors.walking.toArgb())
            },
        )
    }

internal fun companionSource(at: WayCoordinate): GeoJsonSource =
    geoJsonSource(HonorWayRendering.COMPANION_SOURCE_ID) {
        geometry(Point.fromLngLat(at.lon, at.lat))
    }

/**
 * Radius 6, a white 1.5 stroke at the SDK's default opacity, flat on the
 * map; no transition, so a move is a jump (`PilgrimMapView+HonorWay.swift:246-258@7c200bf`).
 */
internal fun companionLayer(style: GhostStyle): CircleLayer =
    circleLayer(HonorWayRendering.COMPANION_LAYER_ID, HonorWayRendering.COMPANION_SOURCE_ID) {
        circleRadius(HonorWayRendering.COMPANION_RADIUS)
        circleColor(style.companionArgb)
        circleOpacity(style.companionOpacity)
        circleStrokeColor(HonorWayRendering.COMPANION_STROKE_ARGB)
        circleStrokeWidth(HonorWayRendering.COMPANION_STROKE_WIDTH)
        circlePitchAlignment(CirclePitchAlignment.MAP)
    }
