# Stage 4-A Implementation Plan — Goshuin Seal Renderer

Spec: `docs/superpowers/specs/2026-04-19-stage-4a-seal-renderer-design.md`.

Ship a pure `@Composable SealRenderer` + debug preview screen. 5 layers: rings, radial lines, arcs, dots, center text. Seed via FNV-1a → SplitMix64 → 32 bytes. Tint via SeasonalColorEngine.Intensity.Full.

---

## Task 1 — `SealSpec.kt` (input data + hash + byte stretcher)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealSpec.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Per-walk input for the goshuin seal renderer. Same shape as
 * [org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyStrokeSpec]
 * (uuid + startMillis + distance seed + pre-resolved color), plus a
 * pre-formatted [displayDistance] string for the seal's center-text
 * layer. The caller resolves [ink] via
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
 * at `Intensity.Full`.
 */
@Immutable
data class SealSpec(
    val uuid: String,
    val startMillis: Long,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val displayDistance: String,    // e.g. "5.2", "420" — caller formats
    val unitLabel: String,          // "km" or "m", locale-aware
    val ink: Color,
)

/**
 * FNV-1a 64-bit hash, seeded from uuid (UTF-16 code units),
 * startMillis, integer-meters distance. Byte-wise copy of
 * [org.walktalkmeditate.pilgrim.ui.design.calligraphy.fnv1aHash] —
 * see the Stage 4-A spec for why we don't extract a shared helper yet.
 *
 * NOT byte-identical to the iOS seal hash (iOS uses SHA-256 over
 * route+distance+durations+date). Same divergence story as the
 * calligraphy port.
 */
internal fun fnv1aHash(spec: SealSpec): Long {
    val prime: ULong = 1099511628211UL
    var h: ULong = 14695981039346656037UL
    spec.uuid.forEach { c ->
        h = (h xor c.code.toULong()) * prime
    }
    h = (h xor spec.startMillis.toULong()) * prime
    h = (h xor spec.distanceMeters.toLong().toULong()) * prime
    return (h and 0x7FFFFFFFFFFFFFFFUL).toLong()
}

/**
 * Stretch the 64-bit FNV-1a hash to 32 deterministic bytes via
 * SplitMix64 iteration. Matches iOS's `SealGeometry` which consumes
 * 32 bytes (from SHA-256) across positions 0..31 to drive rotation,
 * ring counts, radial angles, etc. SplitMix64 is the same deterministic
 * RNG iOS uses for its seal weather-texture layer; reuse for continuity.
 */
internal fun sealHashBytes(spec: SealSpec): ByteArray {
    var state = fnv1aHash(spec).toULong()
    val bytes = ByteArray(32)
    for (i in 0 until 4) {
        state += 0x9E3779B97F4A7C15UL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9UL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBUL
        z = z xor (z shr 31)
        val longValue = z.toLong()
        for (b in 0 until 8) {
            bytes[i * 8 + b] = (longValue shr (b * 8)).toByte()
        }
    }
    return bytes
}

/** Read a byte as an unsigned int in [0, 255]. */
internal fun ByteArray.u(index: Int): Int = this[index].toInt() and 0xFF

/**
 * Build a [SealSpec] from a finished [Walk] + its GPS samples and a
 * caller-resolved [ink] color. Caller is also responsible for
 * formatting [displayDistance] + [unitLabel] — locale-aware formatting
 * lives in [org.walktalkmeditate.pilgrim.ui.walk.WalkFormat], which
 * this data class intentionally doesn't depend on.
 */
