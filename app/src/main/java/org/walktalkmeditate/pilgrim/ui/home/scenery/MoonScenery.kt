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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
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
 * left entirely. Plus 6 white moonlight rays, 6 stars (each with its own
 * twinkle speed), 2 drifting parchment-ish clouds, and a halo glow that
 * pulses every 3 s.
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

    val timeSec by sceneryTimeSeconds()

    Canvas(modifier = Modifier.size(sizeDp * 2f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val s = sizeDp.toPx()

        // Moonlight rays
        val rayPulse = ((sin(timeSec * 0.3) + 1.0) / 2.0 * 0.04 + 0.02).toFloat()
        for (i in 0 until 6) {
            val angle = (i * 60.0 + sin(timeSec * 0.2) * 5.0).toFloat()
            translate(left = cx, top = cy) {
                rotate(angle, pivot = Offset.Zero) {
                    drawRect(
                        color = Color.White.copy(alpha = rayPulse.coerceIn(0f, 1f)),
                        topLeft = Offset(-s * 0.01f, -s * 0.6f - s * 0.15f),
                        size = GeomSize(s * 0.02f, s * 0.6f),
                    )
                }
            }
        }

        // Halo glow — pulses every 3 s.
        val haloPulse = ((sin(timeSec * (PI / 3.0)) + 1.0) / 2.0).toFloat()
        val haloAlpha = 0.05f * (0.4f + haloPulse * 0.4f)
        drawCircle(
            color = tintColor.copy(alpha = haloAlpha),
            radius = s * 0.9f,
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
                drawCrescent(GeomSize(mSize * 1.06f, mSize * 1.06f), tintColor.copy(alpha = 0.10f))
            }

            // Main crescent
            translate(left = cx - mSize / 2f, top = cy - mSize / 2f) {
                drawCrescent(GeomSize(mSize, mSize), tintColor.copy(alpha = 0.35f))
            }

            // Inner highlight (0.92×, white-ish)
            translate(left = cx - mSize * 0.92f / 2f - 1f, top = cy - mSize * 0.92f / 2f - 1f) {
                drawCrescent(GeomSize(mSize * 0.92f, mSize * 0.92f), Color.White.copy(alpha = 0.10f))
            }
        }

        // Stars
        val starData = listOf(
            Triple(-0.35f, -0.30f, 2.1),
            Triple(0.40f, -0.35f, 3.0),
            Triple(-0.25f, 0.30f, 1.7),
            Triple(0.35f, 0.25f, 2.5),
            Triple(-0.40f, 0.05f, 1.9),
            Triple(0.15f, -0.40f, 2.8),
        )
        for ((x, y, speed) in starData) {
            val twinkle = ((sin(timeSec * speed) + 1.0) / 2.0).toFloat()
            drawCircle(
                color = Color.White.copy(alpha = (0.15f + twinkle * 0.25f).coerceIn(0f, 1f)),
                radius = s * 0.02f,
                center = Offset(cx + s * x, cy + s * y),
            )
        }

        // Drifting clouds — parchment-ish ellipses
        val clouds = listOf(
            Triple(-0.05f, 0.15, 0.5f),
            Triple(0.10f, 0.10, 0.35f),
        )
        for ((yOff, speed, width) in clouds) {
            val drift = (sin(timeSec * speed) * s * 0.3).toFloat()
            val fadeEdge = ((cos(timeSec * speed * 0.8) + 1.0) / 2.0 * 0.06 + 0.03).toFloat()
            drawOval(
                color = parchmentColor.copy(alpha = fadeEdge.coerceIn(0f, 1f)),
                topLeft = Offset(cx - s * width / 2f + drift, cy + s * yOff - s * 0.06f),
                size = GeomSize(s * width, s * 0.12f),
            )
        }
    }
}

/** iOS `phaseScale` — constant 0.9 since the real-phase carve landed. */
internal const val MOON_PHASE_SCALE = 0.9f

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

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCrescent(
    size: GeomSize,
    color: Color,
) {
    val (outer, inner) = moonOuterAndInner(size)
    val crescent = Path().apply { op(outer, inner, PathOperation.Difference) }
    drawPath(path = crescent, color = color)
}
