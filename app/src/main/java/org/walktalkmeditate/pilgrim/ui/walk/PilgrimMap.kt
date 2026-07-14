// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Terrain
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory
import org.walktalkmeditate.pilgrim.ui.theme.LocalPilgrimDarkTheme
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.turningAccentColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.mapbox.geojson.Point
import kotlinx.coroutines.delay
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.ImageHolder
import com.mapbox.maps.MapInitOptions
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.LocationPuck2D
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotation
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotation
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.PolylineAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createCircleAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.annotation.generated.createPolylineAnnotationManager
import com.mapbox.maps.plugin.attribution.attribution
import com.mapbox.maps.plugin.compass.compass
import com.mapbox.maps.plugin.locationcomponent.OnIndicatorPositionChangedListener
import com.mapbox.maps.plugin.locationcomponent.location
import com.mapbox.maps.plugin.scalebar.scalebar
import org.walktalkmeditate.pilgrim.data.walk.RouteActivity
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.seek.SeekFogState
import org.walktalkmeditate.pilgrim.domain.seek.SeekPulseVisual
import org.walktalkmeditate.pilgrim.ui.walk.map.MapboxSeekFogStyle
import org.walktalkmeditate.pilgrim.ui.walk.map.SeekFogRenderer
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_CAMERA_EASE_MS
import org.walktalkmeditate.pilgrim.ui.walk.summary.REVEAL_ZOOM_PLANT_MS
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
    // Walk Summary's radial-gradient mask needs the map pixels to live
    // in the parent compose graphics layer (not a separate SurfaceView
    // window) so the overlay actually covers map content at the corners.
    // Caller opt-in to keep ActiveWalk + WalkShare on the faster
    // SurfaceView backend by default.
    textureBackend: Boolean = false,
    // iOS parity `ActiveWalkView.swift:597@v1.6.0` —
    // `walkingColor: activeTurning?.uiColor ?? .moss`. The single-polyline
    // (live walk) route line draws in this color; defaults to the fixed
    // walking moss. Active Walk passes the turning's cardinal accent on a
    // solstice/equinox so the route matches the celestial-vignette halo.
    // Ignored by the segment path (Summary / Share), which colors per
    // activity via [segmentColors].
    walkingColor: Color = RouteSegmentColors.Fixed.walking,
    // iOS parity `ActiveWalkView.swift:574-659@db4196e` — proximity pin
    // layer (whisper + cairn). Already-filtered list from
    // [ProximityPinFilter]; this composable just renders + wires the tap
    // callback. Empty list → no pin manager work, no allocations.
    proximityPins: List<ProximityPinFilter.Pin> = emptyList(),
    onProximityPinTap: (ProximityPinFilter.Pin) -> Unit = {},
    // iOS parity `PilgrimMapView+SeekFog.swift@c1745e8` (port spec
    // docs/parity/2026-07-14-port-seek-fog-u6.md) — seek fog circles +
    // pulse ring as runtime style layers. Null fog state is the wander
    // default: the renderer's `null == null` fast path never touches the
    // style, so non-seek walks pay nothing. [seekPulse] advances its token
    // once per heartbeat; [seekLightColor] tints the ring with the hour's
    // light (U7's SeekSkyLight computes it; golden home light until then).
    seekFog: SeekFogState? = null,
    seekPulse: SeekPulseVisual = SeekPulseVisual.NONE,
    seekLightColor: Color = Color(0xFFC4956A),
) {
    // Mapbox's `MapView(context, initOptions)` constructor throws
    // `MapboxConfigurationException` synchronously when no access
    // token is configured (empty string counts as "no token"). The
    // earlier comment claiming "empty token is accepted, placeholder
    // handles it" was wrong — the throw happens at View construction,
    // long before any tile load, so it crashed every Walk Summary on
    // a token-less build (the default contributor / CI experience).
    // Short-circuit to a static parchment fallback when the token is
    // blank. iOS parity intent: contributors without Mapbox tokens
    // see a quiet placeholder, not a crash.
    if (org.walktalkmeditate.pilgrim.BuildConfig.MAPBOX_ACCESS_TOKEN.isBlank()) {
        Box(
            modifier = modifier.background(
                org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors.parchmentSecondary,
            ),
            contentAlignment = androidx.compose.ui.Alignment.Center,
        ) {
            Text(
                text = "Map unavailable",
                style = org.walktalkmeditate.pilgrim.ui.theme.pilgrimType.caption,
                color = org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors.fog,
            )
        }
        return
    }
    val darkMode = LocalPilgrimDarkTheme.current
    val styleUri = if (darkMode) Style.DARK else Style.LIGHT
    // iOS parity `PilgrimMapView.swift:321-329@v1.6.0` — the route line
    // is constant across light/dark/constellation (was a darkMode-
    // dependent stone hex pair that drifted with appearance). Defaults to
    // the fixed walking moss; on a turning day Active Walk hands us the
    // cardinal accent via [walkingColor] (`ActiveWalkView.swift:597`).
    val lineColor = walkingColor.toArgb()
    // iOS parity `PilgrimMapView.swift:231-251@v1.6.0` — the user puck
    // is the seasonal/appearance `stone` color, NOT Mapbox blue. Read
    // it from the live theme (`pilgrimColors.stone`) rather than the
    // hex-by-darkMode `lineColor` path so it reflects the constellation
    // appearance override + seasonal shifts (the hardcoded hex pair
    // above is frozen to the base palette and would not flip in
    // constellation mode).
    val stoneArgb = org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors.stone.toArgb()
    // EdgeInsets values are physical pixels; convert from a dp constant so
    // the padding looks consistent across screen densities.
    val paddingPx = with(LocalDensity.current) { FIT_PADDING_DP.dp.toPx().toDouble() }
    val bottomInsetPx = with(LocalDensity.current) { bottomInsetDp.toPx().toDouble() }

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var polylineManager by remember { mutableStateOf<PolylineAnnotationManager?>(null) }
    var polyline by remember { mutableStateOf<PolylineAnnotation?>(null) }
    var segmentPolylines by remember { mutableStateOf<List<PolylineAnnotation>>(emptyList()) }
    // AF46: cache the prior domain points + their projected Mapbox points so
    // the live polyline maps only the new tail per GPS fix instead of
    // re-projecting the whole growing list on the main thread. Reset alongside
    // `polyline` whenever the route is torn down (boundary check falls back to
    // a full remap on any mismatch, so this is purely to release memory).
    var liveRoutePoints by remember { mutableStateOf<List<LocationPoint>>(emptyList()) }
    var liveMapboxPoints by remember { mutableStateOf<List<Point>>(emptyList()) }
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
    // iOS parity: each waypoint renders its type's glyph (leaf / eye /
    // heart / chair / sparkles / flag / pin), not one shared solid dot.
    val waypointBitmaps = rememberWaypointBitmaps(
        org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors.stone,
    )
    // iOS parity: whisper/cairn map markers are bare glyphs (wind /
    // mountain.2), not solid circles. One whisper bitmap per category
    // color; one moss cairn bitmap scaled per tier via iconSize.
    val whisperGlyphBitmaps = rememberWhisperGlyphBitmaps()
    val cairnGlyphBitmap = rememberCairnGlyphBitmap(darkMode)
    // iOS parity `PilgrimMapView.swift:231-251@v1.6.0` — custom 2D
    // puck: stone-filled outer disc + white@0.9 inner dot. Keyed on
    // `stoneArgb` only (the single value the draw reads) so a theme /
    // appearance / seasonal flip rebuilds it but unrelated
    // recompositions reuse the cached bitmap.
    val puckBitmap = remember(stoneArgb) { createPuckBitmap(stoneArgb) }
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
    // iOS parity `PilgrimMapView.buildCircles@v1.6.0` — meditation is a
    // duration-scaled dawn CircleAnnotation, not a fixed pin dot. Same
    // for the post-walk summary map (was rendering a tiny static pin).
    var meditationCircleManager by remember {
        mutableStateOf<com.mapbox.maps.plugin.annotation.generated.CircleAnnotationManager?>(
            null,
        )
    }
    var renderedMeditationCircles by remember {
        mutableStateOf<List<com.mapbox.maps.plugin.annotation.generated.CircleAnnotation>>(
            emptyList(),
        )
    }
    var renderedWalkAnnotations by remember {
        mutableStateOf<List<PointAnnotation>>(emptyList())
    }
    var renderedWalkAnnotationsKey by remember {
        mutableStateOf<Triple<List<WalkMapAnnotation>, WalkAnnotationColors?, Map<Long, Bitmap>>?>(null)
    }
    var proximityManager by remember { mutableStateOf<PointAnnotationManager?>(null) }
    var renderedProximityPins by remember {
        mutableStateOf<List<ProximityPinFilter.Pin>>(emptyList())
    }
    // Bitmap pin id → (Pin) for the click listener — Mapbox annotation
    // click callback delivers the PointAnnotation; we look up the
    // associated Pin via the annotation's textField (used as a key
    // because PointAnnotation has no opaque data slot).
    var proximityPinIndex by remember {
        mutableStateOf<Map<String, ProximityPinFilter.Pin>>(emptyMap())
    }
    // 13-B/13-D flicker-class gates for the live-route branch: the update
    // lambda re-fires on recompositions that change neither the points nor
    // the camera target (seek fog states, pulse tokens). Without these keys
    // every such re-fire re-uploaded the unchanged polyline geometry and
    // re-issued an easeTo to the camera's current target.
    var renderedLiveLineColor by remember { mutableStateOf<Int?>(null) }
    var lastFollowCameraKey by remember { mutableStateOf<Pair<Point, Double>?>(null) }
    // Seek fog renderer — bookkeeping + Mapbox writes live in
    // ui/walk/map/SeekFogRenderer.kt; this composable only owns lifecycle.
    // The surface checks the route layer id for existence at install time,
    // so fog lands below the route line once the manager exists and at the
    // top of the stack (iOS fallback parity) before then.
    val seekFogStyle = remember(mapView) {
        mapView?.let { view ->
            MapboxSeekFogStyle(view.mapboxMap) { ROUTE_LINE_LAYER_ID }
        }
    }
    val seekFogRenderer = remember(seekFogStyle) {
        seekFogStyle?.let { SeekFogRenderer(it) }
    }
    // The pulse ring fires at the puck's rendered position (iOS reads
    // location.latestLocation at fire time; Android caches the indicator
    // position — spec D3). Registered only while a seek is active so
    // wander walks never pay for the per-frame listener.
    val seekFogActive = seekFog != null
    DisposableEffect(mapView, seekFogActive) {
        val view = mapView
        val style = seekFogStyle
        if (view == null || style == null || !seekFogActive) {
            return@DisposableEffect onDispose {}
        }
        val listener = OnIndicatorPositionChangedListener { point ->
            style.latestPuckPoint = point
        }
        view.location.addOnIndicatorPositionChangedListener(listener)
        onDispose {
            view.location.removeOnIndicatorPositionChangedListener(listener)
            style.latestPuckPoint = null
        }
    }
    val annotationBitmaps = remember(walkAnnotationColors, darkMode) {
        walkAnnotationColors?.let { colors ->
            mapOf(
                "startEnd" to createCircleBitmap(colors.startEnd, darkMode),
                "meditation" to createCircleBitmap(colors.meditation, darkMode),
                "voice" to createCircleBitmap(colors.voice, darkMode),
                // Placeholder photo pin — shown while the real circular
                // thumbnail loads from the content URI. Replaced per-photo
                // via [photoPinBitmaps] below as soon as the decode +
                // mask job completes.
                "photo" to createCircleBitmap(colors.photo, darkMode),
            )
        }
    }
    // Per-photo circular thumbnail bitmaps, decoded from the content URIs.
    // Loaded asynchronously on Dispatchers.IO inside a LaunchedEffect so
    // the AndroidView update lambda can render the placeholder bitmap
    // immediately and swap in the real thumbnail when ready.
    val photoPinBitmaps = rememberPhotoPinBitmaps(walkAnnotations, darkMode)
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
                val target = CameraOptions.Builder()
                    .center(Point.fromLngLat(first.longitude, first.latitude))
                    .zoom(REVEAL_ZOOM)
                    .build()
                // iOS WalkSummaryView.swift:362 uses cameraDuration = 0.1
                // for the Hidden → Zoomed plant — quick pull-in, not an
                // instant snap. Reduce-motion path stays on setCamera
                // (Mapbox SDK 11.11.0 last-write-wins; the Revealed
                // branch below supersedes any in-flight 100ms ease).
                if (reduceMotion) {
                    view.mapboxMap.setCamera(target)
                } else {
                    view.mapboxMap.easeTo(
                        target,
                        MapAnimationOptions.Builder().duration(REVEAL_ZOOM_PLANT_MS).build(),
                    )
                }
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
        liveRoutePoints = emptyList()
        liveMapboxPoints = emptyList()
        renderedSegments = null
        renderedSegmentColors = null
        renderedLiveLineColor = null
        lastFollowCameraKey = null
        waypointManager?.let { view.annotations.removeAnnotationManager(it) }
        waypointManager = null
        waypointAnnotations = emptyList()
        renderedWaypoints = null
        annotationManager?.let { view.annotations.removeAnnotationManager(it) }
        annotationManager = null
        renderedWalkAnnotations = emptyList()
        renderedWalkAnnotationsKey = null
        meditationCircleManager?.let { view.annotations.removeAnnotationManager(it) }
        meditationCircleManager = null
        renderedMeditationCircles = emptyList()
        proximityManager?.let { view.annotations.removeAnnotationManager(it) }
        proximityManager = null
        renderedProximityPins = emptyList()
        proximityPinIndex = emptyMap()
        view.mapboxMap.loadStyle(styleUri) {
            // Show the "you are here" puck on the Active Walk map only.
            // The summary map is a post-hoc review; a live puck there
            // would be out of place. iOS parity
            // `PilgrimMapView.swift:123,128-144@v1.6.0` — iOS calls
            // `configurePuck(on:)` in `makeUIView` BEFORE the
            // `onStyleLoaded` observer recreates the annotation managers
            // (`applyRouteSource` / `applyAnnotations`), so the
            // location-indicator layer is established first and the
            // annotation symbol layers stack on top of it. Mapbox
            // inserts the location-indicator layer at the TOP of the
            // layer stack (bound with a null LayerPosition); enabling
            // the location component AFTER creating the annotation
            // managers would put the puck above a waypoint dropped at
            // the user's exact location, fully occluding it. Enabling
            // it FIRST keeps the annotation SymbolLayers on top so the
            // waypoint stays visible. The actual `locationPuck` +
            // `pulsingColor` are applied (and re-applied on theme flip)
            // by the dedicated puck LaunchedEffect below; here we only
            // enable the component + pulsing so the puck appears as
            // soon as the style finishes loading.
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
            if (followLatest) {
                view.location.updateSettings {
                    enabled = true
                    pulsingEnabled = true
                }
            }
            // Named layer id so the seek fog can insert below the route
            // line without touching Mapbox's restricted associatedLayers
            // accessor (iOS anchors on its named "pilgrim-route-casing").
            polylineManager = view.annotations.createPolylineAnnotationManager(
                com.mapbox.maps.plugin.annotation.AnnotationConfig(layerId = ROUTE_LINE_LAYER_ID),
            )
            // Above the route line, below the point pins (start/end/voice).
            meditationCircleManager = view.annotations.createCircleAnnotationManager()
            waypointManager = view.annotations.createPointAnnotationManager()
                .also(::allowIconOverlap)
            annotationManager = view.annotations.createPointAnnotationManager()
                .also(::allowIconOverlap)
            proximityManager = view.annotations.createPointAnnotationManager().apply {
                allowIconOverlap(this)
                addClickListener { annotation ->
                    val pin = proximityPinIndex[annotation.textField.orEmpty()]
                    if (pin != null) {
                        onProximityPinTap(pin)
                        true
                    } else false
                }
            }
            // iOS parity `PilgrimMapView.swift:119-120@v1.6.0` hides BOTH
            // the scale bar and the compass. The compass appears on zoom/
            // rotate near the top-right X and was device-flagged as noise.
            view.scalebar.enabled = false
            view.compass.enabled = false
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
            // The fresh style has no seek layers; reinstall from the
            // renderer's pending state. Must run AFTER the annotation
            // managers above so the fog's below-route insert can find the
            // polyline layer (iOS parity: reinstallSeekFog from
            // onStyleLoaded, PilgrimMapView.swift:163@c1745e8).
            seekFogRenderer?.onStyleReloaded(reduceMotion)
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

    // iOS parity `PilgrimMapView.swift:231-251@v1.6.0` — apply the
    // custom stone-tinted 2D puck + stone@0.3 pulsing ring. Separate
    // from the loadStyle effect so a constellation / seasonal
    // appearance change that flips `stoneArgb` WITHOUT flipping
    // `styleUri` (dark/light) still retints the puck. Re-keys on
    // `puckBitmap` (which is `remember(stoneArgb)`-keyed) and waits for
    // `styleLoaded` so `view.location.updateSettings` lands after the
    // location component is initialized by the loadStyle block above.
    LaunchedEffect(mapView, puckBitmap, followLatest, styleLoaded) {
        if (!followLatest || !styleLoaded) return@LaunchedEffect
        val view = mapView ?: return@LaunchedEffect
        // Stone@0.3 — matches iOS `stoneColor.withAlphaComponent(0.3)`.
        val pulseArgb = (stoneArgb and 0x00FFFFFF) or (0x4D shl 24)
        view.location.updateSettings {
            enabled = true
            pulsingEnabled = true
            pulsingColor = pulseArgb
            locationPuck = buildStonePuck(puckBitmap)
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
            val initOptions = MapInitOptions(
                context = context,
                textureView = textureBackend,
            )
            MapView(context, initOptions).also { view ->
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
                // AF46: project only the new tail; reuse the cached prefix.
                val mapboxPoints = incrementalMap(
                    prevSource = liveRoutePoints,
                    prevMapped = liveMapboxPoints,
                    newSource = points,
                    sameElement = { a, b -> a == b },
                    transform = { Point.fromLngLat(it.longitude, it.latitude) },
                )
                // incrementalMap returns the PREVIOUS list instance when the
                // route is unchanged, so identity is the rebuild gate: a
                // re-fire of this lambda without a new fix (seek fog state,
                // pulse token) must not re-upload unchanged geometry.
                val routeChanged = mapboxPoints !== liveMapboxPoints
                liveRoutePoints = points
                liveMapboxPoints = mapboxPoints
                val existing = polyline
                if (existing == null) {
                    polyline = manager.create(
                        PolylineAnnotationOptions()
                            .withPoints(mapboxPoints)
                            .withLineColor(lineColor)
                            .withLineWidth(POLYLINE_WIDTH_DP),
                    )
                    renderedLiveLineColor = lineColor
                } else if (routeChanged || renderedLiveLineColor != lineColor) {
                    // Mutate in place — cheaper than delete + create for
                    // walks with thousands of samples.
                    existing.points = mapboxPoints
                    existing.lineColorInt = lineColor
                    manager.update(existing)
                    renderedLiveLineColor = lineColor
                }

                if (followLatest) {
                    // Ease rather than snap — each new GPS sample nudges
                    // the camera smoothly instead of jittering it. Keep
                    // the duration below the typical GPS interval so the
                    // ease completes before the next cancel/restart. Only
                    // center + zoom are written; bearing, pitch, padding
                    // come from the live camera so user-set rotation /
                    // tilt survives each sample. Keyed on (target, inset)
                    // so recompositions that move neither don't re-issue
                    // the ease.
                    val cameraKey = mapboxPoints.last() to bottomInsetPx
                    if (lastFollowCameraKey != cameraKey) {
                        lastFollowCameraKey = cameraKey
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
                    }
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
                // Same (target, inset) gate as the multi-point follow branch.
                val cameraKey = Point.fromLngLat(only.longitude, only.latitude) to bottomInsetPx
                if (lastFollowCameraKey != cameraKey) {
                    lastFollowCameraKey = cameraKey
                    val current = view.mapboxMap.cameraState
                    view.mapboxMap.easeTo(
                        CameraOptions.Builder()
                            .center(cameraKey.first)
                            .zoom(FOLLOW_ZOOM)
                            .bearing(current.bearing)
                            .pitch(current.pitch)
                            .padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))
                            .build(),
                        MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                    )
                }
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
                            .withIconImage(
                                waypointBitmaps[wp.icon]
                                    ?: waypointBitmaps.getValue("mappin"),
                            ),
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
                // Snapshot the photo cache into an immutable Map so the
                // Triple equality check actually sees content changes —
                // a SnapshotStateMap reference is stable, so without the
                // toMap() copy the key would always compare equal and
                // the placeholder bitmaps would never get replaced.
                val key = Triple(walkAnnotations, walkAnnotationColors, photoPinBitmaps.toMap())
                if (renderedWalkAnnotationsKey != key) {
                    if (renderedWalkAnnotations.isNotEmpty()) {
                        renderedWalkAnnotations.forEach { annoMgr.delete(it) }
                    }
                    // iOS parity `PilgrimMapView.buildCircles@v1.6.0` —
                    // meditation renders as a duration-scaled dawn
                    // CircleAnnotation, NOT a fixed pin dot. Build the
                    // circles into the dedicated manager and exclude
                    // Meditation from the point-pin pass below.
                    meditationCircleManager?.let { medMgr ->
                        if (renderedMeditationCircles.isNotEmpty()) {
                            renderedMeditationCircles.forEach { medMgr.delete(it) }
                        }
                        val dawnArgb =
                            (walkAnnotationColors?.meditation ?: WalkAnnotationColors.Fixed.meditation)
                                .toArgb()
                        renderedMeditationCircles = walkAnnotations
                            .mapNotNull { ann ->
                                val med = ann.kind as? WalkMapAnnotationKind.Meditation
                                    ?: return@mapNotNull null
                                val scale = ((med.durationMillis / 1000.0) / 600.0)
                                    .coerceIn(0.0, 1.0)
                                val radius = 10.0 + (24.0 - 10.0) * scale
                                medMgr.create(
                                    com.mapbox.maps.plugin.annotation.generated
                                        .CircleAnnotationOptions()
                                        .withPoint(
                                            Point.fromLngLat(ann.longitude, ann.latitude),
                                        )
                                        .withCircleRadius(radius)
                                        .withCircleColor(dawnArgb)
                                        .withCircleOpacity(0.7)
                                        .withCircleStrokeColor(dawnArgb)
                                        .withCircleStrokeWidth(2.0)
                                        .withCircleStrokeOpacity(1.0),
                                )
                            }
                    }
                    renderedWalkAnnotations = walkAnnotations
                        .filter { it.kind !is WalkMapAnnotationKind.Meditation }
                        .map { ann ->
                        val bitmap = when (val k = ann.kind) {
                            WalkMapAnnotationKind.StartPoint,
                            WalkMapAnnotationKind.EndPoint ->
                                bitmaps.getValue("startEnd")
                            is WalkMapAnnotationKind.Meditation ->
                                bitmaps.getValue("meditation")
                            is WalkMapAnnotationKind.VoiceRecording ->
                                bitmaps.getValue("voice")
                            is WalkMapAnnotationKind.Photo ->
                                photoPinBitmaps[k.walkPhotoId]
                                    ?: bitmaps.getValue("photo")
                            is WalkMapAnnotationKind.Waypoint ->
                                // iOS-parity per-type glyph (bare,
                                // appearance-stone), same bitmaps the
                                // live walk map uses; null/unknown key
                                // → pin glyph.
                                waypointBitmaps[k.iconKey]
                                    ?: waypointBitmaps.getValue("mappin")
                            is WalkMapAnnotationKind.Whisper ->
                                // iOS parity: bare `wind` (Air) glyph tinted
                                // the category color — not a plain circle.
                                whisperGlyphBitmaps[k.categoryColor] ?: cairnGlyphBitmap
                            is WalkMapAnnotationKind.Cairn ->
                                // iOS parity: bare `mountain.2` (Terrain)
                                // glyph; tier scales via iconSize below.
                                cairnGlyphBitmap
                        }
                        val iconScale = when (val k2 = ann.kind) {
                            is WalkMapAnnotationKind.Whisper -> WHISPER_GLYPH_ICON_SIZE
                            is WalkMapAnnotationKind.Cairn ->
                                cairnGlyphIconSize((k2.tier - 1).coerceAtLeast(0))
                            else -> 1.0
                        }
                        annoMgr.create(
                            PointAnnotationOptions()
                                .withPoint(Point.fromLngLat(ann.longitude, ann.latitude))
                                .withIconImage(bitmap)
                                .withIconSize(iconScale),
                        )
                    }
                    renderedWalkAnnotationsKey = key
                }
            }
            // iOS parity `ActiveWalkView.swift:574-659@db4196e` —
            // proximity pin layer rebuild. Snapshot-rebuild gate on
            // the pin list reference so style-load / zoom re-fires
            // don't churn the manager.
            val proxMgr = proximityManager
            if (proxMgr != null && renderedProximityPins != proximityPins) {
                proxMgr.deleteAll()
                val newIndex = mutableMapOf<String, ProximityPinFilter.Pin>()
                proximityPins.forEach { pin ->
                    // iOS parity `PilgrimMapView.swift:485-503` — bare SF
                    // symbol glyphs (whisper = `wind`/Air tinted category;
                    // cairn = `mountain.2`/Terrain tinted moss), not circles.
                    val bitmap: Bitmap
                    val iconScale: Double
                    when (pin) {
                        is ProximityPinFilter.Pin.Whisper -> {
                            bitmap = whisperGlyphBitmaps[packArgbLong(pin.category.borderColor)]
                                ?: cairnGlyphBitmap
                            iconScale = WHISPER_GLYPH_ICON_SIZE
                        }
                        is ProximityPinFilter.Pin.Cairn -> {
                            bitmap = cairnGlyphBitmap
                            iconScale = cairnGlyphIconSize(pin.tier.ordinal)
                        }
                    }
                    val key = pin.id
                    newIndex[key] = pin
                    proxMgr.create(
                        PointAnnotationOptions()
                            .withPoint(Point.fromLngLat(pin.longitude, pin.latitude))
                            .withIconImage(bitmap)
                            .withIconSize(iconScale)
                            .withTextField(key)
                            // Text is the routing key only — render
                            // invisible (size 0 + transparent).
                            .withTextOpacity(0.0)
                            .withTextSize(0.0),
                    )
                }
                proximityPinIndex = newIndex
                renderedProximityPins = proximityPins
            }
            // Seek fog + pulse ring (iOS parity: applySeekFog at the end of
            // updateUIView, PilgrimMapView.swift:208@c1745e8). The renderer
            // gates on whole-state structural equality and swallows repeat
            // pulse tokens, so this line is a no-op for wander walks and
            // for recompositions that change neither fog nor pulse.
            seekFogRenderer?.apply(
                state = seekFog,
                pulse = seekPulse,
                reduceMotion = reduceMotion,
                lightColorArgb = seekLightColor.toArgb(),
            )
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
                liveRoutePoints = emptyList()
                liveMapboxPoints = emptyList()
                renderedSegments = null
                renderedSegmentColors = null
                renderedLiveLineColor = null
                lastFollowCameraKey = null
                waypointManager = null
                waypointAnnotations = emptyList()
                renderedWaypoints = null
                annotationManager = null
                renderedWalkAnnotations = emptyList()
                renderedWalkAnnotationsKey = null
                meditationCircleManager = null
                renderedMeditationCircles = emptyList()
                proximityManager = null
                renderedProximityPins = emptyList()
                proximityPinIndex = emptyMap()
            }
        },
    )
}