fun Walk.toSealSpec(
    samples: List<RouteDataSample>,
    ink: Color,
    displayDistance: String,
    unitLabel: String,
): SealSpec {
    val endMs = requireNotNull(endTimestamp) {
        "toSealSpec called on an unfinished walk (uuid=$uuid); filter before calling."
    }
    val distance = walkDistanceMeters(
        samples.map { LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude) },
    )
    val durationSec = (endMs - startTimestamp) / 1000.0
    return SealSpec(
        uuid = uuid,
        startMillis = startTimestamp,
        distanceMeters = distance,
        durationSeconds = durationSec,
        displayDistance = displayDistance,
        unitLabel = unitLabel,
        ink = ink,
    )
}
```

**Verify:** file compiles in isolation. No tests yet.

---

## Task 2 — `SealGeometry.kt` (deterministic procedural geometry)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealGeometry.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

/**
 * Pure data struct describing a seal's procedural geometry — ring
 * list, radial lines, arc segments, decorative dots, and the overall
 * rotation. Computed once from a [SealSpec]'s 32-byte hash via
 * [sealGeometry]. All radii/angles are unit-agnostic (fractions of
 * the canvas outer radius or degrees); the renderer scales them at
 * draw time. Port of iOS's `SealGeometry.swift` but with our
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
    /** Fraction of outer radius, roughly 0.3..1.0. */
    val radiusFrac: Float,
    val strokeWidthFrac: Float,     // fraction of canvas size (converts to px)
    val opacity: Float,
    /** null → solid; non-null → on/off dash lengths in fractions of canvas size. */
    val dashPattern: FloatArray?,
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

/**
 * Ring count bounds from iOS's `SealGeometry.swift`. 3..8 including
 * meditation-driven extras (out of scope for 4-A — constant 0.0 ratio).
 */
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
        rotationDeg = (b.u(0).toFloat() / 255f) * 360f,
        rings = buildRings(b),
        radialLines = buildRadials(b),
        arcs = buildArcs(b),
        dots = buildDots(b),
    )
}

private fun buildRings(b: ByteArray): List<Ring> {
    val count = (BASE_RING_MIN + b.u(1) % BASE_RING_RANGE).coerceAtMost(MAX_RINGS)
    return (0 until count).map { i ->
        // Radius monotonically decreases from outer edge inward, with
        // a small per-ring jitter from hash bytes.
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
                floatArrayOf(dashLen, gapLen)
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
            angleDeg = (i * angleStep + angleJitter).mod(360f),
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
            angleDeg = ((b.u(29) / 255f) * 360f + i * angularStep).mod(360f),
            distanceFrac = 0.3f + (b.u(29 + i % 3) / 255f) * 0.5f,
            radiusFrac = 0.002f + (b.u(30 + i % 2) % 3) * 0.001f,
            opacity = 0.6f,
        )
    }
}

/**
 * `Float.mod(Float)` — Kotlin's `%` can return negative values for
 * negative left operands. We always want `[0, 360)` for angles.
 */
private fun Float.mod(divisor: Float): Float {
    val r = this % divisor
    return if (r < 0f) r + divisor else r
}
```

**Verify:** file compiles.

---

## Task 3 — `SealRenderer.kt` (the Composable + DrawScope layers)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRenderer.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.graphics.Paint as NativePaint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.ResourcesCompat
import androidx.compose.ui.geometry.Offset
import androidx.core.content.res.ResourcesCompat as AndroidxResourcesCompat
import kotlin.math.cos
import kotlin.math.sin
import org.walktalkmeditate.pilgrim.R

/**
 * Renders a single goshuin seal from [spec]. Pure draw layer — seeding
 * and geometry are computed once per spec via [remember]; the seasonal
 * tint comes in via [SealSpec.ink].
 *
 * Five procedural layers (all drawn under a global hash-derived rotation):
 *   1. Concentric rings (3..8, count/jitter/dash from hash)
 *   2. Radial spokes (4..12)
 *   3. Arc accent segments (2..4)
 *   4. Decorative dots (3..7)
 *   5. Center text (distance + unit)
 *
 * iOS ships a further 4 decorative layers (weather, ghost route,
 * elevation ring, curved outer text) which Stage 4-A explicitly defers
 * — see the design spec's "Non-goals" block.
 *
 * Size-agnostic. Caller chooses the Canvas size via [modifier]
 * (`.size(200.dp)`, `.fillMaxWidth().aspectRatio(1f)`, etc.); the
 * renderer enforces 1:1 aspect internally.
 */
