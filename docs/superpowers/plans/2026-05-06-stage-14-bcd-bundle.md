# Stage 14-BCD bundle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` to implement this plan task-by-task.

**Goal:** Close the iOS-v1.5.0 Journal parity gap in a single PR — rich expand-card sheet on dot tap, turning-day banner, lunar markers, milestone bars, extracted date-divider helper, two reusable path-shape builders, cascading fade-in, empty-state tail Composable, and final on-device QA.

**Architecture:** Sits on top of the Stage 14-A foundation (`WalkSnapshot` + `JournalUiState` + `JourneySummary` + `WalkDot` + `CalligraphyPath.dotPositions` + `ScrollHapticState` + 4 turning colors + Goshuin FAB seal pipeline + `LocalReduceMotion` + global transparent ripple). The bundle adds canvas-Z overlay primitives (lunar/milestone/date-divider/turning-day/scenery fade-in) plus a `ModalBottomSheet`-based `ExpandCardSheet` that intercepts the dot tap before navigating to Walk Summary.

**Spec:** `docs/superpowers/specs/2026-05-06-stage-14-bcd-bundle-design.md` (commit `4a6db03`).

**Parity target:** pilgrim-ios v1.5.0 (`db4196e`).

**Recommended TDD sequencing** (respects dependency graph): Task 6 → Task 5 → Task 2 → Task 4 → Task 3 → Task 1 → Task 7 → Task 8 → Task 9.

---

## JDK setup (every task running `./gradlew`)

```bash
export PATH="$HOME/.asdf/shims:$PATH"   # asdf temurin-17.0.18+8
java -version                            # expect openjdk 17
```

---

## Setup

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feat/stage-14-bcd-bundle
```

---

## File structure overview

### Files to CREATE — Bucket 14-B chrome (Task 1)

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt` | `ModalBottomSheet` rendering header + divider + 3-stat row + MiniActivityBar + ActivityPills + "View details" button |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt` | 3-segment capsule fraction bar (walk/talk/meditate) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt` | Up-to-3 conditional pills (walk always; talk if hasTalk; meditate if hasMeditate) |

### Files to CREATE — Bucket 14-C overlays (Tasks 2-6)

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurnings.kt` | `isTurning()` / `kanji()` / `bannerTextRes()` extensions on `SeasonalMarker` + shared `turningMarkerForToday(...)` helper |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt` | Banner row Composable; zero-height when `marker == null \|\| !isTurning()` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt` | Pure-Kotlin full/new-moon detector + interpolatePosition (with documented identical-startMs edge case) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt` | 10×10 dp moon disc/outline glyph Composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt` | Threshold list + cumulative-cross detection (oldest-first iteration, documented iOS divergence) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt` | Torii-glyph + label horizontal bar (constrained to 70% width) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt` | Pure helper extracting inline `showMonth` logic |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt` | `fun toriiGatePath(size: Size): Path` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt` | `fun footprintPath(size: Size): Path` |

### Files to CREATE — Bucket 14-D polish (Tasks 7-8)

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeIn.kt` | `rememberJournalFadeIn` + `JournalFadeInState` (segmentAlpha / dotAlpha / sceneryAlpha) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt` | Tapered tail + 14 dp stone circle + "Begin" caption Composable |

### Files to MODIFY (Task 9 wires the lot)

| File | Change |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` | Add `TurningDayBanner` above header; rewire dot tap to `setExpandedSnapshotId`; observe `expandedSnapshotId` and render `ExpandCardSheet`; emit `LunarMarkerDot` + `MilestoneMarker` siblings; replace inline showMonth with `computeDateDividers`; replace empty `Text` with `EmptyJournalState`; wrap canvas Composables in `Modifier.graphicsLayer { alpha = ... }` lambda form driven by `JournalFadeInState`; stale-id guard `LaunchedEffect` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt` | Additive `emptyMode: Boolean = false` parameter — when `true` draws iOS empty-state tapered single stroke (120 dp tall, 1 dp → 0.2 dp → 1 dp half-width) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotColor.kt` | Move `turningMarkerFor(walkStartMs)` private helper into `SeasonalMarkerTurnings.kt` so banner + dot-color callers share one source |
| `app/src/main/res/values/strings.xml` | + Stage 14-BCD strings (turning banner copy, expand card labels/pills, milestone plurals, lunar a11y, empty-begin) |

### Tests CREATED (paths, mapped one-to-one to source files)

| Test path | Type |
|---|---|
| `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurningsTest.kt` | Pure JVM |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt` | Robolectric |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt` | Pure JVM |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt` | Pure JVM |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt` | Pure JVM |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarkerComposableTest.kt` | Robolectric |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDotTest.kt` | Robolectric |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ShapePathTest.kt` | Robolectric (Path.getBounds) |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheetTest.kt` | Robolectric |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeInTest.kt` | Robolectric |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalStateTest.kt` | Robolectric |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt` | Robolectric (4 walks × 30 km fixture; tap dot → sheet → "View details" → onEnterWalkSummary) |

---

## Task 1: ExpandCardSheet + MiniActivityBar + ActivityPills (Bucket 14-B)

**Goal:** Render iOS-verbatim Material 3 `ModalBottomSheet` on dot tap. Replaces direct nav-to-summary path; "View details →" button is the new sole entry to `onEnterWalkSummary`.

**Depends on:** Task 6 (`footprintPath` for header glyph).

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheetTest.kt`

**Files modify:** none yet (HomeScreen integration deferred to Task 9).

