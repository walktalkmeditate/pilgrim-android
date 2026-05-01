# Stage 13-C — Activity views Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fill the iOS sections 13/14/15 placeholders on Walk Summary with timeline bar (colored segments + tooltip + time labels + pace sparkline + legend), insights card, and activity list.

**Architecture:** 5 new composable/helper files + 4 new test files. VM exposes `voiceRecordings`, `meditationIntervals`, `routeSamples` on `WalkSummary`. Screen wires three new sections inside the existing 13-B `AnimatedVisibility` reveal wrapper.

**Tech Stack:** Kotlin 2.x, Jetpack Compose Material 3 (`Canvas`, `Path`, `pointerInput`, `Brush.linearGradient`), JUnit 4 + Robolectric. Reuses existing `pilgrimColors`, `WalkFormat`, `RouteSegment`-style pure-function pattern.

**Spec:** `docs/superpowers/specs/2026-05-01-stage-13c-activity-views-design.md`

---

## Task 1: Branch off main

- [ ] **Step 1:** Verify clean tree on main → `git status && git branch --show-current`.
- [ ] **Step 2:** `git fetch origin main && git pull --ff-only origin main`.
- [ ] **Step 3:** `git checkout -b feat/stage-13c-activity-views`.
- [ ] **Step 4:** Commit spec + plan:
```bash
git add docs/superpowers/specs/2026-05-01-stage-13c-activity-views-design.md \
        docs/superpowers/plans/2026-05-01-stage-13c-activity-views.md
git commit -m "docs(walk-summary): Stage 13-C spec + plan (activity views)"
```

---

## Task 2: TimelineSegments classifier + tests

**Files:** `app/src/main/java/.../ui/walk/summary/TimelineSegments.kt`, `app/src/test/java/.../ui/walk/summary/TimelineSegmentsTest.kt`

- [ ] **Step 1: Test first.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

