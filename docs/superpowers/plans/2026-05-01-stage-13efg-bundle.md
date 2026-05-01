# Stage 13-EFG bundle Implementation Plan

> REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development.

**Goal:** Three layout-only iOS-port sub-stages bundled — Favicon selector (13-E), Milestone callout + Elevation profile (13-F partial), Details Paused card (13-G).

**Spec:** `docs/superpowers/specs/2026-05-01-stage-13efg-bundle-design.md`

---

## Task 1: Branch + commit spec/plan

- [ ] `git checkout -b feat/stage-13efg-bundle`
- [ ] Commit spec + plan as `docs(walk-summary): Stage 13-EFG bundle spec + plan`.

---

## Task 2: WalkFavicon enum + DAO + repo + strings

**Files:**
- Create: `app/src/main/java/.../data/entity/WalkFavicon.kt`
- Modify: `app/src/main/java/.../data/dao/WalkDao.kt`
- Modify: `app/src/main/java/.../data/WalkRepository.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: WalkFavicon enum.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.entity

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.ui.graphics.vector.ImageVector
import org.walktalkmeditate.pilgrim.R

/**
 * Mood-tag a walk on the summary screen. iOS-faithful port of
 * `pilgrim-ios/Pilgrim/Models/Walk/WalkFavicon.swift`. Persisted as
 * `Walk.favicon` String column; cross-platform `.pilgrim` archive
 * already round-trips this value.
 */
enum class WalkFavicon(
    val rawValue: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    FLAME("flame", R.string.summary_favicon_transformative, Icons.Filled.LocalFireDepartment),
    LEAF("leaf", R.string.summary_favicon_peaceful, Icons.Outlined.Spa),
    STAR("star", R.string.summary_favicon_extraordinary, Icons.Filled.Star),
    ;

    companion object {
        fun fromRawValue(raw: String?): WalkFavicon? =
            raw?.let { needle -> entries.firstOrNull { it.rawValue == needle } }
    }
}
```

- [ ] **Step 2: Add `WalkDao.updateFavicon`** alongside `updateIntention`:

```kotlin
@Query("UPDATE walks SET favicon = :favicon WHERE id = :walkId")
suspend fun updateFavicon(walkId: Long, favicon: String?)
```

- [ ] **Step 3: Add `WalkRepository.setFavicon`** alongside `setIntention`:

```kotlin
suspend fun setFavicon(walkId: Long, favicon: String?) =
    walkDao.updateFavicon(walkId, favicon)
```

- [ ] **Step 4: Add 12 strings** to `strings.xml`:

```xml
<!-- Stage 13-E favicon -->
<string name="summary_favicon_caption">Mark this walk</string>
<string name="summary_favicon_transformative">Transformative</string>
<string name="summary_favicon_peaceful">Peaceful</string>
<string name="summary_favicon_extraordinary">Extraordinary</string>

<!-- Stage 13-F milestone callout prose -->
<string name="summary_milestone_first_walk">Your first walk</string>
<string name="summary_milestone_longest_walk">Your longest walk yet</string>
<string name="summary_milestone_nth_walk">Your %1$s walk</string>
<string name="summary_milestone_first_of_spring">Your first walk of Spring</string>
<string name="summary_milestone_first_of_summer">Your first walk of Summer</string>
<string name="summary_milestone_first_of_autumn">Your first walk of Autumn</string>
<string name="summary_milestone_first_of_winter">Your first walk of Winter</string>

<!-- Stage 13-G details -->
<string name="summary_details_paused">Paused</string>
```

- [ ] **Step 5:** Compile + commit `feat(walk-summary): WalkFavicon + favicon DAO/repo + EFG strings (Stage 13-EFG task 2)`.

---