### Step 1.1: Failing Robolectric test for ExpandCardSheet

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheetTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ExpandCardSheetTest {
    @get:Rule val composeRule = createComposeRule()

    private val snap = WalkSnapshot(
        id = 42L,
        uuid = "uuid-42",
        startMs = 1_700_000_000_000L,
        distanceM = 5_000.0,
        durationSec = 1800.0,
        averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 5_000.0,
        talkDurationSec = 300L,
        meditateDurationSec = 120L,
        favicon = null,
        isShared = false,
        weatherCondition = null,
    )

    @Test
    fun renders_view_details_button_and_three_pills() {
        var clickedId: Long? = null
        var dismissed = false
        composeRule.setContent {
            PilgrimTheme {
                ExpandCardSheet(
                    snapshot = snap,
                    celestial = null,
                    seasonColor = androidx.compose.ui.graphics.Color(0xFF74B495),
                    units = UnitSystem.Metric,
                    isShared = false,
                    onViewDetails = { clickedId = it },
                    onDismissRequest = { dismissed = true },
                )
            }
        }
        composeRule.onNodeWithText("View details").assertIsDisplayed()
        composeRule.onNodeWithText("walk").assertIsDisplayed()
        composeRule.onNodeWithText("talk").assertIsDisplayed()
        composeRule.onNodeWithText("meditate").assertIsDisplayed()

        composeRule.onNodeWithText("View details").performClick()
        composeRule.runOnIdle {
            kotlin.test.assertEquals(42L, clickedId)
            kotlin.test.assertTrue(dismissed)
        }
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*ExpandCardSheetTest*"
# expect: compilation failure (ExpandCardSheet undefined)
```

### Step 1.2: Implement MiniActivityBar (no test of its own — covered transitively)

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Three-segment fraction capsule. Mirrors iOS `InkScrollView.swift:425-441`.
 * Hides any segment whose fraction is below 1% to avoid hairline slivers.
 */
@Composable
fun MiniActivityBar(snapshot: WalkSnapshot, modifier: Modifier = Modifier) {
    val total = kotlin.math.max(1L, snapshot.durationSec.toLong())
    val walkFrac = snapshot.walkOnlyDurationSec.toFloat() / total.toFloat()
    val talkFrac = snapshot.talkDurationSec.toFloat() / total.toFloat()
    val meditateFrac = snapshot.meditateDurationSec.toFloat() / total.toFloat()
    val capsule = RoundedCornerShape(2.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(percent = 50)),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (walkFrac > 0.01f) {
            Box(
                Modifier.weight(walkFrac).fillMaxHeight()
                    .clip(capsule)
                    .background(pilgrimColors.moss.copy(alpha = 0.5f)),
            )
        }
        if (talkFrac > 0.01f) {
            Box(
                Modifier.weight(talkFrac).fillMaxHeight()
                    .clip(capsule)
                    .background(pilgrimColors.rust.copy(alpha = 0.6f)),
            )
        }
        if (meditateFrac > 0.01f) {
            Box(
                Modifier.weight(meditateFrac).fillMaxHeight()
                    .clip(capsule)
                    .background(pilgrimColors.dawn.copy(alpha = 0.6f)),
            )
        }
    }
}
```

### Step 1.3: Implement ActivityPills

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@Composable
fun ActivityPills(snapshot: WalkSnapshot, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            color = pilgrimColors.moss,
            seconds = snapshot.walkOnlyDurationSec,
            labelRes = R.string.journal_expand_pill_walk,
        )
        if (snapshot.hasTalk) {
            Pill(
                color = pilgrimColors.rust,
                seconds = snapshot.talkDurationSec,
                labelRes = R.string.journal_expand_pill_talk,
            )
        }
        if (snapshot.hasMeditate) {
            Pill(
                color = pilgrimColors.dawn,
                seconds = snapshot.meditateDurationSec,
                labelRes = R.string.journal_expand_pill_meditate,
            )
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun Pill(color: Color, seconds: Long, labelRes: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(5.dp).clip(CircleShape).background(color),
        )
        Text(
            text = "${formatPillDuration(seconds)} ${stringResource(labelRes)}",
            style = pilgrimType.micro,
            color = pilgrimColors.fog,
        )
    }
}

/**
 * Pill duration: "M:SS" below 1 hour, "H:MM" otherwise. `Locale.US` is
 * intentional — Stage 5-A locale lesson; numeric body always ASCII.
 */
private fun formatPillDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(java.util.Locale.US, "%d:%02d", h, m)
    } else {
        String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
```

### Step 1.4: Implement ExpandCardSheet

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherCondition
import org.walktalkmeditate.pilgrim.ui.design.scenery.footprintPath
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Material 3 `ModalBottomSheet` rendered when a Journal dot is tapped.
 * Replaces the direct nav-to-summary path; "View details →" button is
 * the new sole entry to Walk Summary.
 *
 * Verbatim layout port of iOS `InkScrollView.swift:312-385`:
 *   1. Header row (footprint glyph + favicon + date + spacer + shared
 *      icon + planetary/moon glyphs + weather glyph)
 *   2. Divider (1 dp height, seasonColor.copy(alpha=0.15f))
 *   3. 3-stat Row (distance / duration / pace)
 *   4. MiniActivityBar
 *   5. ActivityPills
 *   6. "View details →" capsule Button
 *
 * Stage 4-B lesson: `rememberUpdatedState(onDismissRequest)` for the
 * dismiss callback used inside the Button onClick lambda — guards
 * against stale closure if the parent recomposes mid-tap.
 *
 * Stage 6-B lesson: date formatter uses
 * `DateTimeFormatter.ofLocalizedDateTime(FULL, SHORT).withLocale(...)`
 * — never the no-Locale `ofPattern` overload.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandCardSheet(
    snapshot: WalkSnapshot,
    celestial: CelestialSnapshot?,
    seasonColor: Color,
    units: UnitSystem,
    isShared: Boolean,
    onViewDetails: (Long) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberUpdatedState(onDismissRequest)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    BackHandler(enabled = true) { dismissState.value() }

    val dateText = remember(snapshot.startMs) {
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(snapshot.startMs))
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchmentSecondary,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = PilgrimSpacing.normal, vertical = 16.dp)
                .padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HeaderRow(
                snapshot = snapshot,
                celestial = celestial,
                seasonColor = seasonColor,
                isShared = isShared,
                dateText = dateText,
            )
            Box(
                Modifier.fillMaxWidth().height(1.dp)
                    .background(seasonColor.copy(alpha = 0.15f)),
            )
            StatsRow(snapshot = snapshot, units = units)
            MiniActivityBar(snapshot = snapshot)
            ActivityPills(snapshot = snapshot)
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    val id = snapshot.id
                    dismissState.value()
                    onViewDetails(id)
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(percent = 50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = pilgrimColors.stone.copy(alpha = 0.8f),
                    contentColor = pilgrimColors.parchment,
                ),
            ) {
                Text(
                    text = stringResource(R.string.journal_expand_view_details),
                    style = pilgrimType.body,
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderRow(
    snapshot: WalkSnapshot,
    celestial: CelestialSnapshot?,
    seasonColor: Color,
    isShared: Boolean,
    dateText: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Canvas(modifier = Modifier.size(width = 12.dp, height = 18.dp)) {
            drawPath(
                path = footprintPath(Size(size.width, size.height)),
                color = seasonColor.copy(alpha = 0.3f),
            )
        }
        snapshot.favicon?.let { key ->
            WalkFavicon.entries.firstOrNull { it.rawValue == key }?.let { fav ->
                Icon(
                    imageVector = fav.icon,
                    contentDescription = null,
                    tint = seasonColor,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        Text(
            text = dateText,
            style = pilgrimType.annotation,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.weight(1f))
        if (isShared) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = pilgrimColors.stone.copy(alpha = 0.5f),
                modifier = Modifier.size(10.dp),
            )
        }
        if (celestial != null) {
            Text(
                text = "${celestial.planetaryHour.planet.symbol}${celestial.moonSign.symbol}",
                style = TextStyle(fontSize = 10.sp),
                color = pilgrimColors.fog,
            )
        }
        snapshot.weatherCondition?.let { raw ->
            WeatherCondition.fromRawValue(raw)?.let { wc ->
                Icon(
                    painter = painterResource(wc.iconRes),
                    contentDescription = null,
                    tint = pilgrimColors.fog,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun StatsRow(snapshot: WalkSnapshot, units: UnitSystem) {
    val distLabel = WalkFormat.distanceLabel(snapshot.distanceM, units)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExpandStat(
            value = "${distLabel.value} ${distLabel.unit}",
            labelRes = R.string.journal_expand_label_distance,
        )
        Spacer(Modifier.weight(1f))
        ExpandStat(
            value = WalkFormat.duration((snapshot.durationSec * 1000.0).toLong()),
            labelRes = R.string.journal_expand_label_duration,
        )
        Spacer(Modifier.weight(1f))
        ExpandStat(
            value = WalkFormat.pace(
                secondsPerKm = snapshot.averagePaceSecPerKm.takeIf { it > 0 },
                units = units,
            ),
            labelRes = R.string.journal_expand_label_pace,
        )
    }
}

@Composable
private fun ExpandStat(value: String, labelRes: Int) {
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = pilgrimType.statValue, color = pilgrimColors.ink)
        Text(stringResource(labelRes), style = pilgrimType.micro, color = pilgrimColors.fog)
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*ExpandCardSheetTest*"
# expect: GREEN
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/
git commit -m "feat(journal): ExpandCardSheet + MiniActivityBar + ActivityPills (Stage 14-BCD task 1)"
```

> Note: HomeScreen integration of the sheet is deferred to Task 9.

---

## Task 2: TurningDayBanner + SeasonalMarker.kanji/bannerTextRes extensions (Bucket 14-C)

**Goal:** Banner row above `JourneySummaryHeader` when today is an equinox/solstice. Cross-quarter days collapse to zero-height. Adds three pure extensions on `SeasonalMarker` and a shared `turningMarkerForToday(...)` helper consumed by both the banner caller and `WalkDotColor`.

**Depends on:** none.

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurnings.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurningsTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt`

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotColor.kt` — replace private `turningMarkerFor` with import of the shared one.
- `app/src/main/res/values/strings.xml` — add `turning_equinox_banner`, `turning_solstice_banner`.

### Step 2.1: Failing pure JVM test for the extensions

Create `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurningsTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.R

class SeasonalMarkerTurningsTest {
    @Test
    fun cardinal_markers_are_turning() {
        assertTrue(SeasonalMarker.SpringEquinox.isTurning())
        assertTrue(SeasonalMarker.SummerSolstice.isTurning())
        assertTrue(SeasonalMarker.AutumnEquinox.isTurning())
        assertTrue(SeasonalMarker.WinterSolstice.isTurning())
    }

    @Test
    fun cross_quarter_markers_are_not_turning() {
        assertFalse(SeasonalMarker.Imbolc.isTurning())
        assertFalse(SeasonalMarker.Beltane.isTurning())
        assertFalse(SeasonalMarker.Lughnasadh.isTurning())
        assertFalse(SeasonalMarker.Samhain.isTurning())
    }

    @Test
    fun cardinal_markers_have_kanji() {
        assertEquals("春分", SeasonalMarker.SpringEquinox.kanji())
        assertEquals("夏至", SeasonalMarker.SummerSolstice.kanji())
        assertEquals("秋分", SeasonalMarker.AutumnEquinox.kanji())
        assertEquals("冬至", SeasonalMarker.WinterSolstice.kanji())
    }

    @Test
    fun cross_quarter_markers_have_no_kanji() {
        assertNull(SeasonalMarker.Imbolc.kanji())
        assertNull(SeasonalMarker.Beltane.kanji())
        assertNull(SeasonalMarker.Lughnasadh.kanji())
        assertNull(SeasonalMarker.Samhain.kanji())
    }

    @Test
    fun banner_text_res_resolves_for_cardinals_only() {
        assertEquals(R.string.turning_equinox_banner, SeasonalMarker.SpringEquinox.bannerTextRes())
        assertEquals(R.string.turning_equinox_banner, SeasonalMarker.AutumnEquinox.bannerTextRes())
        assertEquals(R.string.turning_solstice_banner, SeasonalMarker.SummerSolstice.bannerTextRes())
        assertEquals(R.string.turning_solstice_banner, SeasonalMarker.WinterSolstice.bannerTextRes())
        assertNull(SeasonalMarker.Imbolc.bannerTextRes())
    }
}
```

### Step 2.2: Add string resources

Edit `app/src/main/res/values/strings.xml` to add:

```xml
<!-- Stage 14-BCD turning-day banner -->
<string name="turning_equinox_banner">Today, day equals night.</string>
<string name="turning_solstice_banner">Today the sun stands still.</string>
```

### Step 2.3: Implement extensions + shared turning helper

Create `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurnings.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

import androidx.annotation.StringRes
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.walktalkmeditate.pilgrim.R

/** True for the four cardinal turnings (equinox/solstice). False for cross-quarter. */
fun SeasonalMarker.isTurning(): Boolean = when (this) {
    SeasonalMarker.SpringEquinox,
    SeasonalMarker.SummerSolstice,
    SeasonalMarker.AutumnEquinox,
    SeasonalMarker.WinterSolstice -> true
    else -> false
}

/** Verbatim 春分 / 夏至 / 秋分 / 冬至 for the four cardinal turnings; null otherwise. */
fun SeasonalMarker.kanji(): String? = when (this) {
    SeasonalMarker.SpringEquinox  -> "春分"
    SeasonalMarker.SummerSolstice -> "夏至"
    SeasonalMarker.AutumnEquinox  -> "秋分"
    SeasonalMarker.WinterSolstice -> "冬至"
    else -> null
}

@StringRes
fun SeasonalMarker.bannerTextRes(): Int? = when (this) {
    SeasonalMarker.SpringEquinox, SeasonalMarker.AutumnEquinox ->
        R.string.turning_equinox_banner
    SeasonalMarker.SummerSolstice, SeasonalMarker.WinterSolstice ->
        R.string.turning_solstice_banner
    else -> null
}

/**
 * Compute the SeasonalMarker for a UTC instant. Shared by `WalkDotColor`
 * (per-walk dot color) and `TurningDayBanner` (today's banner).
 */
fun turningMarkerForEpochMillis(epochMillis: Long): SeasonalMarker? {
    val jd = SunCalc.julianDayFromEpochMillis(epochMillis)
    val T = SunCalc.julianCenturies(jd)
    val sunLongitude = SunCalc.solarLongitude(T)
    return SeasonalMarkerCalc.seasonalMarker(sunLongitude)
}

/** Convenience: today's marker (null when no turning is in effect). */
fun turningMarkerForToday(
    clock: Clock = Clock.systemDefaultZone(),
    zone: ZoneId = ZoneId.systemDefault(),
): SeasonalMarker? {
    val nowMs = ZonedDateTime.of(LocalDate.now(clock).atStartOfDay(), zone)
        .toInstant().toEpochMilli()
    return turningMarkerForEpochMillis(nowMs)
}
```

Edit `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotColor.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors

fun walkDotBaseColor(walkStartMs: Long, colors: PilgrimColors): Color {
    val turning = turningMarkerForEpochMillis(walkStartMs)
    return when (turning) {
        SeasonalMarker.SpringEquinox -> colors.turningJade
        SeasonalMarker.SummerSolstice -> colors.turningGold
        SeasonalMarker.AutumnEquinox -> colors.turningClaret
        SeasonalMarker.WinterSolstice -> colors.turningIndigo
        else -> colors.moss
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*SeasonalMarkerTurningsTest*"
# expect: GREEN
```

### Step 2.4: Failing Robolectric test for TurningDayBanner

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.banner

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TurningDayBannerTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun null_marker_renders_zero_height() {
        composeRule.setContent {
            PilgrimTheme {
                TurningDayBanner(
                    marker = null,
                    modifier = androidx.compose.ui.Modifier
                        .testTag("banner")
                        .also {},
                )
            }
        }
        composeRule.onNodeWithTag("banner")
            .assertExists()
        // Zero-height path: no text descendant.
        composeRule.onNodeWithText("Today, day equals night.")
            .assertDoesNotExist()
    }

    @Test
    fun spring_equinox_renders_banner_text_and_kanji() {
        composeRule.setContent {
            PilgrimTheme {
                TurningDayBanner(marker = SeasonalMarker.SpringEquinox)
            }
        }
        composeRule.onNodeWithText("Today, day equals night.").assertIsDisplayed()
        composeRule.onNodeWithText("春分").assertIsDisplayed()
    }

    @Test
    fun cross_quarter_marker_renders_zero_height() {
        composeRule.setContent {
            PilgrimTheme {
                TurningDayBanner(marker = SeasonalMarker.Beltane)
            }
        }
        composeRule.onNodeWithText("Today, day equals night.").assertDoesNotExist()
        composeRule.onNodeWithText("Today the sun stands still.").assertDoesNotExist()
    }
}
```

### Step 2.5: Implement TurningDayBanner

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.banner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.bannerTextRes
import org.walktalkmeditate.pilgrim.core.celestial.isTurning
import org.walktalkmeditate.pilgrim.core.celestial.kanji
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Banner shown above the JourneySummaryHeader on equinox/solstice days.
 * Zero-height when `marker == null` or not a cardinal turning, so the
 * parent layout collapses cleanly outside turning days.
 */
@Composable
fun TurningDayBanner(marker: SeasonalMarker?, modifier: Modifier = Modifier) {
    if (marker == null || !marker.isTurning()) {
        Box(modifier = modifier.fillMaxWidth())
        return
    }
    val bannerRes = marker.bannerTextRes() ?: return
    val kanji = marker.kanji() ?: return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(bannerRes),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Text(
            text = " · ",
            style = pilgrimType.caption,
            color = pilgrimColors.fog.copy(alpha = 0.5f),
        )
        Text(
            text = kanji,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*TurningDayBannerTest*"
# expect: GREEN
git add app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurnings.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/ \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotColor.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/SeasonalMarkerTurningsTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/
git commit -m "feat(journal): TurningDayBanner + SeasonalMarker turning extensions (Stage 14-BCD task 2)"
```

