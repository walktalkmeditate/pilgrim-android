# Stage 3-C Implementation Plan — Calligraphy Path Renderer

Spec: `docs/superpowers/specs/2026-04-18-stage-3c-calligraphy-path-design.md`.

Ship the pure-renderer + debug preview for a port of the iOS calligraphy
ink-path into Compose `DrawScope`. No HomeScreen integration yet (Stage 3-E's job).

---

## Task 1 — `CalligraphyStrokeSpec.kt` + FNV-1a hash + helpers

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyStrokeSpec.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * One pre-resolved contribution to the calligraphy path renderer.
 *
 * The renderer is pure — all per-walk inputs land here, the caller does
 * the Walk → spec conversion (see [Walk.toStrokeSpec]). That keeps the
 * draw layer testable in isolation and lets the Stage 3-D seasonal
 * engine upgrade [ink] without touching the renderer.
 *
 * @property uuid stable UUID string — seeds the FNV-1a meander hash
 * @property startMillis walk start timestamp in epoch ms — seed + seasonal tint
 * @property distanceMeters total walked distance (haversine sum)
 * @property averagePaceSecPerKm pace in sec/km; 0 → base stroke width fallback
 * @property ink resolved ink color (Stage 3-C picks by month; 3-D adds HSB shifts)
 */
@Immutable
data class CalligraphyStrokeSpec(
    val uuid: String,
    val startMillis: Long,
    val distanceMeters: Double,
    val averagePaceSecPerKm: Double,
    val ink: Color,
)

/**
 * FNV-1a 64-bit hash, ported from iOS's CalligraphyPathRenderer. Mixes
 * the UUID characters (UTF-16 code units), the startMillis, and the
 * integer meters distance.
 *
 * Not byte-identical to iOS (Swift hashes raw UUID bytes + Double
 * bit-pattern) — cross-platform determinism isn't a goal for 3-C.
 * See the spec's "iOS deviation noted" block.
 */
internal fun fnv1aHash(spec: CalligraphyStrokeSpec): Long {
    val prime: ULong = 1099511628211UL
    var h: ULong = 14695981039346656037UL
    spec.uuid.forEach { c ->
        h = (h xor c.code.toULong()) * prime
    }
    h = (h xor spec.startMillis.toULong()) * prime
    h = (h xor spec.distanceMeters.toLong().toULong()) * prime
    return (h and 0x7FFFFFFFFFFFFFFFUL).toLong()
}

/** Meander seed in [-1, 1], deterministic for a given spec. */
internal fun meanderSeed(spec: CalligraphyStrokeSpec): Float {
    val h = fnv1aHash(spec)
    return ((h % 2000L).toFloat() / 1000f) - 1f
}

/** Horizontal X offset (in pixels) from the canvas centerX. */
internal fun xOffsetPx(
    spec: CalligraphyStrokeSpec,
    centerXPx: Float,
    maxMeanderPx: Float,
): Float {
    val h = fnv1aHash(spec)
    val normalizedOffset = (h % 1000L).toFloat() / 1000f - 0.5f
    return centerXPx + normalizedOffset * maxMeanderPx * 1.6f
}

/** Fast=300 sec/km → base, slow=900 sec/km → max; pre-taper. */
internal fun paceDrivenWidth(
    averagePaceSecPerKm: Double,
    baseWidthPx: Float,
    maxWidthPx: Float,
): Float {
    if (averagePaceSecPerKm <= 0.0) return baseWidthPx
    val clamped = averagePaceSecPerKm.coerceIn(300.0, 900.0)
    val t = ((clamped - 300.0) / (900.0 - 300.0)).toFloat()
    return baseWidthPx + t * (maxWidthPx - baseWidthPx)
}

/** Oldest walk tapers to 60% of its pace-derived width. Index 0 = newest. */
internal fun taperFactor(index: Int, total: Int): Float {
    if (total <= 1) return 1f
    return 1f - (index.toFloat() / (total - 1).toFloat()) * 0.4f
}

/** Segment opacity: newest 0.35, oldest 0.15, linear. */
internal fun segmentOpacity(index: Int, total: Int): Float {
    if (total <= 1) return 0.35f
    return 0.35f - (index.toFloat() / (total - 1).toFloat()) * 0.2f
}

/**
 * Build a [CalligraphyStrokeSpec] from a finished [Walk] + its GPS samples.
 *
 * Pre-condition: [Walk.endTimestamp] is non-null. Caller should filter.
 */
fun Walk.toStrokeSpec(samples: List<RouteDataSample>, ink: Color): CalligraphyStrokeSpec {
    val distance = walkDistanceMeters(
        samples.map { LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude) },
    )
    val endMs = endTimestamp ?: startTimestamp
    val durationSec = (endMs - startTimestamp) / 1000.0
    val pace = if (distance > 0.0 && durationSec > 0.0) durationSec / (distance / 1000.0) else 0.0
    return CalligraphyStrokeSpec(
        uuid = uuid,
        startMillis = startTimestamp,
        distanceMeters = distance,
        averagePaceSecPerKm = pace,
        ink = ink,
    )
}
```

**Verify:** file compiles in isolation. No tests yet (Task 4).

---

## Task 2 — `SeasonalInkFlavor.kt` (month → color helper)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/SeasonalInkFlavor.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Northern-hemisphere month → base color bucket. Stage 3-D will wrap
 * this with the HSB shift + hemisphere inversion; for 3-C we pick the
 * nearest base PilgrimColors token.
 */
enum class SeasonalInkFlavor {
    Ink, Moss, Rust, Dawn;

    companion object {
        fun forMonth(startMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SeasonalInkFlavor {
            val month = Instant.ofEpochMilli(startMillis).atZone(zone).monthValue
            return when (month) {
                in 3..5 -> Moss
                in 6..8 -> Rust
                in 9..11 -> Dawn
                else -> Ink
            }
        }
    }
}

@Composable
@ReadOnlyComposable
fun SeasonalInkFlavor.toColor(): Color = when (this) {
    SeasonalInkFlavor.Ink -> pilgrimColors.ink
    SeasonalInkFlavor.Moss -> pilgrimColors.moss
    SeasonalInkFlavor.Rust -> pilgrimColors.rust
    SeasonalInkFlavor.Dawn -> pilgrimColors.dawn
}
```

