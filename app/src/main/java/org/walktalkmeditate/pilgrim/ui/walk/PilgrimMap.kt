// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
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

    var polylineManager by remember { mutableStateOf<PolylineAnnotationManager?>(null) }
    var didFitBounds by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            val mapView = MapView(context)
            mapView.mapboxMap.loadStyle(styleUri) {
                polylineManager = mapView.annotations.createPolylineAnnotationManager()
            }
            mapView
        },
        update = { mapView ->
            val manager = polylineManager ?: return@AndroidView
            manager.deleteAll()
            if (points.size >= 2) {
                val mapboxPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
                manager.create(
                    PolylineAnnotationOptions()
                        .withPoints(mapboxPoints)
                        .withLineColor(lineColor)
                        .withLineWidth(POLYLINE_WIDTH_DP),
                )

                if (followLatest) {
                    val latest = mapboxPoints.last()
                    mapView.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(latest)
                            .zoom(FOLLOW_ZOOM)
                            .build(),
                    )
                } else if (!didFitBounds) {
                    val camera = mapView.mapboxMap.cameraForCoordinates(
                        mapboxPoints,
                        CameraOptions.Builder().build(),
                        EdgeInsets(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX),
                        null,
                        null,
                    )
                    mapView.mapboxMap.setCamera(camera)
                    didFitBounds = true
                }
            } else if (points.size == 1 && followLatest) {
                val only = points.first()
                mapView.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(only.longitude, only.latitude))
                        .zoom(FOLLOW_ZOOM)
                        .build(),
                )
            }
        },
    )
}

private const val POLYLINE_WIDTH_DP = 4.0
private const val FOLLOW_ZOOM = 16.0
private const val PADDING_PX = 64.0