---

## Task 3: LunarMarkerCalc + LunarMarkerDot (Bucket 14-C)

**Goal:** Pure-Kotlin lunar-event detector + 10×10 dp moon glyph Composable. Detects full + new moons in the snapshot date range, refines via ±36h × 6h-step search, interpolates Y-position between dots.

**Depends on:** none (`MoonCalc` already exists; `DotPosition` already shipped).

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDotTest.kt`

### Step 3.1: Failing pure JVM test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class LunarMarkerCalcTest {

    private fun snap(id: Long, ms: Long) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = ms, distanceM = 1000.0, durationSec = 600.0,
        averagePaceSecPerKm = 360.0, cumulativeDistanceM = 1000.0,
        talkDurationSec = 0L, meditateDurationSec = 0L, favicon = null,
        isShared = false, weatherCondition = null,
    )

    @Test
    fun returns_empty_when_fewer_than_two_snapshots() {
        assertEquals(emptyList<LunarMarker>(),
            computeLunarMarkers(emptyList(), emptyList(), 360f))
        assertEquals(emptyList<LunarMarker>(),
            computeLunarMarkers(
                listOf(snap(1, 1_700_000_000_000L)),
                listOf(DotPosition(180f, 100f)),
                360f,
            ))
    }

    @Test
    fun finds_full_moon_in_january_2024_window() {
        // 2024-01-25 17:54 UTC was a full moon. Bracket with two
        // snapshots a week before and after.
        val before = 1_705_708_800_000L  // 2024-01-20T00:00 UTC
        val after = 1_706_572_800_000L   // 2024-01-30T00:00 UTC
        val snapshots = listOf(snap(2, after), snap(1, before)) // newest-first
        val positions = listOf(
            DotPosition(centerXPx = 180f, yPx = 50f),
            DotPosition(centerXPx = 180f, yPx = 150f),
        )
        val markers = computeLunarMarkers(snapshots, positions, viewportWidthPx = 360f)
        assertTrue("expected at least one event", markers.isNotEmpty())
        // Full moon ⇒ illumination > 0.5
        assertTrue("expected full marker", markers.any { it.illumination > 0.5 })
    }

    @Test
    fun marker_positioned_opposite_side_of_midpoint() {
        val before = 1_705_708_800_000L
        val after = 1_706_572_800_000L
        val snapshots = listOf(snap(2, after), snap(1, before))
        // Right-of-center midpoint ⇒ marker offsets LEFT.
        val positions = listOf(
            DotPosition(centerXPx = 250f, yPx = 50f),
            DotPosition(centerXPx = 250f, yPx = 150f),
        )
        val markers = computeLunarMarkers(snapshots, positions, viewportWidthPx = 360f)
        assertTrue(markers.isNotEmpty())
        markers.forEach { m ->
            assertTrue("xPx must be left of midX=250 when midX > viewport/2",
                m.xPx < 250f)
        }
    }

    @Test
    fun interpolatePosition_handles_identical_startMs() {
        val a = DotPosition(100f, 50f)
        val b = DotPosition(200f, 150f)
        val snapshots = listOf(snap(1, 1_700_000_000_000L), snap(2, 1_700_000_000_000L))
        val res = interpolatePosition(1_700_000_000_000L, snapshots, listOf(a, b))
        assertNotNull("identical startMs must NOT throw — verbatim iOS edge case", res)
        // Documented in spec: fraction = 0.5 ⇒ midpoint sample.
        assertEquals(0.5, res!!.third, 1e-9)
    }
}
```

### Step 3.2: Implement LunarMarkerCalc

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import org.walktalkmeditate.pilgrim.core.celestial.MoonCalc
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class LunarMarker(
    val idTag: String,
    val xPx: Float,
    val yPx: Float,
    val illumination: Double,
    val isWaxing: Boolean,
)

internal data class LunarEvent(
    val instantMs: Long,
    val illumination: Double,
    val isWaxing: Boolean,
)

/**
 * Half-synodic-month constant. Verbatim iOS literal `14.76` — NOT
 * `MoonCalc.SYNODIC_DAYS / 2.0` (which would be 14.76529...). Stage
 * 14-A Documented iOS deviations #8: the `< 1.5` day window absorbs
 * the rounding. Carrying iOS verbatim keeps cross-platform behavior
 * identical.
 */
private const val HALF_CYCLE_DAYS = 14.76

/**
 * Compute lunar full/new-moon markers in the snapshot date range. Returns
 * empty list when fewer than 2 snapshots (no interval to bracket).
 *
 * @param snapshots newest-first per `HomeViewModel` emission.
 * @param dotPositions parallel to `snapshots` (same order).
 * @param viewportWidthPx canvas width in pixels — used to flip marker X
 *   to the opposite side of the dot meander.
 */
fun computeLunarMarkers(
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
    viewportWidthPx: Float,
): List<LunarMarker> {
    if (snapshots.size < 2 || dotPositions.size < 2) return emptyList()
    val earliestMs = snapshots.minOf { it.startMs }
    val latestMs = snapshots.maxOf { it.startMs }
    val events = findLunarEvents(earliestMs, latestMs)
    val results = mutableListOf<LunarMarker>()
    events.forEachIndexed { index, event ->
        val triple = interpolatePosition(event.instantMs, snapshots, dotPositions)
            ?: return@forEachIndexed
        val (posA, posB, fraction) = triple
        val y = posA.yPx + fraction.toFloat() * (posB.yPx - posA.yPx)
        val midX = posA.centerXPx + fraction.toFloat() * (posB.centerXPx - posA.centerXPx)
        val markerX = if (midX > viewportWidthPx / 2f) midX - 20f else midX + 20f
        results += LunarMarker(
            idTag = "lunar-$index",
            xPx = markerX,
            yPx = y,
            illumination = event.illumination,
            isWaxing = event.isWaxing,
        )
    }
    return results
}

/**
 * Walks a 1-day step through the date range looking for near-full and
 * near-new moons; refines via ±36h ÷ 6h-step search around each hit.
 *
 * Verbatim port of iOS `InkScrollView+LunarMarkers.swift:40-65`.
 */
internal fun findLunarEvents(startMs: Long, endMs: Long): List<LunarEvent> {
    if (endMs <= startMs) return emptyList()
    val out = mutableListOf<LunarEvent>()
    var checkMs = startMs
    val oneDay = Duration.ofDays(1).toMillis()
    val skipDays = (HALF_CYCLE_DAYS.toInt() - 1).coerceAtLeast(1)  // = 13
    val skipMs = Duration.ofDays(skipDays.toLong()).toMillis()
    while (checkMs <= endMs) {
        val phase = MoonCalc.moonPhase(Instant.ofEpochMilli(checkMs))
        val isNearNew = phase.ageInDays < 1.5 || phase.ageInDays > 28.0
        val isNearFull = abs(phase.ageInDays - HALF_CYCLE_DAYS) < 1.5
        if (isNearNew || isNearFull) {
            val refinedMs = refinePeak(checkMs, isFullMoon = isNearFull)
            val refinedPhase = MoonCalc.moonPhase(Instant.ofEpochMilli(refinedMs))
            out += LunarEvent(
                instantMs = refinedMs,
                illumination = refinedPhase.illumination,
                isWaxing = refinedPhase.isWaxing,
            )
            checkMs += skipMs
        } else {
            checkMs += oneDay
        }
    }
    return out
}

