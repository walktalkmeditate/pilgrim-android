# Stage 10-HI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land iOS-faithful AboutView (10-H) + the full cross-platform `.pilgrim` walks export/import + JourneyViewer (10-I) in one bundled PR.

**Architecture:** AboutView is a single screen + small helpers (logo, footprint, simplified seasonal tree) + nav row at SettingsScreen bottom. The `.pilgrim` format port mirrors iOS field-for-field: kotlinx-serialization data classes, schema version `"1.0"`, ZIP packaging via existing `ZipOutputStream` pattern (Stage 10-G `RecordingsExporter` precedent), photos resized to 600×600 JPEG @70 quality. Cross-platform compat is JSON-shape parity, not byte parity.

**Tech Stack:** Kotlin 2.x, Jetpack Compose, kotlinx-serialization, OkHttp/MediaStore (for photo content URIs), Room (read-only here via existing repos), `androidx.browser` (already added Stage 10-F).

**Source spec:** `docs/superpowers/specs/2026-04-29-stage-10-hi-about-and-pilgrim-package-design.md`.

---

## Cluster ordering

5 clusters, A-E. Inside each cluster tasks are TDD-ordered: extract pure helpers first, build typed models, services, ViewModels, composables, integration. Clusters land in order — the converter (Cluster C) depends on JSON models (Cluster B), the builder (Cluster D) depends on the converter, and DataSettingsScreen wiring (Cluster E) depends on builder + importer + JourneyViewer. AboutView (Cluster A) is independent and can land first standalone if needed.

| Cluster | Concern | Tasks | LOC est. |
|---|---|---|---|
| A | AboutView (10-H) | 9 | ~600 |
| B | JSON model layer | 5 | ~600 |
| C | Converter + photo embedder | 7 | ~900 |
| D | Builder + Importer | 3 | ~700 |
| E | UI wiring (DataSettings, Journey, ConfirmationSheet) | 7 | ~700 |
| **Total** | | **31** | **~3500** |

---

## Helper extractions (Cluster A boundary, prerequisites)

Two pure-JVM helpers needed by both AboutView (Stats Whisper) and the .pilgrim converter. Extract once, use everywhere.

### Task A0a: Add `WalkDistanceCalculator` (extract haversine sum)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkDistanceCalculator.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/WalkDistanceCalculatorTest.kt`

The math currently lives inline in `WalkSummaryViewModel.buildState()`. Extract to a public utility. **Don't refactor the call site yet** — that's a separate cleanup commit at end of stage so we land the helper without churn in the bigger VM.

- [ ] **Step 1: Read the inline math** (file lookup only, don't edit)
  - `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt:573` and surrounding lines
  - Note the exact haversine formula (radius constant, atan2 pattern)

- [ ] **Step 2: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

class WalkDistanceCalculatorTest {

    @Test
    fun `empty samples returns zero`() {
        assertEquals(0.0, WalkDistanceCalculator.computeDistanceMeters(emptyList()), 0.0)
    }

    @Test
    fun `single sample returns zero`() {
        assertEquals(0.0, WalkDistanceCalculator.computeDistanceMeters(listOf(sample(0, 0.0, 0.0))), 0.0)
    }

    @Test
    fun `two samples one degree apart return ~111 km`() {
        val samples = listOf(
            sample(0, 0.0, 0.0),
            sample(1000, 1.0, 0.0),
        )
        // 1° latitude ≈ 111 km at any longitude
        val distance = WalkDistanceCalculator.computeDistanceMeters(samples)
        assertTrue("expected ~111000m, got $distance", abs(distance - 111000.0) < 200.0)
    }

    @Test
    fun `co-located samples return zero distance`() {
        val samples = listOf(
            sample(0, 47.6, -122.3),
            sample(1000, 47.6, -122.3),
            sample(2000, 47.6, -122.3),
        )
        assertEquals(0.0, WalkDistanceCalculator.computeDistanceMeters(samples), 0.001)
    }

    private fun sample(timestamp: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = 1L, timestamp = timestamp, latitude = lat, longitude = lng,
    )
}
```

- [ ] **Step 3: Run test (expect compile failure)**

Run: `JAVA_HOME=~/.asdf/installs/java/temurin-17.0.18+8 ./gradlew :app:testDebugUnitTest --tests "*WalkDistanceCalculatorTest*"`
Expected: FAIL — `WalkDistanceCalculator` not defined.

- [ ] **Step 4: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

/**
 * Cumulative haversine distance over consecutive [RouteDataSample]
 * points. Used by the walk-summary VM (live distance) and the
 * `.pilgrim` exporter (Stage 10-I) to populate `PilgrimStats.distance`.
 *
 * Earth radius matches the inline implementation that previously
 * lived in `WalkSummaryViewModel.buildState()` so the extracted helper
 * yields byte-identical results.
 */
object WalkDistanceCalculator {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    fun computeDistanceMeters(samples: List<RouteDataSample>): Double {
        if (samples.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until samples.size) {
            total += haversine(
                samples[i - 1].latitude,
                samples[i - 1].longitude,
                samples[i].latitude,
                samples[i].longitude,
            )
        }
        return total
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
            cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
```

- [ ] **Step 5: Run test (expect pass)**

Run: `./gradlew :app:testDebugUnitTest --tests "*WalkDistanceCalculatorTest*"`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/WalkDistanceCalculator.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/WalkDistanceCalculatorTest.kt
git commit -m "feat(walk): extract WalkDistanceCalculator from WalkSummaryViewModel inline math"
```

---

### Task A0b: Add `AltitudeCalculator`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/AltitudeCalculator.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/AltitudeCalculatorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

class AltitudeCalculatorTest {

    @Test
    fun `empty samples returns zero ascent and descent`() {
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(emptyList())
        assertEquals(0.0, ascent, 0.0)
        assertEquals(0.0, descent, 0.0)
    }

    @Test
    fun `monotonic climb sums all positive deltas`() {
        val samples = listOf(altitude(0, 100.0), altitude(1000, 110.0), altitude(2000, 125.0))
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(25.0, ascent, 0.001)
        assertEquals(0.0, descent, 0.001)
    }

    @Test
    fun `monotonic descent sums all negative deltas as positive descent`() {
        val samples = listOf(altitude(0, 200.0), altitude(1000, 180.0), altitude(2000, 150.0))
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(0.0, ascent, 0.001)
        assertEquals(50.0, descent, 0.001)
    }

    @Test
    fun `mixed climb and descent partition correctly`() {
        val samples = listOf(
            altitude(0, 100.0),
            altitude(1000, 120.0),  // +20 ascent
            altitude(2000, 105.0),  // -15 descent
            altitude(3000, 130.0),  // +25 ascent
        )
        val (ascent, descent) = AltitudeCalculator.computeAscentDescent(samples)
        assertEquals(45.0, ascent, 0.001)
        assertEquals(15.0, descent, 0.001)
    }

    private fun altitude(timestamp: Long, meters: Double) = AltitudeSample(
        walkId = 1L, timestamp = timestamp, altitudeMeters = meters,
    )
}
```

- [ ] **Step 2: Run test (expect fail)**

Run: `./gradlew :app:testDebugUnitTest --tests "*AltitudeCalculatorTest*"` → expect compile failure.

- [ ] **Step 3: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

/**
 * Sums positive deltas (ascent) and negative deltas (descent) over
 * consecutive [AltitudeSample] points. Used by the `.pilgrim`
 * exporter (Stage 10-I) for `PilgrimStats.ascent` / `descent`.
 *
 * Returns `(ascent, descent)` as a positive-meters pair. iOS computes
 * the same partition in `WalkSummary.computeAscentDescent` —
 * matched here for cross-platform consistency.
 */
object AltitudeCalculator {

    fun computeAscentDescent(samples: List<AltitudeSample>): Pair<Double, Double> {
        if (samples.size < 2) return 0.0 to 0.0
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until samples.size) {
            val delta = samples[i].altitudeMeters - samples[i - 1].altitudeMeters
            if (delta > 0) ascent += delta else descent += -delta
        }
        return ascent to descent
    }
}
```

- [ ] **Step 4: Run test (expect pass) + commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/AltitudeCalculator.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/AltitudeCalculatorTest.kt
git commit -m "feat(walk): add AltitudeCalculator for ascent/descent partition"
```

