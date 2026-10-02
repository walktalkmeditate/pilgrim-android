// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import androidx.core.view.animation.PathInterpolatorCompat
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.easeTo
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/**
 * The two Mapbox calls a bounds fit makes, extracted so [CameraFitApplier]'s
 * bookkeeping is JVM-testable against a fake. [MapboxCameraFitSurface] is
 * the real surface.
 */
internal interface CameraFitSurface<C> {
    /**
     * Computes the camera that frames [bounds] inside [padding]. May answer
     * synchronously or later; answers null when no usable camera came back.
     */
    fun computeCamera(bounds: MapCameraBounds, padding: CameraFitPaddingDp, onResult: (C?) -> Unit)

    /** Calls [onDone] once, with whether the ease reached its camera (false when it was cut short). */
    fun ease(camera: C, durationMs: Long, onDone: (landed: Boolean) -> Unit)
}

/**
 * Applies [decideCameraFit] to one map and keeps its last-applied record.
 * One instance per `MapView`.
 *
 * iOS computes and eases in one synchronous pass
 * (`PilgrimMapView.swift:277-294@7c200bf`). Android's camera computation
 * waits for the map to have a size, so every pass takes a new generation and
 * a result landing for an older one is dropped: whatever the latest pass
 * decided (a newer fit, an unchanged skip, a no-room skip) wins.
 *
 * A fit is recorded only once its ease lands. One cut short (by a style
 * load moving the camera, say) records nothing, so the next pass fits
 * again rather than skipping as unchanged. While an ease runs, a pass
 * compares against the fit it is easing to.
 */
internal class CameraFitApplier<C>(private val surface: CameraFitSurface<C>) {

    var lastApplied: AppliedCameraFit? = null
        private set

    private var generation = 0L

    /** The fit whose ease is running, as its own instance so a cut ease can't clear a newer one. */
    private var easing: AppliedCameraFit? = null

    fun apply(
        bounds: MapCameraBounds,
        bottomInsetDp: Double,
        viewWidthDp: Double,
        viewHeightDp: Double,
        durationMs: Long,
    ) {
        val requestGeneration = ++generation
        val decision = decideCameraFit(bounds, bottomInsetDp, viewWidthDp, viewHeightDp, easing ?: lastApplied)
        if (decision is CameraFitDecision.Fit) {
            surface.computeCamera(bounds, decision.padding) { camera ->
                // A null camera is iOS's throw: the record stays as it was,
                // so the next pass tries again (iOS #89).
                if (requestGeneration != generation || camera == null) return@computeCamera
                val fit = AppliedCameraFit(bounds, bottomInsetDp)
                easing = fit
                surface.ease(camera, durationMs) { landed ->
                    if (easing !== fit) return@ease
                    easing = null
                    if (landed) lastApplied = fit
                }
            }
        }
    }

    /** Drops any result still in flight, for a map leaving composition. */
    fun cancelPending() {
        generation++
    }
}

/**
 * The real surface. Uses the asynchronous `cameraForCoordinates`, which
 * waits for the map's first size; the synchronous overload returns an empty
 * camera until then.
 */
internal class MapboxCameraFitSurface(
    private val map: MapboxMap,
    private val density: Float,
) : CameraFitSurface<CameraOptions> {

    override fun computeCamera(
        bounds: MapCameraBounds,
        padding: CameraFitPaddingDp,
        onResult: (CameraOptions?) -> Unit,
    ) {
        map.cameraForCoordinates(
            listOf(
                Point.fromLngLat(bounds.swLng, bounds.swLat),
                Point.fromLngLat(bounds.neLng, bounds.neLat),
            ),
            CameraOptions.Builder().build(),
            fitPaddingPx(padding, density),
            // iOS `maxZoom: nil`: no zoom clamp on a fit.
            null,
            null,
        ) { camera ->
            // The SDK answers an empty camera on an internal error (padding
            // the view cannot hold, say).
            onResult(camera.takeIf { it.center != null && it.zoom != null })
        }
    }

    override fun ease(camera: CameraOptions, durationMs: Long, onDone: (landed: Boolean) -> Unit) {
        map.easeTo(
            camera,
            easeOutCameraAnimation(durationMs),
            object : AnimatorListenerAdapter() {
                private var cut = false

                override fun onAnimationCancel(animation: Animator) {
                    cut = true
                }

                // Also called after a cancel, so it reports either way.
                override fun onAnimationEnd(animation: Animator) {
                    onDone(!cut)
                }
            },
        )
    }
}

internal fun fitPaddingPx(padding: CameraFitPaddingDp, density: Float): EdgeInsets =
    EdgeInsets(
        padding.top * density,
        padding.left * density,
        padding.bottom * density,
        padding.right * density,
    )

/**
 * iOS `mapView.camera.ease(to:duration:)` with the SDK's default
 * `curve: .easeOut` (mapbox-maps-ios@v11.20.0
 * `CameraAnimationsManager.swift:61-66`), UIKit's cubic-bezier
 * (0, 0, 0.58, 1). Android's own default is FastOutSlowIn.
 */
internal fun easeOutCameraAnimation(durationMs: Long): MapAnimationOptions =
    MapAnimationOptions.Builder()
        .duration(durationMs)
        .interpolator(cameraEaseOut())
        .build()

private fun cameraEaseOut(): TimeInterpolator = PathInterpolatorCompat.create(0f, 0f, 0.58f, 1f)
