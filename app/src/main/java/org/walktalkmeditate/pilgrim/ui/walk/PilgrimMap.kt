// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.walktalkmeditate.pilgrim.ui.theme.LocalPilgrimDarkTheme
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.mapbox.geojson.Point
import kotlinx.coroutines.delay
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.locationcomponent.createDefault2DPuck
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_CAMERA_EASE_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.RevealPhase
import org.walktalkmeditate.pilgrim.ui.walk.summary.RouteSegmentColors
import org.walktalkmeditate.pilgrim.ui.walk.summary.SEGMENT_ZOOM_EASE_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkAnnotationColors

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
internal fun PilgrimMap(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
    initialCenter: LocationPoint? = null,
    bottomInsetDp: Dp = 0.dp,
    waypoints: List<org.walktalkmeditate.pilgrim.data.entity.Waypoint> = emptyList(),
    routeSegments: List<RouteSegment> = emptyList(),
    segmentColors: RouteSegmentColors? = null,
    revealPhase: RevealPhase? = null,
    reduceMotion: Boolean = false,
    walkAnnotations: List<WalkMapAnnotation> = emptyList(),
    walkAnnotationColors: WalkAnnotationColors? = null,
    zoomTargetBounds: MapCameraBounds? = null,
) {
    val darkMode = LocalPilgrimDarkTheme.current
    val styleUri = if (darkMode) Style.DARK else Style.LIGHT
    // Pilgrim stone palette, light-mode + dark-mode — see ui/theme/Color.kt.
    val lineColor = if (darkMode) 0xFFB8976E.toInt() else 0xFF8B7355.toInt()
    // EdgeInsets values are physical pixels; convert from a dp constant so
    // the padding looks consistent across screen densities.
    val paddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.toPx().toDouble() }
    val bottomInsetPx = with(LocalDensity.current) { bottomInsetDp.toPx().toDouble() }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var polylineManager by remember { mutableStateOf<PolylineAnnotationManager?>(null) }
    var polyline by remember { mutableStateOf<PolylineAnnotation?>(null) }
    var segmentPolylines by remember { mutableStateOf<List<PolylineAnnotation>>(emptyList()) }
    // Snapshot of the segments + colors that produced the current polyline
    // set, so subsequent recompositions (e.g. revealPhase transitions) skip
    // the delete-and-recreate when the segments themselves haven't changed.
    // Without this guard the multi-segment branch fires on every `update`
    // pass that revealPhase touches — visible flicker as Mapbox tears
    // annotations down and back up.
    var renderedSegments by remember { mutableStateOf<List<RouteSegment>?>(null) }
    var renderedSegmentColors by remember { mutableStateOf<RouteSegmentColors?>(null) }
    var waypointManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
    var waypointAnnotations by remember { mutableStateOf<List<PointAnnotation>>(emptyList()) }
    val waypointBitmap = remember(darkMode) { createWaypointBitmap(darkMode) }
    // Snapshot of the waypoint list that produced the current pin set, so
    // recomposition triggers that don't change waypoints (e.g. Stage 13-D's
    // segment-tap zoom changes `zoomTargetBounds` → re-fires the update
    // lambda) skip the wholesale delete-and-recreate. Same gate pattern as
    // `renderedSegments` / `renderedWalkAnnotationsKey` below.
    var renderedWaypoints by remember {
        mutableStateOf<List<org.walktalkmeditate.pilgrim.data.entity.Waypoint>?>(null)
    }
    // Stage 13-D annotation pins (start/end + meditation + voice). Same
    // snapshot-rebuild pattern as `renderedSegments` above: the update
    // lambda re-runs on every revealPhase / zoomTargetBounds tick, but we
    // only want to delete + recreate when the annotation set or its
    // theme-resolved colors actually change. Without the gate the pins
    // would visibly flicker every time the user taps a timeline segment.
    var annotationManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
    var renderedWalkAnnotations by remember {
        mutableStateOf<List<PointAnnotation>>(emptyList())
    }
    var renderedWalkAnnotationsKey by remember {
        mutableStateOf<Pair<List<WalkMapAnnotation>, WalkAnnotationColors?>?>(null)
    }
    val annotationBitmaps = remember(walkAnnotationColors, darkMode) {
        walkAnnotationColors?.let { colors ->
            mapOf(
                "startEnd" to createCircleBitmap(colors.startEnd, darkMode),
                "meditation" to createCircleBitmap(colors.meditation, darkMode),
                "voice" to createCircleBitmap(colors.voice, darkMode),
            )
        }
    }
    var didFitBounds by remember { mutableStateOf(false) }
    // One-shot: set the camera to [initialCenter] exactly once, on
    // whichever composition first has a non-null center AND points is
    // still empty. Later GPS fixes then drive the follow-latest branch.
    var didSetInitialCenter by remember { mutableStateOf(false) }
    // Fade the AndroidView in once the Mapbox style has loaded. First
    // style-load on a cold MapView is visually chunky (black flash
    // while tiles fetch); fading from 0 → 1 when `loadStyle` invokes
    // its completion callback reads as intentional rather than janky.
    // Matches the iOS app's onStyleLoaded → opacity-1 pattern.
    var styleLoaded by remember { mutableStateOf(false) }
    val mapAlpha by animateFloatAsState(
        targetValue = if (styleLoaded) 1f else 0f,
        animationSpec = tween(durationMillis = FADE_IN_MS),
        label = "mapFadeIn",
    )
    // Tracked per-composition: when onRelease clears mapView the composable
    // is exiting so remember resets naturally, giving a new MapView instance
    // a fresh opt-out on next entry.
    var telemetryOptedOut by remember { mutableStateOf(false) }

    // Stage 13-B: reveal-driven camera control. Fires whenever the phase,
    // map view instance, first GPS point, or reduce-motion flag changes.
    // Gated on `revealPhase != null` so legacy callers (Active Walk, Walk
    // Share) keep their existing fit-bounds-once behavior — they pass the
    // default `null` revealPhase and never enter this branch.
    //
    //   Hidden  -> no-op
    //   Zoomed  -> instant plant at first GPS point at zoom 16
    //   Revealed -> 2.5s ease to fit-bounds (or setCamera under reduce-motion)
    //
    // The style-load LaunchedEffect below owns annotation-manager lifecycle;
    // this one only touches camera state.
    LaunchedEffect(mapView, revealPhase, points.firstOrNull(), reduceMotion, zoomTargetBounds) {
        if (revealPhase == null) return@LaunchedEffect
        val view = mapView ?: return@LaunchedEffect
        when (revealPhase) {
            RevealPhase.Hidden -> { /* no camera change */ }
            RevealPhase.Zoomed -> {
                val first = points.firstOrNull() ?: return@LaunchedEffect
                view.mapboxMap.setCamera(
                    CameraOptions.Builder()
                        .center(Point.fromLngLat(first.longitude, first.latitude))
                        .zoom(REVEAL_ZOOM)
                        .build(),
                )
            }
            RevealPhase.Revealed -> {
                // Stage 13-D: when a timeline-bar segment is selected the
                // screen feeds us `zoomTargetBounds` covering that
                // segment's GPS samples; ease there at 350ms instead of
                // the full 2.5s reveal. Deselect snaps `zoomTargetBounds`
                // back to null and we re-key into the original fit-bounds
                // path below.
                val target = if (zoomTargetBounds != null) {
                    cameraOptionsForBounds(view, zoomTargetBounds, paddingPx)
                } else {
                    if (points.size < 2) return@LaunchedEffect
                    cameraOptionsForFitBounds(view, points, paddingPx)
                }
                val duration = if (zoomTargetBounds != null) {
                    SEGMENT_ZOOM_EASE_MS
                } else {
                    REVEAL_CAMERA_EASE_MS
                }
                // Reduce-motion: snap straight to the target. iOS bypasses
                // the camera ease entirely under accessibilityReduceMotion;
                // mirror here via setCamera in place of easeTo.
                if (reduceMotion) {
                    view.mapboxMap.setCamera(target)
                } else {
                    view.mapboxMap.easeTo(
                        target,
                        MapAnimationOptions.Builder().duration(duration).build(),
                    )
                }
            }
        }
    }

    LaunchedEffect(mapView, styleUri) {
        val view = mapView ?: return@LaunchedEffect
        polylineManager?.let { view.annotations.removeAnnotationManager(it) }
        polylineManager = null
        polyline = null
        segmentPolylines = emptyList()
        renderedSegments = null
        renderedSegmentColors = null
        waypointManager?.let { view.annotations.removeAnnotationManager(it) }
        waypointManager = null
        waypointAnnotations = emptyList()
        renderedWaypoints = null
        annotationManager?.let { view.annotations.removeAnnotationManager(it) }
        annotationManager = null
        renderedWalkAnnotations = emptyList()
        renderedWalkAnnotationsKey = null
        view.mapboxMap.loadStyle(styleUri) {
            polylineManager = view.annotations.createPolylineAnnotationManager()
            waypointManager = view.annotations.createPointAnnotationManager()
            annotationManager = view.annotations.createPointAnnotationManager()
            // Show Mapbox's built-in "you are here" puck on the Active
            // Walk map only. The summary map is a post-hoc review; a live
            // puck there would be out of place. The default 2D puck uses
            // Mapbox's stock assets (no custom drawables needed) and
            // orients to device bearing when available.
            //
            // Tech debt: Mapbox's DefaultLocationProvider creates its own
            // FusedLocationProviderClient subscription, separate from our
            // WalkTrackingService's FusedLocationSource. Both request
            // AccuracyLevel.HIGH / PRIORITY_HIGH_ACCURACY so the platform-
            // merged work item stays at full GPS fidelity — no harm to
            // sample quality — but it's two callback chains for the same
            // GNSS stream. Future cleanup: implement a LocationProvider
            // backed by FusedLocationSource (turning it into a SharedFlow)
            // so the map + service share one subscription.
            // iOS reference doesn't show a scale bar on the walk map and
            // device QA flagged the "0—150m" indicator as visually noisy.
            view.scalebar.enabled = false
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
            // Style is textured; kick the fade-in animation.
            styleLoaded = true
        }
        // Safety net for the failure case: loadStyle's success callback
        // is only delivered on success. With an empty/invalid Mapbox
        // token, a network failure before any tile is cached, or a
        // certificate error, the callback never fires — without this
        // timeout the map card would render alpha=0 forever with no
        // feedback. Better to show Mapbox's error tile than an
        // invisible rectangle. The delay is cancelled naturally when
        // LaunchedEffect re-keys (theme toggle) or the composable
        // leaves composition.
        delay(STYLE_LOAD_TIMEOUT_MS)
        if (!styleLoaded) {
            Log.w(
                "PilgrimMap",
                "style load did not complete within ${STYLE_LOAD_TIMEOUT_MS}ms; " +
                    "fading in anyway (check MAPBOX_ACCESS_TOKEN + network)",
            )
            styleLoaded = true
        }
    }

    AndroidView(
        modifier = modifier.alpha(mapAlpha),
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
            if (routeSegments.isNotEmpty() && segmentColors != null) {
                // Multi-segment path. Skip the delete-and-recreate when the
                // segment list AND colors are structurally identical to what's
                // already rendered (revealPhase changes re-fire the update
                // lambda but don't change segments, so without this guard the
                // polylines would visibly flicker during the reveal sequence).
                val needsRebuild =
                    renderedSegments != routeSegments ||
                        renderedSegmentColors != segmentColors
                if (needsRebuild) {
                    if (segmentPolylines.isNotEmpty()) {
                        segmentPolylines.forEach { manager.delete(it) }
                    }
                    segmentPolylines = routeSegments.map { seg ->
                        val mapboxPoints =
                            seg.points.map { Point.fromLngLat(it.longitude, it.latitude) }
                        val color = when (seg.activity) {
                            RouteActivity.Walking -> segmentColors.walking.toArgb()
                            RouteActivity.Talking -> segmentColors.talking.toArgb()
                            RouteActivity.Meditating -> segmentColors.meditating.toArgb()
                        }
                        manager.create(
                            PolylineAnnotationOptions()
                                .withPoints(mapboxPoints)
                                .withLineColor(color)
                                .withLineWidth(POLYLINE_WIDTH_DP),
                        )
                    }
                    renderedSegments = routeSegments
                    renderedSegmentColors = segmentColors
                }
            } else if (points.size >= 2) {
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
                            .padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))
                            .build(),
                        MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                    )
                } else if (!didFitBounds && revealPhase == null) {
                    val camera = view.mapboxMap.cameraForCoordinates(
                        mapboxPoints,
                        CameraOptions.Builder().build(),
                        EdgeInsets(paddingPx, paddingPx, paddingPx + bottomInsetPx, paddingPx),
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
                        .padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))
                        .build(),
                    MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                )
            } else if (points.isEmpty() && followLatest && !didSetInitialCenter) {
                // No GPS samples yet. If the caller handed us a cached
                // last-known location, snap the camera there so the
                // first paint lands near the user instead of at
                // Mapbox's global default (historically over the US
                // east coast). Setting via setCamera (not easeTo) so
                // the world doesn't visibly fly from zoom 0 to here.
                val center = initialCenter
                if (center != null) {
                    view.mapboxMap.setCamera(
                        CameraOptions.Builder()
                            .center(Point.fromLngLat(center.longitude, center.latitude))
                            .zoom(FOLLOW_ZOOM)
                            .padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))
                            .build(),
                    )
                    didSetInitialCenter = true
                }
            }
            // Sync waypoint annotations: delete existing pins and re-create
            // for the current list. Snapshot-rebuild gate skips the
            // delete-and-recreate when waypoints haven't actually changed
            // (the update lambda re-fires on Stage 13-D's segment-tap
            // zoomTargetBounds change; without the gate, every tap would
            // flicker every waypoint pin). The list is short (typically
            // <30 per walk) so wholesale replace remains cheaper than
            // diffing on actual change.
            val pointMgr = waypointManager
            if (pointMgr != null && renderedWaypoints != waypoints) {
                if (waypointAnnotations.isNotEmpty()) {
                    waypointAnnotations.forEach { pointMgr.delete(it) }
                }
                waypointAnnotations = waypoints.map { wp ->
                    pointMgr.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(wp.longitude, wp.latitude))
                            .withIconImage(waypointBitmap),
                    )
                }
                renderedWaypoints = waypoints
            }
            // Stage 13-D walk-summary annotations (start/end + meditation
            // + voice). Snapshot-rebuild gate keyed on the (annotations,
            // colors) pair so revealPhase / zoomTargetBounds re-fires of
            // the update lambda don't tear the pins down. Legacy callers
            // (Active Walk, Walk Share) pass empty annotations and skip
            // this block entirely.
            val annoMgr = annotationManager
            val bitmaps = annotationBitmaps
            if (annoMgr != null && walkAnnotations.isNotEmpty() && bitmaps != null) {
                val key = walkAnnotations to walkAnnotationColors
                if (renderedWalkAnnotationsKey != key) {
                    if (renderedWalkAnnotations.isNotEmpty()) {
                        renderedWalkAnnotations.forEach { annoMgr.delete(it) }
                    }
                    renderedWalkAnnotations = walkAnnotations.map { ann ->
                        val bitmap = when (ann.kind) {
                            WalkMapAnnotationKind.StartPoint,
                            WalkMapAnnotationKind.EndPoint ->
                                bitmaps.getValue("startEnd")
                            is WalkMapAnnotationKind.Meditation ->
                                bitmaps.getValue("meditation")
                            is WalkMapAnnotationKind.VoiceRecording ->
                                bitmaps.getValue("voice")
                        }
                        annoMgr.create(
                            PointAnnotationOptions()
                                .withPoint(Point.fromLngLat(ann.longitude, ann.latitude))
                                .withIconImage(bitmap),
                        )
                    }
                    renderedWalkAnnotationsKey = key
                }
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
                segmentPolylines = emptyList()
                renderedSegments = null
                renderedSegmentColors = null
                waypointManager = null
                waypointAnnotations = emptyList()
                renderedWaypoints = null
                annotationManager = null
                renderedWalkAnnotations = emptyList()
                renderedWalkAnnotationsKey = null
            }
        },
    )
}

