// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.map

import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/**
 * Padding for a bounds fit, in dp (iOS points). Converted to pixels only at
 * the Mapbox `EdgeInsets` boundary.
 */
internal data class CameraFitPaddingDp(
    val top: Double,
    val left: Double,
    val bottom: Double,
    val right: Double,
)

/**
 * The bounds a fit last eased to, with the RAW (unclamped) inset it was fit
 * under. iOS `coordinator.lastAppliedBounds` + `lastAppliedBoundsInset`
 * (`PilgrimMapView.swift:659-662@7c200bf`); a null record forces the first
 * application through.
 */
internal data class AppliedCameraFit(
    val bounds: MapCameraBounds,
    val bottomInsetDp: Double,
)

internal sealed interface CameraFitDecision {
    /** The bounds and the raw inset both match the last applied fit. */
    data object SkipUnchanged : CameraFitDecision

    /** The padding leaves no room in the view; nothing is recorded. */
    data object SkipNoRoom : CameraFitDecision

    data class Fit(val padding: CameraFitPaddingDp) : CameraFitDecision
}

/**
 * iOS parity `PilgrimMapView.swift:256-295@7c200bf` (the #81/#89 fit rules
 * F1-F4 in the Stage 21-0 port spec):
 *
 * ```swift
 * let changed = context.coordinator.lastAppliedBounds != bounds
 *     || context.coordinator.lastAppliedBoundsInset != bottomInset
 * let roomForRoute: CGFloat = 160
 * let headroom = max(0, mapView.bounds.height - roomForRoute - 80)
 * let padding = UIEdgeInsets(top: 40, left: 30,
 *                            bottom: 40 + min(bottomInset, headroom), right: 30)
 * let fits = mapView.bounds.height > padding.top + padding.bottom
 *     && mapView.bounds.width > padding.left + padding.right
 * if changed && fits { ... }
 * ```
 */
internal fun decideCameraFit(
    bounds: MapCameraBounds,
    bottomInsetDp: Double,
    viewWidthDp: Double,
    viewHeightDp: Double,
    lastApplied: AppliedCameraFit?,
): CameraFitDecision {
    // The raw inset is compared exactly, as iOS ships it: a card-height
    // change past the clamp still re-eases (pilgrim-ios #94, matched).
    val changed = lastApplied == null ||
        !sameBounds(lastApplied.bounds, bounds) ||
        lastApplied.bottomInsetDp != bottomInsetDp
    if (!changed) return CameraFitDecision.SkipUnchanged

    val headroom = maxOf(0.0, viewHeightDp - FIT_ROOM_FOR_ROUTE_DP - FIT_VERTICAL_BASE_DP)
    val padding = CameraFitPaddingDp(
        top = FIT_PADDING_TOP_DP,
        left = FIT_PADDING_SIDE_DP,
        bottom = FIT_PADDING_BOTTOM_DP + minOf(bottomInsetDp, headroom),
        right = FIT_PADDING_SIDE_DP,
    )
    val fits = viewHeightDp > padding.top + padding.bottom &&
        viewWidthDp > padding.left + padding.right
    return if (fits) CameraFitDecision.Fit(padding) else CameraFitDecision.SkipNoRoom
}

/**
 * iOS `MapCameraBounds.==` (`PilgrimAnnotation.swift:60-70@7c200bf`): plain
 * `==` on the four doubles. Written out because a data class's `equals`
 * compares doubles with `compare`, which splits 0.0 from -0.0.
 */
private fun sameBounds(a: MapCameraBounds, b: MapCameraBounds): Boolean =
    a.swLat == b.swLat && a.swLng == b.swLng && a.neLat == b.neLat && a.neLng == b.neLng

internal const val FIT_PADDING_TOP_DP = 40.0
internal const val FIT_PADDING_SIDE_DP = 30.0
internal const val FIT_PADDING_BOTTOM_DP = 40.0

/** iOS `roomForRoute`: the height a fit always leaves the route. */
internal const val FIT_ROOM_FOR_ROUTE_DP = 160.0

/** The 40 top plus the 40 base bottom in iOS's `headroom` arithmetic. */
private const val FIT_VERTICAL_BASE_DP = FIT_PADDING_TOP_DP + FIT_PADDING_BOTTOM_DP

/** iOS `var cameraDuration: TimeInterval = 0.4` (`PilgrimMapView.swift:46@7c200bf`). */
internal const val DEFAULT_CAMERA_FIT_EASE_MS = 400L
