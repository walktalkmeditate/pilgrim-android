# Stage 13-A — Walk Summary skeleton overhaul Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring Walk Summary screen layout to iOS parity — date toolbar with top-right Done, journey quote, duration hero, intention card, 3-col stats row, 3-card time breakdown — replacing the existing vertical SummaryStats list.

**Architecture:** Six new Composables under `ui/walk/summary/`, two new pure helpers under `data/walk/`, VM gains 3 new fields (`talkMillis`, `activeMillis`, `ascendMeters`). `WalkSummaryScreen` rewires section order + drops bottom Done button. iOS reference: `pilgrim-ios/Pilgrim/Scenes/WalkSummary/WalkSummaryView.swift`.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3, Hilt, Room (read-only here), JUnit 4 + Robolectric for tests. Reuses existing `WalkFormat` helpers + `pilgrimColors` + `pilgrimType` + `PilgrimSpacing` tokens.

**Spec:** `docs/superpowers/specs/2026-05-01-stage-13a-walk-summary-skeleton-design.md`

---

## Task 1: Branch off main

**Files:** none

- [ ] **Step 1: Verify clean working tree**

Run: `git status`
Expected: `On branch main / nothing to commit, working tree clean`

- [ ] **Step 2: Update main**

Run: `git fetch origin main && git checkout main && git pull --ff-only origin main`
Expected: Up to date or fast-forward.

- [ ] **Step 3: Create feature branch**

Run: `git checkout -b feat/stage-13a-walk-summary-skeleton`
Expected: `Switched to a new branch 'feat/stage-13a-walk-summary-skeleton'`

---

## Task 2: ElevationCalc pure helper + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/ElevationCalc.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/ElevationCalcTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/ElevationCalcTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

class ElevationCalcTest {
    private fun sample(t: Long, alt: Double) = AltitudeSample(
        walkId = 1L,
        timestamp = t,
        altitudeMeters = alt,
    )

    @Test fun emptySamples_returnsZero() {
        assertEquals(0.0, computeAscend(emptyList()), 0.0)
    }

    @Test fun singleSample_returnsZero() {
        assertEquals(0.0, computeAscend(listOf(sample(0L, 100.0))), 0.0)
    }

    @Test fun monotonicAscent_sumsDeltas() {
        val samples = listOf(
            sample(0L, 100.0),
            sample(1L, 110.0),
            sample(2L, 125.0),
        )
        assertEquals(25.0, computeAscend(samples), 0.0001)
    }

    @Test fun mixedDeltas_sumsOnlyPositive() {
        val samples = listOf(
            sample(0L, 100.0),
            sample(1L, 110.0),
            sample(2L, 105.0),
            sample(3L, 120.0),
        )
        assertEquals(25.0, computeAscend(samples), 0.0001)
    }

