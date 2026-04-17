// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.attribution.attribution
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Mapbox-backed map showing the walk's route polyline. Style follows
 * the system day/night preference to match the iOS app's behavior
 * (which uses Mapbox's stock `.light` and `.dark` styles). Line color
 * is Pilgrim's `stone` token in the appropriate palette.
 *
 * When [followLatest] is true (Active Walk), the camera recenters on
 * the newest sample on every recomposition so the map tracks the
 * walker. When false (Summary), the camera fits the full route's
 * bounds once on first render.
 */
@Composable
fun PilgrimMap(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
) {
    val darkMode = isSystemInDarkTheme()
    val styleUri = if (darkMode) Style.DARK else Style.LIGHT
    // Pilgrim stone palette, light-mode + dark-mode — see ui/theme/Color.kt.
    val lineColor = if (darkMode) 0xFFB8976E.toInt() else 0xFF8B7355.toInt()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var polylineManager by remember { mutableStateOf<PolylineAnnotationManager?>(null) }
    var polyline by remember { mutableStateOf<PolylineAnnotation?>(null) }
    var didFitBounds by remember { mutableStateOf(false) }

    // Reload the style when the system theme toggles between light and
    // dark. Without this, a map that was rendered in light mode stays
    // light after the user toggles dark mode (or vice versa).
    LaunchedEffect(styleUri) {
        val view = mapView ?: return@LaunchedEffect
        // Null synchronously *before* loadStyle kicks off. loadStyle is
        // async — if we waited to clear these inside the callback, the
        // AndroidView update block could run in the interim and call
        // manager.update() on a manager whose annotations the new style
        // has already invalidated. Clearing first makes that window a
        // no-op (update bails on null manager).
        polylineManager = null
        polyline = null
        view.mapboxMap.loadStyle(styleUri) {
            polylineManager = view.annotations.createPolylineAnnotationManager()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { view ->
                mapView = view
                // Opt out of Mapbox's anonymous event collection. Pilgrim's
                // privacy posture is no-telemetry-by-default; this covers
                // the Mapbox plugin's own usage pings (map interaction
                // events, style loads, etc.). Default attribution UI still
                // shows and still lets users opt back in if they want.
                view.attribution.getMapAttributionDelegate()
                    .telemetry()
                    .setUserTelemetryRequestState(false)
                view.mapboxMap.loadStyle(styleUri) {
                    polylineManager = view.annotations.createPolylineAnnotationManager()
                }
            }
        },
        update = { view ->
            val manager = polylineManager ?: return@AndroidView
            if (points.size >= 2) {
                val mapboxPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
                val existing = polyline
                if (existing == null) {
                    polyline = manager.create(
                        PolylineAnnotationOptions()
                            .withPoints(mapboxPoints)
                            .withLineColor(lineColor)
                            .withLineWidth(POLYLINE_WIDTH_DP),
                    )
                } else {
                    // Mutate in place — cheaper than delete + create for
                    // walks with thousands of samples.
                    existing.points = mapboxPoints
                    existing.lineColorInt = lineColor
                    manager.update(existing)
                }

                if (followLatest) {
                    // Animate rather than snap — each new GPS sample nudges
                    // the camera smoothly instead of jittering it.
                    view.mapboxMap.easeTo(
                        CameraOptions.Builder()
                            .center(mapboxPoints.last())
                            .zoom(FOLLOW_ZOOM)
                            .build(),
                        MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                    )
                } else if (!didFitBounds) {
                    val camera = view.mapboxMap.cameraForCoordinates(
                        mapboxPoints,
                        CameraOptions.Builder().build(),
                        EdgeInsets(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX),
                        null,
                        null,
                    )
                    // Clamp max zoom for fit-bounds — a walk contained to a
                    // single city block otherwise resolves to street-level
                    // zoom, which looks like the map is broken.
                    val clamped = CameraOptions.Builder()
                        .center(camera.center)
                        .zoom(camera.zoom?.coerceAtMost(MAX_FIT_ZOOM))
                        .padding(camera.padding)
                        .bearing(camera.bearing)
                        .pitch(camera.pitch)
                        .anchor(camera.anchor)
                        .build()
                    view.mapboxMap.setCamera(clamped)
                    didFitBounds = true
                }
            } else if (points.size == 1 && followLatest) {
                val only = points.first()
                view.mapboxMap.easeTo(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(only.longitude, only.latitude))
                        .zoom(FOLLOW_ZOOM)
                        .build(),
                    MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                )
            }
        },
        onRelease = {
            // MapView auto-wires its own lifecycle observer in v10+. The
            // Compose AndroidView release already detaches the view; calling
            // onDestroy() here would double-dispose and risk a NPE when the
            // internal observer fires later.
            mapView = null
            polylineManager = null
            polyline = null
        },
    )
}

private const val POLYLINE_WIDTH_DP = 4.0
private const val FOLLOW_ZOOM = 16.0
private const val MAX_FIT_ZOOM = 17.0
private const val FOLLOW_EASE_MS = 1500L
private const val PADDING_PX = 64.0