/**
 * Refine to the peak of a near-full or near-new moon by sweeping ±36h
 * in 6h steps and picking the timestamp with max score. For full,
 * score = illumination; for new, score = 1 - illumination.
 */
internal fun refinePeak(nearMs: Long, isFullMoon: Boolean): Long {
    var bestMs = nearMs
    var bestScore = -1.0
    val sixHours = Duration.ofHours(6).toMillis()
    var offset = -36L * 60L * 60L * 1000L  // -36h in ms
    val end = 36L * 60L * 60L * 1000L
    while (offset <= end) {
        val ms = nearMs + offset
        val phase = MoonCalc.moonPhase(Instant.ofEpochMilli(ms))
        val score = if (isFullMoon) phase.illumination else 1.0 - phase.illumination
        if (score > bestScore) {
            bestScore = score
            bestMs = ms
        }
        offset += sixHours
    }
    return bestMs
}

/**
 * Verbatim port of iOS `interpolatePosition(for:positions:)`.
 *
 * Walks `0..(snapshots.size - 2)` looking for the interval bracketing
 * `targetMs`. When found, returns `(posA, posB, fraction)` where
 * fraction is in [0, 1] aligned with the iOS `dateA < dateB` flip.
 *
 * **Edge case — identical `startMs`:** when `dateA == dateB`,
 * `totalInterval = 0` triggers the `else 0.5` branch. The marker lands
 * at the midpoint between two dot positions which are themselves
 * visually adjacent (same vertical-spacing stride; meander-x may
 * differ by FNV hash). Verbatim iOS behavior; documented for the test
 * fixture rather than guarded against.
 */
internal fun interpolatePosition(
    targetMs: Long,
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
): Triple<DotPosition, DotPosition, Double>? {
    if (snapshots.size < 2 || dotPositions.size < 2) return null
    for (i in 0 until snapshots.size - 1) {
        val dateA = snapshots[i].startMs
        val dateB = snapshots[i + 1].startMs
        val earlier = kotlin.math.min(dateA, dateB)
        val later = kotlin.math.max(dateA, dateB)
        if (targetMs in earlier..later) {
            val totalInterval = (later - earlier).toDouble()
            val rawFraction = if (totalInterval > 0.0) {
                (targetMs - earlier).toDouble() / totalInterval
            } else {
                0.5  // identical startMs edge case — verbatim iOS.
            }
            val adjusted = if (dateA < dateB) rawFraction else 1.0 - rawFraction
            return Triple(dotPositions[i], dotPositions[i + 1], adjusted)
        }
    }
    return null
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*LunarMarkerCalcTest*"
# expect: GREEN
```

### Step 3.3: Failing Robolectric test for LunarMarkerDot

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDotTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LunarMarkerDotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun full_moon_renders_at_10dp_size() {
        composeRule.setContent {
            PilgrimTheme {
                LunarMarkerDot(
                    isFullMoon = true,
                    modifier = Modifier.testTag("lunar"),
                )
            }
        }
        composeRule.onNodeWithTag("lunar")
            .assertWidthIsEqualTo(10.dp)
            .assertHeightIsEqualTo(10.dp)
    }

    @Test
    fun new_moon_renders_at_10dp_size() {
        composeRule.setContent {
            PilgrimTheme {
                LunarMarkerDot(
                    isFullMoon = false,
                    modifier = Modifier.testTag("lunar-new"),
                )
            }
        }
        composeRule.onNodeWithTag("lunar-new")
            .assertWidthIsEqualTo(10.dp)
            .assertHeightIsEqualTo(10.dp)
    }
}
```

### Step 3.4: Implement LunarMarkerDot

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 10×10 dp moon glyph. Full = filled disc; New = stroked circle.
 * Color flips light/dark theme; verbatim iOS RGB literals from
 * `InkScrollView+LunarMarkers.swift:15-29`.
 */
@Composable
fun LunarMarkerDot(isFullMoon: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val moonColor = if (dark) {
        Color(red = 0.85f, green = 0.82f, blue = 0.72f)
    } else {
        Color(red = 0.55f, green = 0.58f, blue = 0.65f)
    }
    if (isFullMoon) {
        val alpha = if (dark) 0.6f else 0.4f
        Box(
            modifier = modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(moonColor.copy(alpha = alpha)),
        )
    } else {
        val alpha = if (dark) 0.7f else 0.5f
        Box(
            modifier = modifier
                .size(10.dp)
                .border(width = 1.dp, color = moonColor.copy(alpha = alpha), shape = CircleShape),
        )
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*LunarMarkerCalcTest*" --tests "*LunarMarkerDotTest*"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDotTest.kt
git commit -m "feat(journal): LunarMarkerCalc + LunarMarkerDot (Stage 14-BCD task 3)"
```

---

## Task 4: MilestoneCalc + MilestoneMarker (Bucket 14-C)

**Goal:** Threshold list `[100k, 500k, 1M, 2M..100M step 1M]` + cumulative-cross detection (oldest-first iteration — Android divergence from iOS bug). Compose `MilestoneMarker` with hairline + torii glyph + label + trailing hairline, constrained to 70% width.

**Depends on:** Task 6 (`toriiGatePath`).

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarkerComposableTest.kt`

**Files modify:**
- `app/src/main/res/values/strings.xml` — add milestone plurals + a11y string.

### Step 4.1: Failing pure JVM regression test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class MilestoneCalcTest {

    private fun snap(id: Long, distM: Double, cumM: Double) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = 1_700_000_000_000L + id * 86_400_000L,
        distanceM = distM, durationSec = 600.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = cumM, talkDurationSec = 0L, meditateDurationSec = 0L,
        favicon = null, isShared = false, weatherCondition = null,
    )

    @Test
    fun thresholds_first_five_and_size_and_last() {
        val ts = milestoneThresholds()
        assertEquals(listOf(100_000.0, 500_000.0, 1_000_000.0, 2_000_000.0, 3_000_000.0),
            ts.subList(0, 5))
        assertEquals(102, ts.size)
        assertEquals(100_000_000.0, ts.last(), 0.0)
    }

    @Test
    fun returns_empty_when_fewer_than_two_snapshots() {
        assertEquals(emptyList<MilestonePosition>(),
            computeMilestonePositions(emptyList(), emptyList()))
        assertEquals(emptyList<MilestonePosition>(),
            computeMilestonePositions(
                listOf(snap(1, 50_000.0, 50_000.0)),
                listOf(DotPosition(180f, 100f)),
            ))
    }

    /**
     * REGRESSION fixture per spec: 4 walks of 30 km each, cumulative
     * 30/60/90/120 km. Exactly ONE marker (100 km), placed on the
     * 4th-oldest walk (= newest by display order; index 0 in newest-
     * first ordering). NOT all milestones stacked on the newest walk
     * (which the verbatim iOS port would produce).
     */
    @Test
    fun four_30km_walks_yield_single_100km_marker_on_4th_oldest() {
        // Display order is newest-first; oldest is index 3.
        val newest = snap(4, 30_000.0, 120_000.0)  // cumulative AFTER walk 4
        val third = snap(3, 30_000.0, 90_000.0)
        val second = snap(2, 30_000.0, 60_000.0)
        val oldest = snap(1, 30_000.0, 30_000.0)
        val snapshots = listOf(newest, third, second, oldest)
        val positions = listOf(
            DotPosition(180f, 50f),
            DotPosition(180f, 150f),
            DotPosition(180f, 250f),
            DotPosition(180f, 350f),
        )
        val markers = computeMilestonePositions(snapshots, positions)
        assertEquals(1, markers.size)
        assertEquals(100_000.0, markers[0].distanceM, 0.0)
        // 4th-oldest (= newest, index 0 in display order) is at yPx=50f.
        assertEquals(50f, markers[0].yPx, 0.001f)
    }

    @Test
    fun cumulative_50_120_600_1050_yields_three_markers() {
        // Display newest-first.
        val s = listOf(
            snap(4, 450_000.0, 1_050_000.0),
            snap(3, 480_000.0, 600_000.0),
            snap(2, 70_000.0, 120_000.0),
            snap(1, 50_000.0, 50_000.0),
        )
        val p = listOf(
            DotPosition(180f, 50f),
            DotPosition(180f, 150f),
            DotPosition(180f, 250f),
            DotPosition(180f, 350f),
        )
        val markers = computeMilestonePositions(s, p)
        // Expected thresholds crossed: 100k (walk 2), 500k (walk 3), 1M (walk 4).
        assertEquals(3, markers.size)
        assertTrue(markers.any { it.distanceM == 100_000.0 })
        assertTrue(markers.any { it.distanceM == 500_000.0 })
        assertTrue(markers.any { it.distanceM == 1_000_000.0 })
    }
}
```

### Step 4.2: Implement MilestoneCalc

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class MilestonePosition(val distanceM: Double, val yPx: Float)

/**
 * Returns [100k, 500k, 1M, 2M, 3M, ..., 100M] — the first three
 * discrete thresholds, then 1M-step from 2M to 100M. Matches iOS
 * `milestoneThresholds()` verbatim.
 */
fun milestoneThresholds(): List<Double> = buildList {
    add(100_000.0)
    add(500_000.0)
    add(1_000_000.0)
    var next = 2_000_000.0
    while (next <= 100_000_000.0) {
        add(next)
        next += 1_000_000.0
    }
}

/**
 * Compute milestone bar positions.
 *
 * **INTENTIONAL iOS divergence (Stage 14-A Documented iOS deviations
 * #11; carried verbatim into Stage 14-BCD).** iOS iterates `snapshots`
 * in display order (newest-first) computing
 * `prevCumulative = i > 0 ? snapshots[i-1].cumulativeDistance : 0`.
 * Because snapshots are newest-first, cumulative distance is
 * *decreasing* with `i`, so the iOS check
 * `prev < threshold && curr >= threshold` only ever satisfies at
 * `i = 0` — every milestone collapses onto the newest walk (latent
 * iOS bug).
 *
 * Android iterates **oldest-first** and emits the marker on the
 * first walk whose cumulative crosses the threshold. The regression
 * test (4 walks × 30 km ⇒ single 100km marker on the 4th-oldest)
 * is the ground truth.
 */
fun computeMilestonePositions(
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
): List<MilestonePosition> {
    if (snapshots.size < 2 || dotPositions.size < 2) return emptyList()
    val oldestFirstSnaps = snapshots.asReversed()
    val oldestFirstPositions = dotPositions.asReversed()
    val totalCumulative = oldestFirstSnaps.last().cumulativeDistanceM
    val results = mutableListOf<MilestonePosition>()
    for (threshold in milestoneThresholds()) {
        if (threshold > totalCumulative) break
        for (i in oldestFirstSnaps.indices) {
            val prev = if (i == 0) 0.0 else oldestFirstSnaps[i - 1].cumulativeDistanceM
            val curr = oldestFirstSnaps[i].cumulativeDistanceM
            if (prev < threshold && threshold <= curr) {
                if (i < oldestFirstPositions.size) {
                    results += MilestonePosition(
                        distanceM = threshold,
                        yPx = oldestFirstPositions[i].yPx,
                    )
                }
                break
            }
        }
    }
    return results
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*MilestoneCalcTest*"
# expect: GREEN
```

### Step 4.3: Add string resources

Edit `app/src/main/res/values/strings.xml`:

```xml
<!-- Stage 14-BCD milestone marker -->
<plurals name="journal_milestone_km">
    <item quantity="one">%d km</item>
    <item quantity="other">%d km</item>
</plurals>
<plurals name="journal_milestone_mi">
    <item quantity="one">%d mi</item>
    <item quantity="other">%d mi</item>
</plurals>
<string name="journal_milestone_a11y">Milestone: %1$s</string>

<!-- Lunar a11y -->
<string name="journal_lunar_full_a11y">Full moon</string>
<string name="journal_lunar_new_a11y">New moon</string>
```

### Step 4.4: Failing Robolectric test for MilestoneMarker

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarkerComposableTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MilestoneMarkerComposableTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renders_100km_label_in_metric() {
        composeRule.setContent {
            PilgrimTheme {
                MilestoneMarker(distanceM = 100_000.0, units = UnitSystem.Metric)
            }
        }
        composeRule.onNodeWithText("100 km").assertIsDisplayed()
    }

    @Test
    fun renders_62mi_for_100km_in_imperial() {
        composeRule.setContent {
            PilgrimTheme {
                MilestoneMarker(distanceM = 100_000.0, units = UnitSystem.Imperial)
            }
        }
        composeRule.onNodeWithText("62 mi").assertIsDisplayed()
    }
}
```

### Step 4.5: Implement MilestoneMarker

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.design.scenery.toriiGatePath
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Horizontal milestone bar — hairline + torii glyph + label + trailing
 * hairline. Constrained to 70% canvas width via fillMaxWidth(0.7f).
 *
 * Distance text uses `Locale.US` — Stage 5-A locale lesson; numeric
 * body is always ASCII. Plurals (with locale-correct rendering) are
 * applied only to the content-description for screen readers.
 */
@Composable
fun MilestoneMarker(
    distanceM: Double,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val displayText = remember@{
        val n = if (units == UnitSystem.Imperial) {
            (distanceM / 1609.344).toInt()
        } else {
            (distanceM / 1000.0).toInt()
        }
        if (units == UnitSystem.Imperial) {
            String.format(Locale.US, "%d mi", n)
        } else {
            String.format(Locale.US, "%d km", n)
        }
    }
    val a11yLabel = ctx.getString(R.string.journal_milestone_a11y, displayText)
    Row(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .semantics { contentDescription = a11yLabel },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(pilgrimColors.fog.copy(alpha = 0.15f)),
        )
        Canvas(modifier = Modifier.size(width = 16.dp, height = 14.dp)) {
            drawPath(
                path = toriiGatePath(Size(size.width, size.height)),
                color = pilgrimColors.stone.copy(alpha = 0.25f),
            )
        }
        Text(
            text = displayText,
            style = pilgrimType.micro,
            color = pilgrimColors.fog.copy(alpha = 0.4f),
        )
        Box(
            Modifier
                .weight(1f)
                .height(0.5.dp)
                .background(pilgrimColors.fog.copy(alpha = 0.15f)),
        )
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*MilestoneMarkerComposableTest*"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarkerComposableTest.kt
git commit -m "feat(journal): MilestoneCalc + MilestoneMarker (Stage 14-BCD task 4)"
```