@Composable
fun SealRenderer(
    spec: SealSpec,
    modifier: Modifier = Modifier,
) {
    val geometry = remember(spec) { sealGeometry(spec) }
    val context = LocalContext.current
    // Load once per composition; no @ReadOnlyComposable needed.
    val cormorantTypeface = remember {
        AndroidxResourcesCompat.getFont(context, R.font.cormorant_garamond_variable)
            ?: Typeface.DEFAULT
    }
    val latoTypeface = remember {
        AndroidxResourcesCompat.getFont(context, R.font.lato_regular)
            ?: Typeface.DEFAULT
    }

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val canvasSize = size.minDimension
        if (canvasSize <= 0f) return@Canvas

        val center = Offset(canvasSize / 2f, canvasSize / 2f)
        val outerR = canvasSize * 0.44f

        rotate(degrees = geometry.rotationDeg, pivot = center) {
            drawRings(geometry.rings, center, outerR, canvasSize, spec.ink)
            drawRadialLines(geometry.radialLines, center, outerR, canvasSize, spec.ink)
            drawArcs(geometry.arcs, center, outerR, canvasSize, spec.ink)
            drawDots(geometry.dots, center, outerR, canvasSize, spec.ink)
        }

        // Text layer is NOT inside the rotation — center text stays
        // upright regardless of the seal's hash-derived rotation.
        drawCenterText(
            center = center,
            canvasSize = canvasSize,
            distance = spec.displayDistance,
            unit = spec.unitLabel,
            ink = spec.ink,
            distanceTypeface = cormorantTypeface,
            unitTypeface = latoTypeface,
        )
    }
}

// --- layer helpers ---------------------------------------------------

private fun DrawScope.drawRings(
    rings: List<Ring>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    rings.forEach { ring ->
        val radiusPx = outerR * ring.radiusFrac
        val strokePx = canvasSize * ring.strokeWidthFrac
        val pathEffect = ring.dashPattern?.let { pattern ->
            PathEffect.dashPathEffect(
                intervals = pattern.map { it * canvasSize }.toFloatArray(),
                phase = 0f,
            )
        }
        val color = ink.copy(alpha = ring.opacity)
        drawCircle(
            color = color,
            radius = radiusPx,
            center = center,
            style = Stroke(width = strokePx, pathEffect = pathEffect),
        )
    }
}