class TimelineSegmentsTest {
    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L, startTimestamp = start, endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )
    private fun recording(start: Long, dur: Long) = VoiceRecording(
        walkId = 1L, startTimestamp = start, endTimestamp = start + dur,
        durationMillis = dur, fileRelativePath = "x.wav", transcription = null,
    )

    @Test fun emptyInputs_returnsEmpty() {
        val segs = computeTimelineSegments(0L, 1000L, emptyList(), emptyList())
        assertTrue(segs.isEmpty())
    }

    @Test fun singleTalk_yieldsOneTalkSegment_atCorrectFraction() {
        // walk 0..1000 ms, talk 200..400 ms → fraction 0.2..0.4 (width 0.2)
        val segs = computeTimelineSegments(
            startMs = 0L, endMs = 1000L,
            meditations = emptyList(),
            recordings = listOf(recording(start = 200L, dur = 200L)),
        )
        assertEquals(1, segs.size)
        assertEquals(TimelineSegmentType.Talking, segs[0].type)
        assertEquals(0.2f, segs[0].startFraction, 0.0001f)
        assertEquals(0.2f, segs[0].widthFraction, 0.0001f)
    }

    @Test fun singleMeditation_yieldsOneMeditationSegment() {
        val segs = computeTimelineSegments(
            startMs = 0L, endMs = 2000L,
            meditations = listOf(meditation(start = 500L, end = 1500L)),
            recordings = emptyList(),
        )
        assertEquals(1, segs.size)
        assertEquals(TimelineSegmentType.Meditating, segs[0].type)
        assertEquals(0.25f, segs[0].startFraction, 0.0001f)
        assertEquals(0.5f, segs[0].widthFraction, 0.0001f)
    }

    @Test fun bothInOneWalk_returnsSortedByStart() {
        val segs = computeTimelineSegments(
            startMs = 0L, endMs = 1000L,
            meditations = listOf(meditation(start = 600L, end = 800L)),
            recordings = listOf(recording(start = 100L, dur = 200L)),
        )
        assertEquals(2, segs.size)
        assertEquals(TimelineSegmentType.Talking, segs[0].type)
        assertEquals(TimelineSegmentType.Meditating, segs[1].type)
    }

    @Test fun boundaryFractions_clampedZeroToOne() {
        // Interval starts before walkStart, ends after walkEnd → start=0, end=1
        val segs = computeTimelineSegments(
            startMs = 1000L, endMs = 2000L,
            meditations = listOf(meditation(start = 500L, end = 2500L)),
            recordings = emptyList(),
        )
        assertEquals(1, segs.size)
        assertEquals(0f, segs[0].startFraction, 0.0001f)
        assertEquals(1f, segs[0].widthFraction, 0.0001f)
    }

    @Test fun nonMeditationActivityType_excluded() {
        val intervals = listOf(
            ActivityInterval(walkId = 1L, startTimestamp = 100L, endTimestamp = 200L,
                activityType = ActivityType.WALKING),
        )
        val segs = computeTimelineSegments(0L, 1000L, intervals, emptyList())
        assertTrue(segs.isEmpty())
    }
}
```

- [ ] **Step 2:** Run test — expect FAIL.
- [ ] **Step 3: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType

internal enum class TimelineSegmentType { Talking, Meditating }

/**
 * Pure data for one segment on the activity timeline bar. iOS-faithful
 * port of `ActivityTimelineBar.Segment` (`ActivityTimelineBar.swift:195-262`).
 *
 * `startFraction` and `widthFraction` are clamped to `[0, 1]` so segments
 * that started before the walk OR ended after it lay out within the bar's
 * visible bounds.
 */
@Immutable
internal data class TimelineSegment(
    val id: Int,
    val type: TimelineSegmentType,
    val startFraction: Float,
    val widthFraction: Float,
    val startMillis: Long,
    val endMillis: Long,
)

/**
 * Build the timeline-bar segments. Meditations + voice recordings combined,
 * sorted by start timestamp. Out-of-range intervals get clamped to the bar's
 * span. Pure function — no Compose dependency.
 */
internal fun computeTimelineSegments(
    startMs: Long,
    endMs: Long,
    meditations: List<ActivityInterval>,
    recordings: List<VoiceRecording>,
): List<TimelineSegment> {
    val totalMs = (endMs - startMs).coerceAtLeast(1L).toFloat()
    val out = mutableListOf<TimelineSegment>()
    var nextId = 0

    for (m in meditations) {
        if (m.activityType != ActivityType.MEDITATING) continue
        val (sf, wf) = clampFraction(m.startTimestamp, m.endTimestamp, startMs, totalMs)
        if (wf <= 0f) continue
        out += TimelineSegment(
            id = nextId++,
            type = TimelineSegmentType.Meditating,
            startFraction = sf,
            widthFraction = wf,
            startMillis = m.startTimestamp,
            endMillis = m.endTimestamp,
        )
    }
    for (r in recordings) {
        val (sf, wf) = clampFraction(r.startTimestamp, r.endTimestamp, startMs, totalMs)
        if (wf <= 0f) continue
        out += TimelineSegment(
            id = nextId++,
            type = TimelineSegmentType.Talking,
            startFraction = sf,
            widthFraction = wf,
            startMillis = r.startTimestamp,
            endMillis = r.endTimestamp,
        )
    }

    return out.sortedBy { it.startFraction }
}

private fun clampFraction(
    startTs: Long,
    endTs: Long,
    walkStartMs: Long,
    totalMs: Float,
): Pair<Float, Float> {
    val rawStart = (startTs - walkStartMs) / totalMs
    val rawEnd = (endTs - walkStartMs) / totalMs
    val sf = rawStart.coerceIn(0f, 1f)
    val ef = rawEnd.coerceIn(0f, 1f)
    val wf = (ef - sf).coerceAtLeast(0f)
    return sf to wf
}
```

- [ ] **Step 4:** Re-run tests — expect 6 passing.
- [ ] **Step 5:** Commit `feat(walk-summary): TimelineSegments classifier (Stage 13-C task 2)`.

---

## Task 3: PaceSparkline pure helper + tests

**Files:** `app/src/main/java/.../ui/walk/summary/PaceSparkline.kt`, `app/src/test/java/.../ui/walk/summary/PaceSparklineTest.kt`

- [ ] **Step 1: Test first.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

class PaceSparklineTest {
    private fun sample(t: Long, speed: Float?): RouteDataSample = RouteDataSample(
        walkId = 1L, timestamp = t, latitude = 0.0, longitude = 0.0,
        altitudeMeters = 0.0, speedMetersPerSecond = speed,
    )

    @Test fun emptySamples_returnsEmpty() {
        assertTrue(computePaceSparklinePoints(emptyList(), 0L, 1000L).isEmpty())
    }

    @Test fun underThreeSamples_returnsEmpty() {
        val samples = listOf(sample(100L, 1.5f), sample(200L, 1.5f))
        assertTrue(computePaceSparklinePoints(samples, 0L, 1000L).isEmpty())
    }