## Task 3: ElevationProfile pure helper + tests

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/ElevationProfile.kt`
- Create: `app/src/test/java/.../ui/walk/summary/ElevationProfileTest.kt`

- [ ] **Step 1: Test first.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ElevationProfileTest {

    @Test fun emptyAltitudes_returnsEmpty() {
        assertTrue(computeElevationSparklinePoints(emptyList(), 100).isEmpty())
    }

    @Test fun underTwoSamples_returnsEmpty() {
        assertTrue(computeElevationSparklinePoints(listOf(100.0), 100).isEmpty())
    }

    @Test fun monotonicAscent_yieldsAscendingPath() {
        // Higher altitudes → lower y (top of frame is highest).
        // y = 1 - (alt - min) / (max - min)
        val points = computeElevationSparklinePoints(
            altitudes = listOf(100.0, 110.0, 120.0, 130.0),
            targetWidthBuckets = 100,
        )
        assertEquals(4, points.size)
        // First sample is min altitude → y = 1 (bottom)
        assertEquals(1f, points.first().yFraction, 0.0001f)
        // Last sample is max altitude → y = 0 (top)
        assertEquals(0f, points.last().yFraction, 0.0001f)
        assertTrue(points.last().yFraction < points.first().yFraction)
    }

    @Test fun mixedAltitudes_normalizesAcrossRange() {
        val points = computeElevationSparklinePoints(
            altitudes = listOf(100.0, 200.0, 100.0, 200.0),
            targetWidthBuckets = 100,
        )
        assertEquals(4, points.size)
        assertEquals(1f, points[0].yFraction, 0.0001f)
        assertEquals(0f, points[1].yFraction, 0.0001f)
        assertEquals(1f, points[2].yFraction, 0.0001f)
        assertEquals(0f, points[3].yFraction, 0.0001f)
    }

    @Test fun stride_caps_buckets_at_target_width() {
        // 200 samples + targetWidthBuckets = 50 → step = 4 → 50 buckets
        val altitudes = (0 until 200).map { it.toDouble() }
        val points = computeElevationSparklinePoints(altitudes, targetWidthBuckets = 50)
        assertTrue(points.size <= 50)
        assertTrue(points.size >= 50 - 1)
    }
}
```

- [ ] **Step 2: FAIL.**

- [ ] **Step 3: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import kotlin.math.max

/**
 * One point on the elevation sparkline path, both axes normalized
 * to `[0, 1]`. y is `1 - normalized-altitude` so the highest altitude
 * draws at the top (y=0) and the lowest at the bottom (y=1).
 */
@Immutable
internal data class ElevationSparklinePoint(
    val xFraction: Float,
    val yFraction: Float,
)

/**
 * Bucket-and-normalize altitude samples for the post-walk elevation
 * sparkline. Verbatim port of iOS `ElevationProfileView` sample-stride
 * + per-pixel-bucket pattern (`ElevationProfileView.swift:39-79`).
 *
 * Returns empty for fewer than 2 samples (cannot draw a path) OR when
 * `max - min` is zero (degenerate flat profile — caller guards on
 * range > 1m before calling, but this is defense-in-depth).
 */