/**
 * Per-waypoint-type icon bitmaps. iOS parity
 * `PilgrimMapView.buildPoints@v1.6.0` —
 * `renderSFSymbol(icon, size: 18, color: .stone)`: a BARE glyph
 * (leaf / eye / heart / chair / sparkles / flag / pin), no circle, no
 * stroke, tinted the appearance-resolved `stone` color (so it tracks
 * light / dark / constellation, unlike the route line which is fixed).
 *
 * A fixed (constant) number of `rememberVectorPainter` calls — one per
 * known [iconKeyToVector] key — keyed into a map; an unknown / null
 * `Waypoint.iconKey` falls back to the "mappin" glyph at the call
 * site. Rebuilt only when the resolved [stoneColor] changes.
 */
@Composable
internal fun rememberWaypointBitmaps(stoneColor: Color): Map<String, Bitmap> {
    val leaf = rememberVectorPainter(iconKeyToVector("leaf"))
    val eye = rememberVectorPainter(iconKeyToVector("eye"))
    val heart = rememberVectorPainter(iconKeyToVector("heart"))
    val seated = rememberVectorPainter(iconKeyToVector("figure.seated.side"))
    val sparkles = rememberVectorPainter(iconKeyToVector("sparkles"))
    val flag = rememberVectorPainter(iconKeyToVector("flag.fill"))
    val pin = rememberVectorPainter(iconKeyToVector("mappin"))
    return remember(stoneColor, leaf, eye, heart, seated, sparkles, flag, pin) {
        mapOf(
            "leaf" to leaf,
            "eye" to eye,
            "heart" to heart,
            "figure.seated.side" to seated,
            "sparkles" to sparkles,
            "flag.fill" to flag,
            "mappin" to pin,
        ).mapValues { (_, painter) -> renderWaypointGlyphBitmap(painter, stoneColor) }
    }
}