    @Test fun monotonicAcceleration_yieldsAscendingY() {
        // Higher speed at later timestamp → lower y (top of frame is fastest).
        // y = 1f - (avgSpeed / maxSpeed) * 0.85f → speed=max gives y=0.15.
        val samples = listOf(
            sample(100L, 1.0f),
            sample(200L, 2.0f),
            sample(300L, 3.0f),
        )
        val points = computePaceSparklinePoints(samples, 0L, 400L)
        assertEquals(3, points.size)
        // Last point (highest speed) has lowest y.
        assertTrue(points.last().yFraction < points.first().yFraction)
    }

    @Test fun mixedSpeeds_filtersBelowThreshold() {
        // Speeds at or below 0.3 m/s drop out.
        val samples = listOf(
            sample(100L, 0.2f),
            sample(150L, 1.0f),
            sample(200L, 1.5f),
            sample(250L, 2.0f),
            sample(300L, 0.1f),
        )
        val points = computePaceSparklinePoints(samples, 0L, 400L)
        // Filtered list has 3 above-threshold samples → 3 points.
        assertEquals(3, points.size)
    }

    @Test fun maxSpeedZero_returnsEmpty() {
        // All below threshold → filtered list empty → guard returns empty.
        val samples = listOf(
            sample(100L, 0.1f),
            sample(200L, 0.2f),
            sample(300L, 0.0f),
        )
        assertTrue(computePaceSparklinePoints(samples, 0L, 400L).isEmpty())
    }
}
```

- [ ] **Step 2:** Run test — expect FAIL.
- [ ] **Step 3: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.runtime.Immutable
import kotlin.math.max
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample

@Immutable
internal data class PaceSparklinePoint(
    val xFraction: Float,
    val yFraction: Float,
)

/**
 * Pure port of iOS `PaceSparklineView.sparklinePoints`
 * (`PaceSparklineView.swift:53-80`). Filters route samples by speed
 * threshold (0.3 m/s — drops standing or near-stop noise), buckets up to 50
 * windows, computes a normalized fraction in `[0, 1]` for x (timestamp
 * within the walk's span) and y (1 minus 0.85× normalized speed; iOS
 * leaves 15% top headroom).
 *
 * Returns empty when fewer than 3 above-threshold samples exist OR when
 * the max bucketed speed is zero.
 */
internal fun computePaceSparklinePoints(
    samples: List<RouteDataSample>,
    walkStartMs: Long,
    walkEndMs: Long,
): List<PaceSparklinePoint> {
    val filtered = samples
        .filter { (it.speedMetersPerSecond ?: 0f) > SPEED_THRESHOLD_MPS }
        .sortedBy { it.timestamp }
    if (filtered.size < 3) return emptyList()

    val totalMs = max(1f, (walkEndMs - walkStartMs).toFloat())
    val step = max(1, filtered.size / TARGET_BUCKETS)

    data class Bucket(val xFraction: Float, val avgSpeed: Float)
    val buckets = mutableListOf<Bucket>()
    var i = 0
    while (i < filtered.size) {
        val end = (i + step).coerceAtMost(filtered.size)
        val window = filtered.subList(i, end)
        val avg = window.sumOf { (it.speedMetersPerSecond ?: 0f).toDouble() }.toFloat() / window.size
        val mid = window[window.size / 2]
        val frac = ((mid.timestamp - walkStartMs) / totalMs).coerceIn(0f, 1f)
        buckets += Bucket(frac, avg)
        i += step
    }

    val maxSpeed = buckets.maxOfOrNull { it.avgSpeed } ?: 0f
    if (maxSpeed <= 0f) return emptyList()

    return buckets.map { b ->
        PaceSparklinePoint(
            xFraction = b.xFraction,
            yFraction = 1f - (b.avgSpeed / maxSpeed) * Y_FILL_FRACTION,
        )
    }
}

private const val SPEED_THRESHOLD_MPS = 0.3f
private const val TARGET_BUCKETS = 50
private const val Y_FILL_FRACTION = 0.85f
```

- [ ] **Step 4:** Re-run — expect 5 passing.
- [ ] **Step 5:** Commit `feat(walk-summary): PaceSparkline pure helper (Stage 13-C task 3)`.

---