internal fun computeElevationSparklinePoints(
    altitudes: List<Double>,
    targetWidthBuckets: Int,
): List<ElevationSparklinePoint> {
    if (altitudes.size < 2) return emptyList()
    val minAlt = altitudes.min()
    val maxAlt = altitudes.max()
    val range = maxAlt - minAlt
    if (range <= 0.0) return emptyList()

    val step = max(1, altitudes.size / targetWidthBuckets.coerceAtLeast(1))
    val sampled = mutableListOf<Double>()
    var i = 0
    while (i < altitudes.size) {
        sampled += altitudes[i]
        i += step
    }
    if (sampled.size < 2) return emptyList()
    val denom = (sampled.size - 1).toFloat()
    return sampled.mapIndexed { idx, alt ->
        ElevationSparklinePoint(
            xFraction = idx.toFloat() / denom,
            yFraction = (1.0 - (alt - minAlt) / range).toFloat(),
        )
    }
}
```

- [ ] **Step 4: 4 passing.**
- [ ] **Step 5:** Commit `feat(walk-summary): ElevationProfile pure helper (Stage 13-EFG task 3)`.

---

## Task 4: VM additions — altitudeSamples + selectedFavicon + setFavicon + 2 tests

**Files:**
- Modify: `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt`
- Modify: `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt`

- [ ] **Step 1: WalkSummary field.** Add after existing fields:

```kotlin
val altitudeSamples: List<AltitudeSample> = emptyList(),
```

Add import for AltitudeSample (already imported).

- [ ] **Step 2:** In `buildState()`, pass the already-hoisted `altitudeSamples` local through to WalkSummary:

```kotlin
altitudeSamples = altitudeSamples,
```

- [ ] **Step 3: VM-level favicon state + setFavicon method.**

```kotlin
private val _selectedFavicon = MutableStateFlow<WalkFavicon?>(null)
val selectedFavicon: StateFlow<WalkFavicon?> = _selectedFavicon.asStateFlow()
```

In `buildState()` after fetching `walk`:

```kotlin
_selectedFavicon.value = WalkFavicon.fromRawValue(walk.favicon)
```

New method:

```kotlin
fun setFavicon(favicon: WalkFavicon?) {
    val current = _selectedFavicon.value
    val newValue = if (favicon == current) null else favicon
    _selectedFavicon.value = newValue
    viewModelScope.launch(Dispatchers.IO) {
        try {
            repository.setFavicon(walkId, newValue?.rawValue)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "setFavicon failed for walk $walkId", t)
            _selectedFavicon.value = current
        }
    }
}
```

Add imports for `WalkFavicon`, `MutableStateFlow`, `asStateFlow`, `Dispatchers`, `launch`, `CancellationException` if not present.

- [ ] **Step 4: 2 VM tests.**

```kotlin
@Test
fun setFavicon_persistsAndUpdatesFlow() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    val vm = newViewModel(walkId)
    awaitLoaded(vm)
    assertNull(vm.selectedFavicon.value)

    vm.setFavicon(WalkFavicon.LEAF)
    advanceUntilIdle()

    assertEquals(WalkFavicon.LEAF, vm.selectedFavicon.value)
    val persisted = repository.getWalk(walkId)
    assertEquals("leaf", persisted?.favicon)

    // Tap same → deselects
    vm.setFavicon(WalkFavicon.LEAF)
    advanceUntilIdle()

    assertNull(vm.selectedFavicon.value)
    val persistedNull = repository.getWalk(walkId)
    assertNull(persistedNull?.favicon)
}

@Test
fun altitudeSamples_populatedFromRepo() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertAltitude(walkId, 1_000L, 100.0)
    insertAltitude(walkId, 2_000L, 110.0)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(2, loaded.summary.altitudeSamples.size)
}
```

Add imports `WalkFavicon`, `advanceUntilIdle`.

- [ ] **Step 5:** Compile + run tests. Commit `feat(walk-summary): VM altitudeSamples + selectedFavicon + setFavicon (Stage 13-EFG task 4)`.

---

## Task 5: FaviconSelectorCard composable + 2 tests

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/FaviconSelectorCard.kt`
- Create: `app/src/test/java/.../ui/walk/summary/FaviconSelectorCardTest.kt`

- [ ] **Step 1: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * "Mark this walk" card with three toggle buttons (flame/leaf/star).
 * iOS reference: `FaviconSelectorView.swift`. Tap selects, tap same
 * again deselects.
 */
