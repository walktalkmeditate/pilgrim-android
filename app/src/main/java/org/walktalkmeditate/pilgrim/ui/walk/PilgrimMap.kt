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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * Mapbox-backed map showing the walk's route polyline. Style follows
 * the system day/night preference to match the iOS app's behavior
 * (which uses Mapbox's stock `.light` and `.dark` styles). Line color
 * is Pilgrim's `stone` token in the appropriate palette.
 *
 * When [followLatest] is true (Active Walk), the camera eases to the
 * newest sample on every recomposition so the map tracks the walker.
 * When false (Summary), the camera fits the full route's bounds once
 * on first render.
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
    // EdgeInsets values are physical pixels; convert from a dp constant so
    // the padding looks consistent across screen densities.
    val paddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.toPx().toDouble() }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var polylineManager by remember { mutableStateOf<PolylineAnnotationManager?>(null) }
    var polyline by remember { mutableStateOf<PolylineAnnotation?>(null) }
    var didFitBounds by remember { mutableStateOf(false) }
    // Tracked per-composition: when onRelease clears mapView the composable
    // is exiting so remember resets naturally, giving a new MapView instance
    // a fresh opt-out on next entry.
    var telemetryOptedOut by remember { mutableStateOf(false) }

    // Own the style load from one place so init and theme toggles cannot
    // race each other. Keyed on (mapView, styleUri): first pass with
    // mapView == null bails, next pass (once the factory has assigned it)
    // does the initial load, and subsequent theme changes trigger a clean
    // reload. Any prior annotation manager is detached before a new one
    // is created to avoid orphaning it on the stale style.
    LaunchedEffect(mapView, styleUri) {
        val view = mapView ?: return@LaunchedEffect
        polylineManager?.let { view.annotations.removeAnnotationManager(it) }
        polylineManager = null
        polyline = null
        view.mapboxMap.loadStyle(styleUri) {
            polylineManager = view.annotations.createPolylineAnnotationManager()
            // Show Mapbox's built-in "you are here" puck on the Active
            // Walk map only. The summary map is a post-hoc review; a live
            // puck there would be out of place. The default 2D puck uses
            // Mapbox's stock assets (no custom drawables needed) and
            // orients to device bearing when available.
            if (followLatest) {
                view.location.updateSettings {
                    enabled = true
                    pulsingEnabled = true
                    locationPuck = createDefault2DPuck(withBearing = true)
                }
            }
            // Opt out of Mapbox's anonymous event collection once per
            // MapView instance. Pilgrim's privacy posture is
            // no-telemetry-by-default; this covers the plugin's own usage
            // pings (map interaction events, style loads, etc.). Done
            // inside the loadStyle callback so the telemetry subsystem is
            // initialized before we flip the flag. The default attribution
            // UI still shows and still lets users opt back in from there.
            // Guarded by telemetryOptedOut so theme toggles don't re-flip
            // the bit (redundant, and future SDKs might interpret repeated
            // writes as preference cycling).
            if (!telemetryOptedOut) {
                try {
                    view.attribution.getMapAttributionDelegate()
                        .telemetry()
                        .setUserTelemetryRequestState(false)
                    telemetryOptedOut = true
                } catch (_: Exception) {
                    // Tolerate Mapbox shaving or renaming the telemetry
                    // accessor in a point release — a failed opt-out must
                    // not crash the map. Errors (OOM, etc.) still propagate.
                }
            }
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            // No MapInitOptions(styleUri): earlier attempts to pre-load the
            // style from the constructor raced against LaunchedEffect's
            // loadStyle, with no documented coalescing in the Mapbox SDK —
            // two callbacks could fire and create a second annotation
            // manager that orphaned the first one's polyline. The cost of
            // avoiding that is a ~100ms blank canvas on first render, which
            // is acceptable for a contemplative walking app.
            MapView(context).also { view ->
                mapView = view
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
                    // Ease rather than snap — each new GPS sample nudges
                    // the camera smoothly instead of jittering it. Keep
                    // the duration below the typical GPS interval so the
                    // ease completes before the next cancel/restart. Only
                    // center + zoom are written; bearing, pitch, padding
                    // come from the live camera so user-set rotation /
                    // tilt survives each sample.
                    val current = view.mapboxMap.cameraState
                    view.mapboxMap.easeTo(
                        CameraOptions.Builder()
                            .center(mapboxPoints.last())
                            .zoom(FOLLOW_ZOOM)
                            .bearing(current.bearing)
                            .pitch(current.pitch)
                            .padding(current.padding)
                            .build(),
                        MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                    )
                } else if (!didFitBounds) {
                    val camera = view.mapboxMap.cameraForCoordinates(
                        mapboxPoints,
                        CameraOptions.Builder().build(),
                        EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx),
                        null,
                        null,
                    )
                    // Clamp max zoom for fit-bounds — a walk contained to a
                    // single city block otherwise resolves to street-level
                    // zoom, which reads as "the map is broken". Fall back
                    // to MAX_FIT_ZOOM if cameraForCoordinates returns a
                    // null zoom (degenerate bounding box); leaving it null
                    // means setCamera preserves the prior zoom, which on a
                    // fresh map is 0 — the whole globe.
                    val clampedZoom = camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM
                    val clamped = camera.toBuilder()
                        .zoom(clampedZoom)
                        .build()
                    view.mapboxMap.setCamera(clamped)
                    didFitBounds = true
                }
            } else if (points.size == 1 && followLatest) {
                val only = points.first()
                val current = view.mapboxMap.cameraState
                view.mapboxMap.easeTo(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(only.longitude, only.latitude))
                        .zoom(FOLLOW_ZOOM)
                        .bearing(current.bearing)
                        .pitch(current.pitch)
                        .padding(current.padding)
                        .build(),
                    MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                )
            }
        },
        onRelease = { view ->
            // Mapbox v11's lifecycle plugin drives onStart/onStop via the
            // view's attach/detach transitions, but the native GL surface
            // + renderer teardown only happens on an explicit onDestroy().
            // Under AndroidView interop, onRelease is the composable-exit
            // hook — without this call, each navigation to/from the map
            // leaks ~12 MB of native memory (mapbox-maps-android#2079).
            // try/finally so that even if onDestroy throws (e.g., the
            // system already released the GL surface under low-memory
            // trim), we still null our references — otherwise a remount
            // would resurrect a dead MapView.
            try {
                view.onDestroy()
            } finally {
                mapView = null
                polylineManager = null
                polyline = null
            }
        },
    )
}

private const val POLYLINE_WIDTH_DP = 4.0
private const val FOLLOW_ZOOM = 16.0
private const val MAX_FIT_ZOOM = 17.0
private const val FOLLOW_EASE_MS = 800L
private const val FIT_PADDING_DP = 32