/**
 * Generate the bitmap used as the icon for every waypoint annotation.
 * A `WAYPOINT_BITMAP_SIZE_PX`-sized rust circle with a thin parchment
 * stroke — visible against both light and dark Mapbox styles. Drawn
 * once per theme change (`remember(darkMode)` in the caller) and
 * reused across all waypoints in the walk.
 */
private fun createWaypointBitmap(darkMode: Boolean): android.graphics.Bitmap {
    val size = WAYPOINT_BITMAP_SIZE_PX
    val bitmap = android.graphics.Bitmap.createBitmap(
        size,
        size,
        android.graphics.Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val strokeWidth = size * 0.08f
    // Pilgrim rust + parchment tokens — see ui/theme/Color.kt. Hardcoded
    // here because Compose ColorScheme isn't reachable from this raw
    // Bitmap helper; if the palette ever shifts, update both places.
    val rust = if (darkMode) 0xFFB85F4D.toInt() else 0xFFA8543E.toInt()
    val parchment = if (darkMode) 0xFF1A1814.toInt() else 0xFFF5F0E6.toInt()
    val fill = android.graphics.Paint().apply {
        isAntiAlias = true
        color = rust
        style = android.graphics.Paint.Style.FILL
    }
    val stroke = android.graphics.Paint().apply {
        isAntiAlias = true
        color = parchment
        style = android.graphics.Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val radius = (size / 2f) - strokeWidth
    canvas.drawCircle(cx, cy, radius, fill)
    canvas.drawCircle(cx, cy, radius, stroke)
    return bitmap
}

/**
 * Generate a parchment-stroked circle bitmap in the given fill color.
 * Same shape as [createWaypointBitmap] but the fill is theme-resolved
 * by the caller (start/end → stone, meditation → dawn, voice → rust).
 * Reused across all annotations of the same kind in a walk via
 * `remember(walkAnnotationColors, darkMode)`.
 */
private fun createCircleBitmap(color: Color, darkMode: Boolean): Bitmap {
    val size = WAYPOINT_BITMAP_SIZE_PX
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = size / 2f
    val cy = size / 2f
    val strokeWidth = size * 0.08f
    // Parchment hex-pair matches `createWaypointBitmap` — keep in sync
    // if the palette ever shifts. Compose ColorScheme isn't reachable
    // from this raw Bitmap helper.
    val parchment = if (darkMode) 0xFF1A1814.toInt() else 0xFFF5F0E6.toInt()
    val fill = Paint().apply {
        isAntiAlias = true
        this.color = color.toArgb()
        style = Paint.Style.FILL
    }
    val stroke = Paint().apply {
        isAntiAlias = true
        this.color = parchment
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    val radius = (size / 2f) - strokeWidth
    canvas.drawCircle(cx, cy, radius, fill)
    canvas.drawCircle(cx, cy, radius, stroke)
    return bitmap
}

/**
 * Fit the camera to a [MapCameraBounds] rectangle with uniform
 * `paddingPx` insets on every edge, clamped to [MAX_FIT_ZOOM]. Used
 * by the segment-tap zoom path (Stage 13-D); kept zoom-clamp parity
 * with [cameraOptionsForFitBounds] so a tap on a tiny segment doesn't
 * dive past street level.
 */
private fun cameraOptionsForBounds(
    view: MapView,
    bounds: MapCameraBounds,
    paddingPx: Double,
): CameraOptions {
    val sw = Point.fromLngLat(bounds.swLng, bounds.swLat)
    val ne = Point.fromLngLat(bounds.neLng, bounds.neLat)
    val camera = view.mapboxMap.cameraForCoordinates(
        listOf(sw, ne),
        CameraOptions.Builder().build(),
        EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx),
        null,
        null,
    )
    val clampedZoom = camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM
    return camera.toBuilder().zoom(clampedZoom).build()
}

/**
 * Fit the camera to all [points] with uniform `paddingPx` insets on
 * every edge, clamped to [MAX_FIT_ZOOM]. Extracted from the inline
 * Revealed-branch fit-bounds so the segment-tap branch can read the
 * same code path.
 */
private fun cameraOptionsForFitBounds(
    view: MapView,
    points: List<LocationPoint>,
    paddingPx: Double,
): CameraOptions {
    val mapboxPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
    val camera = view.mapboxMap.cameraForCoordinates(
        mapboxPoints,
        CameraOptions.Builder().build(),
        EdgeInsets(paddingPx, paddingPx, paddingPx, paddingPx),
        null,
        null,
    )
    val clampedZoom = camera.zoom?.coerceAtMost(MAX_FIT_ZOOM) ?: MAX_FIT_ZOOM
    return camera.toBuilder().zoom(clampedZoom).build()
}

private const val POLYLINE_WIDTH_DP = 4.0
private const val FOLLOW_ZOOM = 16.0
private const val REVEAL_ZOOM = 16.0
private const val MAX_FIT_ZOOM = 17.0
private const val FOLLOW_EASE_MS = 800L
private const val FIT_PADDING_DP = 32
private const val FADE_IN_MS = 400
// Bitmap size in pixels for the waypoint marker. Mapbox icon images
// scale by `iconSize` (default 1.0); 56px draws as a ~22dp marker on
// 320dpi devices, comparable in visual weight to iOS waypoint pins.
private const val WAYPOINT_BITMAP_SIZE_PX = 56
// 3s is comfortably above typical cold-load times (~100-500ms per
// Mapbox v11 traces) but short enough to avoid leaving the user
// staring at a blank card on failure paths.
private const val STYLE_LOAD_TIMEOUT_MS = 3_000L
