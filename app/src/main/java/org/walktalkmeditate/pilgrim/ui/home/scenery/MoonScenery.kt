// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import java.time.Instant
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Port of `SceneryItemView.swift` moon branch — the real moon of that
 * night. [MoonCalc] gives the illuminated fraction and the waxing half
 * orients the lit limb (waxing lights the right, waning the left). A
 * shadow disc slides off the moon as illumination grows; at full it has
 * left entirely. Plus 6 white moonlight rays fanning from a shared base
 * just below the disc, a breathing halo, 6 stars (each with its own
 * twinkle speed), and 2 drifting parchment clouds.
 *
 * iOS blurs (rays 2pt, halo 8pt, clouds 3pt) have no cheap Canvas
 * equivalent; the package idiom (LanternScenery glow) stands them in
 * with gradient feathering — a soft cross-ray gradient for the shafts,
 * flat-core radial gradients for halo and clouds. None of the glow
 * layers are phase-gated on iOS: rays, halo, stars, and clouds show at
 * every phase; only the carve reads the phase.
 */
@Composable
internal fun MoonScenery(
    sizeDp: Dp,
    tintColor: Color,
    walkDateMs: Long,
    parchmentColor: Color,
) {
    // Astronomy hoisted to composition — once per walkDate, never per
    // frame (the iOS P4 hoisting idiom).
    val moonPhase = remember(walkDateMs) {
        MoonCalc.moonPhase(Instant.ofEpochMilli(walkDateMs))
    }
    val illumination = moonPhase.illumination.toFloat()
    val waxing = moonPhase.isWaxing
    val carveCache = remember(walkDateMs) { MoonCarveCache(illumination, waxing) }
    val crescentCache = remember { MoonCrescentCache() }

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        // Moonlight rays — six shafts sharing one base point 0.15·s below
        // the disc center (iOS rotates each ray about its bottom edge and
        // then lifts the fan by 0.15·s, so every base lands on the same
        // point in unrotated space).
        val rayPulse = moonRayPulseAlpha(timeSec)
        val hazeW = s * MOON_RAY_HAZE_WIDTH_FRACTION
        val rayColor = Color.White.copy(alpha = rayPulse.coerceIn(0f, 1f))
        for (i in 0 until MOON_RAY_COUNT) {
            translate(left = cx, top = cy + s * MOON_RAY_BASE_DROP_FRACTION) {
                rotate(moonRayAngleDegrees(i, timeSec), pivot = Offset.Zero) {
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, rayColor, Color.Transparent),
                            startX = -hazeW / 2f,
                            endX = hazeW / 2f,
                        ),
                        topLeft = Offset(-hazeW / 2f, -s * MOON_RAY_LENGTH_FRACTION),
                        size = GeomSize(hazeW, s * MOON_RAY_LENGTH_FRACTION),
                    )
                }
            }
        }

        // Halo glow — breathes dim-to-bright over 6 s (iOS phaseAnimator
        // 0.4↔0.8 × easeInOut 3.0 s). Flat core + feathered rim reads as
        // the iOS blur(8) disc, not a hard circle.
        val haloAlpha = moonHaloAlpha(timeSec)
        val haloRadius = s * MOON_HALO_RADIUS_FRACTION
        drawCircle(
            brush = Brush.radialGradient(
                0.00f to tintColor.copy(alpha = haloAlpha),
                0.75f to tintColor.copy(alpha = haloAlpha),
                1.00f to Color.Transparent,
                center = Offset(cx, cy),
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = Offset(cx, cy),
        )

        // Two-disc phase carve clips the moon layers: the shadow slides
        // off as illumination grows. Rays / stars / clouds / halo stay
        // un-carved, matching the iOS mask scope.
        val mSize = s * MOON_PHASE_SCALE
        val carve = carveCache.pathFor(center = Offset(cx, cy), diameter = mSize)
        clipPath(carve) {
            // Crescent ghost (1.06×, low alpha, slight offset).
            translate(left = cx - mSize * 1.06f / 2f + 1f, top = cy - mSize * 1.06f / 2f + 1f) {
                drawPath(crescentCache.pathFor(mSize, scale = 1.06f), tintColor.copy(alpha = 0.10f))
            }

            // Main crescent
            translate(left = cx - mSize / 2f, top = cy - mSize / 2f) {
                drawPath(crescentCache.pathFor(mSize, scale = 1f), tintColor.copy(alpha = 0.35f))
            }

            // Inner highlight (0.92×, white-ish)
            translate(left = cx - mSize * 0.92f / 2f - 1f, top = cy - mSize * 0.92f / 2f - 1f) {
                drawPath(crescentCache.pathFor(mSize, scale = 0.92f), Color.White.copy(alpha = 0.10f))
            }
        }

        // Stars
        for ((x, y, speed) in MOON_STARS) {
            val twinkle = ((sin(timeSec * speed) + 1.0) / 2.0).toFloat()
            drawCircle(
                color = Color.White.copy(alpha = (0.15f + twinkle * 0.25f).coerceIn(0f, 1f)),
                radius = s * 0.02f,
                center = Offset(cx + s * x, cy + s * y),
            )
        }

        // Drifting clouds — parchment wisps. A circle under an
        // anisotropic scale gives an elliptical flat-core gradient,
        // standing in for the iOS blur(3) ellipse.
        for ((yOff, speed, width) in MOON_CLOUDS) {
            val drift = (sin(timeSec * speed) * s * 0.3).toFloat()
            val fadeEdge = ((cos(timeSec * speed * 0.8) + 1.0) / 2.0 * 0.06 + 0.03).toFloat()
            val cloudCenter = Offset(cx + drift, cy + s * yOff)
            val ry = s * 0.06f
            val rx = s * width / 2f
            scale(scaleX = rx / ry, scaleY = 1f, pivot = cloudCenter) {
                drawCircle(
                    brush = Brush.radialGradient(
                        0.00f to parchmentColor.copy(alpha = fadeEdge.coerceIn(0f, 1f)),
                        0.55f to parchmentColor.copy(alpha = fadeEdge.coerceIn(0f, 1f)),
                        1.00f to Color.Transparent,
                        center = cloudCenter,
                        radius = ry,
                    ),
                    radius = ry,
                    center = cloudCenter,
                )
            }
        }
    }
}