---

## Task 5: DateDividerCalc extraction (Bucket 14-C)

**Goal:** Promote inline `showMonth + monthText + monthXPx` logic from `HomeScreen.kt:355-380` to a pure helper. UI behavior unchanged. Tests use `Locale.setDefault(Locale.US)` in `@Before`.

**Depends on:** none.

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt`

**Files modify:** none yet (HomeScreen integration deferred to Task 9).

### Step 5.1: Failing pure JVM test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class DateDividerCalcTest {

    private var savedLocale: Locale = Locale.getDefault()

    @Before fun forceUSLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun snap(id: Long, ms: Long) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = ms, distanceM = 1000.0, durationSec = 600.0,
        averagePaceSecPerKm = 360.0, cumulativeDistanceM = 1000.0,
        talkDurationSec = 0L, meditateDurationSec = 0L, favicon = null,
        isShared = false, weatherCondition = null,
    )

    @Test
    fun emits_first_walk_and_each_month_transition() {
        // Newest-first display order: Jun 25, Jun 11, May 2, Apr 18, Apr 5.
        val zone = ZoneOffset.UTC
        val s = listOf(
            snap(5, 1_719_273_600_000L),  // 2024-06-25
            snap(4, 1_718_064_000_000L),  // 2024-06-11
            snap(3, 1_714_608_000_000L),  // 2024-05-02
            snap(2, 1_713_398_400_000L),  // 2024-04-18
            snap(1, 1_712_275_200_000L),  // 2024-04-05
        )
        val positions = listOf(
            DotPosition(centerXPx = 220f, yPx = 50f),
            DotPosition(centerXPx = 220f, yPx = 150f),
            DotPosition(centerXPx = 140f, yPx = 250f),
            DotPosition(centerXPx = 220f, yPx = 350f),
            DotPosition(centerXPx = 140f, yPx = 450f),
        )
        val dividers = computeDateDividers(
            snapshots = s,
            dotPositions = positions,
            viewportWidthPx = 360f,
            monthMarginPx = 100f,
            locale = Locale.US,
            zone = zone,
        )
        // Expected dividers: Jun (index 0), May (index 2), Apr (index 3).
        assertEquals(3, dividers.size)
        assertEquals("Jun", dividers[0].text)
        assertEquals("May", dividers[1].text)
        assertEquals("Apr", dividers[2].text)
    }

    @Test
    fun divider_x_lands_on_opposite_side_of_dot() {
        val zone = ZoneOffset.UTC
        val s = listOf(snap(2, 1_719_273_600_000L), snap(1, 1_712_275_200_000L))
        val viewport = 360f
        val margin = 100f
        // Right-of-center dot ⇒ divider on LEFT (xPx == margin).
        val dividers = computeDateDividers(
            snapshots = s,
            dotPositions = listOf(
                DotPosition(centerXPx = 250f, yPx = 50f),
                DotPosition(centerXPx = 110f, yPx = 150f),
            ),
            viewportWidthPx = viewport,
            monthMarginPx = margin,
            locale = Locale.US,
            zone = zone,
        )
        assertTrue(dividers.size >= 1)
        assertEquals(margin, dividers[0].xPx, 0.001f)
        if (dividers.size >= 2) {
            assertEquals(viewport - margin, dividers[1].xPx, 0.001f)
        }
    }

    @Test
    fun empty_input_returns_empty() {
        assertEquals(emptyList<DateDivider>(),
            computeDateDividers(emptyList(), emptyList(), 360f, 100f,
                Locale.US, ZoneId.systemDefault()))
    }
}
```

### Step 5.2: Implement DateDividerCalc

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class DateDivider(
    val idTag: Int,        // unique per-divider id (the snapshot index)
    val text: String,      // localized "MMM" output ("Apr", "May", ...)
    val xPx: Float,        // OPPOSITE side of the dot meander
    val yPx: Float,
)

/**
 * Pure helper extracting the inline `showMonth + monthText + monthXPx`
 * logic in `HomeScreen.kt:355-380`. UI behavior unchanged.
 *
 * Iterates snapshots; tracks the previous YearMonth; emits a divider
 * on every transition (and unconditionally for index 0). xPx flips
 * to the opposite side of the dot meander, matching iOS.
 */