## Task 4: Strings + VM additions + VM tests

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/java/.../ui/walk/WalkSummaryViewModel.kt`
- Modify: `app/src/test/java/.../ui/walk/WalkSummaryViewModelTest.kt`

- [ ] **Step 1:** Add strings per spec's "Strings to add" section.

- [ ] **Step 2:** Add three new fields to `WalkSummary`:

```kotlin
val voiceRecordings: List<VoiceRecording> = emptyList(),
val meditationIntervals: List<ActivityInterval> = emptyList(),
val routeSamples: List<RouteDataSample> = emptyList(),
```

Add imports as needed (entity types are already used elsewhere in this file via qualified names).

- [ ] **Step 3:** In `buildState()`, populate the new fields when constructing `WalkSummary`. Use the already-hoisted `voiceRecordings`, `activityIntervals`, and `samples` locals from earlier in the function. Filter `activityIntervals` for the meditation type:

```kotlin
WalkSummary(
    // existing fields…
    voiceRecordings = voiceRecordings,
    meditationIntervals = activityIntervals.filter { it.activityType == ActivityType.MEDITATING },
    routeSamples = samples,
)
```

- [ ] **Step 4:** Compile — `./gradlew :app:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 5:** Add 2 VM tests:

```kotlin
@Test
fun voiceRecordings_populatedFromRepo() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertVoiceRecording(walkId, startOffset = 1_000L, durationMillis = 5_000L)
    insertVoiceRecording(walkId, startOffset = 10_000L, durationMillis = 3_000L)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(2, loaded.summary.voiceRecordings.size)
}

@Test
fun meditationIntervals_filtered_excludesNonMeditationTypes() = runTest(dispatcher) {
    val walkId = createFinishedWalk(durationMillis = 60_000L)
    insertActivityInterval(walkId, startTimestamp = 5_000L, endTimestamp = 15_000L,
        type = ActivityType.MEDITATING)
    insertActivityInterval(walkId, startTimestamp = 20_000L, endTimestamp = 30_000L,
        type = ActivityType.WALKING)

    val vm = newViewModel(walkId)
    val loaded = awaitLoaded(vm)

    assertEquals(1, loaded.summary.meditationIntervals.size)
    assertEquals(ActivityType.MEDITATING, loaded.summary.meditationIntervals[0].activityType)
}
```

- [ ] **Step 6:** Run tests — expect all passing.
- [ ] **Step 7:** Commit `feat(walk-summary): VM exposes voiceRecordings + meditationIntervals + routeSamples (Stage 13-C task 4)`.

---

## Task 5: WalkActivityInsightsCard composable + tests

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/WalkActivityInsightsCard.kt`
- Create: `app/src/test/java/.../ui/walk/summary/WalkActivityInsightsCardTest.kt`

- [ ] **Step 1: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * "Insights" card on Walk Summary. Two optional text rows (meditation
 * count/duration, talk percentage). iOS reference:
 * `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityInsightsView.swift`.
 *
 * Caller is responsible for the empty-state guard — this composable
 * always renders at least the header when invoked.
 */
@Composable
fun WalkActivityInsightsCard(
    talkMillis: Long,
    activeMillis: Long,
    meditationIntervals: List<ActivityInterval>,
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
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(PilgrimSpacing.xs))
                Text(
                    text = stringResource(R.string.summary_insights_header),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
            }
            meditationInsightText(meditationIntervals)?.let { msg ->
                Text(text = msg, style = pilgrimType.body, color = pilgrimColors.fog)
            }
            talkInsightText(talkMillis, activeMillis)?.let { msg ->
                Text(text = msg, style = pilgrimType.body, color = pilgrimColors.fog)
            }
        }
    }
}

@Composable
private fun meditationInsightText(intervals: List<ActivityInterval>): String? {
    if (intervals.isEmpty()) return null
    val longestMs = intervals.maxOf { it.endTimestamp - it.startTimestamp }
    val longestFmt = formatCompactDuration(longestMs)
    return if (intervals.size == 1) {
        stringResource(R.string.summary_insight_meditated_once, longestFmt)
    } else {
        stringResource(R.string.summary_insight_meditated_multiple, intervals.size, longestFmt)
    }
}

@Composable
private fun talkInsightText(talkMillis: Long, activeMillis: Long): String? {
    if (talkMillis <= 0L || activeMillis <= 0L) return null
    val pct = ((talkMillis * 100) / activeMillis).toInt()
    return stringResource(R.string.summary_insight_talked_pct, pct)
}

@Composable
private fun formatCompactDuration(millis: Long): String {
    val totalSeconds = (millis / 1_000L).coerceAtLeast(0L).toInt()
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    return when {
        totalSeconds < 60 ->
            stringResource(R.string.summary_compact_duration_seconds, totalSeconds)
        seconds == 0 ->
            stringResource(R.string.summary_compact_duration_minutes, minutes)
        else ->
            stringResource(R.string.summary_compact_duration_minutes_seconds, minutes, seconds)
    }
}
```