/**
 * iOS `renderSFSymbol(icon, size: 18, color: .stone)` — the glyph
 * alone, [tint]-colored, filling the bitmap (no circle / stroke
 * background). Rendered larger than the on-screen size so it stays
 * crisp after Mapbox scales the icon image down.
 */
private fun renderWaypointGlyphBitmap(painter: Painter, tint: Color): Bitmap {
    val size = WAYPOINT_GLYPH_SIZE_PX
    val image = ImageBitmap(size, size)
    val canvas = androidx.compose.ui.graphics.Canvas(image)
    CanvasDrawScope().draw(
        density = Density(1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = canvas,
        size = Size(size.toFloat(), size.toFloat()),
    ) {
        with(painter) {
            draw(Size(size.toFloat(), size.toFloat()), colorFilter = ColorFilter.tint(tint))
        }
    }
    return image.asAndroidBitmap()
}

// ~18dp glyph rendered at 4x for crispness; iOS uses SF-symbol
// pointSize 18 with iconSize 1.0.
private const val WAYPOINT_GLYPH_SIZE_PX = 72

/**
 * iOS parity `PilgrimMapView.swift:485-495@v1.6.0` — whisper map markers
 * are the bare `wind` SF symbol tinted the category color (Android: the
 * `Icons.Outlined.Air` substitution from `CustomPromptIcons`), NOT a
 * filled circle. One tinted bitmap per category, keyed by the color's
 * argb so both the live-walk and post-walk summary maps can look it up.
 */
@Composable
internal fun rememberWhisperGlyphBitmaps(): Map<Long, Bitmap> {
    val air = rememberVectorPainter(Icons.Outlined.Air)
    return remember(air) {
        WhisperCategory.entries.associate { category ->
            packArgbLong(category.borderColor) to renderWaypointGlyphBitmap(air, category.borderColor)
        }
    }
}

/**
 * Pack a Compose [Color] into the same positive ARGB `Long` that
 * [org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotationKind.Whisper.categoryColor]
 * carries, so both map render sites key the whisper-glyph cache identically.
 */
private fun packArgbLong(c: Color): Long {
    val a = (c.alpha * 255f).toInt() and 0xFF
    val r = (c.red * 255f).toInt() and 0xFF
    val g = (c.green * 255f).toInt() and 0xFF
    val b = (c.blue * 255f).toInt() and 0xFF
    return (a.toLong() shl 24) or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
}

/**
 * iOS parity `PilgrimMapView.swift:496-503@v1.6.0` — cairn map markers
 * are the bare `mountain.2` SF symbol (Android: `Icons.Outlined.Terrain`)
 * tinted moss; per-tier size is applied at annotation time via
 * [cairnGlyphIconSize], not by re-rendering the bitmap per tier.
 */
@Composable
internal fun rememberCairnGlyphBitmap(darkMode: Boolean): Bitmap {
    val terrain = rememberVectorPainter(Icons.Outlined.Terrain)
    val moss = if (darkMode) Color(0xFF95A888) else Color(0xFF7A8B6F)
    return remember(terrain, moss) { renderWaypointGlyphBitmap(terrain, moss) }
}

/** Mapbox iconSize multiplier for a cairn glyph by tier ordinal (0..6). */
private fun cairnGlyphIconSize(tierOrdinal: Int): Double =
    (CAIRN_PIN_BASE_DP + tierOrdinal) / 18.0

/** Whisper glyph iconSize (~14dp against the ~18dp glyph baseline). */
private const val WHISPER_GLYPH_ICON_SIZE = 14.0 / 18.0

/**
 * iOS parity `PilgrimMapView.swift:231-251@v1.6.0` — the user-location
 * puck. A [stoneArgb]-filled outer disc with a white@0.9 inner dot
 * (iOS insets the inner ellipse by 4 of 22 pt → ~18% of the radius).
 * No stroke (iOS draws none). The pulsing ring (stone@0.3) is applied
 * separately via the location-component `pulsingColor` setting.
 *
 * `stoneArgb` is resolved from the live theme so it tracks the
 * constellation appearance override + seasonal shifts (unlike the
 * frozen hex-by-darkMode `lineColor` path). Same fixed-4x density
 * factor as the other raw-Bitmap helpers for crisp rendering without a
 * Density read in this raw-Bitmap helper.
 */
internal fun createPuckBitmap(stoneArgb: Int): Bitmap {
    val sizePx = PUCK_SIZE_DP * 4
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val outerRadius = sizePx / 2f
    // iOS: innerRect = rect.insetBy(4 of 22) → inner radius is
    // (1 - 4/11) of the outer radius.
    val innerRadius = outerRadius * (1f - 4f / 11f)
    val outerPaint = Paint().apply {
        isAntiAlias = true
        color = stoneArgb
        style = Paint.Style.FILL
    }
    val innerPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.argb(230, 255, 255, 255)
        style = Paint.Style.FILL
    }
    canvas.drawCircle(cx, cy, outerRadius, outerPaint)
    canvas.drawCircle(cx, cy, innerRadius, innerPaint)
    return bitmap
}