fun computeDateDividers(
    snapshots: List<WalkSnapshot>,
    dotPositions: List<DotPosition>,
    viewportWidthPx: Float,
    monthMarginPx: Float,
    locale: Locale,
    zone: ZoneId,
): List<DateDivider> {
    if (snapshots.isEmpty() || dotPositions.isEmpty()) return emptyList()
    val formatter = DateTimeFormatter.ofPattern("MMM", locale).withZone(zone)
    val out = mutableListOf<DateDivider>()
    var lastYearMonth: YearMonth? = null
    val n = kotlin.math.min(snapshots.size, dotPositions.size)
    for (i in 0 until n) {
        val ms = snapshots[i].startMs
        val ym = YearMonth.from(Instant.ofEpochMilli(ms).atZone(zone))
        val emit = (i == 0) || (ym != lastYearMonth)
        if (emit) {
            val pos = dotPositions[i]
            val xPx = if (pos.centerXPx > viewportWidthPx / 2f) {
                monthMarginPx
            } else {
                viewportWidthPx - monthMarginPx
            }
            out += DateDivider(
                idTag = i,
                text = formatter.format(Instant.ofEpochMilli(ms)),
                xPx = xPx,
                yPx = pos.yPx,
            )
        }
        lastYearMonth = ym
    }
    return out
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*DateDividerCalcTest*"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt
git commit -m "feat(journal): DateDividerCalc pure helper (Stage 14-BCD task 5)"
```

---

## Task 6: FootprintShape + ToriiGateShape path builders (Bucket 14-C)

**Goal:** Two pure Compose `Path` builders. Pure path math + Robolectric `Path.getBounds()` tests. **Blocks Tasks 1 (footprint) and 4 (torii).**

**Depends on:** none.

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ShapePathTest.kt`

### Step 6.1: Failing Robolectric test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ShapePathTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ShapePathTest {

    @Test
    fun footprint_path_bounds_within_size() {
        val size = Size(12f, 18f)
        val p = footprintPath(size)
        val b = p.getBounds()
        assertTrue("non-empty path", !b.isEmpty)
        assertTrue("left ≥ 0",   b.left >= 0f)
        assertTrue("top ≥ 0",    b.top >= 0f)
        assertTrue("right ≤ width",  b.right <= size.width + 0.01f)
        assertTrue("bottom ≤ height", b.bottom <= size.height + 0.01f)
    }

    @Test
    fun torii_path_bounds_within_size() {
        val size = Size(16f, 14f)
        val p = toriiGatePath(size)
        val b = p.getBounds()
        assertTrue("non-empty path", !b.isEmpty)
        assertTrue(b.left >= 0f)
        assertTrue(b.top >= 0f)
        assertTrue(b.right <= size.width + 0.01f)
        assertTrue(b.bottom <= size.height + 0.01f)
    }

    @Test
    fun footprint_scales_with_size() {
        val small = footprintPath(Size(12f, 18f)).getBounds()
        val large = footprintPath(Size(60f, 90f)).getBounds()
        assertTrue("large path is wider than small",
            large.width > small.width)
        assertTrue("large path is taller than small",
            large.height > small.height)
    }
}
```

### Step 6.2: Implement FootprintShape

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Footprint outline — verbatim port of iOS `FootprintShape.swift`.
 *
 * Shape: an asymmetric oval-with-toes. The body is an oval (3/4 of the
 * height); five toe ovals sit above it. Used by `ExpandCardSheet`
 * header glyph.
 *
 * Pure path math; no Compose Composable wrapper. Consumers call
 * `Canvas(size) { drawPath(footprintPath(size), ...) }` directly.
 */
fun footprintPath(size: Size): Path {
    val p = Path()
    if (size.width <= 0f || size.height <= 0f) return p
    val w = size.width
    val h = size.height
    // Body — oval covering bottom 3/4 of the box, slightly inset.
    val bodyRect = Rect(
        offset = Offset(w * 0.20f, h * 0.30f),
        size = Size(w * 0.60f, h * 0.65f),
    )
    p.addOval(bodyRect)
    // Big toe (largest, top-left of toe row).
    p.addOval(
        Rect(
            offset = Offset(w * 0.05f, h * 0.05f),
            size = Size(w * 0.30f, h * 0.28f),
        ),
    )
    // Four small toes — descending size.
    val smallToes = listOf(
        Rect(Offset(w * 0.40f, h * 0.00f), Size(w * 0.20f, h * 0.22f)),
        Rect(Offset(w * 0.55f, h * 0.04f), Size(w * 0.18f, h * 0.20f)),
        Rect(Offset(w * 0.68f, h * 0.10f), Size(w * 0.16f, h * 0.18f)),
        Rect(Offset(w * 0.78f, h * 0.18f), Size(w * 0.14f, h * 0.16f)),
    )
    smallToes.forEach { p.addOval(it) }
    return p
}
```

### Step 6.3: Implement ToriiGateShape

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Torii gate outline — verbatim port of iOS `ToriiGateShape.swift`.
 *
 * Two horizontal beams (top kasagi + lintel) + two vertical pillars.
 * Used by MilestoneMarker; shareable with future ToriiScenery refactor.
 *
 * Pure path math.
 */
fun toriiGatePath(size: Size): Path {
    val p = Path()
    if (size.width <= 0f || size.height <= 0f) return p
    val w = size.width
    val h = size.height
    val pillarWidth = w * 0.10f
    val beamHeight = h * 0.12f

    // Top beam (kasagi) — slightly wider than lintel below.
    p.addRect(
        Rect(
            offset = Offset(0f, 0f),
            size = Size(w, beamHeight),
        ),
    )
    // Lintel (nuki) — slightly inset from edges.
    p.addRect(
        Rect(
            offset = Offset(w * 0.05f, beamHeight + h * 0.10f),
            size = Size(w * 0.90f, beamHeight),
        ),
    )
    // Left pillar — from below kasagi to baseline.
    p.addRect(
        Rect(
            offset = Offset(w * 0.18f, beamHeight),
            size = Size(pillarWidth, h - beamHeight),
        ),
    )
    // Right pillar.
    p.addRect(
        Rect(
            offset = Offset(w - w * 0.18f - pillarWidth, beamHeight),
            size = Size(pillarWidth, h - beamHeight),
        ),
    )
    return p
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*ShapePathTest*"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ShapePathTest.kt
git commit -m "feat(scenery): footprintPath + toriiGatePath path builders (Stage 14-BCD task 6)"
```

---

## Task 7: Cascading fade-in primitives (Bucket 14-D)

**Goal:** `JournalFadeInState` driving per-segment + per-dot + per-scenery `Modifier.graphicsLayer { alpha = ... }` lambda form. Reduce-motion ⇒ snap to 1f.

**Stage 5-A lessons:**
- `Modifier.graphicsLayer { alpha = ... }` lambda form (NOT `Modifier.alpha(value)` value form) — value form reads animated state in COMPOSITION phase causing thousands of unnecessary recompositions.
- `rememberSaveable` for `hasAppeared` so rotation doesn't replay the fade.

**Depends on:** none.

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeIn.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeInTest.kt`

### Step 7.1: Failing Robolectric test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeInTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.animation

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JournalFadeInTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun reduce_motion_snaps_to_one() {
        var alpha = -1f
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                val state = rememberJournalFadeIn(reduceMotion = true)
                alpha = state.dotAlpha(0)
            }
        }
        composeRule.runOnIdle {
            assertEquals(1f, alpha, 0.0001f)
        }
    }

    @Test
    fun default_animates_from_0_to_1() {
        composeRule.mainClock.autoAdvance = false
        var observed = 0f
        composeRule.setContent {
            val state = rememberJournalFadeIn(reduceMotion = false)
            observed = state.dotAlpha(0)
        }
        composeRule.mainClock.advanceTimeBy(0)
        // Initial frame ⇒ 0f.
        assertEquals(0f, observed, 0.001f)
        // Pump through total dot animation (300 ms delay + 500 ms dur).
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.runOnIdle {
            assertTrue("alpha should reach ~1f after 2s", observed > 0.99f)
        }
    }

    @Test
    fun segment_alpha_animates() {
        composeRule.mainClock.autoAdvance = false
        var observed = 0f
        composeRule.setContent {
            val state = rememberJournalFadeIn(reduceMotion = false)
            observed = state.segmentAlpha(0)
        }
        composeRule.mainClock.advanceTimeBy(0)
        assertEquals(0f, observed, 0.001f)
        composeRule.mainClock.advanceTimeBy(2500)
        composeRule.runOnIdle {
            assertTrue("segment alpha should reach ~1f after 2.5s", observed > 0.99f)
        }
    }
}
```

### Step 7.2: Implement JournalFadeIn

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeIn.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.animation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/**
 * Cascading fade-in state. Per iOS `InkScrollView.swift`:
 *   - segments[i]: delay 200 + i*30 ms, duration 1200 ms, EaseOut
 *   - dots[i]    : delay 300 + i*30 ms, duration  500 ms, EaseOut
 *   - scenery[i] : shares dot timing
 *
 * Stage 5-A lesson: consumer sites use
 *   `Modifier.graphicsLayer { alpha = state.dotAlpha(i) }`
 * lambda form — NOT the value form `Modifier.alpha(state.dotAlpha(i))`,
 * which reads animated state in COMPOSITION phase and causes thousands
 * of unnecessary recompositions per session.
 *
 * Stage 5-A lesson: `rememberSaveable` for `hasAppeared` so screen
 * rotation doesn't replay the fade.
 *
 * `LocalReduceMotion` gate: when reduceMotion is true, every alpha
 * snaps to 1f immediately (no animation).
 */
@Composable
fun rememberJournalFadeIn(reduceMotion: Boolean): JournalFadeInState {
    var hasAppeared by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { hasAppeared = true }
    return remember(reduceMotion, hasAppeared) {
        JournalFadeInState(hasAppeared = hasAppeared, reduceMotion = reduceMotion)
    }
}

@Stable
class JournalFadeInState(
    private val hasAppeared: Boolean,
    private val reduceMotion: Boolean,
) {
    @Composable
    fun segmentAlpha(index: Int): Float {
        if (reduceMotion) return 1f
        val target = if (hasAppeared) 1f else 0f
        val animation = animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = SEGMENT_DURATION_MS,
                delayMillis = SEGMENT_DELAY_BASE_MS + index * STAGGER_STEP_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "journal-segment-fade",
        )
        return animation.value
    }

    @Composable
    fun dotAlpha(index: Int): Float {
        if (reduceMotion) return 1f
        val target = if (hasAppeared) 1f else 0f
        val animation = animateFloatAsState(
            targetValue = target,
            animationSpec = tween(
                durationMillis = DOT_DURATION_MS,
                delayMillis = DOT_DELAY_BASE_MS + index * STAGGER_STEP_MS,
                easing = FastOutSlowInEasing,
            ),
            label = "journal-dot-fade",
        )
        return animation.value
    }

    /** Scenery shares dot timing per spec. */
    @Composable
    fun sceneryAlpha(index: Int): Float = dotAlpha(index)

    private companion object {
        const val SEGMENT_DELAY_BASE_MS = 200
        const val SEGMENT_DURATION_MS = 1200
        const val DOT_DELAY_BASE_MS = 300
        const val DOT_DURATION_MS = 500
        const val STAGGER_STEP_MS = 30
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*JournalFadeInTest*"
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeIn.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/animation/JournalFadeInTest.kt
git commit -m "feat(journal): cascading fade-in primitives (Stage 14-BCD task 7)"
```

---

## Task 8: EmptyJournalState + CalligraphyPath emptyMode parameter (Bucket 14-D)

**Goal:** Replace current empty-state Text with the iOS-parity tapered tail + 14 dp stone circle + "Begin" caption. Add an additive `emptyMode: Boolean = false` parameter to `CalligraphyPath` for the empty-state stroke.

**Depends on:** none.

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalStateTest.kt`

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt` — add `emptyMode: Boolean = false`. When `true`, draw the iOS empty-state tapered single stroke (120 dp tall, half-width 1 dp top, 0.2 dp bottom, 1 dp tail). Existing call sites unaffected by the default.
- `app/src/main/res/values/strings.xml` — add `home_empty_begin`. KEEP existing `home_empty_message` for accessibility fallback.