private fun DrawScope.drawRadialLines(
    radials: List<Radial>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    radials.forEach { radial ->
        val rad = Math.toRadians(radial.angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val innerOffset = Offset(
            center.x + cosA * outerR * radial.innerFrac,
            center.y + sinA * outerR * radial.innerFrac,
        )
        val outerOffset = Offset(
            center.x + cosA * outerR * radial.outerFrac,
            center.y + sinA * outerR * radial.outerFrac,
        )
        drawLine(
            color = ink.copy(alpha = radial.opacity),
            start = innerOffset,
            end = outerOffset,
            strokeWidth = canvasSize * radial.strokeWidthFrac,
        )
    }
}

private fun DrawScope.drawArcs(
    arcs: List<ArcSegment>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    arcs.forEach { arc ->
        val radiusPx = outerR * arc.radiusFrac
        drawArc(
            color = ink.copy(alpha = arc.opacity),
            startAngle = arc.startAngleDeg,
            sweepAngle = arc.sweepDeg,
            useCenter = false,
            topLeft = Offset(center.x - radiusPx, center.y - radiusPx),
            size = androidx.compose.ui.geometry.Size(radiusPx * 2f, radiusPx * 2f),
            style = Stroke(width = canvasSize * arc.strokeWidthFrac),
        )
    }
}

private fun DrawScope.drawDots(
    dots: List<Dot>,
    center: Offset,
    outerR: Float,
    canvasSize: Float,
    ink: Color,
) {
    dots.forEach { dot ->
        val rad = Math.toRadians(dot.angleDeg.toDouble())
        val dotCenter = Offset(
            center.x + cos(rad).toFloat() * outerR * dot.distanceFrac,
            center.y + sin(rad).toFloat() * outerR * dot.distanceFrac,
        )
        drawCircle(
            color = ink.copy(alpha = dot.opacity),
            radius = canvasSize * dot.radiusFrac,
            center = dotCenter,
        )
    }
}

private fun DrawScope.drawCenterText(
    center: Offset,
    canvasSize: Float,
    distance: String,
    unit: String,
    ink: Color,
    distanceTypeface: Typeface,
    unitTypeface: Typeface,
) {
    // Font sizes as fractions of canvas, tuned to mirror iOS's 0.09 +
    // 0.032 proportions.
    val distanceTextPx = canvasSize * 0.09f
    val unitTextPx = canvasSize * 0.032f
    val gapPx = canvasSize * 0.008f
    val argb = ink.toArgb()
    drawIntoCanvas { composeCanvas ->
        val native = composeCanvas.nativeCanvas
        val distancePaint = NativePaint().apply {
            typeface = distanceTypeface
            textSize = distanceTextPx
            color = argb
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
        val unitPaint = NativePaint().apply {
            typeface = unitTypeface
            textSize = unitTextPx
            // Slightly dim the unit label to match iOS's 0.9 alpha.
            color = ink.copy(alpha = ink.alpha * 0.9f).toArgb()
            isAntiAlias = true
            textAlign = NativePaint.Align.CENTER
        }
        // Position distance centered vertically-ish, with the unit
        // below. drawText's Y param is the baseline.
        val distanceBaseline = center.y
        val unitBaseline = distanceBaseline + unitTextPx + gapPx
        native.drawText(distance, center.x, distanceBaseline, distancePaint)
        native.drawText(unit, center.x, unitBaseline, unitPaint)
    }
}
```

> **Import note.** `androidx.compose.ui.text.font.ResourcesCompat` doesn't exist — that's a typo in the initial draft. The correct import is `androidx.core.content.res.ResourcesCompat`. Delete the stale `import androidx.compose.ui.text.font.ResourcesCompat` line and keep the `androidx.core.content.res.ResourcesCompat as AndroidxResourcesCompat` alias.

---

## Task 4 — Pure-JVM tests for hash + geometry

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealHashTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SealHashTest {
    private fun spec(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMillis: Long = 1_700_000_000_000L,
        distance: Double = 5_000.0,
        duration: Double = 3_600.0,
    ) = SealSpec(
        uuid = uuid,
        startMillis = startMillis,
        distanceMeters = distance,
        durationSeconds = duration,
        displayDistance = "5.0",
        unitLabel = "km",
        ink = Color.Black,
    )

    @Test fun `fnv1a hash deterministic for identical specs`() {
        assertEquals(fnv1aHash(spec()), fnv1aHash(spec()))
    }

    @Test fun `fnv1a hash differs across uuids`() {
        assertNotEquals(
            fnv1aHash(spec(uuid = "aaaaaaaa-0000-0000-0000-000000000000")),
            fnv1aHash(spec(uuid = "bbbbbbbb-0000-0000-0000-000000000000")),
        )
    }

    @Test fun `sealHashBytes length is 32`() {
        assertEquals(32, sealHashBytes(spec()).size)
    }

    @Test fun `sealHashBytes deterministic`() {
        assertTrue(sealHashBytes(spec()).contentEquals(sealHashBytes(spec())))
    }

    @Test fun `sealHashBytes differs across specs`() {
        val a = sealHashBytes(spec(uuid = "aaaa0000-0000-0000-0000-000000000000"))
        val b = sealHashBytes(spec(uuid = "bbbb0000-0000-0000-0000-000000000000"))
        assertTrue("byte arrays should differ", !a.contentEquals(b))
    }

    @Test fun `byte unsigned reader stays in 0 to 255`() {
        val bytes = ByteArray(32) { (it * 8).toByte() }  // includes negative byte values
        for (i in 0 until 32) {
            val u = bytes.u(i)
            assertTrue("byte[$i] = $u out of range", u in 0..255)
        }
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealGeometryTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SealGeometryTest {
    private fun spec(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMillis: Long = 1_700_000_000_000L,
        distance: Double = 5_000.0,
    ) = SealSpec(
        uuid = uuid,
        startMillis = startMillis,
        distanceMeters = distance,
        durationSeconds = 3_600.0,
        displayDistance = "5.0",
        unitLabel = "km",
        ink = Color.Black,
    )

    @Test fun `ring count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "uuid-$i"))
            assertTrue("ring count ${g.rings.size} out of [3, 8]", g.rings.size in 3..8)
        }
    }

    @Test fun `radial line count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "radial-$i"))
            assertTrue("radial count ${g.radialLines.size} out of [4, 12]", g.radialLines.size in 4..12)
        }
    }

    @Test fun `arc count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "arc-$i"))
            assertTrue("arc count ${g.arcs.size} out of [2, 4]", g.arcs.size in 2..4)
        }
    }

    @Test fun `dot count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "dot-$i"))
            assertTrue("dot count ${g.dots.size} out of [3, 7]", g.dots.size in 3..7)
        }
    }

    @Test fun `rotation in 0 to 360`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "rot-$i"))
            assertTrue("rotation ${g.rotationDeg} out of range", g.rotationDeg in 0f..360f)
        }
    }

    @Test fun `sealGeometry deterministic`() {
        val a = sealGeometry(spec())
        val b = sealGeometry(spec())
        assertEquals(a.rotationDeg, b.rotationDeg, 0.0001f)
        assertEquals(a.rings.size, b.rings.size)
        assertEquals(a.radialLines.size, b.radialLines.size)
        assertEquals(a.arcs.size, b.arcs.size)
        assertEquals(a.dots.size, b.dots.size)
    }

    @Test fun `different uuids produce different rotations with high probability`() {
        val rotations = (0 until 100).map { i ->
            sealGeometry(spec(uuid = "var-$i")).rotationDeg
        }
        val distinct = rotations.toSet().size
        // FNV-1a on distinct uuids should give wide rotation spread. 100
        // samples → expect >80 distinct (some collision tolerance).
        assertTrue("only $distinct distinct rotations across 100 specs — hash has poor spread", distinct > 80)
    }

    @Test fun `ring radii are monotonically decreasing before jitter`() {
        // After jitter there's some overlap, but the mean trend should
        // be decreasing. Check: ring[0].radiusFrac > ring[last].radiusFrac.
        val g = sealGeometry(spec())
        assertTrue(
            "first ring radius ${g.rings.first().radiusFrac} should exceed last ${g.rings.last().radiusFrac}",
            g.rings.first().radiusFrac > g.rings.last().radiusFrac,
        )
    }

    @Test fun `ring opacity decreases toward center`() {
        val g = sealGeometry(spec())
        // Ring 0 is outermost and most opaque by construction.
        assertTrue(g.rings.first().opacity >= g.rings.last().opacity)
    }
}
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*ui.design.seals.SealHashTest' --tests '*SealGeometryTest'
```

