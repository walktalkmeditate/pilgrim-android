// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

/**
 * Pure data struct describing a seal's procedural geometry — ring
 * list, radial lines, arc segments, decorative dots, and the overall
 * rotation. Computed once from a [SealSpec]'s 32-byte hash via
 * [sealGeometry]. All radii and widths are unit-agnostic (fractions
 * of the canvas outer radius or canvas size); the renderer scales
 * them at draw time. Port of iOS's `SealGeometry.swift` but with our
 * FNV-1a-stretched seed instead of SHA-256.
 */
internal data class SealGeometry(
    val rotationDeg: Float,
    val rings: List<Ring>,
    val radialLines: List<Radial>,
    val arcs: List<ArcSegment>,
    val dots: List<Dot>,
)

internal data class Ring(
    /** Fraction of outer radius, roughly 0.25..1.0. */
    val radiusFrac: Float,
    val strokeWidthFrac: Float,
    val opacity: Float,
    /**
     * null → solid; non-null → on/off dash lengths as fractions of
     * the canvas size (so the dash pattern scales with the seal).
     *
     * Stored as `List<Float>` (not `FloatArray`) so the enclosing
     * `data class`'s auto-generated `equals`/`hashCode` uses content
     * equality. The renderer converts to `FloatArray` at draw time
     * via `toFloatArray()` for `PathEffect.dashPathEffect`.
     */
    val dashPattern: List<Float>?,
)

internal data class Radial(
    val angleDeg: Float,
    /** Inner-end radius as fraction of outer radius. 0.25..0.4 typical. */
    val innerFrac: Float,
    /** Outer-end radius as fraction of outer radius. 0.85..1.0 typical. */
    val outerFrac: Float,
    val strokeWidthFrac: Float,
    val opacity: Float,
)

internal data class ArcSegment(
    val startAngleDeg: Float,
    val sweepDeg: Float,
    /** Radius as fraction of outer radius. 0.55..0.80 typical. */
    val radiusFrac: Float,
    val strokeWidthFrac: Float,
    val opacity: Float,
)

internal data class Dot(
    val angleDeg: Float,
    /** Distance from center as fraction of outer radius. 0.3..0.8 typical. */
    val distanceFrac: Float,
    /** Dot radius as fraction of canvas size. */
    val radiusFrac: Float,
    val opacity: Float,
)

// Bounds from iOS's `SealGeometry.swift`.
private const val BASE_RING_MIN = 3
private const val BASE_RING_RANGE = 3           // 3..5 base before ratio bumps
private const val MAX_RINGS = 8

private const val BASE_RADIAL_MIN = 4
private const val BASE_RADIAL_RANGE = 5         // 4..8 base
private const val MAX_RADIALS = 12

/**
 * Build a full seal geometry from the spec's deterministic seed.
 * Pure function — the renderer calls `remember(spec) { sealGeometry(spec) }`
 * so the structure is computed once per spec and reused across
 * recompositions.
 */
internal fun sealGeometry(spec: SealSpec): SealGeometry {
    val b = sealHashBytes(spec)
    return SealGeometry(
        // Divide by 256 (not 255) so the 256 distinct byte values map to
        // the half-open interval [0, 360). Dividing by 255 would produce
        // exactly 360° for byte=0xFF, which is visually identical to 0°
        // but violates the [0, 360) contract and would break any future
        // tightened test.
        rotationDeg = (b.u(0).toFloat() / 256f) * 360f,
        rings = buildRings(b),
        radialLines = buildRadials(b),
        arcs = buildArcs(b),
        dots = buildDots(b),
    )
}

private fun buildRings(b: ByteArray): List<Ring> {
    val count = (BASE_RING_MIN + b.u(1) % BASE_RING_RANGE).coerceAtMost(MAX_RINGS)
    return (0 until count).map { i ->
        val baseFrac = 1.0f - i * (0.8f / count)
        val jitter = (b.u(2 + i % 6) / 255f - 0.5f) * 0.08f
        Ring(
            radiusFrac = (baseFrac + jitter).coerceIn(0.25f, 1.0f),
            strokeWidthFrac = if (i == 0) 0.004f else 0.0016f + (b.u(6 + i % 4) % 3) * 0.0006f,
            opacity = (0.9f - i * 0.05f).coerceIn(0.35f, 1.0f),
            dashPattern = if (i == 0 || b.u(6 + i % 4) % 3 == 0) {
                null
            } else {
                val dashLen = 0.008f + (b.u(8 + i % 4) % 5) * 0.004f
                val gapLen = 0.004f + (b.u(10 + i % 4) % 4) * 0.003f
                listOf(dashLen, gapLen)
            },
        )
    }
}

private fun buildRadials(b: ByteArray): List<Radial> {
    val count = (BASE_RADIAL_MIN + b.u(8) % BASE_RADIAL_RANGE).coerceAtMost(MAX_RADIALS)
    val angleStep = 360f / count
    return (0 until count).map { i ->
        val angleJitter = (b.u(9 + i % 8) / 255f - 0.5f) * (angleStep * 0.2f)
        Radial(
            angleDeg = (i * angleStep + angleJitter).floorMod(360f),
            innerFrac = 0.25f + (b.u(16 + i % 8) / 255f) * 0.15f,
            outerFrac = 0.85f + (b.u(20 + i % 8) / 255f) * 0.15f,
            strokeWidthFrac = 0.001f + (b.u(i % 8) % 3) * 0.0006f,
            opacity = 0.5f + (b.u((i + 12) % 32) / 255f) * 0.3f,
        )
    }
}

private fun buildArcs(b: ByteArray): List<ArcSegment> {
    val count = 2 + b.u(24) % 3    // 2..4
    return (0 until count).map { i ->
        ArcSegment(
            startAngleDeg = (b.u(24 + i % 8) / 255f) * 360f,
            sweepDeg = 20f + (b.u(26 + i % 6) / 255f) * 60f,
            radiusFrac = 0.55f + (b.u(28 + i % 4) / 255f) * 0.25f,
            strokeWidthFrac = 0.0016f,
            opacity = 0.65f,
        )
    }
}

private fun buildDots(b: ByteArray): List<Dot> {
    val count = 3 + b.u(28) % 5    // 3..7
    val angularStep = 47f           // golden-angle-ish spacing from iOS
    return (0 until count).map { i ->
        Dot(
            angleDeg = ((b.u(29) / 255f) * 360f + i * angularStep).floorMod(360f),
            distanceFrac = 0.3f + (b.u(29 + i % 3) / 255f) * 0.5f,
            radiusFrac = 0.002f + (b.u(30 + i % 2) % 3) * 0.001f,
            opacity = 0.6f,
        )
    }
}

/**
 * Kotlin's `%` can return negative values for negative left operands.
 * Angles always want `[0, divisor)`.
 */
private fun Float.floorMod(divisor: Float): Float {
    val r = this % divisor
    return if (r < 0f) r + divisor else r
}