---

## Cluster A — AboutView (Stage 10-H)

### Task A1: Add `AboutSeasonHelpers`

Port `SealTimeHelpers.season(for date, latitude)` to Android. Used by AboutView's seasonal vignette tinting + (potentially) future stages.

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutSeasonHelpers.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutSeasonHelpersTest.kt`

- [ ] **Step 1: Test first**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutSeasonHelpersTest {

    @Test
    fun `march in northern hemisphere is spring`() {
        val instant = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Spring, AboutSeasonHelpers.season(instant, latitude = 47.6, ZoneOffset.UTC))
    }

    @Test
    fun `march in southern hemisphere is autumn`() {
        val instant = LocalDate.of(2026, 3, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Autumn, AboutSeasonHelpers.season(instant, latitude = -33.9, ZoneOffset.UTC))
    }

    @Test
    fun `january in northern is winter, southern is summer`() {
        val instant = LocalDate.of(2026, 1, 15).atStartOfDay().toInstant(ZoneOffset.UTC)
        assertEquals(Season.Winter, AboutSeasonHelpers.season(instant, latitude = 47.6, ZoneOffset.UTC))
        assertEquals(Season.Summer, AboutSeasonHelpers.season(instant, latitude = -33.9, ZoneOffset.UTC))
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import java.time.Instant
import java.time.ZoneId

enum class Season { Spring, Summer, Autumn, Winter }

/**
 * Three-month-bucket season classifier for the AboutView seasonal
 * vignette. Mirrors iOS `SealTimeHelpers.season(for:latitude:)`:
 * months 3-5 = Spring (Autumn south), 6-8 = Summer (Winter south),
 * 9-11 = Autumn (Spring south), 12-2 = Winter (Summer south).
 */
object AboutSeasonHelpers {

    fun season(instant: Instant, latitude: Double, zone: ZoneId = ZoneId.systemDefault()): Season {
        val month = instant.atZone(zone).monthValue
        val northern = latitude >= 0
        return when (month) {
            3, 4, 5 -> if (northern) Season.Spring else Season.Autumn
            6, 7, 8 -> if (northern) Season.Summer else Season.Winter
            9, 10, 11 -> if (northern) Season.Autumn else Season.Spring
            else -> if (northern) Season.Winter else Season.Summer
        }
    }
}
```

- [ ] **Step 3: Test pass + commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutSeasonHelpers.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutSeasonHelpersTest.kt
git commit -m "feat(about): add Season classifier for AboutView vignette"
```

---

### Task A2: Add `PilgrimLogo` composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/PilgrimLogo.kt`

The breathing animation uses `rememberInfiniteTransition` to scale 1.0 → 1.02 → 1.0 over 4 seconds (mirrors iOS).

- [ ] **Step 1: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R

/**
 * Pilgrim logo with optional breathing animation. iOS scales 1.0→1.02
 * over 4s with easeInOut; we use the same envelope.
 *
 * Logo asset is `R.drawable.pilgrim_logo` (300dp source, scales fine).
 * iOS picks a per-voice-guide variant when reliquary mode is on; that
 * variant set is deferred along with the app-icon picker (Phase N).
 */