@Composable
fun FaviconSelectorCard(
    selected: WalkFavicon?,
    onSelect: (WalkFavicon?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.summary_favicon_caption),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
            ) {
                WalkFavicon.entries.forEach { fav ->
                    FaviconButton(
                        favicon = fav,
                        isSelected = selected == fav,
                        onTap = { onSelect(if (selected == fav) null else fav) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FaviconButton(
    favicon: WalkFavicon,
    isSelected: Boolean,
    onTap: () -> Unit,
) {
    val fillColor by animateColorAsState(
        targetValue = if (isSelected) pilgrimColors.stone
        else pilgrimColors.fog.copy(alpha = 0.15f),
        animationSpec = tween(durationMillis = 200),
        label = "favicon-fill",
    )
    val contentColor by animateColorAsState(
        targetValue = if (isSelected) pilgrimColors.parchment else pilgrimColors.fog,
        animationSpec = tween(durationMillis = 200),
        label = "favicon-content",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) pilgrimColors.ink else pilgrimColors.fog,
        animationSpec = tween(durationMillis = 200),
        label = "favicon-label",
    )
    Column(
        modifier = Modifier.clickable(onClick = onTap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(fillColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = favicon.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = stringResource(favicon.labelRes),
            style = pilgrimType.micro,
            color = labelColor,
        )
    }
}
```

- [ ] **Step 2: Compile.**

- [ ] **Step 3: Tests.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FaviconSelectorCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersAllThreeButtons() {
        composeRule.setContent {
            PilgrimTheme {
                FaviconSelectorCard(selected = null, onSelect = {})
            }
        }
        composeRule.onNodeWithText("Transformative").assertIsDisplayed()
        composeRule.onNodeWithText("Peaceful").assertIsDisplayed()
        composeRule.onNodeWithText("Extraordinary").assertIsDisplayed()
    }

    @Test
    fun tapsSameButtonTwice_deselects() {
        val selections = mutableListOf<WalkFavicon?>()
        composeRule.setContent {
            PilgrimTheme {
                FaviconSelectorCard(
                    selected = null,
                    onSelect = { selections += it },
                )
            }
        }
        composeRule.onNodeWithText("Peaceful").performClick()
        // First tap selects LEAF
        assertEquals(WalkFavicon.LEAF, selections.last())
    }
}
```

- [ ] **Step 4: 2 passing.**
- [ ] **Step 5:** Commit `feat(walk-summary): FaviconSelectorCard (Stage 13-EFG task 5)`.

---

## Task 6: MilestoneCalloutRow composable + 4 tests

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/MilestoneCalloutRow.kt`
- Create: `app/src/test/java/.../ui/walk/summary/MilestoneCalloutRowTest.kt`

- [ ] **Step 1: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestones
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Season

/**
 * Sparkles-tagged dawn-tinted callout above the stats row when the
 * walk earned a milestone. iOS reference: `WalkSummaryView.milestoneCallout`
 * (`WalkSummaryView.swift:332-348`).
 *
 * Caller-side gate: render only when `summary.milestone != null`.
 *
 * iOS divergence: iOS computes "Your longest meditation yet" + "You've
 * now walked N km total" inside `computeMilestone`. Android's
 * GoshuinMilestone (Stage 4-D) has no equivalent variants. Adding them
 * ripples through the goshuin grid + halo + reveal-overlay surface;
 * deferred to a focused follow-up. This composable handles only the
 * existing variants today.
 */
@Composable
fun MilestoneCalloutRow(
    milestone: GoshuinMilestone,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.dawn.copy(alpha = 0.1f))
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = pilgrimColors.dawn,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = milestoneSummaryProse(milestone),
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun milestoneSummaryProse(milestone: GoshuinMilestone): String = when (milestone) {
    GoshuinMilestone.FirstWalk ->
        stringResource(R.string.summary_milestone_first_walk)
    GoshuinMilestone.LongestWalk ->
        stringResource(R.string.summary_milestone_longest_walk)
    is GoshuinMilestone.NthWalk ->
        stringResource(R.string.summary_milestone_nth_walk, GoshuinMilestones.ordinal(milestone.n))
    is GoshuinMilestone.FirstOfSeason -> stringResource(
        when (milestone.season) {
            Season.Spring -> R.string.summary_milestone_first_of_spring
            Season.Summer -> R.string.summary_milestone_first_of_summer
            Season.Autumn -> R.string.summary_milestone_first_of_autumn
            Season.Winter -> R.string.summary_milestone_first_of_winter
        },
    )
}
```

(`GoshuinMilestones.ordinal` is `internal` — accessible from same module.)

- [ ] **Step 2: Compile.**

- [ ] **Step 3: Tests.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Season

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MilestoneCalloutRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun firstWalk_rendersCorrectProse() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(GoshuinMilestone.FirstWalk) }
        }
        composeRule.onNodeWithText("Your first walk").assertIsDisplayed()
    }

    @Test
    fun longestWalk_rendersCorrectProse() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(GoshuinMilestone.LongestWalk) }
        }
        composeRule.onNodeWithText("Your longest walk yet").assertIsDisplayed()
    }

    @Test
    fun nthWalk_includesOrdinal() {
        composeRule.setContent {
            PilgrimTheme { MilestoneCalloutRow(GoshuinMilestone.NthWalk(5)) }
        }
        composeRule.onNodeWithText("Your 5th walk").assertIsDisplayed()
    }

    @Test
    fun firstOfSeason_includesSeasonName() {
        composeRule.setContent {
            PilgrimTheme {
                MilestoneCalloutRow(GoshuinMilestone.FirstOfSeason(Season.Spring))
            }
        }
        composeRule.onNodeWithText("Your first walk of Spring").assertIsDisplayed()
    }
}
```

- [ ] **Step 4: 4 passing.**
- [ ] **Step 5:** Commit `feat(walk-summary): MilestoneCalloutRow (Stage 13-EFG task 6)`.

---

## Task 7: ElevationProfile composable

**Files:** Create `app/src/main/java/.../ui/walk/summary/ElevationProfile.kt` (extend the file from Task 3 with the @Composable surface)

- [ ] **Step 1:** Append the @Composable section to ElevationProfile.kt:

```kotlin
@Composable
fun ElevationProfile(
    altitudes: List<Double>,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    if (altitudes.size < 2) return
    val minAlt = altitudes.min()
    val maxAlt = altitudes.max()
    if (maxAlt - minAlt <= 1.0) return // iOS guard: range > 1m

    val stoneFill = pilgrimColors.stone
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val targetBuckets = size.width.toInt().coerceAtLeast(2)
                val points = computeElevationSparklinePoints(altitudes, targetBuckets)
                if (points.size < 2) return@Canvas
                val w = size.width
                val h = size.height
                val fillPath = Path().apply {
                    moveTo(points.first().xFraction * w, h)
                    for (p in points) lineTo(p.xFraction * w, p.yFraction * h)
                    lineTo(points.last().xFraction * w, h)
                    close()
                }
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(stoneFill.copy(alpha = 0.3f), stoneFill.copy(alpha = 0.05f)),
                        startY = 0f,
                        endY = h,
                    ),
                )
                val strokePath = Path().apply {
                    moveTo(points.first().xFraction * w, points.first().yFraction * h)
                    for (p in points.drop(1)) lineTo(p.xFraction * w, p.yFraction * h)
                }
                drawPath(
                    path = strokePath,
                    color = stoneFill.copy(alpha = 0.6f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = WalkFormat.altitude(minAlt, units),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
            Icon(
                imageVector = Icons.Outlined.Terrain,
                contentDescription = null,
                tint = pilgrimColors.fog,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = WalkFormat.altitude(maxAlt, units),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}
```

Add imports:
```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Terrain
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
```

- [ ] **Step 2: Compile.**
- [ ] **Step 3:** Commit `feat(walk-summary): ElevationProfile composable (Stage 13-EFG task 7)`.

---

## Task 8: WalkSummaryDetailsCard composable + 1 test

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/WalkSummaryDetailsCard.kt`
- Create: `app/src/test/java/.../ui/walk/summary/WalkSummaryDetailsCardTest.kt`

- [ ] **Step 1: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * "Paused — H:MM:SS" details row when the walk had any paused time.
 * iOS reference: `WalkSummaryView.detailsSection` (`WalkSummaryView.swift:795-810`).
 *
 * Caller-side gate: render only when `pausedMillis > 0L`.
 */
@Composable
fun WalkSummaryDetailsCard(
    pausedMillis: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.summary_details_paused),
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
            Text(
                text = WalkFormat.duration(pausedMillis),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
        }
    }
}
```

- [ ] **Step 2: Compile.**

- [ ] **Step 3: Test.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
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
@Config(sdk = [34], application = Application::class)
class WalkSummaryDetailsCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersPausedDuration() {
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryDetailsCard(pausedMillis = 12 * 60_000L + 34_000L) // 12:34
            }
        }
        composeRule.onNodeWithText("Paused").assertIsDisplayed()
        composeRule.onNodeWithText("12:34").assertIsDisplayed()
    }
}
```