---

## Task 5 — Robolectric smoke test for the Composable

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRendererComposableTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Composition smoke tests for [SealRenderer]. The actual DrawScope
 * execution goes through Android's graphics stack; Robolectric
 * provides the shadow implementations.
 *
 * Separately, [SealGeometryTest] + [SealHashTest] cover the
 * deterministic geometry builder with plain JVM tests (fast, no
 * Android runtime).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SealRendererComposableTest {

    @get:Rule val composeRule = createComposeRule()

    private fun spec(i: Int) = SealSpec(
        uuid = "seal-test-$i",
        startMillis = 1_700_000_000_000L + i * 86_400_000L,
        distanceMeters = 2_000.0 + i * 500.0,
        durationSeconds = 1_800.0 + i * 60.0,
        displayDistance = "%.1f".format(2.0 + i * 0.5),
        unitLabel = "km",
        ink = Color(0xFFA0634B),       // PilgrimColors.rust baseline
    )

    private fun renderInsideSizedBox(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            Box(modifier = Modifier.size(200.dp)) {
                content()
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `single seal renders without crashing`() {
        renderInsideSizedBox { SealRenderer(spec = spec(0)) }
    }

    @Test fun `seal with zero distance renders without crashing`() {
        val zeroDistance = spec(0).copy(distanceMeters = 0.0, displayDistance = "0")
        renderInsideSizedBox { SealRenderer(spec = zeroDistance) }
    }

    @Test fun `seal with long distance label renders without crashing`() {
        val longLabel = spec(0).copy(displayDistance = "123.45", unitLabel = "km")
        renderInsideSizedBox { SealRenderer(spec = longLabel) }
    }

    @Test fun `multiple seals with distinct uuids render without crashing`() {
        composeRule.setContent {
            androidx.compose.foundation.layout.Column {
                (0 until 8).forEach { i ->
                    Box(modifier = Modifier.size(120.dp)) {
                        SealRenderer(spec = spec(i))
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }
}
```

> **Stage 3-C precedent note.** Stage 3-C extracted `buildRibbonPath(...)` as `internal fun` and wrote a direct `Path.getBounds()` test to prove the Skia Path construction actually ran. 4-A's renderer uses `drawCircle`, `drawLine`, `drawArc`, `drawPath` (indirectly for `drawCircle`'s internal Path), and `drawText` — all DrawScope primitives that compile their own Paths internally. There's no single `buildXPath()` helper to extract for the direct test; the composition smoke tests cover the path by exercising all draw calls. If a future Stage 4 review demands direct coverage, extract one layer (e.g., `private fun buildRingPath(radius, strokeWidth, dashPattern): Path`) — but for 4-A the composition test is sufficient.

---

## Task 6 — `SealPreviewViewModel.kt` (debug VM)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealPreviewViewModel.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Debug-only VM for the Stage 4-A seal preview. Observes Room for
 * finished walks, maps each to a [PreviewSeal] with raw spec fields
 * (ink left as [Color.Transparent] — the preview screen resolves it
 * via [SeasonalColorEngine] in a `@Composable` context).
 *
 * If there are no finished walks, falls back to eight synthetic seals
 * spread across the year for visual verification. Mirrors Stage 3-C's
 * `CalligraphyPathPreviewViewModel` pattern (which we deleted in 3-E)
 * — will be deleted when Stage 4-B's reveal animation lands in the
 * real walk-finish flow.
 */
@HiltViewModel
class SealPreviewViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
) : ViewModel() {

    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    init {
        viewModelScope.launch {
            hemisphereRepository.refreshFromLocationIfNeeded()
        }
    }

    val state: StateFlow<List<PreviewSeal>> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isNotEmpty()) {
                finished.map { walk ->
                    val samples = repository.locationSamplesFor(walk.id)
                    val durationMs = (walk.endTimestamp ?: walk.startTimestamp) - walk.startTimestamp
                    // The preview uses a rough distance formatter; the
                    // real finish-walk path in 4-B will use WalkFormat.
                    val spec = walk.toSealSpec(
                        samples = samples,
                        ink = Color.Transparent,
                        displayDistance = WalkFormat.distance(
                            org.walktalkmeditate.pilgrim.domain.walkDistanceMeters(
                                samples.map {
                                    org.walktalkmeditate.pilgrim.domain.LocationPoint(
                                        timestamp = it.timestamp,
                                        latitude = it.latitude,
                                        longitude = it.longitude,
                                    )
                                },
                            ),
                        ).substringBefore(' '),   // "5.20 km" → "5.20"
                        unitLabel = "km",         // hardcoded for preview
                    )
                    PreviewSeal(spec = spec, walkStartMillis = walk.startTimestamp)
                }
            } else {
                synthetic()
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    private fun synthetic(): List<PreviewSeal> {
        val baseMillis = clock.now() - 240L * 86_400_000L
        return (0 until 8).map { i ->
            val start = baseMillis + i * 30L * 86_400_000L
            PreviewSeal(
                spec = SealSpec(
                    uuid = "seal-synthetic-$i",
                    startMillis = start,
                    distanceMeters = 2_500.0 + i * 400.0,
                    durationSeconds = 1_200.0 + i * 120.0,
                    displayDistance = "%.1f".format(2.5 + i * 0.4),
                    unitLabel = "km",
                    ink = Color.Transparent,
                ),
                walkStartMillis = start,
            )
        }
    }

    data class PreviewSeal(
        val spec: SealSpec,
        val walkStartMillis: Long,
    )

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
```

---

## Task 7 — `SealPreview.kt` (debug screen)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealPreview.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * Debug-only preview screen for Stage 4-A's seal renderer. Shown
 * behind a BuildConfig.DEBUG button on HomeScreen; deleted when Stage
 * 4-B's reveal animation integrates the renderer into the real
 * walk-finish flow.
 */
@Composable
fun SealPreviewScreen(
    viewModel: SealPreviewViewModel = hiltViewModel(),
) {
    val previews by viewModel.state.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()
    val baseInk = pilgrimColors.rust

    val resolvedSpecs = remember(previews, hemisphere, baseInk) {
        previews.map { preview ->
            val walkDate = Instant.ofEpochMilli(preview.walkStartMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val ink = SeasonalColorEngine.applySeasonalShift(
                base = baseInk,
                intensity = SeasonalColorEngine.Intensity.Full,
                date = walkDate,
                hemisphere = hemisphere,
            )
            preview.spec.copy(ink = ink)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
    ) {
        Text(
            text = "Seal preview",
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        resolvedSpecs.forEach { spec ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                SealRenderer(
                    spec = spec,
                    modifier = Modifier.size(240.dp),
                )
            }
        }
        if (resolvedSpecs.isEmpty()) {
            Text(
                text = "Loading seals...",
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
        }
    }

    // Suppress unused-color warning when theme is stable but the
    // variable isn't consumed in a loading frame.
    @Suppress("UNUSED_PARAMETER", "UNUSED_VARIABLE")
    val _unused: Color = Color.Transparent
}
```

> **Clean-up self-note.** Drop the `@Suppress` block at the bottom of the file during implementation — it was scratch paper from the design. The final file ends with the `Column` block.

---

## Task 8 — Wire debug button + nav route

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

Add import:
```kotlin
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.ui.design.seals.SealPreviewScreen
```

Add route constant inside `Routes`:
```kotlin
const val SEAL_PREVIEW = "seal_preview"
```

In the `HomeScreen` call-site, add callback parameter:
```kotlin
HomeScreen(
    permissionsViewModel = permissionsViewModel,
    onEnterActiveWalk = { navController.navigate(Routes.ACTIVE_WALK) },
    onEnterWalkSummary = { walkId ->
        navController.navigate(Routes.walkSummary(walkId)) { launchSingleTop = true }
    },
    onEnterSealPreview = {
        if (BuildConfig.DEBUG) {
            navController.navigate(Routes.SEAL_PREVIEW) { launchSingleTop = true }
        }
    },
)
```

Add composable destination under the rest, gated:
```kotlin
if (BuildConfig.DEBUG) {
    composable(Routes.SEAL_PREVIEW) {
        SealPreviewScreen()
    }
}
```

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

Add import:
```kotlin
import androidx.compose.material3.TextButton
import org.walktalkmeditate.pilgrim.BuildConfig
```

Add parameter:
```kotlin
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterSealPreview: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
)
```

Below the existing `BatteryExemptionCard(...)` call, add the debug button:
```kotlin
BatteryExemptionCard(viewModel = permissionsViewModel)

// Debug-only: seal renderer preview (Stage 4-A).
// Deleted when Stage 4-B integrates the seal into walk finish.
if (BuildConfig.DEBUG) {
    Spacer(Modifier.height(PilgrimSpacing.big))
    TextButton(
        onClick = onEnterSealPreview,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Seal preview (debug)")
    }
}
```

---

## Task 9 — Full CI gate

```
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: green. ~20 new tests across 3 test files + existing suite stays passing.

---

## Out-of-plan notes

- **No strings.xml changes.** Debug button text hardcoded like Stage 3-C.
- **No Manifest changes.**
- **No Gradle changes.** All deps already on main — Compose, Material3, Hilt navigation, androidx.core (ResourcesCompat).
- **Hilt wiring.** `SealPreviewViewModel` is `@HiltViewModel` — the graph already binds its deps (`WalkRepository`, `Clock`, `HemisphereRepository`).
- **Font loading.** `ResourcesCompat.getFont(context, R.font.cormorant_garamond_variable)` returns the variable-weight `Typeface`. In `drawText` we don't get weight axis control — we'll get the font's default instance (~weight 300 for Cormorant per Stage 3-B). Acceptable for 4-A; polish pass could use `Typeface.Builder` with `FontVariationSettings` if needed on API 26+.