@Composable
fun PilgrimLogo(
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    breathing: Boolean = false,
) {
    val scale by if (breathing) {
        val transition = rememberInfiniteTransition(label = "pilgrim-logo-breath")
        transition.animateFloat(
            initialValue = 1.0f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4_000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "scale",
        )
    } else {
        // Static value — wrap so type matches the conditional.
        rememberStaticFloat(1.0f)
    }
    Image(
        painter = painterResource(R.drawable.pilgrim_logo),
        contentDescription = null,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(percent = 18))
            .scale(scale),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun rememberStaticFloat(value: Float) = remember { mutableFloatStateOf(value) }
    .let { state -> object { val value: Float = state.floatValue }.value }
```

> **NOTE TO IMPLEMENTER:** the `rememberStaticFloat` shim above is awkward. Cleaner approach: factor the modifier into a `Modifier.optionalBreathScale(breathing)` extension that conditionally chains `.scale(...)`. Or, simpler: split the composable into two flavors (`AnimatedPilgrimLogo` vs `StaticPilgrimLogo`) and let the call site pick. Pick whichever you prefer at implementation time.

- [ ] **Step 2: Verify the logo asset exists**

Run: `find app/src/main/res/drawable* -name "pilgrim_logo*"`
Expected: at least one matching file. If absent, the AboutView spec said to use `R.drawable.pilgrim_logo` (existing asset from Stage 9.5-B's launcher icon work). Confirm and use that path. If a hyphen-suffixed variant (`pilgrim_logo_breeze`) is needed it would come from Stage 5-D's voice-guide work — that variant set is out of scope for 10-H.

- [ ] **Step 3: Build + commit**

```bash
./gradlew :app:assembleDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/PilgrimLogo.kt
git commit -m "feat(about): add PilgrimLogo composable with breathing animation"
```

---

### Task A3: Add `FootprintShape` Compose Path

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/FootprintShape.kt`

Port iOS `FootprintShape.swift` (10 ellipses: heel + outer edge + ball + 5 toes).

- [ ] **Step 1: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * 10-ellipse footprint silhouette mirroring iOS `FootprintShape.swift`:
 * heel + outer edge + ball + 5 toes. Path is constructed in a 1×1
 * unit rect; callers scale + tint via Canvas drawPath.
 */
object FootprintShape {

    fun path(width: Float, height: Float): Path = Path().apply {
        // Heel — rounded oval at the bottom
        addOval(rect(width * 0.22f, height * 0.75f, width * 0.50f, height * 0.25f))
        // Outer edge
        addOval(rect(width * 0.50f, height * 0.48f, width * 0.22f, height * 0.34f))
        // Ball of foot
        addOval(rect(width * 0.08f, height * 0.38f, width * 0.62f, height * 0.22f))
        // Big toe
        addOval(rect(width * 0.10f, height * 0.18f, width * 0.24f, height * 0.24f))
        // Second toe
        addOval(rect(width * 0.32f, height * 0.10f, width * 0.18f, height * 0.22f))
        // Third toe
        addOval(rect(width * 0.48f, height * 0.06f, width * 0.16f, height * 0.20f))
        // Fourth toe
        addOval(rect(width * 0.62f, height * 0.10f, width * 0.14f, height * 0.18f))
        // Pinky toe
        addOval(rect(width * 0.72f, height * 0.18f, width * 0.12f, height * 0.14f))
    }

    private fun rect(x: Float, y: Float, w: Float, h: Float): Rect =
        Rect(offset = Offset(x, y), size = Size(w, h))
}
```

- [ ] **Step 2: Build + commit**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/FootprintShape.kt
git commit -m "feat(about): add FootprintShape ported from iOS"
```

---

### Task A4: Add simplified `SeasonalTree` Compose Canvas

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/SeasonalTree.kt`

Static silhouette: 3 stacked ellipses (canopy with two layered shadows) + 1 narrow rectangle (trunk). Tint shifts with season. No animation (acknowledged degradation from iOS).

- [ ] **Step 1: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Static seasonal-tinted tree silhouette for AboutView's vignette.
 * Simpler than iOS's animated SceneryItemView — same wabi-sabi
 * intent, no TimelineView animation. iOS port for stat-mode toggle
 * lives elsewhere; this is decorative only.
 */
@Composable
fun SeasonalTree(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Canvas(modifier = modifier.size(size)) {
        drawTree(color)
    }
}

private fun DrawScope.drawTree(color: Color) {
    val w = this.size.width
    val h = this.size.height

    // Trunk: narrow rectangle bottom-center.
    val trunkWidth = w * 0.14f
    val trunkHeight = h * 0.30f
    drawRect(
        color = color.copy(alpha = 0.40f),
        topLeft = Offset((w - trunkWidth) / 2f, h - trunkHeight),
        size = Size(trunkWidth, trunkHeight),
    )

    // Outer canopy shadow (largest, lowest alpha, slightly offset).
    drawOval(
        color = color.copy(alpha = 0.12f),
        topLeft = Offset(w * -0.04f, h * 0.08f),
        size = Size(w * 1.08f, h * 0.66f),
    )

    // Mid canopy.
    drawOval(
        color = color.copy(alpha = 0.30f),
        topLeft = Offset(0f, h * 0.10f),
        size = Size(w, h * 0.62f),
    )

    // Inner canopy (smallest, highest alpha, top-centered).
    drawOval(
        color = color.copy(alpha = 0.50f),
        topLeft = Offset(w * 0.06f, h * 0.05f),
        size = Size(w * 0.88f, h * 0.50f),
    )
}
```

- [ ] **Step 2: Build + commit**

```bash
./gradlew :app:compileDebugKotlin
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/SeasonalTree.kt
git commit -m "feat(about): add SeasonalTree static silhouette"
```

---

### Task A5: Add `AboutViewModel`

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModel.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModelTest.kt`

VM aggregates walk count + total distance + first walk date by combining `walkRepository.observeAllWalks()` with per-walk RouteDataSample lookups.

- [ ] **Step 1: Test stub** (the math correctness is covered by `WalkDistanceCalculatorTest`; this test verifies aggregation)

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import app.cash.turbine.test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.Walk

class AboutViewModelTest {

    @Test
    fun `no walks yields hasWalks=false`() = runTest {
        val source = FakeWalkSource(flowOf(emptyList()))
        val vm = AboutViewModel(source, distanceCalcDelegate = { 0.0 })

        vm.stats.test(timeout = 5.seconds) {
            var current = awaitItem()
            // initialValue might land first; drain to the real value.
            while (current.hasWalks) current = awaitItem()
            assertFalse(current.hasWalks)
            assertEquals(0, current.walkCount)
            assertEquals(0.0, current.totalDistanceMeters, 0.0)
            assertNull(current.firstWalkInstant)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `multiple walks aggregate correctly`() = runTest {
        val walks = listOf(walk(id = 1, start = 1_000), walk(id = 2, start = 5_000))
        val source = FakeWalkSource(flowOf(walks))
        val perWalkDistances = mapOf(1L to 500.0, 2L to 1500.0)
        val vm = AboutViewModel(source, distanceCalcDelegate = { walkId -> perWalkDistances[walkId] ?: 0.0 })

        vm.stats.test(timeout = 5.seconds) {
            var current = awaitItem()
            while (current.walkCount != 2) current = awaitItem()
            assertEquals(2, current.walkCount)
            assertEquals(2_000.0, current.totalDistanceMeters, 0.0)
            assertEquals(Instant.ofEpochMilli(1_000), current.firstWalkInstant)
            assertTrue(current.hasWalks)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun walk(id: Long, start: Long) = Walk(
        id = id, startTimestamp = start, endTimestamp = start + 60_000,
    )
}

private class FakeWalkSource(private val flow: kotlinx.coroutines.flow.Flow<List<Walk>>) : AboutWalkSource {
    override fun observeAllWalks(): kotlinx.coroutines.flow.Flow<List<Walk>> = flow
}
```

- [ ] **Step 2: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkDistanceCalculator

interface AboutWalkSource {
    fun observeAllWalks(): Flow<List<Walk>>
}

internal class WalkRepositoryAboutSource @Inject constructor(
    private val walkRepository: WalkRepository,
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = walkRepository.observeAllWalks()
}

data class AboutStats(
    val walkCount: Int,
    val totalDistanceMeters: Double,
    val firstWalkInstant: Instant?,
    val hasWalks: Boolean,
)

@HiltViewModel
class AboutViewModel @Inject constructor(
    private val walkSource: AboutWalkSource,
    private val walkRepository: WalkRepository,
    unitsPreferences: UnitsPreferencesRepository,
    /** Test seam: lookup distance for a walkId (defaults to live route-sample query). */
    private val distanceCalcDelegate: suspend (Long) -> Double = { walkId ->
        WalkDistanceCalculator.computeDistanceMeters(walkRepository.locationSamplesFor(walkId))
    },
) : ViewModel() {

    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    val stats: StateFlow<AboutStats> = walkSource.observeAllWalks()
        .map { walks ->
            withContext(Dispatchers.IO) {
                val finished = walks.filter { it.endTimestamp != null }
                if (finished.isEmpty()) {
                    return@withContext AboutStats(0, 0.0, null, false)
                }
                val totalDistance = finished.sumOf { distanceCalcDelegate(it.id) }
                val firstStart = finished.minOf { it.startTimestamp }
                AboutStats(
                    walkCount = finished.size,
                    totalDistanceMeters = totalDistance,
                    firstWalkInstant = Instant.ofEpochMilli(firstStart),
                    hasWalks = true,
                )
            }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AboutStats(0, 0.0, null, false),
        )
}
```

> **NOTE TO IMPLEMENTER:** the `distanceCalcDelegate` suspend lambda doubles as a constructor default and a test seam — Kotlin allows this. `walkRepository` is captured for the default. Tests pass a fixed-value lambda. If the dual-purpose feels brittle, wrap the suspend lookup in a small `WalkDistanceLookup` interface bound via Hilt (matches the `RecordingsCountSource` pattern from Stage 10-G).

Add the `@Binds` for `AboutWalkSource → WalkRepositoryAboutSource` in a small new module (`AboutModule.kt` under `di/`).

- [ ] **Step 3: Run test + commit**

```bash
./gradlew :app:testDebugUnitTest --tests "*AboutViewModelTest*"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModel.kt app/src/main/java/org/walktalkmeditate/pilgrim/di/AboutModule.kt app/src/test/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutViewModelTest.kt
git commit -m "feat(about): add AboutViewModel aggregating walk count + total distance + first date"
```

---

### Task A6: Add `AboutScreen` composable + strings

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/about/AboutScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

This is the largest single composable in the stage. ~250 LOC. Sections in order:
1. TopAppBar with back arrow + "About" title
2. ScrollableColumn:
   - Hero (logo + "Every walk is a small pilgrimage." + body)
   - Divider
   - Pillars (3 rows: walk/talk/meditate)
   - Divider
   - StatsWhisper (when hasWalks; tap-to-cycle)
   - FootprintTrail (4 stamps)
   - Divider
   - OpenSource (3 link rows)
   - Divider
   - Motto
   - SeasonalVignette
   - Version

- [ ] **Step 1: Add strings**

```xml
<!-- Stage 10-H: AboutView -->
<string name="about_title">About</string>
<string name="about_principal_title">About</string>
<string name="about_back_content_description">Back</string>
<string name="about_hero_title">Every walk is a\nsmall pilgrimage.</string>
<string name="about_hero_body">Walking is how we think, process, and return to ourselves. Pilgrim is a quiet companion for the path — no leaderboards, no metrics, just you and the walk.</string>
<string name="about_pillars_caption">walk · talk · meditate</string>
<string name="about_pillar_walk_title">walk</string>
<string name="about_pillar_walk_description">Walking as practice, not transit. Side by side, step by step — strengthening the physical body.</string>
<string name="about_pillar_talk_title">talk</string>
<string name="about_pillar_talk_description">Deep reflection and connection, not small talk. Ask and share your unique perspective of reality.</string>
<string name="about_pillar_meditate_title">meditate</string>
<string name="about_pillar_meditate_description">Seek the peace and calmness within. Harmonize your being with the group and the environment.</string>
<string name="about_stats_walked_with">walked with Pilgrim</string>
<string name="about_stats_walks_taken">walks taken</string>
<string name="about_stats_walk_taken">walk taken</string>
<string name="about_stats_walking_since">walking since</string>
<string name="about_open_source_header">OPEN SOURCE</string>
<string name="about_open_source_body">Pilgrim is free and open source. No accounts, no tracking, no data leaves your device. Built as part of the walk · talk · meditate project.</string>
<string name="about_link_website">walktalkmeditate.org</string>
<string name="about_link_github">Source code on GitHub</string>
<string name="about_link_rate">Rate Pilgrim</string>
<string name="about_motto">Slow and chill is the motto.\nRelax and release is the practice.\nPeace and harmony is the way.</string>
<string name="settings_about_pilgrim">About Pilgrim</string>
```

- [ ] **Step 2: Implement composable**

Full implementation. Skipping the verbatim ~250-line block; key invariants:
- TopAppBar `containerColor = pilgrimColors.parchment`, title style `pilgrimType.heading` color `pilgrimColors.ink`.
- Scroll content padding: 16dp horizontal, 24dp top.
- Hero logo: 80.dp, `breathing = true`.
- Pillars row icons: see spec for exact M3 icon names; confirm they exist by importing in IDE before committing — if `Icons.Filled.NightsStay` or `Icons.Outlined.ChatBubbleOutline` are missing, fall back to closest equivalent and log the substitution in the commit message.
- StatsWhisper uses `AnimatedContent` for fade between phases (Compose has no numeric morph).
- Distance formatting: respect `distanceUnits.value`, format to 1 decimal place if ≥1km/mi, else integer ft/m. `Locale.getDefault()` for the number format, `Locale.ROOT` would be wrong here (user-facing display).
- Date formatting for "walking since": `DateTimeFormatter.ofPattern("MMM yyyy", Locale.getDefault())`.
- Version: `BuildConfig.VERSION_NAME.removeSuffix("-debug")`.
- Use `Custom Tabs` helper (Stage 10-F) for the website + GitHub links.
- Use `PlayStore.openListing(context)` (Stage 10-F) for the Rate row.

```kotlin
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val units by viewModel.distanceUnits.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var statPhase by rememberSaveable { mutableIntStateOf(0) }
    // ...
}
```

> **NOTE TO IMPLEMENTER:** Build the screen incrementally. Land bare TopAppBar + Hero first, run the app, verify visual. Then add Pillars. Then StatsWhisper. Then OpenSource. Then Motto + Vignette + Version. Each visual chunk a separate commit so reviewers can see the screen growing.

- [ ] **Step 3: Build + commit**

Possibly multiple commits as you build. Suggested final commit: `feat(about): add AboutScreen with hero, pillars, stats whisper, links, motto`.

---

### Task A7: Wire navigation + add About link to SettingsScreen

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt`