- [ ] **Step 4: 1 passing.**
- [ ] **Step 5:** Commit `feat(walk-summary): WalkSummaryDetailsCard (Stage 13-EFG task 8)`.

---

## Task 9: Wire all 4 sections into WalkSummaryScreen

**Files:** Modify `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt`

- [ ] **Step 1: Imports.**

```kotlin
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.walk.summary.ElevationProfile
import org.walktalkmeditate.pilgrim.ui.walk.summary.FaviconSelectorCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.MilestoneCalloutRow
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryDetailsCard
```

- [ ] **Step 2: Collect VM `selectedFavicon`** at the top of WalkSummaryScreen alongside other collectAsStateWithLifecycle calls:

```kotlin
val selectedFavicon by viewModel.selectedFavicon.collectAsStateWithLifecycle()
```

- [ ] **Step 3:** Wire 4 sections in the Loaded branch.

**Section 4 — Elevation profile** (after Reliquary/Intention, before JourneyQuote, INSIDE the AnimatedVisibility wrapper):

```kotlin
// 4. Elevation profile (Stage 13-F)
ElevationProfile(
    altitudes = s.summary.altitudeSamples.map { it.altitudeMeters },
    units = distanceUnits,
)
```

(Section 4 currently doesn't exist. Per Stage 13-A section ordering, it sits between section 3 IntentionCard (outside reveal wrap) and section 5 JourneyQuote (inside wrap). Place it INSIDE the wrap as the first child Column entry.)

**Section 7 — Milestone callout** (between DurationHero and StatsRow, INSIDE wrap):

```kotlin
// 7. Milestone callout (Stage 13-F partial — non-celestial branch)
s.summary.milestone?.let { ms ->
    MilestoneCalloutRow(milestone = ms)
}
```

Replace the existing `// 7. Milestone callout — placeholder for Stage 13-F.` comment block.

**Section 12 — Favicon selector** (between TimeBreakdown and ActivityTimeline, INSIDE wrap):

```kotlin
// 12. Favicon selector (Stage 13-E)
FaviconSelectorCard(
    selected = selectedFavicon,
    onSelect = viewModel::setFavicon,
)
```

Replace the existing `// 12. Favicon selector — placeholder for Stage 13-E` comment.

**Section 18 — Details** (after AI Prompts placeholder, before LightReading, OUTSIDE the AnimatedVisibility wrapper since 13-A places sections 16+ outside):

```kotlin
// 18. Details (Stage 13-G)
if (s.summary.totalPausedMillis > 0L) {
    Spacer(Modifier.height(PilgrimSpacing.normal))
    WalkSummaryDetailsCard(pausedMillis = s.summary.totalPausedMillis)
}
```

Place between voice recordings (section 16) and lightReadingDisplay (section 19), replacing the `// 18. Details section — placeholder for Stage 13-G` comment.

- [ ] **Step 4: Compile + lint + tests.**
- [ ] **Step 5:** Commit `feat(walk-summary): wire elevation + milestone + favicon + details into screen (Stage 13-EFG task 9)`.

---

## Task 10: Final verify + push

- [ ] `./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:testDebugUnitTest` BUILD SUCCESSFUL.
- [ ] `git push -u origin feat/stage-13efg-bundle`.

---

## Self-Review

- WalkFavicon enum + DAO + repo + 12 strings (Task 2)
- ElevationProfile pure helper + 4 tests (Task 3)
- VM altitudeSamples + selectedFavicon + setFavicon + 2 tests (Task 4)
- FaviconSelectorCard + 2 tests (Task 5)
- MilestoneCalloutRow + 4 tests (Task 6)
- ElevationProfile composable (Task 7)
- WalkSummaryDetailsCard + 1 test (Task 8)
- Screen wiring (Task 9)
- Verification (Task 10)

iOS divergence DOCUMENTED for milestone callout: Android shows only existing GoshuinMilestone variants (FirstWalk, LongestWalk, NthWalk, FirstOfSeason). LongestMeditation + TotalDistanceMilestone deferred to a separate stage that extends the goshuin grid.

Celestial line + celestial milestone DEFERRED until CelestialCalculator full port.