---

## Task 3 — `CalligraphyPath.kt` (the Composable renderer)

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders the Pilgrim "calligraphy path" — a thread of variable-width
 * ink ribbons connecting per-walk dot positions, drawn as filled
 * polygons between two parallel cubic Béziers.
 *
 * Pure draw layer. Caller supplies pre-resolved [strokes] (see
 * [CalligraphyStrokeSpec] + `Walk.toStrokeSpec`). No coroutines, no
 * state, no side effects.
 *
 * See `docs/superpowers/specs/2026-04-18-stage-3c-calligraphy-path-design.md`.
 */
@Composable
fun CalligraphyPath(
    strokes: List<CalligraphyStrokeSpec>,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 90.dp,
    topInset: Dp = 40.dp,
    maxMeander: Dp = 100.dp,
    baseWidth: Dp = 1.5.dp,
    maxWidth: Dp = 4.5.dp,
) {
    val totalHeight = topInset + verticalSpacing * (strokes.size + 1)
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight),
    ) {
        if (strokes.size < 2) return@Canvas
        val verticalSpacingPx = verticalSpacing.toPx()
        val topInsetPx = topInset.toPx()
        val maxMeanderPx = maxMeander.toPx()
        val baseWidthPx = baseWidth.toPx()
        val maxWidthPx = maxWidth.toPx()
        val centerX = size.width / 2f

        val positions: List<Pair<Float, Float>> = strokes.mapIndexed { i, spec ->
            val x = xOffsetPx(spec, centerX, maxMeanderPx)
            val y = topInsetPx + i * verticalSpacingPx + verticalSpacingPx / 2f
            x to y
        }

        for (i in 0 until positions.size - 1) {
            val (sx, sy) = positions[i]
            val (ex, ey) = positions[i + 1]

            val strokeWidth = paceDrivenWidth(
                averagePaceSecPerKm = strokes[i].averagePaceSecPerKm,
                baseWidthPx = baseWidthPx,
                maxWidthPx = maxWidthPx,
            ) * taperFactor(i, strokes.size)
            val halfWidth = strokeWidth / 2f

            val midY = (sy + ey) / 2f
            val cpOffset = meanderSeed(strokes[i]) * maxMeanderPx * 0.4f
            val cp1X = sx + cpOffset
            val cp1Y = midY - verticalSpacingPx * 0.2f
            val cp2X = ex - cpOffset
            val cp2Y = midY + verticalSpacingPx * 0.2f

            val path = Path().apply {
                moveTo(sx - halfWidth, sy)
                cubicTo(
                    cp1X - halfWidth, cp1Y,
                    cp2X - halfWidth, cp2Y,
                    ex - halfWidth, ey,
                )
                lineTo(ex + halfWidth, ey)
                cubicTo(
                    cp2X + halfWidth, cp2Y,
                    cp1X + halfWidth, cp1Y,
                    sx + halfWidth, sy,
                )
                close()
            }
            drawInkRibbon(path, strokes[i].ink, segmentOpacity(i, strokes.size))
        }
    }
}