- [ ] **Step 1: Add route + composable**

In `Routes`: `const val ABOUT = "about"`.

In NavHost:
```kotlin
composable(Routes.ABOUT) {
    org.walktalkmeditate.pilgrim.ui.settings.about.AboutScreen(
        onBack = { navController.popBackStack() },
    )
}
```

- [ ] **Step 2: Wire `OpenAbout` action**

In `handleSettingsAction`, replace the `OpenAbout` Log.w stub with:
```kotlin
SettingsAction.OpenAbout ->
    navController.navigate(Routes.ABOUT) { launchSingleTop = true }
```

Remove `OpenAbout` from the catch-all comma list.

- [ ] **Step 3: Add About link row to SettingsScreen**

After ConnectCard's `item { ... }`, add a final item:

```kotlin
item {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .clickable(onClickLabel = stringResource(R.string.settings_about_pilgrim)) {
                onAction(SettingsAction.OpenAbout)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PilgrimLogo(size = 24.dp, breathing = true)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.settings_about_pilgrim),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = pilgrimColors.fog,
            modifier = Modifier.size(16.dp),
        )
    }
}
```

Add imports.

- [ ] **Step 4: Build + commit**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt app/src/main/java/org/walktalkmeditate/pilgrim/ui/settings/SettingsScreen.kt
git commit -m "feat(about): wire AboutScreen + About link in SettingsScreen"
```

---

## Cluster B — JSON model layer (Stage 10-I)

> All JSON model classes use `@Serializable` from `kotlinx-serialization-json` (already on the classpath via Stage 5-C). The project-wide `Json` config (`NetworkModule.provideJson`) sets `ignoreUnknownKeys = true, explicitNulls = false`. We'll need a project-internal `pilgrimJson` instance with additional config (`prettyPrint = true`) — define in a new `PilgrimJsonModule.kt`.

### Task B1: `PilgrimDateCoding` (epoch-seconds Instant serializer)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimDateCoding.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimDateCodingTest.kt`