    @Test fun monotonicDescent_returnsZero() {
        val samples = listOf(
            sample(0L, 100.0),
            sample(1L, 80.0),
            sample(2L, 50.0),
        )
        assertEquals(0.0, computeAscend(samples), 0.0001)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.walk.ElevationCalcTest'`
Expected: FAIL — unresolved reference `computeAscend`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/ElevationCalc.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

/**
 * Sum of positive altitude deltas across consecutive samples. Returns
 * 0.0 for fewer than 2 samples and for purely descending routes.
 *
 * Matches iOS `walk.ascend` semantics on the Walk Summary screen,
 * where elevation = total ascent (gain), not net change. A walk that
 * climbs 100m and descends 100m back reports 100m, not 0m.
 *
 * Pure function — caller responsible for ordering samples by
 * `timestamp` if needed (Room's `getForWalk` already does this via
 * an `ORDER BY timestamp` clause on the DAO query).
 */
fun computeAscend(samples: List<AltitudeSample>): Double =
    if (samples.size < 2) 0.0
    else samples.zipWithNext().sumOf { (a, b) ->
        (b.altitudeMeters - a.altitudeMeters).coerceAtLeast(0.0)
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.data.walk.ElevationCalcTest'`
Expected: 5 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/walk/ElevationCalc.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/walk/ElevationCalcTest.kt
git commit -m "feat(walk-summary): add computeAscend helper for elevation gain (Stage 13-A task 2)"
```

---

## Task 3: JourneyQuoteCase classifier + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/JourneyQuoteCase.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/JourneyQuoteCaseTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/JourneyQuoteCaseTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyQuoteCaseTest {

    @Test fun walkAndTalkAndMeditate_returnsWalkTalkMeditate() {
        val result = classifyJourneyQuote(
            talkMillis = 60_000L,
            meditateMillis = 60_000L,
            distanceMeters = 2_000.0,
        )
        assertEquals(JourneyQuoteCase.WalkTalkMeditate, result)
    }

    @Test fun meditateOnly_underHundredMeters_returnsMeditateShort() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 60_000L,
            distanceMeters = 50.0,
        )
        assertEquals(JourneyQuoteCase.MeditateShort, result)
    }

    @Test fun meditateOnly_atHundredMetersBoundary_returnsMeditateWithDistance() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 60_000L,
            distanceMeters = 100.0,
        )
        assertTrue(result is JourneyQuoteCase.MeditateWithDistance)
        assertEquals(100.0, (result as JourneyQuoteCase.MeditateWithDistance).distanceMeters, 0.0)
    }

    @Test fun meditateOnly_overHundredMeters_returnsMeditateWithDistance_carryingMeters() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 60_000L,
            distanceMeters = 500.0,
        )
        assertTrue(result is JourneyQuoteCase.MeditateWithDistance)
        assertEquals(500.0, (result as JourneyQuoteCase.MeditateWithDistance).distanceMeters, 0.0)
    }

    @Test fun talkOnly_returnsTalkOnly() {
        val result = classifyJourneyQuote(
            talkMillis = 60_000L,
            meditateMillis = 0L,
            distanceMeters = 2_000.0,
        )
        assertEquals(JourneyQuoteCase.TalkOnly, result)
    }

    @Test fun walkOnly_overFiveKm_returnsLongRoad() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 0L,
            distanceMeters = 6_000.0,
        )
        assertEquals(JourneyQuoteCase.LongRoad, result)
    }

    @Test fun walkOnly_overOneKm_returnsSmallArrival() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 0L,
            distanceMeters = 2_000.0,
        )
        assertEquals(JourneyQuoteCase.SmallArrival, result)
    }

    @Test fun walkOnly_underOneKm_returnsQuietWalk() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 0L,
            distanceMeters = 800.0,
        )
        assertEquals(JourneyQuoteCase.QuietWalk, result)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.JourneyQuoteCaseTest'`
Expected: FAIL — unresolved reference `classifyJourneyQuote` / `JourneyQuoteCase`.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/JourneyQuoteCase.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

/**
 * Pure classification of which contextual journey quote to render on
 * Walk Summary, based on the user's mix of walking, talking, and
 * meditation. Verbatim port of iOS `WalkSummaryView.generateJourneyQuote`
 * (`WalkSummaryView.swift:396-417`).
 *
 * The composable [WalkJourneyQuote] resolves the case to a localized
 * string via `stringResource`, with `MeditateWithDistance` injecting
 * the formatted distance.
 */
internal sealed class JourneyQuoteCase {
    data object WalkTalkMeditate : JourneyQuoteCase()
    data object MeditateShort : JourneyQuoteCase()
    data class MeditateWithDistance(val distanceMeters: Double) : JourneyQuoteCase()
    data object TalkOnly : JourneyQuoteCase()
    data object LongRoad : JourneyQuoteCase()
    data object SmallArrival : JourneyQuoteCase()
    data object QuietWalk : JourneyQuoteCase()
}

internal fun classifyJourneyQuote(
    talkMillis: Long,
    meditateMillis: Long,
    distanceMeters: Double,
): JourneyQuoteCase {
    val hasTalk = talkMillis > 0L
    val hasMed = meditateMillis > 0L
    val distanceKm = distanceMeters / 1_000.0
    return when {
        hasTalk && hasMed -> JourneyQuoteCase.WalkTalkMeditate
        hasMed && distanceKm < 0.1 -> JourneyQuoteCase.MeditateShort
        hasMed -> JourneyQuoteCase.MeditateWithDistance(distanceMeters)
        hasTalk -> JourneyQuoteCase.TalkOnly
        distanceKm > 5.0 -> JourneyQuoteCase.LongRoad
        distanceKm > 1.0 -> JourneyQuoteCase.SmallArrival
        else -> JourneyQuoteCase.QuietWalk
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.JourneyQuoteCaseTest'`
Expected: 8 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/JourneyQuoteCase.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/JourneyQuoteCaseTest.kt
git commit -m "feat(walk-summary): add JourneyQuoteCase classifier (Stage 13-A task 3)"
```

---

## Task 4: Add quote strings to strings.xml + delete summary_title

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Read existing summary keys**

Run: `grep -n "summary_" app/src/main/res/values/strings.xml`
Expected: `summary_title`, `summary_action_done`, `summary_unavailable` printed with line numbers.

- [ ] **Step 2: Verify summary_title has no other consumers**

Run: `grep -rn "summary_title" app/src/main/java app/src/test`
Expected: Only matches in `WalkSummaryScreen.kt` (the spot we're about to delete in Task 12).

- [ ] **Step 3: Apply XML edits**

(Note: `summary_title` deletion is deferred to Task 12 — its consumer in `WalkSummaryScreen.kt` is removed atomically there. Don't delete it in Task 4; just add new keys.)

Add (anywhere among the existing summary keys):
```xml
<string name="summary_quote_walk_talk_meditate">You walked, spoke your mind, and found stillness.</string>
<string name="summary_quote_meditate_short_distance">A moment of stillness, right where you are.</string>
<string name="summary_quote_meditate_with_distance">A journey inward, %1$s along the way.</string>
<string name="summary_quote_talk_only">You walked and gave voice to your thoughts.</string>
<string name="summary_quote_long_road">A long road, well traveled.</string>
<string name="summary_quote_small_arrival">Every step, a small arrival.</string>
<string name="summary_quote_quiet_walk">A quiet walk, a gentle return.</string>
```

- [ ] **Step 4: Verify resources still compile**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(walk-summary): add 7 journey-quote strings, drop summary_title (Stage 13-A task 4)"
```

---

## Task 5: WalkSummaryViewModel — hoist repo calls + add 3 new fields

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`

- [ ] **Step 1: Open `buildState` in `WalkSummaryViewModel.kt`**

Locate `private suspend fun buildState(): WalkSummaryUiState`. The relevant region is lines ~525-675 (the body of buildState).

- [ ] **Step 2: Add new fields to `WalkSummary` data class**

Find the `data class WalkSummary(...)` declaration (line ~91). Add three new fields immediately after `waypointCount`:

```kotlin
val talkMillis: Long,
val activeMillis: Long,
val ascendMeters: Double,
```

- [ ] **Step 3: Hoist repo calls + compute new field values inside `buildState`**

Inside `buildState`, BEFORE the `val etegamiSpec = runCatching { ... }` block, add:

```kotlin
val voiceRecordings = repository.voiceRecordingsFor(walkId)
val altitudeSamples = repository.altitudeSamplesFor(walkId)

val talkMillis = voiceRecordings.sumOf { it.durationMillis }
val activeMillis = (totalElapsed - totals.totalPausedMillis).coerceAtLeast(0L)
val ascendMeters = computeAscend(altitudeSamples)
```

(`computeAscend` import: `import org.walktalkmeditate.pilgrim.data.walk.computeAscend`.)

Inside the `runCatching { ... }` block, REMOVE the duplicate calls:

```kotlin
// DELETE these lines (they're now hoisted):
val altitudeSamples = repository.altitudeSamplesFor(walkId)
val voiceRecordings = repository.voiceRecordingsFor(walkId)
```

The `composeEtegamiSpec` call inside the runCatching reuses the hoisted `altitudeSamples` + `voiceRecordings` locals — no further change needed since the names match.

`activityIntervals` stays inside `runCatching` (only consumed by etegami).

- [ ] **Step 4: Pass new fields when constructing `WalkSummary`**

Find `return WalkSummaryUiState.Loaded(WalkSummary(...))` near the end of `buildState`. Add three named arguments after `waypointCount = waypoints.size,` and before `routePoints = points,`:

```kotlin
talkMillis = talkMillis,
activeMillis = activeMillis,
ascendMeters = ascendMeters,
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. No new warnings tied to WalkSummary.

- [ ] **Step 6: Add VM tests for new fields**

Open `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt`. Add at the end of the class, before the closing brace:

```kotlin
@Test
fun talkMillis_sumsVoiceRecordingDurations() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertVoiceRecording(walkId, startOffset = 1_000L, durationMillis = 5_000L)
    insertVoiceRecording(walkId, startOffset = 10_000L, durationMillis = 3_000L)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(8_000L, loaded.summary.talkMillis)
}

@Test
fun ascendMeters_sumsPositiveAltitudeDeltas() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertAltitude(walkId, 1_000L, 100.0)
    insertAltitude(walkId, 2_000L, 110.0)
    insertAltitude(walkId, 3_000L, 105.0)
    insertAltitude(walkId, 4_000L, 120.0)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(25.0, loaded.summary.ascendMeters, 0.0001)
}

@Test
fun ascendMeters_zeroForFlatRoute() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertAltitude(walkId, 1_000L, 100.0)
    insertAltitude(walkId, 2_000L, 100.0)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(0.0, loaded.summary.ascendMeters, 0.0001)
}

@Test
fun activeMillis_excludesPausedTime_includesMeditation() = runTest(dispatcher) {
    val walkId = createFinishedWalk(
        durationMillis = 60_000L,
        events = listOf(
            // 10s paused
            WalkEvent(walkId = 0L, timestamp = 5_000L, type = WalkEventType.PAUSE),
            WalkEvent(walkId = 0L, timestamp = 15_000L, type = WalkEventType.RESUME),
            // 10s meditating
            WalkEvent(walkId = 0L, timestamp = 30_000L, type = WalkEventType.MEDITATION_START),
            WalkEvent(walkId = 0L, timestamp = 40_000L, type = WalkEventType.MEDITATION_STOP),
        ),
    )

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    // 60s total - 10s pause = 50s active (meditation included)
    assertEquals(50_000L, loaded.summary.activeMillis)
}
```

The helpers `createFinishedWalk`, `insertVoiceRecording`, `insertAltitude`, `newViewModel`, `awaitLoaded` are existing private helpers in this test class. If any are missing, add minimal versions that match the patterns of other tests in the file (e.g., `createFinishedWalk` already exists for milestone tests; `insertAltitude` may need to be added — it's a one-liner: `db.altitudeDao().insert(AltitudeSample(walkId = walkId, timestamp = ts, altitudeMeters = alt))`).

- [ ] **Step 7: Run VM tests**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModelTest'`
Expected: All tests passing (including 4 new ones).

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt
git commit -m "feat(walk-summary): VM exposes talkMillis + activeMillis + ascendMeters (Stage 13-A task 5)"
```

---

## Task 6: WalkSummaryTopBar composable + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryTopBar.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryTopBarTest.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryTopBar.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Walk Summary top bar. Centered date title with a trailing Done button.
 * Mirrors iOS `WalkSummaryView.toolbar` (`WalkSummaryView.swift:106-116`).
 *
 * Custom Row instead of `TopAppBar` because the screen is composed
 * inside a sheet/modal-like container, not at an Activity nav-host
 * boundary. M3 TopAppBar's Insets handling assumes the latter and
 * paints status-bar padding we don't want here.
 */
@Composable
fun WalkSummaryTopBar(
    startTimestamp: Long,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateText = remember(startTimestamp) { formatLongDate(startTimestamp) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .height(64.dp)
            .padding(horizontal = PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dateText,
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
        }
        TextButton(onClick = onDone) {
            Text(
                text = stringResource(R.string.summary_action_done),
                style = pilgrimType.button,
                color = pilgrimColors.stone,
            )
        }
    }
}

private fun formatLongDate(epochMillis: Long): String {
    val date = Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    return DateTimeFormatter
        .ofPattern("MMMM d, yyyy", Locale.getDefault())
        .format(date)
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Write the test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryTopBarTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkSummaryTopBarTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersFormattedLongDate() {
        // 2026-03-16 12:00 UTC → "March 16, 2026" in any non-east-of-UTC zone.
        // ZoneId.systemDefault on Robolectric defaults to America/Los_Angeles
        // unless overridden — the timestamp 1773_4_xx covers that.
        val ts = java.time.LocalDate.of(2026, 3, 16)
            .atTime(12, 0)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryTopBar(startTimestamp = ts, onDone = {})
            }
        }
        composeRule.onNodeWithText("March 16, 2026").assertIsDisplayed()
    }

    @Test
    fun doneButtonInvokesCallback() {
        var doneTaps = 0
        composeRule.setContent {
            PilgrimTheme {
                WalkSummaryTopBar(
                    startTimestamp = 1_700_000_000_000L,
                    onDone = { doneTaps += 1 },
                )
            }
        }
        composeRule.onNodeWithText("Done").performClick()
        assertTrue(doneTaps == 1)
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryTopBarTest'`
Expected: 2 tests passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryTopBar.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkSummaryTopBarTest.kt
git commit -m "feat(walk-summary): add WalkSummaryTopBar with date title + Done (Stage 13-A task 6)"
```

---

## Task 7: WalkIntentionCard composable + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkIntentionCard.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkIntentionCardTest.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkIntentionCard.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Renders the walk's intention text below the map.
 *
 * Caller is responsible for the `intention.isNotBlank()` guard — this
 * composable always renders. Mirrors iOS `WalkSummaryView.intentionCard`
 * (`WalkSummaryView.swift:295-312`).
 *
 * `Icons.Outlined.Spa` matches iOS `leaf` SFSymbol (the existing
 * waypoint-marker icon mapping uses `Spa` for "leaf" — see
 * `WaypointMarkingSheet.kt:80`).
 */
@Composable
fun WalkIntentionCard(
    intention: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PilgrimCornerRadius.normal))
            .background(pilgrimColors.moss.copy(alpha = 0.06f))
            .padding(PilgrimSpacing.normal),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Icon(
            imageVector = Icons.Outlined.Spa,
            contentDescription = null,
            tint = pilgrimColors.moss,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = intention,
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            textAlign = TextAlign.Center,
        )
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Write the test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkIntentionCardTest.kt`:

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
class WalkIntentionCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersIntentionText() {
        composeRule.setContent {
            PilgrimTheme {
                WalkIntentionCard(intention = "Gratitude for this body that carries me")
            }
        }
        composeRule.onNodeWithText("Gratitude for this body that carries me")
            .assertIsDisplayed()
    }
}
```

- [ ] **Step 4: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.WalkIntentionCardTest'`
Expected: 1 test passing.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkIntentionCard.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkIntentionCardTest.kt
git commit -m "feat(walk-summary): add WalkIntentionCard (Stage 13-A task 7)"
```

---

## Task 8: WalkJourneyQuote composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkJourneyQuote.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkJourneyQuote.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Contextual quote below the elevation profile (when present) and
 * above the duration hero. Six cases driven by [classifyJourneyQuote];
 * mirrors iOS `WalkSummaryView.journeyQuote` (`WalkSummaryView.swift:314-322,
 * 396-417`).
 *
 * Distance formatting goes through the user's preferred [UnitSystem] —
 * iOS does the same via `formatDistance(walk.distance)` which checks
 * `UserPreferences.distanceMeasurementType`.
 */
@Composable
fun WalkJourneyQuote(
    talkMillis: Long,
    meditateMillis: Long,
    distanceMeters: Double,
    distanceUnits: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val text = when (val case = classifyJourneyQuote(
        talkMillis = talkMillis,
        meditateMillis = meditateMillis,
        distanceMeters = distanceMeters,
    )) {
        JourneyQuoteCase.WalkTalkMeditate ->
            stringResource(R.string.summary_quote_walk_talk_meditate)
        JourneyQuoteCase.MeditateShort ->
            stringResource(R.string.summary_quote_meditate_short_distance)
        is JourneyQuoteCase.MeditateWithDistance ->
            stringResource(
                R.string.summary_quote_meditate_with_distance,
                WalkFormat.distance(case.distanceMeters, distanceUnits),
            )
        JourneyQuoteCase.TalkOnly ->
            stringResource(R.string.summary_quote_talk_only)
        JourneyQuoteCase.LongRoad ->
            stringResource(R.string.summary_quote_long_road)
        JourneyQuoteCase.SmallArrival ->
            stringResource(R.string.summary_quote_small_arrival)
        JourneyQuoteCase.QuietWalk ->
            stringResource(R.string.summary_quote_quiet_walk)
    }
    Text(
        text = text,
        style = pilgrimType.body,
        color = pilgrimColors.fog,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.big),
    )
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkJourneyQuote.kt
git commit -m "feat(walk-summary): add WalkJourneyQuote composable (Stage 13-A task 8)"
```

---

## Task 9: WalkDurationHero composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkDurationHero.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkDurationHero.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Large timer-style hero showing the walk's active duration
 * (paused-excluded, meditation-included). iOS reference:
 * `WalkSummaryView.durationHero` (`WalkSummaryView.swift:324-330`).
 *
 * Pure presentation — no animation. Stage 13-B will add the reveal
 * fade-in tied to the `revealPhase` state machine.
 */
@Composable
fun WalkDurationHero(
    durationMillis: Long,
    modifier: Modifier = Modifier,
) {
    Text(
        text = WalkFormat.duration(durationMillis),
        style = pilgrimType.timer,
        color = pilgrimColors.ink,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkDurationHero.kt
git commit -m "feat(walk-summary): add WalkDurationHero (Stage 13-A task 9)"
```

---

## Task 10: WalkStatsRow composable + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkStatsRow.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkStatsRowTest.kt`

- [ ] **Step 1: Write the composable**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkStatsRow.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * 1- to 2-column mini-stats below the duration hero.
 *
 * Steps deferred to a future stage (Walk entity does not yet carry a
 * step counter column). Until then, the row shows Distance always +
 * Elevation when ascend > 1m. iOS reference:
 * `WalkSummaryView.statsRow` (`WalkSummaryView.swift:463-488`).
 */
@Composable
fun WalkStatsRow(
    distanceMeters: Double,
    ascendMeters: Double,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
    ) {
        miniStat(
            label = stringResource(R.string.summary_stat_distance),
            value = WalkFormat.distance(distanceMeters, units),
        )
        if (ascendMeters > 1.0) {
            miniStat(
                label = stringResource(R.string.summary_stat_elevation),
                value = WalkFormat.altitude(ascendMeters, units),
            )
        }
    }
}

@Composable
private fun RowScope.miniStat(label: String, value: String) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}
```

- [ ] **Step 2: Add stat-label strings**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="summary_stat_distance">Distance</string>
<string name="summary_stat_elevation">Elevation</string>
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Write the test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkStatsRowTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkStatsRowTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersDistanceAlone_whenNoElevation() {
        composeRule.setContent {
            PilgrimTheme {
                WalkStatsRow(
                    distanceMeters = 1_500.0,
                    ascendMeters = 0.0,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onAllNodesWithText("Elevation").assertCountEquals(0)
    }

    @Test
    fun rendersDistanceAndElevation_whenAscendOverThreshold() {
        composeRule.setContent {
            PilgrimTheme {
                WalkStatsRow(
                    distanceMeters = 5_000.0,
                    ascendMeters = 100.0,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onNodeWithText("Distance").assertIsDisplayed()
        composeRule.onNodeWithText("Elevation").assertIsDisplayed()
        composeRule.onNodeWithText("100 m").assertIsDisplayed()
    }

    @Test
    fun ascendUnderOneMeter_hidesElevation() {
        composeRule.setContent {
            PilgrimTheme {
                WalkStatsRow(
                    distanceMeters = 5_000.0,
                    ascendMeters = 0.5,
                    units = UnitSystem.Metric,
                )
            }
        }
        composeRule.onAllNodesWithText("Elevation").assertCountEquals(0)
    }
}
```

- [ ] **Step 5: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.WalkStatsRowTest'`
Expected: 3 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkStatsRow.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkStatsRowTest.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(walk-summary): add WalkStatsRow (Stage 13-A task 10)"
```

---

## Task 11: WalkTimeBreakdownGrid composable + tests

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkTimeBreakdownGrid.kt`
- Create: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkTimeBreakdownGridTest.kt`

- [ ] **Step 1: Add stat-label strings**

In `app/src/main/res/values/strings.xml`, add:

```xml
<string name="summary_breakdown_walk">Walk</string>
<string name="summary_breakdown_talk">Talk</string>
<string name="summary_breakdown_meditate">Meditate</string>
```

- [ ] **Step 2: Write the composable**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkTimeBreakdownGrid.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * 3-card breakdown of how time was spent during the walk: walking,
 * talking (voice recordings), meditating. Always renders all three
 * cards even at zero duration. iOS reference:
 * `WalkSummaryView.timeBreakdown` (`WalkSummaryView.swift:535-548`).
 */
@Composable
fun WalkTimeBreakdownGrid(
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        breakdownCard(
            icon = Icons.Rounded.DirectionsWalk,
            label = stringResource(R.string.summary_breakdown_walk),
            millis = walkMillis,
        )
        breakdownCard(
            icon = Icons.Rounded.GraphicEq,
            label = stringResource(R.string.summary_breakdown_talk),
            millis = talkMillis,
        )
        breakdownCard(
            icon = Icons.Rounded.SelfImprovement,
            label = stringResource(R.string.summary_breakdown_meditate),
            millis = meditateMillis,
        )
    }
}

@Composable
private fun RowScope.breakdownCard(
    icon: ImageVector,
    label: String,
    millis: Long,
) {
    Card(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = WalkFormat.duration(millis),
                style = pilgrimType.statValue,
                color = pilgrimColors.ink,
                maxLines = 1,
            )
            Text(
                text = label,
                style = pilgrimType.statLabel,
                color = pilgrimColors.fog,
                maxLines = 1,
            )
        }
    }
}
```

- [ ] **Step 3: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Write the test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkTimeBreakdownGridTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkTimeBreakdownGridTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersAllThreeCards_evenWhenZero() {
        composeRule.setContent {
            PilgrimTheme {
                WalkTimeBreakdownGrid(
                    walkMillis = 0L,
                    talkMillis = 0L,
                    meditateMillis = 0L,
                )
            }
        }
        composeRule.onNodeWithText("Walk").assertIsDisplayed()
        composeRule.onNodeWithText("Talk").assertIsDisplayed()
        composeRule.onNodeWithText("Meditate").assertIsDisplayed()
        composeRule.onAllNodesWithText("0:00").assertCountEquals(3)
    }

    @Test
    fun durationsFormatCorrectly() {
        composeRule.setContent {
            PilgrimTheme {
                WalkTimeBreakdownGrid(
                    walkMillis = 2 * 3_600_000L + 50 * 60_000L, // 2:50:00
                    talkMillis = 22 * 60_000L,                  // 22:00
                    meditateMillis = 25 * 60_000L,              // 25:00
                )
            }
        }
        composeRule.onNodeWithText("2:50:00").assertIsDisplayed()
        composeRule.onNodeWithText("22:00").assertIsDisplayed()
        composeRule.onNodeWithText("25:00").assertIsDisplayed()
    }
}
```

- [ ] **Step 5: Run test**

Run: `./gradlew :app:testDebugUnitTest --tests 'org.walktalkmeditate.pilgrim.ui.walk.summary.WalkTimeBreakdownGridTest'`
Expected: 2 tests passing.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkTimeBreakdownGrid.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/summary/WalkTimeBreakdownGridTest.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat(walk-summary): add WalkTimeBreakdownGrid (Stage 13-A task 11)"
```

---

## Task 12: WalkSummaryScreen rewrite — wire all sections in iOS order

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`

- [ ] **Step 1: Replace the title block**

In `WalkSummaryScreen.kt`, find the existing top of the scrollable Column:

```kotlin
Text(
    text = stringResource(R.string.summary_title),
    style = pilgrimType.displayMedium,
    color = pilgrimColors.ink,
)
Spacer(Modifier.height(PilgrimSpacing.big))
```

This `Text(...)` is INSIDE the scrolling Column. Move the top bar OUT of the scroll (top bars don't scroll in iOS). Refactor the outer `Box(Modifier.fillMaxSize())` body to:

```kotlin
Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar — ALWAYS visible, doesn't scroll. For Loading + NotFound
        // states the timestamp is unavailable; show an empty bar with just
        // the Done button by passing 0L (which renders as the Unix epoch
        // date). Acceptable for a momentary loading state. NotFound users
        // see whatever state-specific message we render below.
        val titleTimestamp = (state as? WalkSummaryUiState.Loaded)
            ?.summary?.walk?.startTimestamp
            ?: 0L
        WalkSummaryTopBar(
            startTimestamp = titleTimestamp,
            onDone = onDone,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.big),
        ) {
            // ... existing when (state) { ... } block ...
        }
    }
    // SealRevealOverlay + SnackbarHost stay where they were.
}
```

Delete the inner `Text(stringResource(R.string.summary_title), ...)` + the immediately-following Spacer.

- [ ] **Step 2: Reorder + insert new sections inside the Loaded branch**

Inside `is WalkSummaryUiState.Loaded -> { ... }`, replace the body with this exact section ordering. Each section has a `Spacer(Modifier.height(PilgrimSpacing.normal))` separating it from the previous (16dp, matches iOS).

```kotlin
// 1. Map (existing) — height stays 200dp until Stage 13-B
SummaryMap(points = s.summary.routePoints)
Spacer(Modifier.height(PilgrimSpacing.normal))

// 2. Photo Reliquary (existing)
PhotoReliquarySection(
    photos = pinnedPhotos,
    onPinPhotos = viewModel::pinPhotos,
    onUnpinPhoto = viewModel::unpinPhoto,
)

// 3. Intention card (NEW — guarded)
val intention = s.summary.walk.intention
if (!intention.isNullOrBlank()) {
    Spacer(Modifier.height(PilgrimSpacing.normal))
    WalkIntentionCard(intention = intention)
}

// 4. Elevation profile — placeholder for Stage 13-F
// (no render in 13-A)

// 5. Journey quote (NEW)
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkJourneyQuote(
    talkMillis = s.summary.talkMillis,
    meditateMillis = s.summary.totalMeditatedMillis,
    distanceMeters = s.summary.distanceMeters,
    distanceUnits = distanceUnits,
)

// 6. Duration hero (NEW)
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkDurationHero(durationMillis = s.summary.activeMillis)

// 7. Milestone text callout — placeholder for Stage 13-F
// (no render in 13-A; the existing reveal-overlay halo remains)

// 8. Stats row (NEW)
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkStatsRow(
    distanceMeters = s.summary.distanceMeters,
    ascendMeters = s.summary.ascendMeters,
    units = distanceUnits,
)

// 9. Weather line (existing, Stage 12)
s.summary.walk.weatherCondition?.let { conditionRaw ->
    val condition = WeatherCondition.fromRawValue(conditionRaw) ?: return@let
    val temperature = s.summary.walk.weatherTemperature ?: return@let
    Spacer(Modifier.height(PilgrimSpacing.normal))
    WalkSummaryWeatherLine(
        condition = condition,
        temperatureCelsius = temperature,
        imperial = distanceUnits == UnitSystem.Imperial,
    )
}

// 10. Celestial line — placeholder for Stage 13-F

// 11. Time breakdown grid (NEW)
Spacer(Modifier.height(PilgrimSpacing.normal))
WalkTimeBreakdownGrid(
    walkMillis = s.summary.activeWalkingMillis,
    talkMillis = s.summary.talkMillis,
    meditateMillis = s.summary.totalMeditatedMillis,
)

// 12. Favicon selector — placeholder for Stage 13-E
// 13-15. Activity timeline + insights + list — placeholders for Stage 13-C

// 16. Voice recordings (existing, Stage 2-E)
if (recordings.isNotEmpty()) {
    Spacer(Modifier.height(PilgrimSpacing.normal))
    VoiceRecordingsSection(
        walkStartTimestamp = s.summary.walk.startTimestamp,
        recordings = recordings,
        playbackUiState = playbackUiState,
        onPlay = viewModel::playRecording,
        onPause = viewModel::pausePlayback,
    )
}

// 17. AI Prompts button — placeholder for Stage 13-X
// 18. Details section — placeholder for Stage 13-G

// 19. Light Reading card (existing, Stage 6-B)
lightReadingDisplay?.let { reading ->
    Spacer(Modifier.height(PilgrimSpacing.normal))
    WalkLightReadingCard(reading = reading)
}

// 20. Etegami + Share Journey (existing, Stage 7-D + 8-A)
if (s.summary.routePoints.size >= 2) {
    s.summary.etegamiSpec?.let { etegami ->
        Spacer(Modifier.height(PilgrimSpacing.normal))
        WalkEtegamiCard(spec = etegami)
        WalkEtegamiShareRow(
            busyAction = etegamiBusy,
            onShare = { viewModel.shareEtegami(etegami) },
            onSave = { viewModel.saveEtegamiToGallery(etegami) },
            onSavePermissionDenied = {
                viewModel.notifyEtegamiSaveNeedsPermission()
            },
        )
    }
    val rowState = cachedShare.toJourneyRowState()
    Spacer(Modifier.height(PilgrimSpacing.normal))
    org.walktalkmeditate.pilgrim.ui.walk.share.WalkShareJourneyRow(
        state = rowState,
        onShareJourney = onShareJourney,
        onReshare = onShareJourney,
        onReopenModal = onShareJourney,
        onCopyUrl = { url ->
            org.walktalkmeditate.pilgrim.ui.walk.share.copyUrl(
                context, url, msgCopied,
            )
        },
        onShareUrl = { url ->
            org.walktalkmeditate.pilgrim.ui.walk.share.launchShareChooser(
                activity ?: context, url, msgChooserTitle,
            )
        },
    )
}
```

- [ ] **Step 3: Delete the bottom Done button + dropped composables**

Delete from the END of the column (the `Spacer(Modifier.height(PilgrimSpacing.breathingRoom))` + `Button(onClick = onDone) { Text("Done") }` block). Replace with just:

```kotlin
Spacer(Modifier.height(PilgrimSpacing.breathingRoom))
```

Delete the private composables `SummaryStats` + `SummaryRow` (no longer called).

- [ ] **Step 4: Add imports**

Add to imports of `WalkSummaryScreen.kt`:

```kotlin
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkSummaryTopBar
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkIntentionCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkJourneyQuote
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkDurationHero
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkStatsRow
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkTimeBreakdownGrid
```

- [ ] **Step 5: Compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. Address any unresolved references (likely missed imports for `pilgrimType.displayMedium` if it's no longer used — remove the import if so).

- [ ] **Step 6: Run lint + unit tests**

Run: `./gradlew :app:lintDebug :app:testDebugUnitTest`
Expected: No new lint findings. All tests passing.

- [ ] **Step 7: Run any existing WalkSummaryScreenTest**

Run: `./gradlew :app:testDebugUnitTest --tests '*WalkSummaryScreen*'`
Expected: All tests passing. If a test was asserting on `R.string.summary_title` or the bottom Done button, update to the new layout.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt
git commit -m "feat(walk-summary): rewire screen layout to iOS section order (Stage 13-A task 12)"
```

---

## Task 13: Final verification

**Files:** none

- [ ] **Step 1: Full build + lint + tests**

Run: `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. No new warnings or test failures.

- [ ] **Step 2: Release-build smoke test**

Run: `./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL. R8 / ProGuard accepts the new types.

- [ ] **Step 3: Push branch**

Run: `git push -u origin feat/stage-13a-walk-summary-skeleton`
Expected: Branch tracks remote.

---

## Self-Review Notes

**Spec coverage check:**
- ✅ WalkSummaryTopBar (Task 6)
- ✅ WalkIntentionCard (Task 7)
- ✅ WalkJourneyQuote + classifier (Tasks 3 + 8)
- ✅ WalkDurationHero (Task 9)
- ✅ WalkStatsRow (Task 10)
- ✅ WalkTimeBreakdownGrid (Task 11)
- ✅ ElevationCalc + computeAscend (Task 2)
- ✅ VM `talkMillis` + `activeMillis` + `ascendMeters` + hoisting (Task 5)
- ✅ Strings (`summary_title` deletion, 7 quotes, 2 stat labels, 3 breakdown labels) (Tasks 4 + 10 + 11)
- ✅ WalkSummaryScreen rewrite + section ordering + bottom-Done deletion (Task 12)
- ✅ JourneyQuoteCaseTest, ElevationCalcTest, VM 4 new tests, 8 Robolectric Compose tests

**No `AltitudeUnit` enum + no custom drawable XMLs** — matches updated spec.

**Hoisting is task 5 step 3** — `voiceRecordingsFor` + `altitudeSamplesFor` move OUT of etegami `runCatching`; `activityIntervals` stays inside.

**Type consistency:** every new type is named consistently across tasks (`JourneyQuoteCase`, `WalkSummaryTopBar`, `computeAscend`). `talkMillis` / `activeMillis` / `ascendMeters` field names match across spec, plan, tests.
