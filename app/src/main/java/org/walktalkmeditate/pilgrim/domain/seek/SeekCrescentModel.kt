// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

/**
 * Pure math for the crescent of light on the puck's rim — span buckets,
 * flare/rest opacities, and the guiding-point derivation. Rendering lives
 * in `ui/walk/map/SeekCrescentRenderer.kt`. Port spec
 * `docs/parity/2026-07-14-port-seek-crescent-u7.md` (iOS names this
 * "wisp"; `SeekFogModel.swift:127-178@c1745e8` +
 * `PilgrimMapView+SeekWisp.swift:32-44@c1745e8`).
 */
object SeekCrescentModel {

    /**
     * The crescent opens as the fog nears: a narrow sliver far out, a
     * full curve close in. Keyed to the fog buckets (nearest first) so
     * span changes inherit their boundary hysteresis and never thrash.
     */
    val SPAN_DEGREES_NEAR_TO_FAR = listOf(96.0, 86.0, 72.0, 60.0, 48.0)

    /**
     * Between pulses the crescent rests dim, almost asleep; each pulse
     * swells it. Under reduce-motion it holds steady instead.
     */
    const val REST_OPACITY = 0.55
    const val STEADY_OPACITY = 0.8

    /** Flare peak grows with closeness; an aligned pulse outshines both. */
    const val FLARE_PEAK_BASE = 0.75
    const val FLARE_PEAK_CLOSENESS_SPAN = 0.15
    const val ALIGNED_FLARE_PEAK = 1.0

    fun spanDegrees(bucket: Int?): Double {
        if (bucket == null) return SPAN_DEGREES_NEAR_TO_FAR.last()
        val clamped = bucket.coerceIn(1, SPAN_DEGREES_NEAR_TO_FAR.size)
        return SPAN_DEGREES_NEAR_TO_FAR[clamped - 1]
    }

    /**
     * The crescent shows only while guiding with a live fix — arrival and
     * reveal hide it (the fog dissolve owns those moments). It rides the
     * walker's own position, aimed at the clearing, bearing normalized to
     * `[0, 360)`.
     */
    fun crescentPoint(
        walkerPosition: SeekPoint?,
        clearingCenter: SeekPoint,
        phase: SeekEnginePhase,
    ): SeekFogState.Crescent? {
        if (phase != SeekEnginePhase.GUIDING || walkerPosition == null) return null
        var bearing = SeekChainGenerator.bearingDegrees(walkerPosition, clearingCenter)
        if (bearing < 0) bearing += 360.0
        return SeekFogState.Crescent(position = walkerPosition, bearingDegrees = bearing)
    }

    fun flarePeak(aligned: Boolean, closeness: Double): Double =
        if (aligned) {
            ALIGNED_FLARE_PEAK
        } else {
            FLARE_PEAK_BASE + FLARE_PEAK_CLOSENESS_SPAN * closeness.coerceIn(0.0, 1.0)
        }

    fun restingOpacity(reduceMotion: Boolean): Double =
        if (reduceMotion) STEADY_OPACITY else REST_OPACITY
}

/**
 * The crescent is a pointer to something beyond sight: the moment the fog
 * itself is on screen — zoomed out to it or walked near it — the pointer
 * is redundant and releases. Screen-space intersection with a hysteresis
 * band so a fog edge grazing the viewport during a pan cannot flicker it.
 * All values in physical screen pixels (the unit `pixelForCoordinate` and
 * the map's size share). iOS `SeekWispVisibilityModel`
 * (`SeekFogModel.swift:232-267@c1745e8`).
 */
object SeekCrescentVisibilityModel {

    /**
     * The fog must reach this far *into* the viewport before the crescent
     * releases, and retreat this far *beyond* the edge before it returns.
     */
    const val RELEASE_INSET_PX = 24.0
    const val RETURN_OUTSET_PX = 24.0

    /**
     * Returns the new released state given the previous one. Null center
     * means the fog could not be projected onto the screen — Mapbox's
     * `pixelForCoordinate` collapses every off-view coordinate to
     * `(-1, -1)` (verified against SDK v11.11.0 source, spec B10),
     * losing HOW far past the edge the fog lies. An unreleased crescent
     * therefore stays shown (an unprojectable fog is not visible), and a
     * released one HOLDS released: the clamp fires the instant the
     * center crosses the raw edge, so flipping back on null would bypass
     * the ±24 px hysteresis band and strobe the handoff exhale on every
     * edge crossing during a pan. The hold lasts until the crescent
     * resets (arrival/reveal/walk end clear `released` via the
     * renderer's remove path).
     */
    fun shouldRelease(
        wasReleased: Boolean,
        fogCenterX: Double?,
        fogCenterY: Double?,
        fogRadiusPx: Double,
        viewWidthPx: Double,
        viewHeightPx: Double,
    ): Boolean {
        if (fogCenterX == null || fogCenterY == null) return wasReleased
        if (viewWidthPx <= 0.0 || viewHeightPx <= 0.0 ||
            !fogCenterX.isFinite() || !fogCenterY.isFinite() || !fogRadiusPx.isFinite()
        ) {
            return wasReleased
        }
        val inset = if (wasReleased) -RETURN_OUTSET_PX else RELEASE_INSET_PX
        return circleIntersects(
            centerX = fogCenterX,
            centerY = fogCenterY,
            radius = fogRadiusPx,
            left = inset,
            top = inset,
            right = viewWidthPx - inset,
            bottom = viewHeightPx - inset,
        )
    }

    fun circleIntersects(
        centerX: Double,
        centerY: Double,
        radius: Double,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Boolean {
        // A rect the inset has swallowed (tiny views) never intersects —
        // matches CGRect.insetBy producing .null/.isEmpty on iOS.
        if (right <= left || bottom <= top) return false
        val dx = maxOf(left - centerX, 0.0, centerX - right)
        val dy = maxOf(top - centerY, 0.0, centerY - bottom)
        return dx * dx + dy * dy <= radius * radius
    }
}