### Step 8.1: Failing Robolectric test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalStateTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.empty

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmptyJournalStateTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun renders_begin_caption() {
        composeRule.setContent {
            PilgrimTheme { EmptyJournalState() }
        }
        composeRule.onNodeWithText("Begin").assertIsDisplayed()
    }
}
```

### Step 8.2: Add string resource

Edit `app/src/main/res/values/strings.xml`:

```xml
<!-- Stage 14-BCD empty-state -->
<string name="home_empty_begin">Begin</string>
<!-- KEEP existing home_empty_message for accessibility fallback. -->
<!-- Expand-card labels (Task 1) -->
<string name="journal_expand_label_distance">distance</string>
<string name="journal_expand_label_duration">duration</string>
<string name="journal_expand_label_pace">pace</string>
<string name="journal_expand_pill_walk">walk</string>
<string name="journal_expand_pill_talk">talk</string>
<string name="journal_expand_pill_meditate">meditate</string>
<string name="journal_expand_view_details">View details</string>
<string name="journal_expand_dismiss_a11y">Close walk details</string>
```

> Tip: any strings already added in earlier tasks (e.g. milestone plurals from Task 4, turning banners from Task 2, lunar a11y from Task 4) should already be present — keep this block as the single source of truth and resolve any duplicate `<string name="...">` lint errors before commit.

### Step 8.3: Modify CalligraphyPath to add emptyMode

Edit `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt`. Add the additive parameter at the END of the existing parameter list (so existing call sites stay green):

```kotlin
@Composable
fun CalligraphyPath(
    strokes: List<CalligraphyStrokeSpec>,
    modifier: Modifier = Modifier,
    verticalSpacing: Dp = 90.dp,
    topInset: Dp = 40.dp,
    maxMeander: Dp = 100.dp,
    baseWidth: Dp = 1.5.dp,
    maxWidth: Dp = 4.5.dp,
    emptyMode: Boolean = false,
) {
    // Empty state — a single tapered tail. iOS InkScrollView.swift:714.
    // 120 dp tall: half-width 1 dp top → 0.2 dp midpoint → 1 dp bottom.
    if (emptyMode) {
        Canvas(modifier = modifier.fillMaxWidth().height(120.dp)) {
            val centerX = size.width / 2f
            val topY = 0f
            val midY = size.height / 2f
            val bottomY = size.height
            val topHalf = 1f * density
            val midHalf = 0.2f * density
            val bottomHalf = 1f * density
            val color = androidx.compose.ui.graphics.Color(0xFF8B7355)  // stone
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(centerX - topHalf, topY)
                cubicTo(
                    centerX - topHalf, midY * 0.6f,
                    centerX - midHalf, midY * 0.9f,
                    centerX - midHalf, midY,
                )
                cubicTo(
                    centerX - midHalf, midY * 1.1f,
                    centerX - bottomHalf, midY * 1.5f,
                    centerX - bottomHalf, bottomY,
                )
                lineTo(centerX + bottomHalf, bottomY)
                cubicTo(
                    centerX + bottomHalf, midY * 1.5f,
                    centerX + midHalf, midY * 1.1f,
                    centerX + midHalf, midY,
                )
                cubicTo(
                    centerX + midHalf, midY * 0.9f,
                    centerX + topHalf, midY * 0.6f,
                    centerX + topHalf, topY,
                )
                close()
            }
            drawPath(path = path, color = color.copy(alpha = 0.5f))
        }
        return
    }

    // ...original body unchanged...
}
```

> Implementation note: place the empty-mode branch as the FIRST `Canvas` early-return, leaving the existing strokes-render body otherwise unmodified. Add a comment block linking back to `InkScrollView.swift:714`.

### Step 8.4: Implement EmptyJournalState

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.empty

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyPath
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Empty-state Composable shown when no walks exist. Verbatim port of
 * iOS `InkScrollView.swift:714`:
 *   - Tapered single calligraphy stroke (120 dp tall, 1 dp ↘ 0.2 dp ↗ 1 dp)
 *   - 14 dp stone-color filled circle
 *   - "Begin" caption (caption typography, fog color)
 */
@Composable
fun EmptyJournalState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CalligraphyPath(
                strokes = emptyList(),
                modifier = Modifier.fillMaxWidth().height(120.dp),
                emptyMode = true,
            )
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(pilgrimColors.stone),
            )
            Text(
                text = stringResource(R.string.home_empty_begin),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}
```

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*EmptyJournalStateTest*"
./gradlew :app:assembleDebug
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/ \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt \
        app/src/main/res/values/strings.xml \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/empty/
git commit -m "feat(journal): EmptyJournalState + CalligraphyPath emptyMode (Stage 14-BCD task 8)"
```

---

## Task 9: Final integration + on-device QA (Bucket 14-D)

**Goal:** Wire Tasks 1-8 into `HomeScreen.kt`. Run full `:app:assembleDebug :app:lintDebug :app:testDebugUnitTest` green. On-device QA on OnePlus 13 per spec checklist.

**Depends on:** Tasks 1, 2, 3, 4, 5, 6, 7, 8.

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

### Step 9.1: Failing Robolectric integration test

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class JournalScreenIntegrationTest {
    @get:Rule val composeRule = createComposeRule()

    /**
     * Smoke-only: render HomeScreen with 4 fixture walks of 30 km each.
     * Tap a walk dot ⇒ ExpandCardSheet opens with that walk's data.
     * Tap "View details" ⇒ onEnterWalkSummary fires with the walk id.
     *
     * Wired in Task 9 after the full HomeScreen rewire. May be marked
     * @Ignore("waits-for-9.2-rewire") in earlier task commits and
     * unblocked once Step 9.2 lands.
     */
    @Test
    fun tap_dot_opens_sheet_and_view_details_navigates() {
        // Implementation depends on Step 9.2 wiring. See Step 9.3.
    }
}
```

> The integration test body fills in once Step 9.2 wiring is complete. For initial commit it can be a placeholder `@Test fun smoke() { /* TODO Step 9.3 */ }`.

### Step 9.2: Wire Tasks 1-8 into HomeScreen.kt

Edit `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` per the changes below. Wire order matches canvas-Z (banner above header; lunar/milestone overlays inside the canvas Box; date dividers from `computeDateDividers`; `ExpandCardSheet` outside the scroll Column; cascading fade-in via `rememberJournalFadeIn`; empty-state replacement).

Specific edits:

1. Imports — add:
   ```kotlin
   import androidx.compose.runtime.collectAsState
   import androidx.compose.ui.platform.LocalConfiguration
   import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForToday
   import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion
   import org.walktalkmeditate.pilgrim.ui.home.animation.rememberJournalFadeIn
   import org.walktalkmeditate.pilgrim.ui.home.banner.TurningDayBanner
   import org.walktalkmeditate.pilgrim.ui.home.empty.EmptyJournalState
   import org.walktalkmeditate.pilgrim.ui.home.expand.ExpandCardSheet
   import org.walktalkmeditate.pilgrim.ui.home.markers.LunarMarkerDot
   import org.walktalkmeditate.pilgrim.ui.home.markers.MilestoneMarker
   import org.walktalkmeditate.pilgrim.ui.home.markers.computeDateDividers
   import org.walktalkmeditate.pilgrim.ui.home.markers.computeLunarMarkers
   import org.walktalkmeditate.pilgrim.ui.home.markers.computeMilestonePositions
   import org.walktalkmeditate.pilgrim.ui.design.calligraphy.dotPositions as calligraphyDotPositions
   ```

2. Empty branch — replace existing `Text` with:
   ```kotlin
   JournalUiState.Empty -> {
       EmptyJournalState(modifier = Modifier.fillMaxSize().padding(PilgrimSpacing.big))
   }
   ```

3. Above `JourneySummaryHeader` (still inside the scrolling Column) insert:
   ```kotlin
   val currentTurning = remember { turningMarkerForToday() }
   TurningDayBanner(marker = currentTurning, modifier = Modifier.fillMaxWidth())
   ```

4. Inside the canvas Box, after the existing `forEachIndexed` block, add overlay siblings — lunar + milestone + date dividers. Wrap each Composable in `Modifier.graphicsLayer { alpha = ... }`:
   ```kotlin
   val reduceMotion = LocalReduceMotion.current
   val fadeInState = rememberJournalFadeIn(reduceMotion = reduceMotion)

   // Compute overlays from already-derived dot positions list.
   val dotPositionsList = remember(strokes, widthPx) {
       calligraphyDotPositions(
           strokes = strokes,
           widthPx = widthPx,
           verticalSpacingPx = verticalSpacingPx,
           topInsetPx = topInsetPx,
           maxMeanderPx = maxMeanderPx,
       )
   }
   val lunarMarkers = remember(s.snapshots, dotPositionsList, widthPx) {
       computeLunarMarkers(s.snapshots, dotPositionsList, widthPx)
   }
   val milestoneMarkers = remember(s.snapshots, dotPositionsList) {
       computeMilestonePositions(s.snapshots, dotPositionsList)
   }
   val dateDividers = remember(s.snapshots, dotPositionsList, widthPx, monthMarginPx) {
       computeDateDividers(
           snapshots = s.snapshots,
           dotPositions = dotPositionsList,
           viewportWidthPx = widthPx,
           monthMarginPx = monthMarginPx,
           locale = Locale.getDefault(),
           zone = ZoneId.systemDefault(),
       )
   }

   // Lunar markers — 10x10 dp Box.offset.
   lunarMarkers.forEachIndexed { idx, marker ->
       Box(
           modifier = Modifier
               .offset { IntOffset((marker.xPx - 5f).toInt(), (marker.yPx - 5f).toInt()) }
               .size(10.dp)
               .graphicsLayer { alpha = fadeInState.dotAlpha(idx) },
       ) {
           LunarMarkerDot(isFullMoon = marker.illumination > 0.5)
       }
   }

   // Milestone markers — fillMaxWidth Row at yPx.
   milestoneMarkers.forEachIndexed { idx, m ->
       MilestoneMarker(
           distanceM = m.distanceM,
           units = units,
           modifier = Modifier
               .offset { IntOffset(0, m.yPx.toInt()) }
               .fillMaxWidth()
               .graphicsLayer { alpha = fadeInState.dotAlpha(idx) },
       )
   }

   // Date dividers — replace inline showMonth + monthText + monthXPx.
   dateDividers.forEach { divider ->
       Text(
           text = divider.text,
           style = pilgrimType.caption,
           color = pilgrimColors.fog.copy(alpha = 0.5f),
           modifier = Modifier
               .offset { IntOffset(divider.xPx.toInt(), divider.yPx.toInt()) }
               .semantics { contentDescription = "" },
       )
   }
   ```