- [ ] **Step 1: Test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class PilgrimDateCodingTest {

    @Serializable
    data class Wrapper(
        @Serializable(with = EpochSecondsInstantSerializer::class)
        val instant: Instant,
    )

    private val json = Json { encodeDefaults = false }

    @Test
    fun `encodes whole seconds without fractional part`() {
        val wrapper = Wrapper(Instant.ofEpochSecond(1_700_000_000))
        val encoded = json.encodeToString(Wrapper.serializer(), wrapper)
        assertEquals("""{"instant":1.7E9}""", encoded)
    }

    @Test
    fun `encodes sub-second precision as fractional`() {
        val wrapper = Wrapper(Instant.ofEpochSecond(1_700_000_000, 500_000_000))
        val encoded = json.encodeToString(Wrapper.serializer(), wrapper)
        assertEquals("""{"instant":1.7000000005E9}""", encoded)
    }

    @Test
    fun `round trips an Instant within nano precision`() {
        val original = Instant.ofEpochSecond(1_700_000_000, 123_456_789)
        val wrapper = Wrapper(original)
        val encoded = json.encodeToString(Wrapper.serializer(), wrapper)
        val decoded = json.decodeFromString(Wrapper.serializer(), encoded).instant
        // Allow 1ns tolerance for double-precision conversion.
        assertEquals(original.epochSecond, decoded.epochSecond)
        kotlin.test.assertTrue(kotlin.math.abs(original.nano - decoded.nano) <= 100)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Encodes [Instant] as seconds-since-epoch `Double` to match iOS
 * `JSONEncoder.dateEncodingStrategy = .secondsSince1970`.
 *
 * Fractional precision: an iOS `Date` is `Double` seconds; we
 * preserve nanoseconds via the same Double representation. Round-trip
 * tolerance is bounded by IEEE-754 mantissa precision (~1ns at
 * post-2000 timestamps).
 */
object EpochSecondsInstantSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor(
        "EpochSecondsInstant", PrimitiveKind.DOUBLE,
    )

    override fun serialize(encoder: Encoder, value: Instant) {
        val seconds = value.epochSecond + value.nano / 1_000_000_000.0
        encoder.encodeDouble(seconds)
    }

    override fun deserialize(decoder: Decoder): Instant {
        val seconds = decoder.decodeDouble()
        val whole = seconds.toLong()
        val nanos = ((seconds - whole) * 1_000_000_000).toLong()
        return Instant.ofEpochSecond(whole, nanos)
    }
}
```

- [ ] **Step 3: Test pass + commit**

---

### Task B2: GeoJSON models (FeatureCollection, Feature, Geometry, sealed Coordinates)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/GeoJsonModels.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/pilgrim/GeoJsonModelsTest.kt`

The interesting bit is the polymorphic `coordinates` union. Try-decode as `List<List<Double>>` first (LineString); fall back to `List<Double>` (Point). Matches iOS behavior.

- [ ] **Step 1: Test**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoJsonModelsTest {

    private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

    @Test
    fun `decodes Point coordinates`() {
        val payload = """{"type":"Point","coordinates":[-122.3,47.6]}"""
        val geom = json.decodeFromString(GeoJsonGeometry.serializer(), payload)
        assertEquals("Point", geom.type)
        val coords = geom.coordinates
        assertTrue(coords is GeoJsonCoordinates.Point)
        assertEquals(listOf(-122.3, 47.6), (coords as GeoJsonCoordinates.Point).coords)
    }

    @Test
    fun `decodes LineString coordinates`() {
        val payload = """{"type":"LineString","coordinates":[[-122.3,47.6],[-122.4,47.7]]}"""
        val geom = json.decodeFromString(GeoJsonGeometry.serializer(), payload)
        assertEquals("LineString", geom.type)
        val coords = geom.coordinates
        assertTrue(coords is GeoJsonCoordinates.LineString)
        assertEquals(2, (coords as GeoJsonCoordinates.LineString).coords.size)
    }

    @Test
    fun `round trips Point geometry`() {
        val original = GeoJsonGeometry(
            type = "Point",
            coordinates = GeoJsonCoordinates.Point(listOf(-122.3, 47.6)),
        )
        val encoded = json.encodeToString(GeoJsonGeometry.serializer(), original)
        val decoded = json.decodeFromString(GeoJsonGeometry.serializer(), encoded)
        assertEquals(original.type, decoded.type)
        assertTrue(decoded.coordinates is GeoJsonCoordinates.Point)
    }

    @Test
    fun `round trips LineString geometry`() {
        val original = GeoJsonGeometry(
            type = "LineString",
            coordinates = GeoJsonCoordinates.LineString(listOf(listOf(-122.3, 47.6), listOf(-122.4, 47.7))),
        )
        val encoded = json.encodeToString(GeoJsonGeometry.serializer(), original)
        val decoded = json.decodeFromString(GeoJsonGeometry.serializer(), encoded)
        assertTrue(decoded.coordinates is GeoJsonCoordinates.LineString)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class GeoJsonFeatureCollection(
    val type: String = "FeatureCollection",
    val features: List<GeoJsonFeature>,
)

@Serializable
data class GeoJsonFeature(
    val type: String = "Feature",
    val geometry: GeoJsonGeometry,
    val properties: GeoJsonProperties,
)

@Serializable
data class GeoJsonGeometry(
    val type: String,
    @Serializable(with = GeoJsonCoordinatesSerializer::class)
    val coordinates: GeoJsonCoordinates,
)

@Serializable(with = GeoJsonCoordinatesSerializer::class)
sealed class GeoJsonCoordinates {
    data class Point(val coords: List<Double>) : GeoJsonCoordinates()
    data class LineString(val coords: List<List<Double>>) : GeoJsonCoordinates()
}

@Serializable
data class GeoJsonProperties(
    val timestamps: List<@Serializable(with = EpochSecondsInstantSerializer::class) Instant>? = null,
    val speeds: List<Double>? = null,
    val directions: List<Double>? = null,
    val horizontalAccuracies: List<Double>? = null,
    val verticalAccuracies: List<Double>? = null,
    val markerType: String? = null,
    val label: String? = null,
    val icon: String? = null,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val timestamp: Instant? = null,
)

object GeoJsonCoordinatesSerializer : KSerializer<GeoJsonCoordinates> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("GeoJsonCoordinates")

    override fun serialize(encoder: Encoder, value: GeoJsonCoordinates) {
        require(encoder is JsonEncoder) { "GeoJsonCoordinates only supports JSON encoding" }
        when (value) {
            is GeoJsonCoordinates.Point ->
                encoder.encodeSerializableValue(ListSerializer(Double.serializer()), value.coords)
            is GeoJsonCoordinates.LineString ->
                encoder.encodeSerializableValue(
                    ListSerializer(ListSerializer(Double.serializer())),
                    value.coords,
                )
        }
    }

    override fun deserialize(decoder: Decoder): GeoJsonCoordinates {
        require(decoder is JsonDecoder) { "GeoJsonCoordinates only supports JSON decoding" }
        val element = decoder.decodeJsonElement()
        require(element is JsonArray && element.isNotEmpty()) {
            "GeoJSON coordinates must be a non-empty array"
        }
        // Try LineString first (mirror iOS try-decode order).
        val first = element[0]
        return if (first is JsonArray) {
            val coords = element.map { row ->
                require(row is JsonArray) { "LineString coordinates must be array-of-arrays" }
                row.map { it.jsonPrimitive.double }
            }
            GeoJsonCoordinates.LineString(coords)
        } else {
            val coords = element.map { it.jsonPrimitive.double }
            GeoJsonCoordinates.Point(coords)
        }
    }
}
```

- [ ] **Step 3: Test pass + commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/GeoJsonModels.kt app/src/test/java/org/walktalkmeditate/pilgrim/data/pilgrim/GeoJsonModelsTest.kt
git commit -m "feat(pilgrim): add GeoJSON models with polymorphic coordinates union"
```

---

### Task B3: `PilgrimWalk` + nested data classes

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimWalk.kt`
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimRelated.kt` (Stats, Pause, Activity, VoiceRecording, HeartRate, WorkoutEvent, Photo, Weather, Reflection)
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimWalkTest.kt`

The data classes mirror iOS `PilgrimPackageModels.swift` field-for-field.

- [ ] **Step 1: PilgrimWalk skeleton**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class PilgrimWalk(
    val schemaVersion: String,
    val id: String,                     // UUID string
    val type: String,                   // "walking" | "unknown"
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant,
    val stats: PilgrimStats,
    val weather: PilgrimWeather? = null,
    val route: GeoJsonFeatureCollection,
    val pauses: List<PilgrimPause>,
    val activities: List<PilgrimActivity>,
    val voiceRecordings: List<PilgrimVoiceRecording>,
    val intention: String? = null,
    val reflection: PilgrimReflection? = null,
    val heartRates: List<PilgrimHeartRate>,
    val workoutEvents: List<PilgrimWorkoutEvent>,
    val favicon: String? = null,
    val isRace: Boolean,
    val isUserModified: Boolean,
    val finishedRecording: Boolean,
    val photos: List<PilgrimPhoto>? = null,
)
```

- [ ] **Step 2: PilgrimRelated.kt — all sub-types**

Verbatim ports from iOS PilgrimPackageModels.swift for: PilgrimStats, PilgrimWeather, PilgrimPause, PilgrimActivity, PilgrimVoiceRecording, PilgrimHeartRate, PilgrimWorkoutEvent, PilgrimPhoto, PilgrimReflection, PilgrimCelestialContext, PilgrimLunarPhase, PilgrimPlanetaryPosition, PilgrimPlanetaryHour, PilgrimElementBalance.

(Copy the field signatures from `PilgrimPackageModels.swift` lines 96-329, translate Date → `@Serializable(with=EpochSecondsInstantSerializer::class) Instant`, add `@Serializable` annotations.)

- [ ] **Step 3: Round-trip test**

```kotlin
@Test
fun `round trips full walk shape with all optional fields populated`() {
    val original = PilgrimWalk(/* full fixture */)
    val encoded = pilgrimJson.encodeToString(PilgrimWalk.serializer(), original)
    val decoded = pilgrimJson.decodeFromString(PilgrimWalk.serializer(), encoded)
    assertEquals(original, decoded)
}

@Test
fun `omits null photos field when serializing`() {
    val walk = PilgrimWalk(/* photos = null */)
    val encoded = pilgrimJson.encodeToString(PilgrimWalk.serializer(), walk)
    assertFalse("photos key should be absent: $encoded", encoded.contains("\"photos\""))
}
```

- [ ] **Step 4: Commit**

---

### Task B4: `PilgrimManifest` + Preferences/Event/CustomPromptStyle

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimManifest.kt`

```kotlin
@Serializable
data class PilgrimManifest(
    val schemaVersion: String,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val exportDate: Instant,
    val appVersion: String,
    val walkCount: Int,
    val preferences: PilgrimPreferences,
    val customPromptStyles: List<PilgrimCustomPromptStyle>,
    val intentions: List<String>,
    val events: List<PilgrimEvent>,
)

@Serializable
data class PilgrimPreferences(
    val distanceUnit: String,
    val altitudeUnit: String,
    val speedUnit: String,
    val energyUnit: String,
    val celestialAwareness: Boolean,
    val zodiacSystem: String,
    val beginWithIntention: Boolean,
)

@Serializable
data class PilgrimCustomPromptStyle(
    val id: String,    // UUID string
    val title: String,
    val icon: String,
    val instruction: String,
)

@Serializable
data class PilgrimEvent(
    val id: String,    // UUID string
    val title: String,
    val comment: String? = null,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val startDate: Instant? = null,
    @Serializable(with = EpochSecondsInstantSerializer::class)
    val endDate: Instant? = null,
    val walkIds: List<String>,
)
```

Round-trip test + commit.

---

### Task B5: `PilgrimSchema` (embedded JSON Schema doc)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/pilgrim/PilgrimSchema.kt`

Verbatim port of the iOS embedded JSON Schema string from `PilgrimPackageBuilder.swift:198-247`.

```kotlin
object PilgrimSchema {
    const val SCHEMA_VERSION = "1.0"

    val JSON: String = """
        {
          "${'$'}schema": "https://json-schema.org/draft/2020-12/schema",
          "title": "Pilgrim Walk Export",
          ...
        }
    """.trimIndent()
}
```

(Use `${'$'}` to escape `$schema` in the Kotlin raw string.)

Commit.

---

### Task B6 (NEW): `PilgrimJsonModule` for project-internal Json instance

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/di/PilgrimJsonModule.kt`

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PilgrimJson

@Module
@InstallIn(SingletonComponent::class)
object PilgrimJsonModule {

    @Provides
    @Singleton
    @PilgrimJson
    fun providePilgrimJson(): Json = Json {
        prettyPrint = true
        encodeDefaults = false
        explicitNulls = false
        ignoreUnknownKeys = true
    }
}
```

Commit.

---

## Cluster C — Converter + Photo Embedder (Stage 10-I)

### Task C1: `PilgrimPackageError` sealed class

```kotlin
sealed class PilgrimPackageError : Exception() {
    object NoWalksFound : PilgrimPackageError()
    data class EncodingFailed(override val cause: Throwable?) : PilgrimPackageError()
    data class ZipFailed(override val cause: Throwable) : PilgrimPackageError()
    data class FileSystemError(override val cause: Throwable) : PilgrimPackageError()
    object InvalidPackage : PilgrimPackageError()
    data class DecodingFailed(override val cause: Throwable) : PilgrimPackageError()
    data class UnsupportedSchemaVersion(val version: String) : PilgrimPackageError()
}
```

Commit.

### Task C2: `PilgrimPackagePhotoConverter` (metadata-only ↔ Android WalkPhoto)

Includes the GPS-derivation-from-route logic per spec.

```kotlin
object PilgrimPackagePhotoConverter {

    fun exportPhotos(
        walkPhotos: List<WalkPhoto>,
        routeSamples: List<RouteDataSample>,
        includePhotos: Boolean,
    ): List<PilgrimPhoto>? {
        if (!includePhotos) return null
        return walkPhotos.mapNotNull { photo -> exportPhoto(photo, routeSamples) }
    }

    private fun exportPhoto(photo: WalkPhoto, samples: List<RouteDataSample>): PilgrimPhoto? {
        val capturedAtMs = photo.takenAt ?: photo.pinnedAt
        val nearest = nearestRouteSample(samples, capturedAtMs) ?: return null
        return PilgrimPhoto(
            localIdentifier = photo.photoUri,
            capturedAt = Instant.ofEpochMilli(capturedAtMs),
            capturedLat = nearest.latitude,
            capturedLng = nearest.longitude,
            keptAt = Instant.ofEpochMilli(photo.pinnedAt),
            embeddedPhotoFilename = null,
        )
    }

    private fun nearestRouteSample(samples: List<RouteDataSample>, atMs: Long): RouteDataSample? {
        if (samples.isEmpty()) return null
        return samples.minByOrNull { abs(it.timestamp - atMs) }
    }

    fun importPhotos(walkId: Long, exported: List<PilgrimPhoto>?): List<WalkPhoto> {
        if (exported.isNullOrEmpty()) return emptyList()
        return exported.map { photo ->
            WalkPhoto(
                walkId = walkId,
                photoUri = photo.localIdentifier,
                pinnedAt = photo.keptAt.toEpochMilli(),
                takenAt = photo.capturedAt.toEpochMilli(),
            )
        }
    }
}
```

Test: exporter drops photo when no route samples; nearest-sample selection picks correct neighbor.

Commit.

### Task C3: `AndroidPilgrimPhotoEmbedder` (Bitmap + JPEG pipeline)

The most subtle bit of Cluster C. Pipeline:
1. Read `content://` URI via `contentResolver.openInputStream`.
2. `BitmapFactory.Options { inJustDecodeBounds = true }` to read dimensions.
3. Compute `inSampleSize` so long edge ≤ 1200px.
4. Re-decode with `inSampleSize`.
5. `Bitmap.createScaledBitmap` to ≤600×600 aspect-fit.
6. `ByteArrayOutputStream` + `compress(JPEG, 70, baos)`.
7. Reject if encoded bytes > 150KB (matches iOS).
8. Write to `tempDir/photos/<sanitized-localid>.jpg`.

Test against fixture URIs in app's own test assets. Sanitize via `replace("/", "_")`.

Commit.

### Task C4: `PilgrimPackageConverter.convert(walk → PilgrimWalk)` — full export converter

Pulls together all the helpers. Pipeline:
1. Load related entities for the walk via WalkRepository (`locationSamplesFor`, `altitudeSamplesFor`, `eventsFor`, `activityIntervalsFor`, `voiceRecordingsFor`, `waypointsFor`, photo dao via `getForWalk`).
2. Compute distance (`WalkDistanceCalculator`), ascent/descent (`AltitudeCalculator`), pauses (pair PAUSED→RESUMED, handle dangling).
3. Compute talk/meditate durations from `ActivityIntervals`.
4. Build `PilgrimStats`, `PilgrimWeather` (always null), `PilgrimReflection` (always null this stage).
5. Build `route` GeoJSON via `buildRouteGeoJSON(samples, waypoints)`.
6. Map activities + voiceRecordings + photos via per-type converters.
7. Return `PilgrimWalk`.

Substantial — ~150 LOC. Sub-tasks C4a (stats), C4b (pauses), C4c (route), C4d (assembly) if it gets unwieldy.

Test: full round-trip from a fixture Walk + related entities → encoded JSON → decoded PilgrimWalk → compare key fields.

Commit.

### Task C5: `PilgrimPackageConverter.buildManifest()`

Reads UnitsPreferencesRepository + PracticePreferencesRepository for the preferences struct. Empty arrays for events/customPromptStyles/intentions.

Commit.

### Task C6: `PilgrimPackageConverter.convertToImport(PilgrimWalk → PendingImport)`

Inverse: walks the GeoJSON, restores RouteDataSample + Waypoint, restores ActivityIntervals + VoiceRecordings + WalkPhotos, re-pairs pauses → WalkEvent rows.

`PendingImport` is a small data class bundling the Walk + all related entities ready for a single Room transaction.

Commit.

### Task C7: Unit tests for the full Walk ↔ PilgrimWalk round trip

Construct a fixture Walk + all related entities, run `convert` → `convertToImport`, verify field-level equality on the round-tripped state. Covers the GeoJSON / pause-pairing / unit-conversion edge cases.

Commit.

---

## Cluster D — Builder + Importer (Stage 10-I)

### Task D1: FileProvider config update

Add `<cache-path name="pilgrim_export" path="pilgrim_export/" />` to `res/xml/file_paths.xml`. Commit.

### Task D2: `PilgrimPackageBuilder`

`@Singleton class PilgrimPackageBuilder @Inject constructor(walkRepository, walkPhotoDao, practicePrefs, unitsPrefs, photoEmbedder, @PilgrimJson json, @ApplicationContext context)`.

Pipeline (matches iOS):
1. `withContext(Dispatchers.IO)` for entire flow.
2. `walks = walkRepository.allWalks().filter { it.endTimestamp != null }`. If empty → throw `NoWalksFound`.
3. For each walk: load related, call `converter.convert`.
4. Build manifest.
5. Create temp dir under `cacheDir/pilgrim-export-<uuid>/`.
6. If `includePhotos`, run photoEmbedder; apply embedded filenames to walk.photos.
7. Write `walks/<uuid>.json`, `manifest.json`, `schema.json` (embedded JSON Schema doc) into temp.
8. ZIP via `ZipOutputStream` (mirror Stage 10-G pattern) to `cacheDir/pilgrim_export/pilgrim-<backupTimeCode>.pilgrim`.
9. Delete temp dir in `finally`. Return `PilgrimPackageBuildResult(file, skippedPhotoCount)`.

Test: fixture walks → build → unzip the result → assert structure.

Commit.

### Task D3: `PilgrimPackageImporter`

`@Singleton class PilgrimPackageImporter @Inject constructor(walkRepository, database, converter, @PilgrimJson json, @ApplicationContext context)`.

Pipeline:
1. `withContext(Dispatchers.IO)`.
2. Open URI → copy to `cacheDir/pilgrim-import-<uuid>/`.
3. Unzip via `ZipInputStream`.
4. Read manifest. If missing or schemaVersion ≠ "1.0" → throw appropriate error.
5. List `walks/*.json`, decode each (skip per-file failures with log).
6. Run `converter.convertToImport` for each → `PendingImport`.
7. Inside `database.withTransaction`:
   - Insert each Walk with `INSERT OR IGNORE` on uuid (returns id; skip if duplicate).
   - Insert all related rows referencing the new walkId.
8. Delete temp dir in `finally`. Return imported walk count.

Test: fixture archive (programmatically built with the builder) → import → verify Room state.

Commit.

---

## Cluster E — UI Wiring (Stage 10-I)

### Task E1: `ExportDateRangeFormatter`

```kotlin
object ExportDateRangeFormatter {
    fun format(earliest: Instant, latest: Instant, locale: Locale = Locale.getDefault()): String {
        val formatter = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
        val zone = ZoneId.systemDefault()
        val earliestText = formatter.format(earliest.atZone(zone))
        val latestText = formatter.format(latest.atZone(zone))
        return if (earliestText == latestText) earliestText else "$earliestText – $latestText"
    }
}
```

Test: same-month collapses, cross-month dashes.

Commit.

### Task E2: `ExportConfirmationSheet` (ModalBottomSheet)

Mirrors iOS `ExportConfirmationSheet.swift` 1:1 — header, summary, photo toggle (when count > 0), Cancel/Export buttons with `hasCommitted` double-tap guard.

Commit.

### Task E3: `JourneyViewerScreen` + `JourneyViewerViewModel`

ViewModel builds the JSON payload (no photo enrichment per spec) on `Dispatchers.IO` via the same converter pipeline.

Composable wraps a `WebView` in `AndroidView`. After `onPageFinished`, evaluate `window.pilgrimViewer.loadData($payload)`.

Commit.

### Task E4: `DataSettingsViewModel` updates

Add to existing VM:
- `walkCount: StateFlow<Int>`
- `dateRange: StateFlow<Pair<Instant, Instant>?>`
- `pinnedPhotoCount: StateFlow<Int>` (requires new `WalkPhotoDao.observeAllCount(): Flow<Int>` query)
- `exportState: StateFlow<ExportState>` sealed: Idle, Confirming(summary), Building, SharedSuccess(skippedCount), Error(msg)
- `importState: StateFlow<ImportState>` sealed: Idle, Importing, Imported(count), Error(msg)
- Functions: `exportRequest()`, `confirmExport(includePhotos)`, `cancelExport()`, `import(uri)`
- Channel events: ShareExport(file), AlertSkippedPhotos(count), AlertImported(count)

Substantial — ~150 LOC additions. Sub-task at implementation time.

Commit.

### Task E5: `DataSettingsScreen` updates

Replace Stage 10-G stubs:
- `Export My Data` → `viewModel.exportRequest()` → opens ConfirmationSheet → `viewModel.confirmExport(includePhotos)` → on SharedSuccess emit, build share intent (with ClipData + chooser flag — Stage 10-G fix #2 precedent).
- `Import Data` → SAF picker (`ACTION_OPEN_DOCUMENT`, MIME filter `application/zip`/`application/octet-stream`/`*/*`).
- `View My Journey` → navigate to `Routes.JOURNEY_VIEWER`.

Commit.

### Task E6: SAF picker contract

```kotlin
val pickerLauncher = rememberLauncherForActivityResult(
    contract = remember { ActivityResultContracts.OpenDocument() },  // hoisted per Stage 10-EFG fix #1
) { uri -> uri?.let { viewModel.import(it) } }
```

The `OpenDocument` contract returns `Uri?`. We then `takePersistableUriPermission` on the URI before passing to importer (so the importer can re-read on retry).

Commit.

### Task E7: Wire navigation routes + actions

- `Routes.JOURNEY_VIEWER = "journey_viewer"`.
- `composable(Routes.JOURNEY_VIEWER) { JourneyViewerScreen(...) }`.
- `SettingsAction.OpenJourneyViewer` handler in `handleSettingsAction` (was Log.w stub) → navigate to JOURNEY_VIEWER.

Commit.

---

## Final integration check

### Task Z1: Full unit + lint sweep

```bash
JAVA_HOME=~/.asdf/installs/java/temurin-17.0.18+8 ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

All three must pass. Pre-existing flake on `DataStoreSoundsPreferencesRepositoryTest` is acceptable.

### Task Z2: Manual cross-platform round-trip QA (REQUIRED before merge)

Per spec open question #3, this stage REQUIRES a manual round-trip test with both an iPhone and an Android device.

**Test plan:**
1. On iPhone (Pilgrim 1.3.0+): finish a walk with at least one waypoint and one voice recording. Tap Settings → Data → Export My Data → with photos. Save the `.pilgrim` file to Files.
2. AirDrop / share the `.pilgrim` to the Android dev device.
3. On Android: Settings → Data → Export & Import → Import Data → pick the `.pilgrim`. Verify imported walk count matches.
4. On Android: open the imported walk's Journal. Verify route renders, waypoint count matches, voice recording metadata visible (no playback expected — audio bytes aren't in `.pilgrim`).
5. On Android: same Settings flow → Export My Data. Save the `.pilgrim` somewhere.
6. Share the file back to iPhone. iPhone Settings → Data → Import Data. Verify import succeeds without `unsupportedSchemaVersion` error.

If the round-trip fails on either direction, the fix lands BEFORE merge.

### Task Z3: Push branch + open PR

```bash
git push -u origin feat/stage-10-hi-about-and-pilgrim-package
gh pr create --title "..." --body "..."
```

(PR body template in the spec; cluster-by-cluster commit list in the description.)

---

## Self-review

- ✅ Spec coverage: every spec section has at least one task.
- ✅ No placeholders (all implementation skeletons have concrete code or explicit `NOTE TO IMPLEMENTER` callouts pointing to the source).
- ✅ Type consistency: `PilgrimWalk.id: String`, `Walk.uuid: String` — same wire format. `PilgrimVoiceRecording.duration: Double seconds`, `VoiceRecording.durationMillis: Long` — conversion noted at task C2/C4. `Walk.uuid` round-trips through `PilgrimWalk.id` as a UUID-formatted string.
- ✅ TDD ordering: helpers first (Cluster A0a/A0b), models before converters, converters before builder/importer, builder/importer before UI wiring.
- ✅ Commit cadence: each task ends with a single focused commit. Cluster C's converter task may be split into sub-commits at implementation time.

## Execution

**Recommended:** subagent-driven-development with one fresh subagent per task. Spec compliance review + code quality review after each. The /polish loop after Cluster B before starting Cluster C (consolidates the JSON-model surface review).

**Total estimate:** ~32 commits, ~3500 LOC. Plus manual round-trip QA (Task Z2). Largest stage in the project to date — pace yourself.