private fun DrawScope.drawInkRibbon(
    path: Path,
    ink: androidx.compose.ui.graphics.Color,
    opacity: Float,
) {
    drawPath(path = path, color = ink.copy(alpha = opacity))
}
```

---

## Task 4 — Pure-JVM unit tests

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyHashTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CalligraphyHashTest {
    private fun spec(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMillis: Long = 1_700_000_000_000L,
        distance: Double = 4321.0,
        pace: Double = 600.0,
    ) = CalligraphyStrokeSpec(uuid, startMillis, distance, pace, Color.Black)

    @Test fun `hash is deterministic for identical specs`() {
        assertEquals(fnv1aHash(spec()), fnv1aHash(spec()))
    }

    @Test fun `hash differs for different uuids`() {
        val a = fnv1aHash(spec(uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        val b = fnv1aHash(spec(uuid = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"))
        assertNotEquals(a, b)
    }

    @Test fun `hash differs for different timestamps`() {
        val a = fnv1aHash(spec(startMillis = 1_000L))
        val b = fnv1aHash(spec(startMillis = 2_000L))
        assertNotEquals(a, b)
    }

    @Test fun `hash differs for different distances`() {
        val a = fnv1aHash(spec(distance = 1000.0))
        val b = fnv1aHash(spec(distance = 2000.0))
        assertNotEquals(a, b)
    }

    @Test fun `meander seed stays in range -1 to 1`() {
        repeat(100) { i ->
            val s = spec(uuid = "seed-$i", startMillis = 1_000L + i, distance = i.toDouble())
            val m = meanderSeed(s)
            assertTrue(m in -1f..1f, "seed=$m for spec=$s")
        }
    }

    @Test fun `xOffset centers around centerX with meander bound`() {
        // Spread for 100 random specs should land near centerX, within ±centerX+maxMeander*0.8
        val centerX = 500f
        val max = 100f
        repeat(100) { i ->
            val s = spec(uuid = "x-$i", startMillis = i.toLong(), distance = (i * 10).toDouble())
            val x = xOffsetPx(s, centerX, max)
            val delta = x - centerX
            assertTrue(delta in -max * 0.8f..max * 0.8f, "x=$x delta=$delta")
        }
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/SegmentCurvesTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmentCurvesTest {
    @Test fun `fast pace gives base width`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 300.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(10f, w)
    }

    @Test fun `slow pace gives max width`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 900.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(30f, w)
    }

    @Test fun `mid pace gives halfway width`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 600.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertTrue(abs(w - 20f) < 0.001f)
    }

    @Test fun `very fast pace clamps to base`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 100.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(10f, w)
    }

    @Test fun `very slow pace clamps to max`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 2000.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(30f, w)
    }

    @Test fun `zero pace falls back to base`() {
        val w = paceDrivenWidth(averagePaceSecPerKm = 0.0, baseWidthPx = 10f, maxWidthPx = 30f)
        assertEquals(10f, w)
    }

    @Test fun `taper newest is 1`() {
        assertEquals(1f, taperFactor(index = 0, total = 10))
    }

    @Test fun `taper oldest is 0_6`() {
        val t = taperFactor(index = 9, total = 10)
        assertTrue(abs(t - 0.6f) < 0.001f, "got $t")
    }

    @Test fun `taper single stroke is 1`() {
        assertEquals(1f, taperFactor(index = 0, total = 1))
    }

    @Test fun `opacity newest is 0_35`() {
        assertEquals(0.35f, segmentOpacity(index = 0, total = 10))
    }

    @Test fun `opacity oldest is 0_15`() {
        val o = segmentOpacity(index = 9, total = 10)
        assertTrue(abs(o - 0.15f) < 0.001f, "got $o")
    }

    @Test fun `opacity single stroke is 0_35`() {
        assertEquals(0.35f, segmentOpacity(index = 0, total = 1))
    }
}
```

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/SeasonalInkFlavorTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class SeasonalInkFlavorTest {
    private fun millisFor(year: Int, month: Int, day: Int, zone: ZoneId = ZoneOffset.UTC): Long =
        LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun `march is moss`() {
        assertEquals(SeasonalInkFlavor.Moss, SeasonalInkFlavor.forMonth(millisFor(2026, 3, 15), ZoneOffset.UTC))
    }

    @Test fun `may is moss`() {
        assertEquals(SeasonalInkFlavor.Moss, SeasonalInkFlavor.forMonth(millisFor(2026, 5, 15), ZoneOffset.UTC))
    }

    @Test fun `june is rust`() {
        assertEquals(SeasonalInkFlavor.Rust, SeasonalInkFlavor.forMonth(millisFor(2026, 6, 1), ZoneOffset.UTC))
    }

    @Test fun `august is rust`() {
        assertEquals(SeasonalInkFlavor.Rust, SeasonalInkFlavor.forMonth(millisFor(2026, 8, 31), ZoneOffset.UTC))
    }

    @Test fun `september is dawn`() {
        assertEquals(SeasonalInkFlavor.Dawn, SeasonalInkFlavor.forMonth(millisFor(2026, 9, 1), ZoneOffset.UTC))
    }

    @Test fun `november is dawn`() {
        assertEquals(SeasonalInkFlavor.Dawn, SeasonalInkFlavor.forMonth(millisFor(2026, 11, 30), ZoneOffset.UTC))
    }

    @Test fun `december is ink`() {
        assertEquals(SeasonalInkFlavor.Ink, SeasonalInkFlavor.forMonth(millisFor(2026, 12, 20), ZoneOffset.UTC))
    }

    @Test fun `january is ink`() {
        assertEquals(SeasonalInkFlavor.Ink, SeasonalInkFlavor.forMonth(millisFor(2026, 1, 1), ZoneOffset.UTC))
    }

    @Test fun `february is ink`() {
        assertEquals(SeasonalInkFlavor.Ink, SeasonalInkFlavor.forMonth(millisFor(2026, 2, 28), ZoneOffset.UTC))
    }
}
```

**Verify after this task:**
```
./gradlew testDebugUnitTest --tests '*CalligraphyHashTest' --tests '*SegmentCurvesTest' --tests '*SeasonalInkFlavorTest'
```

---

## Task 5 — Robolectric Composable smoke test

**New file:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathComposableTest.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import android.app.Application
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rasterizes [CalligraphyPath] through Compose's draw pipeline to
 * confirm the Path + DrawScope calls actually execute. Pixel values
 * aren't asserted — this is a builder-path smoke test, mirroring the
 * project convention for platform-object constructors (see CLAUDE.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CalligraphyPathComposableTest {

    @get:Rule val composeRule = createComposeRule()

    private fun spec(i: Int): CalligraphyStrokeSpec = CalligraphyStrokeSpec(
        uuid = "uuid-$i",
        startMillis = 1_700_000_000_000L + i * 86_400_000L,
        distanceMeters = 2_000.0 + i * 500.0,
        averagePaceSecPerKm = 500.0 + i * 20.0,
        ink = Color.Black,
    )

    @Test fun `empty list renders without crashing`() {
        composeRule.setContent { CalligraphyPath(strokes = emptyList()) }
        composeRule.onRoot().assertExists()
    }

    @Test fun `single stroke renders without crashing`() {
        composeRule.setContent { CalligraphyPath(strokes = listOf(spec(0))) }
        composeRule.onRoot().assertExists()
    }

    @Test fun `two strokes render without crashing`() {
        composeRule.setContent { CalligraphyPath(strokes = listOf(spec(0), spec(1))) }
        composeRule.onRoot().assertExists()
    }

    @Test fun `eight strokes render without crashing`() {
        composeRule.setContent {
            CalligraphyPath(strokes = (0 until 8).map(::spec))
        }
        composeRule.onRoot().assertExists()
    }
}
```