/** iOS `phaseScale` — constant 0.9 since the real-phase carve landed. */
internal const val MOON_PHASE_SCALE = 0.9f

/** Six moonlight shafts (iOS `moonRays`, `SceneryItemView.swift:522-534@c1745e8`). */
internal const val MOON_RAY_COUNT = 6

/** Ray length — iOS frame height `size * 0.6`. */
internal const val MOON_RAY_LENGTH_FRACTION = 0.6f

/** The shared ray base sits `0.15·size` below the disc center. */
internal const val MOON_RAY_BASE_DROP_FRACTION = 0.15f

/**
 * Cross-ray footprint of the gradient stand-in for iOS `blur(radius: 2)`
 * — 3× the 0.02·size core rectangle, fading to transparent at both
 * edges so the peak alpha stays the iOS `rayPulse` value.
 */
internal const val MOON_RAY_HAZE_WIDTH_FRACTION = 0.06f

/** Halo radius — iOS frame `size * 1.8` diameter. */
internal const val MOON_HALO_RADIUS_FRACTION = 0.9f

/** iOS `stars` data — (x, y) in size fractions, twinkle speed. */
private val MOON_STARS = listOf(
    Triple(-0.35f, -0.30f, 2.1),
    Triple(0.40f, -0.35f, 3.0),
    Triple(-0.25f, 0.30f, 1.7),
    Triple(0.35f, 0.25f, 2.5),
    Triple(-0.40f, 0.05f, 1.9),
    Triple(0.15f, -0.40f, 2.8),
)

/** iOS `driftingClouds` data — (yOff, speed, width) in size fractions. */
private val MOON_CLOUDS = listOf(
    Triple(-0.05f, 0.15, 0.5f),
    Triple(0.10f, 0.10, 0.35f),
)

/**
 * Ray direction in degrees clockwise from straight up: a 60° fan with a
 * shared ±5° wobble (iOS `Double(i) * 60.0 + sin(time * 0.2) * 5`).
 */
internal fun moonRayAngleDegrees(index: Int, timeSec: Float): Float =
    (index * 60.0 + sin(timeSec * 0.2) * 5.0).toFloat()

/**
 * Ray brightness breathing between 0.02 and 0.06
 * (iOS `rayPulse = (sin(time * 0.3) + 1) / 2 * 0.04 + 0.02`).
 */
