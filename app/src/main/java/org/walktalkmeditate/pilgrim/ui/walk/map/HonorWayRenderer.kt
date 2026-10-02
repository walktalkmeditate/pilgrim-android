// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.toArgb
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.LineString
import com.mapbox.geojson.Point
import com.mapbox.maps.MapboxStyleManager
import com.mapbox.maps.extension.style.expressions.generated.Expression
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.addLayerBelow
import com.mapbox.maps.extension.style.layers.generated.LineLayer
import com.mapbox.maps.extension.style.layers.generated.lineLayer
import com.mapbox.maps.extension.style.layers.properties.generated.LineCap
import com.mapbox.maps.extension.style.layers.properties.generated.LineJoin
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySpan
import org.walktalkmeditate.pilgrim.domain.honor.WaySpanKind
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors

/*
 * The Way as a faded ghost line on a map: iOS `HonorWayState` and
 * `applyGhostLine` (`PilgrimMapView+HonorWay.swift:28-227@7c200bf`, parity
 * spec E §2). The overview draws it (U21); the walk map adds the companion
 * beside it (U22).
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

internal object HonorWayRendering {
    const val SOURCE_ID = "honor-way-source"
    const val LINE_LAYER_ID = "honor-way-line"
    const val LINE_WIDTH = 4.0
    const val ACTIVITY_PROPERTY = "activityType"
    const val WALKING = "walking"
    const val TALKING = "talking"
    const val MEDITATING = "meditating"

    /** iOS `ghostStyle(dark:)`: only the opacity follows the map style. */
    fun lineOpacity(dark: Boolean): Double = if (dark) 0.4 else 0.22

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
 * The style writes the ghost line needs, so [HonorWayRenderer]'s bookkeeping
 * is JVM-testable against a fake; [MapboxHonorWayStyle] is the real one.
 */
internal interface HonorWayStyle {
    fun isStyleLoaded(): Boolean
    fun ghostLineExists(): Boolean

    /** Returns whether the line went in; a failure is retried on the next pass, as iOS's is. */
    fun installGhostLine(line: HonorWayLine, opacity: Double): Boolean
    fun removeGhostLine()
}

/**
 * The ghost line's bookkeeping for one map: installed once per Way id,
 * removed for a null line, reinstalled after a style reload, and
 * self-healing when a layer vanishes without a style event
 * (`PilgrimMapView+HonorWay.swift:140-209@7c200bf`). [dark] is read at
 * install time, as iOS reads the map's trait.
 */
internal class HonorWayRenderer(
    private val style: HonorWayStyle,
    private val dark: () -> Boolean,
) {
    private var pendingLine: HonorWayLine? = null
    private var appliedWayId: String? = null

    fun apply(line: HonorWayLine?) {
        pendingLine = line
        if (!style.isStyleLoaded()) return
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
        if (style.installGhostLine(line, HonorWayRendering.lineOpacity(dark()))) {
            appliedWayId = line.wayId
        }
    }

    /** From the style-load callback, after the annotation managers: the fresh style has no Honor layers. */
    fun onStyleReloaded() {
        appliedWayId = null
        apply(pendingLine)
    }
}

/**
 * The real surface. The ghost goes below the route casing, else below the
 * route line, else on top (iOS `ghostLinePosition`); the walker's own route
 * stays the legible one.
 */
internal class MapboxHonorWayStyle(
    private val map: MapboxStyleManager,
    private val belowLayerIds: List<String>,
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
            val layer = ghostLineLayer(opacity, colors)
            val below = belowLayerIds.firstOrNull(map::styleLayerExists)
            if (below != null) map.addLayerBelow(layer, below) else map.addLayer(layer)
            true
        } catch (e: Exception) {
            Log.w(TAG, "honor way install failed", e)
            removeGhostLine()
            false
        }

    override fun removeGhostLine() {
        try {
            if (map.styleLayerExists(HonorWayRendering.LINE_LAYER_ID)) {
                map.removeStyleLayer(HonorWayRendering.LINE_LAYER_ID)
            }
            if (map.styleSourceExists(HonorWayRendering.SOURCE_ID)) {
                map.removeStyleSource(HonorWayRendering.SOURCE_ID)
            }
        } catch (e: Exception) {
            Log.w(TAG, "honor way removal failed", e)
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