**Gradle dep note:** Verify `androidx.compose.ui:ui-test-junit4` is already a `testImplementation` — it ships via the Compose BOM in Stage 2-C. If the test module fails to compile, add to `app/build.gradle.kts`:
```
testImplementation("androidx.compose.ui:ui-test-junit4")
testImplementation("androidx.compose.ui:ui-test-manifest")
```

**Verify:**
```
./gradlew testDebugUnitTest --tests '*CalligraphyPathComposableTest'
```

---

## Task 6 — Preview ViewModel + screen

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathPreviewViewModel.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository

/**
 * Debug-only VM for the Stage 3-C preview screen. Loads finished walks
 * from the repository and emits a list of [CalligraphyStrokeSpec]
 * ready for rendering. If there are no walks (clean-install device),
 * falls back to eight synthetic strokes spanning the year for visual
 * verification.
 *
 * Emits raw specs; the preview Composable resolves colors via
 * [SeasonalInkFlavor.toColor] so dark/light theme picks the right token.
 */
@HiltViewModel
class CalligraphyPathPreviewViewModel @Inject constructor(
    private val repository: WalkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<List<PreviewStroke>>(emptyList())
    val state: StateFlow<List<PreviewStroke>> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val walks = repository.allWalks().filter { it.endTimestamp != null }
            _state.value = if (walks.isNotEmpty()) {
                walks.map { walk ->
                    val samples = repository.locationSamplesFor(walk.id)
                    // `ink` is a placeholder — the preview Composable
                    // resolves the real color via SeasonalInkFlavor.toColor()
                    // (which needs a @Composable context, so we can't do it here).
                    val spec = walk.toStrokeSpec(
                        samples = samples,
                        ink = androidx.compose.ui.graphics.Color.Transparent,
                    )
                    PreviewStroke(
                        spec = spec,
                        flavor = SeasonalInkFlavor.forMonth(walk.startTimestamp),
                    )
                }
            } else {
                synthetic()
            }
        }
    }

    private fun synthetic(): List<PreviewStroke> {
        // Eight walks covering four seasons, alternating fast/slow paces.
        val baseMillis = System.currentTimeMillis() - 240L * 86_400_000L // ~8 months ago
        return (0 until 8).map { i ->
            val start = baseMillis + i * 30L * 86_400_000L
            val pace = if (i % 2 == 0) 400.0 else 800.0
            PreviewStroke(
                spec = CalligraphyStrokeSpec(
                    uuid = "synthetic-$i",
                    startMillis = start,
                    distanceMeters = 2_500.0 + i * 400.0,
                    averagePaceSecPerKm = pace,
                    ink = androidx.compose.ui.graphics.Color.Transparent,
                ),
                flavor = SeasonalInkFlavor.forMonth(start),
            )
        }
    }

    data class PreviewStroke(
        val spec: CalligraphyStrokeSpec,
        val flavor: SeasonalInkFlavor,
    )
}
```

**New file:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPathPreview.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Debug preview screen for Stage 3-C. Wired behind a debug button on
 * [HomeScreen] and deleted once Stage 3-E integrates the renderer
 * into the journal list itself.
 */
@Composable
fun CalligraphyPathPreviewScreen(
    viewModel: CalligraphyPathPreviewViewModel = hiltViewModel(),
) {
    val previews by viewModel.state.collectAsStateWithLifecycle()
    val strokes = previews.map { it.spec.copy(ink = it.flavor.toColor()) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Text(
            text = "Calligraphy preview",
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        CalligraphyPath(strokes = strokes)
    }
}
```