/**
 * Builds the Mapbox [LocationPuck2D] from a stone-tinted bitmap.
 * Extracted so the real `ImageHolder.from` + `LocationPuck2D`
 * constructor path is exercised by a Robolectric test (CLAUDE.md
 * platform-object-builder rule) rather than only on-device.
 */
internal fun buildStonePuck(bitmap: Bitmap): LocationPuck2D =
    LocationPuck2D(topImage = ImageHolder.from(bitmap))

/**
 * iOS parity `PilgrimMapView.swift:389@v1.6.0` —
 * `pointManager.iconAllowOverlap = true`. Mapbox's default symbol
 * collision engine culls any icon that overlaps another symbol; a
 * waypoint dropped at the user's exact location collides with the
 * live location puck and is silently never drawn. Setting
 * `iconAllowOverlap` + `iconIgnorePlacement` opts every pin out of
 * collision so dropped waypoints / proximity pins always render.
 *
 * Extracted so the real Mapbox property-setter path is exercised by a
 * Robolectric test (CLAUDE.md platform-object-builder rule) rather
 * than only on-device.
 */
internal fun allowIconOverlap(manager: PointAnnotationManager) {
    manager.iconAllowOverlap = true
    manager.iconIgnorePlacement = true
}

private const val PUCK_SIZE_DP = 22
private const val CAIRN_PIN_BASE_DP = 12

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

/**
 * iOS parity `ActiveWalkView.swift:597@v1.6.0` —
 * `walkingColor: activeTurning?.uiColor ?? .moss`. The live walk's route
 * walking color is the turning's cardinal accent on a solstice/equinox
 * (the SAME color the celestial-vignette halo wears, so the route and the
 * chip corona read as one), and the fixed walking moss on every other day.
 * Cross-quarter and non-turning days return null from [turningAccentColor]
 * and fall through to moss.
 *
 * [turning] should already be hemisphere-corrected by the caller (the
 * Active Walk screen resolves it via `turningMarkerForToday().forHemisphere`),
 * matching the device-hemisphere source used by the watermark + halo.
 */
internal fun activeWalkRouteColor(turning: SeasonalMarker?, colors: PilgrimColors): Color =
    turningAccentColor(turning, colors) ?: RouteSegmentColors.Fixed.walking

/**
 * Explicit id for the route polyline's backing LineLayer (via
 * [com.mapbox.maps.plugin.annotation.AnnotationConfig]) so the seek fog
 * renderer can insert its circles below the route — the analogue of iOS
 * fog sitting below "pilgrim-route-casing".
 */
internal const val ROUTE_LINE_LAYER_ID = "pilgrim-route-line"

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