5. **Replace** the dot tap callback `onTap = { onEnterWalkSummary(snap.id) }` with:
   ```kotlin
   onTap = { homeViewModel.setExpandedSnapshotId(snap.id) },
   ```
   And **delete** the inline `showMonth + monthText + monthXPx` block (now superseded by `dateDividers`).

6. Wrap `CalligraphyPath` and per-dot/per-scenery Composables in `graphicsLayer { alpha = fadeInState.* }`:
   ```kotlin
   CalligraphyPath(
       strokes = strokes,
       modifier = Modifier
           .fillMaxWidth()
           .graphicsLayer { alpha = fadeInState.segmentAlpha(0) }
           .then(calligraphyBlur),
       verticalSpacing = JOURNAL_ROW_HEIGHT,
       topInset = JOURNAL_TOP_INSET_DP,
       maxMeander = JOURNAL_MAX_MEANDER,
   )
   ```
   For per-dot wrap the WalkDot's `modifier`:
   ```kotlin
   modifier = Modifier
       .offset { IntOffset(...) }
       .graphicsLayer { alpha = fadeInState.dotAlpha(index) },
   ```
   For scenery, similarly wrap with `.graphicsLayer { alpha = fadeInState.sceneryAlpha(index) }`.

7. Outside the scroll Column (still inside the outer top-level `Box`), observe `expandedSnapshotId` and render the sheet:
   ```kotlin
   val expandedId by homeViewModel.expandedSnapshotId.collectAsStateWithLifecycle()
   val expandedCelestial by homeViewModel.expandedCelestialSnapshot.collectAsStateWithLifecycle()
   val themeColors = pilgrimColors

   // Stale-id guard: walk deleted while sheet open.
   LaunchedEffect(s.snapshots, expandedId) {
       if (expandedId != null && s.snapshots.none { it.id == expandedId }) {
           homeViewModel.setExpandedSnapshotId(null)
       }
   }

   val expandedSnap = expandedId?.let { id ->
       s.snapshots.firstOrNull { it.id == id }
   }
   if (expandedSnap != null) {
       val seasonColor = walkDotBaseColor(expandedSnap.startMs, themeColors)
       ExpandCardSheet(
           snapshot = expandedSnap,
           celestial = expandedCelestial,
           seasonColor = seasonColor,
           units = units,
           isShared = expandedSnap.isShared,
           onViewDetails = { id ->
               homeViewModel.setExpandedSnapshotId(null)
               onEnterWalkSummary(id)
           },
           onDismissRequest = { homeViewModel.setExpandedSnapshotId(null) },
       )
   }
   ```

> **Stage 14-A note (tap ripples globally dead):** all new `clickable` surfaces inherit the theme's `LocalRippleConfiguration null` + `LocalIndication NoIndication` — no need to opt out per-call. ExpandCardSheet's "View details" Button uses Material 3 default Button, which obeys the global setting.

### Step 9.3: Flesh out the integration test body

Replace the placeholder `@Test fun smoke()` with the real integration assertion driving 4 fixture walks of 30 km each through HomeViewModel + WalkRepository fakes. The test should:

- Spin up Hilt with a fake `WalkRepository.observeAllWalks` returning 4 walks of 30 km each (cumulative 30/60/90/120 km).
- Render `HomeScreen` inside `composeRule.setContent`.
- Assert `onAllNodesWithContentDescription("walk dot 1").onFirst().performClick()` opens a sheet with that walk's data.
- Assert `onNodeWithText("View details").performClick()` triggers `onEnterWalkSummary` with the matching id.
- Assert a `MilestoneMarker` for "100 km" exists in the tree.

Wire fakes via `@HiltAndroidTest` if existing precedent exists, or feed `HomeViewModel` directly via constructor injection in a wrapper Composable for test-only use.

> If wiring complexity exceeds the time budget, scope the integration test to render-only smoke (assert `Pilgrim Log` title + at least one `walk dot 0` content-description visible) and keep the dot-tap → sheet → "View details" assertion as a follow-up. Stage 7-A precedent for fake-repo HomeViewModel tests applies.

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
./gradlew :app:testDebugUnitTest
# All three must be green.
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt
git commit -m "feat(journal): wire ExpandCardSheet + overlays + fade-in into HomeScreen (Stage 14-BCD task 9)"
```

### Step 9.4: On-device QA on OnePlus 13

Per spec § Verification (Stage 5-G pattern):

```bash
# Build + install via android skill or raw adb.
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:installDebug
adb shell monkey -p org.walktalkmeditate.pilgrim.debug -c android.intent.category.LAUNCHER 1
```

Run through this checklist on-device, recording results in the PR description:

- [ ] **QA-1 Empty state.** Fresh install (no walks) → JournalScreen shows EmptyJournalState (tapered tail + 14 dp stone circle + "Begin" caption).
- [ ] **QA-2 Milestone marker.** Seed 4 walks of 30 km each via instrumentation OR manual debug import → MilestoneMarker visible at "100 km" on the newest walk's row.
- [ ] **QA-3 Lunar marker.** Seed walks bracketing 2024-01-25 (full moon) → at least one filled silver disc visible interpolated between dots; `adb shell cmd uimode night yes` re-tints the disc to the warmer dark-theme color.
- [ ] **QA-4 ExpandCardSheet open + back.** Tap any dot → ExpandCardSheet rises from bottom; FootprintShape + favicon + date + 3 stats + activity bar + 3 pills + "View details" all visible. Tap-outside dismisses. `adb shell input keyevent BACK` dismisses (BackHandler).
- [ ] **QA-5 View details nav.** Tap "View details" → ExpandCardSheet animates closed THEN navigates to WalkSummary for the right walkId.
- [ ] **QA-6 TurningDayBanner.** `adb shell date 202503201200` (March 20 noon UTC), restart Pilgrim → TurningDayBanner appears above JourneySummaryHeader with "Today, day equals night. · 春分".
- [ ] **QA-7 Cascading fade-in.** Cold-start with 12 fixture walks → cascading fade-in visible (segments appear over ~1.2 s, dots over ~0.5 s).
- [ ] **QA-8 Reduce-motion gate.** Settings → Accessibility → Reduce Animations on → content snaps in immediately; scroll → no haptics fire.
- [ ] **QA-9 Rotation persistence.** Rotate device while ExpandCardSheet open → sheet stays open; cycler header preserves StatMode (`rememberSaveable`).
- [ ] **QA-10 Memory pressure.** Navigate Journal → WalkSummary → back → Journal 20 times → no leaks reported by Android Studio profiler.

If any QA item fails, fix in-place + recommit; do NOT skip a checkbox.

```bash
git push -u origin feat/stage-14-bcd-bundle
gh pr create --title "feat(journal): Stage 14-BCD bundle — ExpandCardSheet + Overlays + Polish" \
  --body "$(cat <<'EOF'
## Summary

Closes the iOS-v1.5.0 Journal parity gap on top of the Stage 14-A foundation.

- Tasks 1-9 per `docs/superpowers/plans/2026-05-06-stage-14-bcd-bundle.md`
- Spec: `docs/superpowers/specs/2026-05-06-stage-14-bcd-bundle-design.md` (commit 4a6db03)

## Test plan

- [x] :app:assembleDebug
- [x] :app:lintDebug
- [x] :app:testDebugUnitTest
- [x] OnePlus 13 QA-1 through QA-10
EOF
)"
```

---

## Self-review

- [x] **Spec coverage:** every Files-to-create entry in the spec has a numbered task — Task 1 → ExpandCardSheet/Mini/Pills; Task 2 → SeasonalMarkerTurnings + TurningDayBanner; Task 3 → LunarMarkerCalc + LunarMarkerDot; Task 4 → MilestoneCalc + MilestoneMarker; Task 5 → DateDividerCalc; Task 6 → FootprintShape + ToriiGateShape; Task 7 → JournalFadeIn; Task 8 → EmptyJournalState + CalligraphyPath emptyMode; Task 9 → HomeScreen wiring + on-device QA.
- [x] **No placeholders:** every task has explicit file paths, complete code blocks, exact gradle commands. The integration test in Step 9.3 has a documented fallback (smoke-only) with a commit-time decision criterion.
- [x] **Type consistency:** `WalkSnapshot` uses `durationSec: Double` with `talkDurationSec` / `meditateDurationSec` as `Long` and `cumulativeDistanceM: Double`; `DotPosition` carries `centerXPx + yPx`. Plan uses `UnitSystem` (the actual type — spec mentioned `DistanceUnits` informally; `UnitSystem` is the real Kotlin enum).
- [x] **Task ordering respects dependency graph:** Task 6 (path builders) before Tasks 1 (footprint) and 4 (torii); Task 9 (final wiring) last.
- [x] **Lessons baked in:**
  - Task 1 — `rememberUpdatedState(onDismissRequest)` (Stage 4-B), `Locale.getDefault()` for date format (Stage 6-B).
  - Task 4 — `Locale.US` for numeric formatting (Stage 5-A).
  - Task 5 — `Locale.setDefault(Locale.US)` in `@Before` for deterministic month abbreviations.
  - Task 7 — `Modifier.graphicsLayer { alpha = ... }` lambda form + `rememberSaveable` (Stage 5-A).
  - Task 9 — global ripple disabled (Stage 14-A) — no per-call opt-out needed; stale-id guard in `LaunchedEffect`.
  - All — `catch (CancellationException) { throw it }` first (Stage 13-XZ B6) where applicable; orchestrators use `withContext(Dispatchers.Default)` for CPU work (Stage 13-XZ B2).
- [x] **VM test discipline:** no new VM tests needed. Spec confirms VM surface unchanged (`expandedSnapshotId`, `expandedCelestialSnapshot`, `setExpandedSnapshotId` all shipped Stage 14-A). If a VM test is added later, must `vm.viewModelScope.cancel()` before `db.close()` (Stage 7-A flake fix).
- [x] **Plan size:** ~1100 lines — within the 700-1100 target.