---

## Task 7 — Wire debug button + nav route

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

Add to `Routes`:
```kotlin
const val CALLIGRAPHY_PREVIEW = "calligraphy_preview"
```

Update `HomeScreen` invocation:
```kotlin
composable(Routes.HOME) {
    HomeScreen(
        permissionsViewModel = permissionsViewModel,
        onEnterActiveWalk = { navController.navigate(Routes.ACTIVE_WALK) },
        onEnterWalkSummary = { walkId ->
            navController.navigate(Routes.walkSummary(walkId)) {
                launchSingleTop = true
            }
        },
        onEnterCalligraphyPreview = { navController.navigate(Routes.CALLIGRAPHY_PREVIEW) },
    )
}
```

Add new composable destination at the end of the NavHost block:
```kotlin
composable(Routes.CALLIGRAPHY_PREVIEW) {
    CalligraphyPathPreviewScreen()
}
```

Add import for `CalligraphyPathPreviewScreen`.

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

Add the parameter:
```kotlin
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterCalligraphyPreview: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
)
```

Below the BatteryExemptionCard (at the very bottom of the outer Column), add:
```kotlin
if (org.walktalkmeditate.pilgrim.BuildConfig.DEBUG) {
    Spacer(Modifier.height(PilgrimSpacing.big))
    TextButton(
        onClick = onEnterCalligraphyPreview,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Calligraphy preview (debug)")
    }
}
```

Add `androidx.compose.material3.TextButton` import.

**Verify:**
```
./gradlew assembleDebug
```

---

## Task 8 — Final CI gate

Run the full project gate:
```
./gradlew assembleDebug lintDebug testDebugUnitTest
```

Expected: green. Only new tests are the three pure-JVM + one Robolectric file added in Tasks 4–5.

---

## Out-of-plan notes

- No changes to `strings.xml` — the debug-only button uses a hard-coded string that disappears with the button in Stage 3-E.
- No changes to Hilt modules: `CalligraphyPathPreviewViewModel` is `@HiltViewModel`-annotated, the existing `ViewModelComponent` binding handles it.
- No changes to `build.gradle.kts` unless the Compose test library is missing (Task 5 note).
- No screenshot tests; visual verification is manual via the debug preview screen.