(Note: `Locale.US` import may be unused — drop if so before commit.)

- [ ] **Step 2:** Compile.

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
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActivityInsightsCardTest {

    @get:Rule val composeRule = createComposeRule()

    private fun meditation(start: Long, end: Long) = ActivityInterval(
        walkId = 1L, startTimestamp = start, endTimestamp = end,
        activityType = ActivityType.MEDITATING,
    )

    @Test
    fun rendersMeditationOnce_withSingularPhrase() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityInsightsCard(
                    talkMillis = 0L,
                    activeMillis = 60_000L,
                    meditationIntervals = listOf(meditation(0L, 25 * 60_000L)),
                )
            }
        }
        composeRule.onNodeWithText("Meditated once for 25 min").assertIsDisplayed()
    }

    @Test
    fun rendersMeditationMultiple_withCountAndLongest() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityInsightsCard(
                    talkMillis = 0L,
                    activeMillis = 60_000L,
                    meditationIntervals = listOf(
                        meditation(0L, 5 * 60_000L),
                        meditation(10 * 60_000L, 30 * 60_000L), // 20 min — longest
                    ),
                )
            }
        }
        composeRule.onNodeWithText("Meditated 2 times (longest: 20 min)").assertIsDisplayed()
    }

    @Test
    fun rendersTalkPercentage() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityInsightsCard(
                    talkMillis = 11 * 60_000L, // 11 min
                    activeMillis = 100 * 60_000L, // 100 min → 11%
                    meditationIntervals = emptyList(),
                )
            }
        }
        composeRule.onNodeWithText("Talked for 11% of the walk").assertIsDisplayed()
    }
}
```

- [ ] **Step 4:** Run tests — expect 3 passing.
- [ ] **Step 5:** Commit `feat(walk-summary): WalkActivityInsightsCard (Stage 13-C task 5)`.

---

## Task 6: WalkActivityListCard composable + tests

**Files:**
- Create: `app/src/main/java/.../ui/walk/summary/WalkActivityListCard.kt`
- Create: `app/src/test/java/.../ui/walk/summary/WalkActivityListCardTest.kt`

- [ ] **Step 1: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * "Activities" card listing every voice recording + meditation interval
 * sorted by start timestamp. iOS reference:
 * `pilgrim-ios/Pilgrim/Scenes/WalkSummary/ActivityListView.swift`.
 *
 * Caller-side empty-state guard.
 */
@Composable
fun WalkActivityListCard(
    voiceRecordings: List<VoiceRecording>,
    meditationIntervals: List<ActivityInterval>,
    modifier: Modifier = Modifier,
) {
    val entries = remember(voiceRecordings, meditationIntervals) {
        buildEntries(voiceRecordings, meditationIntervals)
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.FormatListBulleted,
                    contentDescription = null,
                    tint = pilgrimColors.stone,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(PilgrimSpacing.xs))
                Text(
                    text = stringResource(R.string.summary_activities_header),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                )
            }
            entries.forEach { entry -> ActivityRow(entry) }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityListEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PilgrimSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(entry.tint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(PilgrimSpacing.small))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(entry.nameRes),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
            )
            Text(
                text = entry.timeRange,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Text(
            text = WalkFormat.duration(entry.durationMillis),
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}

private data class ActivityListEntry(
    val icon: ImageVector,
    val tint: Color,
    val nameRes: Int,
    val startMillis: Long,
    val timeRange: String,
    val durationMillis: Long,
)

@Composable
private fun buildEntries(
    voiceRecordings: List<VoiceRecording>,
    meditationIntervals: List<ActivityInterval>,
): List<ActivityListEntry> {
    val talkTint = pilgrimColors.rust
    val medTint = pilgrimColors.dawn
    val out = mutableListOf<ActivityListEntry>()
    for (rec in voiceRecordings) {
        out += ActivityListEntry(
            icon = Icons.Rounded.GraphicEq,
            tint = talkTint,
            nameRes = R.string.summary_activity_talk,
            startMillis = rec.startTimestamp,
            timeRange = formatTimeRange(rec.startTimestamp, rec.endTimestamp),
            durationMillis = rec.durationMillis,
        )
    }
    for (m in meditationIntervals) {
        out += ActivityListEntry(
            icon = Icons.Rounded.SelfImprovement,
            tint = medTint,
            nameRes = R.string.summary_activity_meditate,
            startMillis = m.startTimestamp,
            timeRange = formatTimeRange(m.startTimestamp, m.endTimestamp),
            durationMillis = m.endTimestamp - m.startTimestamp,
        )
    }
    return out.sortedBy { it.startMillis }
}

private fun formatTimeRange(startMs: Long, endMs: Long): String {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
    val s = fmt.format(Instant.ofEpochMilli(startMs).atZone(zone))
    val e = fmt.format(Instant.ofEpochMilli(endMs).atZone(zone))
    return "$s – $e"
}
```

