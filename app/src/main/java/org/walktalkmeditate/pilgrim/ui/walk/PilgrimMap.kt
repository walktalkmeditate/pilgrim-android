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
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
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
        view.mapboxMap.loadStyle(styleUri) {
            // Style reload drops existing annotations — rebuild the
            // manager and clear our cached polyline reference so the
            // next update recreates it against the fresh manager.
            polylineManager = view.annotations.createPolylineAnnotationManager()
            polyline = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            MapView(context).also { view ->
                mapView = view
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
                    view.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(mapboxPoints.last())
                            .zoom(FOLLOW_ZOOM)
                            .build(),
                    )
                } else if (!didFitBounds) {
                    val camera = view.mapboxMap.cameraForCoordinates(
                        mapboxPoints,
                        CameraOptions.Builder().build(),
                        EdgeInsets(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX),
                        null,
                        null,
                    )
                    view.mapboxMap.setCamera(camera)
                    didFitBounds = true
                }
            } else if (points.size == 1 && followLatest) {
                val only = points.first()
                view.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(only.longitude, only.latitude))
                        .zoom(FOLLOW_ZOOM)
                        .build(),
                )
            }
        },
        onRelease = { view ->
            mapView = null
            polylineManager = null
            polyline = null
            view.onDestroy()
        },
    )
}

private const val POLYLINE_WIDTH_DP = 4.0
private const val FOLLOW_ZOOM = 16.0
private const val PADDING_PX = 64.0