internal fun moonRayPulseAlpha(timeSec: Float): Float =
    ((sin(timeSec * 0.3) + 1.0) / 2.0 * 0.04 + 0.02).toFloat()

/**
 * Halo alpha breathing 0.02→0.04→0.02 on a 6 s cycle — iOS layers a
 * 0.4↔0.8 phaseAnimator (easeInOut, 3.0 s per leg) over a 0.05-alpha
 * fill. The raised cosine starts at the dim end so the frozen
 * Reduce-Motion frame (t = 0) matches the iOS collapsed phase
 * (`animationPhases == [false]` → opacity 0.4 → 0.02).
 */
internal fun moonHaloAlpha(timeSec: Float): Float =
    (0.05 * (0.4 + (1.0 - cos(timeSec * PI / 3.0)) / 2.0 * 0.4)).toFloat()

/**
 * Signed carve offset in disc diameters: waxing slides the shadow left
 * (lit limb right), waning right (lit limb left). The 0.08 floor keeps
 * even a new-moon walk from losing its moon entirely
 * (iOS `SceneryItemView.swift:502-514@c1745e8`).
 */
internal fun moonCarveOffsetFraction(illumination: Float, waxing: Boolean): Float =
    (if (waxing) -1f else 1f) * max(illumination, 0.08f)

/**
 * Composition-hoisted carve holder (the P4 idiom, one step further):
 * the draw block re-runs every frame chasing the animated clock, but
 * the carve depends only on the remembered phase and the canvas
 * geometry — canvas size is unknowable at composition, so the path is
 * built lazily in draw and rebuilt only when the geometry changes,
 * instead of running 2×addOval + a boolean Difference per frame.
 */
internal class MoonCarveCache(
    private val illumination: Float,
    private val waxing: Boolean,
) {
    private var cachedCenter: Offset = Offset.Zero
    private var cachedDiameter: Float = 0f
    private var cachedPath: Path? = null

    fun pathFor(center: Offset, diameter: Float): Path {
        val hit = cachedPath
        if (hit != null && center == cachedCenter && diameter == cachedDiameter) return hit
        cachedCenter = center
        cachedDiameter = diameter
        return moonPhaseCarvePath(center, diameter, illumination, waxing)
            .also { cachedPath = it }
    }
}

/**
 * Base disc minus offset shadow disc. iOS carves with a
 * destination-out compositing mask; a path Difference is the same
 * hard-edge geometry and stays a testable pure function.
 */
internal fun moonPhaseCarvePath(
    center: Offset,
    diameter: Float,
    illumination: Float,
    waxing: Boolean,
): Path {
    val radius = diameter / 2f
    val base = Path().apply {
        addOval(Rect(center - Offset(radius, radius), GeomSize(diameter, diameter)))
    }
    val shadowCenter = center + Offset(moonCarveOffsetFraction(illumination, waxing) * diameter, 0f)
    val shadow = Path().apply {
        addOval(Rect(shadowCenter - Offset(radius, radius), GeomSize(diameter, diameter)))
    }
    return Path().apply { op(base, shadow, PathOperation.Difference) }
}

/** MoonShape.swift as a pure path — outer disc minus the offset bite. */
internal fun moonCrescentPath(size: GeomSize): Path {
    val (outer, inner) = moonOuterAndInner(size)
    return Path().apply { op(outer, inner, PathOperation.Difference) }
}

/**
 * Draw-time cache for the three crescent layers (ghost 1.06× / main 1× /
 * highlight 0.92×) — same rationale as [MoonCarveCache]: the paths
 * depend only on canvas geometry, so build each scale once per size
 * instead of running `moonOuterAndInner` + a boolean Difference three
 * times per frame.
 */
internal class MoonCrescentCache {
    private var cachedBaseDiameter: Float = 0f
    private val byScale = mutableMapOf<Float, Path>()

    fun pathFor(baseDiameter: Float, scale: Float): Path {
        if (baseDiameter != cachedBaseDiameter) {
            byScale.clear()
            cachedBaseDiameter = baseDiameter
        }
        return byScale.getOrPut(scale) {
            val d = baseDiameter * scale
            moonCrescentPath(GeomSize(d, d))
        }
    }
}