- [ ] **Step 2:** Compile.

- [ ] **Step 3: Tests.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkActivityListCardTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rendersBothTalkAndMeditateRows() {
        val recording = VoiceRecording(
            walkId = 1L, startTimestamp = 1_700_000_000_000L,
            endTimestamp = 1_700_000_022_000L, durationMillis = 22_000L,
            fileRelativePath = "x.wav", transcription = null,
        )
        val meditation = ActivityInterval(
            walkId = 1L, startTimestamp = 1_700_000_300_000L,
            endTimestamp = 1_700_001_500_000L,
            activityType = ActivityType.MEDITATING,
        )
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityListCard(
                    voiceRecordings = listOf(recording),
                    meditationIntervals = listOf(meditation),
                )
            }
        }
        composeRule.onNodeWithText("Talk").assertIsDisplayed()
        composeRule.onNodeWithText("Meditate").assertIsDisplayed()
    }

    @Test
    fun headerIsRendered() {
        composeRule.setContent {
            PilgrimTheme {
                WalkActivityListCard(
                    voiceRecordings = emptyList(),
                    meditationIntervals = emptyList(),
                )
            }
        }
        composeRule.onNodeWithText("Activities").assertIsDisplayed()
    }
}
```

(Sort-by-start test would need text introspection of row order — covered implicitly via the data builder. Robolectric doesn't expose ordered child sequence cleanly.)

- [ ] **Step 4:** Run tests — expect 2 passing.
- [ ] **Step 5:** Commit `feat(walk-summary): WalkActivityListCard (Stage 13-C task 6)`.

---

## Task 7: WalkActivityTimelineCard composable

**Files:** `app/src/main/java/.../ui/walk/summary/WalkActivityTimelineCard.kt`

This is the largest single composable in the stage. No new tests beyond the
TimelineSegments + PaceSparkline pure tests already in place — interactive
gestures + Canvas drawing don't reliably introspect under Robolectric.

- [ ] **Step 1: Implementation.**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

private const val BAR_HEIGHT_DP = 16
private const val TALK_HEIGHT_DP = 10
private const val PACE_HEIGHT_DP = 40
private const val MIN_SAMPLES_FOR_PACE = 3

@Composable
fun WalkActivityTimelineCard(
    startTimestamp: Long,
    endTimestamp: Long,
    voiceRecordings: List<VoiceRecording>,
    activityIntervals: List<ActivityInterval>,
    routeSamples: List<RouteDataSample>,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    val segments = remember(startTimestamp, endTimestamp, activityIntervals, voiceRecordings) {
        computeTimelineSegments(startTimestamp, endTimestamp, activityIntervals, voiceRecordings)
    }
    val sparklinePoints = remember(routeSamples, startTimestamp, endTimestamp) {
        computePaceSparklinePoints(routeSamples, startTimestamp, endTimestamp)
    }
    val avgPaceLabel = remember(routeSamples, units) {
        averagePaceLabel(routeSamples, units)
    }
    var selectedId by remember(segments) { mutableStateOf<Int?>(null) }
    var showRelativeTime by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = pilgrimColors.parchmentSecondary),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PilgrimSpacing.normal),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            TimelineBar(
                segments = segments,
                selectedId = selectedId,
                onSegmentTapped = { id ->
                    selectedId = if (selectedId == id) null else id
                },
            )
            selectedId?.let { id ->
                segments.firstOrNull { it.id == id }?.let { seg ->
                    SelectedTooltip(seg, showRelativeTime)
                }
            }
            TimeLabelsRow(
                startTimestamp = startTimestamp,
                endTimestamp = endTimestamp,
                showRelativeTime = showRelativeTime,
                onToggle = { showRelativeTime = !showRelativeTime },
            )
            if (sparklinePoints.size >= 2) {
                if (avgPaceLabel != null) {
                    Text(
                        text = stringResource(R.string.summary_timeline_pace_label, avgPaceLabel),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                }
                PaceSparklineCanvas(sparklinePoints)
            }
            LegendRow()
        }
    }
}

@Composable
private fun TimelineBar(
    segments: List<TimelineSegment>,
    selectedId: Int?,
    onSegmentTapped: (Int) -> Unit,
) {
    var widthPx by remember { mutableStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(BAR_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(pilgrimColors.moss.copy(alpha = 0.4f))
            .onSizeChanged { widthPx = it.width }
            .pointerInput(segments) {
                detectTapGestures { offset ->
                    if (widthPx <= 0) return@detectTapGestures
                    val frac = offset.x / widthPx
                    segments.firstOrNull {
                        frac >= it.startFraction && frac <= it.startFraction + it.widthFraction
                    }?.let { onSegmentTapped(it.id) }
                }
            },
    ) {
        // Meditation segments first (taller — drawn under talks).
        segments.filter { it.type == TimelineSegmentType.Meditating }.forEach { seg ->
            SegmentRect(seg, BAR_HEIGHT_DP, pilgrimColors.dawn, selectedId == seg.id)
        }
        segments.filter { it.type == TimelineSegmentType.Talking }.forEach { seg ->
            SegmentRect(seg, TALK_HEIGHT_DP, pilgrimColors.rust, selectedId == seg.id)
        }
    }
}

@Composable
private fun SegmentRect(
    segment: TimelineSegment,
    heightDp: Int,
    color: Color,
    isSelected: Boolean,
) {
    var parentWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val xOffsetPx = with(density) { (parentWidth * segment.startFraction).toDp() }
    val widthDp = with(density) { (parentWidth * segment.widthFraction).coerceAtLeast(2f).toDp() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { parentWidth = it.width },
    ) {
        Box(
            modifier = Modifier
                .offset(x = xOffsetPx)
                .width(widthDp)
                .height(heightDp.dp)
                .align(Alignment.CenterStart)
                .clip(RoundedCornerShape(3.dp))
                .background(color.copy(alpha = if (isSelected) 0.95f else 0.7f)),
        )
    }
}

@Composable
private fun SelectedTooltip(seg: TimelineSegment, showRelativeTime: Boolean) {
    val labelRes = when (seg.type) {
        TimelineSegmentType.Talking -> R.string.summary_timeline_legend_talk
        TimelineSegmentType.Meditating -> R.string.summary_timeline_legend_meditate
    }
    val color = when (seg.type) {
        TimelineSegmentType.Talking -> pilgrimColors.rust
        TimelineSegmentType.Meditating -> pilgrimColors.dawn
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = stringResource(labelRes),
            style = pilgrimType.caption,
            color = pilgrimColors.ink,
        )
        Text(
            text = formatCompactDurationCaption(seg.endMillis - seg.startMillis),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        if (!showRelativeTime) {
            Text(
                text = formatAbsoluteTime(seg.startMillis),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun TimeLabelsRow(
    startTimestamp: Long,
    endTimestamp: Long,
    showRelativeTime: Boolean,
    onToggle: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = source, indication = null) { onToggle() },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val left =
            if (showRelativeTime) String.format(Locale.US, "%d:%02d", 0, 0)
            else formatAbsoluteTime(startTimestamp)
        val right =
            if (showRelativeTime) WalkFormat.duration(endTimestamp - startTimestamp)
            else formatAbsoluteTime(endTimestamp)
        Text(text = left, style = pilgrimType.caption, color = pilgrimColors.fog)
        Text(text = right, style = pilgrimType.caption, color = pilgrimColors.fog)
    }
}

@Composable
private fun PaceSparklineCanvas(points: List<PaceSparklinePoint>) {
    val stoneFill = pilgrimColors.stone
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(PACE_HEIGHT_DP.dp),
    ) {
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
                colors = listOf(stoneFill.copy(alpha = 0.12f), stoneFill.copy(alpha = 0.02f)),
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
            color = stoneFill.copy(alpha = 0.45f),
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}

@Composable
private fun LegendRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(pilgrimColors.moss, R.string.summary_timeline_legend_walk)
        LegendDot(pilgrimColors.rust, R.string.summary_timeline_legend_talk)
        LegendDot(pilgrimColors.dawn, R.string.summary_timeline_legend_meditate)
    }
}

@Composable
private fun LegendDot(color: Color, labelRes: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(labelRes), style = pilgrimType.caption, color = pilgrimColors.fog)
    }
}

private fun formatAbsoluteTime(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    return DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
        .format(Instant.ofEpochMilli(epochMs).atZone(zone))
}

private fun formatCompactDurationCaption(millis: Long): String {
    val total = (millis / 1_000L).coerceAtLeast(0L).toInt()
    return if (total < 60) "${total}s" else "${total / 60}m"
}

private fun averagePaceLabel(samples: List<RouteDataSample>, units: UnitSystem): String? {
    val speeds = samples.mapNotNull { it.speedMetersPerSecond }.filter { it > 0.3f }
    if (speeds.isEmpty()) return null
    val avgMps = speeds.average()
    if (avgMps <= 0.0) return null
    // pace seconds per km = 1000 / m/s
    val secPerKm = 1000.0 / avgMps
    return WalkFormat.pace(secPerKm, units)
}
```

- [ ] **Step 2:** Compile.
- [ ] **Step 3:** Commit `feat(walk-summary): WalkActivityTimelineCard (Stage 13-C task 7)`.

---

## Task 8: Wire sections 13/14/15 into WalkSummaryScreen

**Files:** Modify `app/src/main/java/.../ui/walk/WalkSummaryScreen.kt`

- [ ] **Step 1: Imports.**

```kotlin
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkActivityInsightsCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkActivityListCard
import org.walktalkmeditate.pilgrim.ui.walk.summary.WalkActivityTimelineCard
```

- [ ] **Step 2: Inside the AnimatedVisibility wrap, after `WalkTimeBreakdownGrid` (section 11) and before the AnimatedVisibility's closing brace, replace placeholder comments for sections 13/14/15 with:**

```kotlin
// 13. Activity timeline bar (Stage 13-C)
WalkActivityTimelineCard(
    startTimestamp = s.summary.walk.startTimestamp,
    endTimestamp = s.summary.walk.endTimestamp ?: s.summary.walk.startTimestamp,
    voiceRecordings = s.summary.voiceRecordings,
    activityIntervals = s.summary.meditationIntervals,
    routeSamples = s.summary.routeSamples,
    units = distanceUnits,
)

// 14. Activity insights (Stage 13-C)
if (s.summary.talkMillis > 0L || s.summary.meditationIntervals.isNotEmpty()) {
    WalkActivityInsightsCard(
        talkMillis = s.summary.talkMillis,
        activeMillis = s.summary.activeMillis,
        meditationIntervals = s.summary.meditationIntervals,
    )
}

// 15. Activity list (Stage 13-C)
if (s.summary.voiceRecordings.isNotEmpty() || s.summary.meditationIntervals.isNotEmpty()) {
    WalkActivityListCard(
        voiceRecordings = s.summary.voiceRecordings,
        meditationIntervals = s.summary.meditationIntervals,
    )
}
```

- [ ] **Step 3:** Compile + lint + tests pass.
- [ ] **Step 4:** Commit `feat(walk-summary): wire activity views into WalkSummaryScreen sections 13/14/15 (Stage 13-C task 8)`.

---

## Task 9: Final verification + push

- [ ] **Step 1:** `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` — BUILD SUCCESSFUL.
- [ ] **Step 2:** `./gradlew :app:assembleRelease` — BUILD SUCCESSFUL.
- [ ] **Step 3:** `git push -u origin feat/stage-13c-activity-views`.

---

## Self-Review

**Coverage:**
- ✅ TimelineSegments + 6 tests (Task 2)
- ✅ PaceSparkline + 5 tests (Task 3)
- ✅ Strings + VM 3 fields + 2 VM tests (Task 4)
- ✅ WalkActivityInsightsCard + 3 Robolectric tests (Task 5)
- ✅ WalkActivityListCard + 2 Robolectric tests (Task 6)
- ✅ WalkActivityTimelineCard composable (Task 7)
- ✅ Section wiring (Task 8)
- ✅ Verification + push (Task 9)

**No placeholders.** Every step has explicit code blocks. Type names consistent (`TimelineSegment`, `TimelineSegmentType`, `PaceSparklinePoint`, `ActivityListEntry`).

**Type consistency:** `WalkSummary.voiceRecordings: List<VoiceRecording>`, `meditationIntervals: List<ActivityInterval>`, `routeSamples: List<RouteDataSample>` flow from VM through `WalkSummaryScreen` to the new composables.

**Reduce-motion:** No new animations introduced in 13-C. Sections render statically; the existing 13-B AnimatedVisibility wrapper handles fade-in. Reduce-motion already plumbed via that wrapper.
