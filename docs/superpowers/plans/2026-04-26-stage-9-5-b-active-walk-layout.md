# Stage 9.5-B Active Walk Layout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the Active Walk screen to match iOS: full-screen Mapbox map with a draggable bottom sheet overlay containing live stats + circular action controls.

**Architecture:** A `Box(fillMaxSize)` layers a full-screen `PilgrimMap` underneath a custom `WalkStatsSheet` aligned `BottomCenter`. Sheet has two states (`Minimized` / `Expanded`) controlled by `rememberSaveable SheetState`, driven by walk-state transitions via a separate `SheetStateController` Composable that uses `LaunchedEffect(walkState::class)` cancellation as the debounce mechanism. Drag gesture: 40dp distance OR 300dp/s flick to commit; 100dp clamp; haptic on commit. Both sheet content variants (Minimized + Expanded) are always composed and switched via `Modifier.graphicsLayer { alpha = ... }` to avoid `AnimatedContent`'s re-mount tearing down the active mic recording.

**Tech Stack:** Jetpack Compose, Kotlin coroutines, Mapbox Maps Android SDK, Hilt, Room (existing), JUnit 4 + Turbine + Robolectric for tests.

**Spec:** `docs/superpowers/specs/2026-04-26-stage-9-5-b-active-walk-layout-design.md`

---

### Task 1: `WalkStats.totalMeditatedMillis`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkStats.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkStatsTotalMeditatedMillisTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkStatsTotalMeditatedMillisTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkStatsTotalMeditatedMillisTest {

    @Test
    fun `idle returns zero`() {
        assertEquals(0L, WalkStats.totalMeditatedMillis(WalkState.Idle, now = 10_000L))
    }

    @Test
    fun `active returns accumulator value verbatim`() {
        val state = WalkState.Active(
            WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 60_000L),
        )
        assertEquals(60_000L, WalkStats.totalMeditatedMillis(state, now = 999_999L))
    }

    @Test
    fun `paused returns accumulator value verbatim`() {
        val state = WalkState.Paused(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 120_000L),
            pausedAt = 200_000L,
        )
        assertEquals(120_000L, WalkStats.totalMeditatedMillis(state, now = 300_000L))
    }

    @Test
    fun `meditating adds running slice on top of accumulator`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 90_000L),
            meditationStartedAt = 100_000L,
        )
        // running slice = now(130_000) - startedAt(100_000) = 30_000
        // total = 90_000 + 30_000 = 120_000
        assertEquals(120_000L, WalkStats.totalMeditatedMillis(state, now = 130_000L))
    }

    @Test
    fun `meditating with clock skew clamps running slice to zero`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 90_000L),
            meditationStartedAt = 200_000L,
        )
        // now < startedAt → coerceAtLeast(0) → running slice = 0
        // total = 90_000 + 0 = 90_000 (NOT a negative)
        assertEquals(90_000L, WalkStats.totalMeditatedMillis(state, now = 100_000L))
    }

    @Test
    fun `finished returns accumulator value verbatim`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 1L, startedAt = 0L, totalMeditatedMillis = 200_000L),
            endedAt = 500_000L,
        )
        assertEquals(200_000L, WalkStats.totalMeditatedMillis(state, now = 999_999L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.domain.WalkStatsTotalMeditatedMillisTest"`
Expected: FAIL with `Unresolved reference: totalMeditatedMillis` on `WalkStats`.

- [ ] **Step 3: Add `totalMeditatedMillis` to WalkStats**

Modify `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkStats.kt` — append a method just before the closing `}` of `object WalkStats`:

```kotlin
    /**
     * Total meditation time including the in-progress meditation if any.
     * For Active/Paused/Finished, returns the accumulator's
     * totalMeditatedMillis (the reducer adds the just-completed slice on
     * MeditateEnd / Finish). For Meditating, adds (now - startedAt) on
     * top, clamped at zero so clock-skew can't produce a negative running
     * total.
     */
    fun totalMeditatedMillis(state: WalkState, now: Long): Long = when (state) {
        is WalkState.Meditating -> state.walk.totalMeditatedMillis +
            (now - state.meditationStartedAt).coerceAtLeast(0L)
        is WalkState.Active -> state.walk.totalMeditatedMillis
        is WalkState.Paused -> state.walk.totalMeditatedMillis
        is WalkState.Finished -> state.walk.totalMeditatedMillis
        WalkState.Idle -> 0L
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.domain.WalkStatsTotalMeditatedMillisTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkStats.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkStatsTotalMeditatedMillisTest.kt
git commit -m "feat(walk): add WalkStats.totalMeditatedMillis with running slice"
```

---

### Task 2: `WalkFormat.shortDuration`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormatShortDurationTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormatShortDurationTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkFormatShortDurationTest {

    @Test
    fun `zero or negative renders em-dash`() {
        assertEquals("—", WalkFormat.shortDuration(0L))
        assertEquals("—", WalkFormat.shortDuration(-1_000L))
    }

    @Test
    fun `under one minute renders M_SS`() {
        assertEquals("0:30", WalkFormat.shortDuration(30_000L))
        assertEquals("0:01", WalkFormat.shortDuration(1_000L))
    }

    @Test
    fun `under one hour renders M_SS`() {
        assertEquals("1:30", WalkFormat.shortDuration(90_000L))
        assertEquals("59:59", WalkFormat.shortDuration(59 * 60 * 1_000L + 59_000L))
    }

    @Test
    fun `at one hour switches to H_MM`() {
        assertEquals("1:00", WalkFormat.shortDuration(60 * 60 * 1_000L))
        // 65 minutes = 1h05m, NOT "65:00"
        assertEquals("1:05", WalkFormat.shortDuration(65 * 60 * 1_000L))
    }

    @Test
    fun `multiple hours render as H_MM`() {
        assertEquals("2:05", WalkFormat.shortDuration(125 * 60 * 1_000L))
        assertEquals("12:34", WalkFormat.shortDuration((12 * 60 + 34) * 60 * 1_000L))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkFormatShortDurationTest"`
Expected: FAIL with `Unresolved reference: shortDuration`.

- [ ] **Step 3: Add `shortDuration` to WalkFormat**

Modify `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt` — append to `object WalkFormat`, just before the closing `}`:

```kotlin
    /**
     * Compact duration for the time-chip pills. Returns "—" for ≤0,
     * "M:SS" below one hour, and "H:MM" at one hour or more so the
     * chip text fits the narrow pill width even on long walks.
     */
    fun shortDuration(millis: Long): String {
        if (millis <= 0) return "—"
        val totalSeconds = millis / 1_000L
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d", hours, minutes)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }
```

(`Locale` is already imported at the top of WalkFormat.kt.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkFormatShortDurationTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormat.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkFormatShortDurationTest.kt
git commit -m "feat(walk): add WalkFormat.shortDuration with H:MM at 1h boundary"
```

---

### Task 3: New string resources

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Append new strings**

In `app/src/main/res/values/strings.xml`, just before the closing `</resources>`:

```xml
    <!-- Stage 9.5-B: Active Walk layout rebuild — bottom sheet stats + chips + actions -->
    <string name="walk_stat_time">Time</string>
    <string name="walk_stat_steps">Steps</string>
    <string name="walk_stat_ascent">Ascent</string>
    <string name="walk_chip_walk">Walk</string>
    <string name="walk_chip_talk">Talk</string>
    <string name="walk_chip_meditate">Meditate</string>
    <!-- TRANSLATORS: contemplative caption shown during an active walk.
         Pilgrim's voice is contemplative + slow; aim for a short
         aphoristic line. -->
    <string name="walk_caption_every_step">every step is enough</string>
    <string name="walk_action_meditate_short">Meditate</string>
    <string name="walk_action_end_meditation_short">End</string>
    <string name="walk_action_finish_short">End</string>
```

- [ ] **Step 2: Verify resources compile**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL (no resource-conflict errors).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(walk): add strings for Stage 9.5-B sheet stats, chips, actions"
```

---

### Task 4: `SheetState` enum

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetState.kt`

- [ ] **Step 1: Create the enum**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetState.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

/**
 * Two-detent state for the Active Walk bottom sheet.
 *
 * Kotlin enums implement [java.io.Serializable] by default, so plain
 * `rememberSaveable { mutableStateOf(SheetState.Expanded) }` survives
 * config changes via the bundle's Serializable saver. No custom Saver
 * required.
 */
enum class SheetState { Minimized, Expanded }
```

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetState.kt
git commit -m "feat(walk): add SheetState enum for Active Walk bottom sheet"
```

---

### Task 5: WalkViewModel — single-source `voiceRecordings` + derive `recordingsCount` + `talkMillis`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelVoiceRecordingsTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelVoiceRecordingsTest.kt`. Mirror the pattern of `WalkViewModelTest`'s `recordingsCount` test (line 453+) — the existing test creates a `FakeWalkRepository` (or reuses `FakeWalkController` + a real Room instance, depending on how the harness is set up there). Read `WalkViewModelTest.kt` first to copy the exact harness setup, then add:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
// (Import the same fakes/harness types used by WalkViewModelTest.)

@OptIn(ExperimentalCoroutinesApi::class)
class WalkViewModelVoiceRecordingsTest {

    // Replicate the test harness from WalkViewModelTest:
    //   Dispatchers.setMain(dispatcher), construct controller + repo +
    //   recorder + viewModel, etc. Read WalkViewModelTest.kt's @Before
    //   for the exact wire-up.
    private val dispatcher = StandardTestDispatcher()
    // ... (harness fields)

    @Before fun setUp() { /* mirror WalkViewModelTest.setUp */ }
    @After fun tearDown() { /* mirror WalkViewModelTest.tearDown */ }

    @Test
    fun `talkMillis is zero when no walk in progress`() = runTest(dispatcher) {
        viewModel.talkMillis.test {
            assertEquals(0L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `talkMillis sums durationMillis across rows for the active walk`() = runTest(dispatcher) {
        startWalkAndAwaitActive() // helper from existing harness
        val walkId = walkIdOf(viewModel.walkState.value)
        viewModel.talkMillis.test {
            assertEquals(0L, awaitItem())
            insertVoiceRecording(walkId, durationMillis = 5_000L)
            assertEquals(5_000L, awaitItem())
            insertVoiceRecording(walkId, durationMillis = 7_500L)
            assertEquals(12_500L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `talkMillis returns to zero when walk finishes`() = runTest(dispatcher) {
        startWalkAndAwaitActive()
        val walkId = walkIdOf(viewModel.walkState.value)
        insertVoiceRecording(walkId, durationMillis = 4_000L)
        viewModel.talkMillis.test {
            // skip the initial 0 + 4_000 emissions to land on the post-Finish 0.
            // (Adapt this to the precise turbine sequence the harness exposes.)
            skipItems(2)
            viewModel.finishWalk()
            assertEquals(0L, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `recordingsCount and talkMillis derive from a single upstream subscription`() =
        runTest(dispatcher) {
            startWalkAndAwaitActive()
            val walkId = walkIdOf(viewModel.walkState.value)
            // Subscribe to BOTH downstream flows.
            viewModel.recordingsCount.test {
                viewModel.talkMillis.test {
                    skipItems(1) // initial 0
                    cancelAndIgnoreRemainingEvents()
                }
                skipItems(1) // initial 0
                insertVoiceRecording(walkId, durationMillis = 3_000L)
                // The fakeRepo can expose an `observeVoiceRecordings` call counter;
                // assert it's been called only ONCE despite two downstream subscribers.
                // (If the harness doesn't surface a counter, add one in this test
                // file's local fake or skip this assertion + leave a TODO.)
                assertEquals(
                    "single upstream subscription expected",
                    1,
                    fakeRepo.observeVoiceRecordingsCallCount,
                )
                cancelAndIgnoreRemainingEvents()
            }
        }
}
```

(If the harness in `WalkViewModelTest.kt` uses a different kind of fake — e.g., a real Room in-memory DB — replicate that approach; the goal is the same assertions.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkViewModelVoiceRecordingsTest"`
Expected: FAIL with `Unresolved reference: talkMillis`.

- [ ] **Step 3: Add `voiceRecordings` flow + refactor `recordingsCount` + add `talkMillis`**

Modify `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`. Locate the existing `recordingsCount` definition (around line 222) and **replace** it with:

```kotlin
    /**
     * Single source for voice-recording rows. Both [recordingsCount] and
     * [talkMillis] derive from this flow so we open ONE Room subscription
     * even when both downstream consumers are active.
     */
    private val voiceRecordings: StateFlow<List<VoiceRecording>> = controller.state
        .map { walkIdOrNull(it) }
        .distinctUntilChanged()
        .flatMapLatest { walkId ->
            if (walkId == null) flowOf(emptyList())
            else repository.observeVoiceRecordings(walkId)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    /**
     * Live count of VoiceRecording rows for the current walk. Derives
     * from [voiceRecordings] to share the upstream subscription with
     * [talkMillis].
     */
    val recordingsCount: StateFlow<Int> = voiceRecordings
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = 0,
        )

    /**
     * Live total voice-recording duration, summed across all rows for the
     * current walk. Drives the Talk time chip in the active-walk sheet.
     */
    val talkMillis: StateFlow<Long> = voiceRecordings
        .map { rows -> rows.sumOf { it.durationMillis } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = 0L,
        )
```

Add the import (if not already present):

```kotlin
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
```

- [ ] **Step 4: Run the new test + the existing recordingsCount test**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkViewModelVoiceRecordingsTest" --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkViewModelTest"`
Expected: PASS — both the new file and the existing `recordingsCount` test continue to pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelVoiceRecordingsTest.kt
git commit -m "feat(walk): single-source voiceRecordings flow + add talkMillis"
```

---

### Task 6: `PilgrimMap.bottomInsetDp` parameter

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt`

- [ ] **Step 1: Add parameter + thread it through camera padding**

Modify `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt`:

1. Update the function signature (around line 49):

```kotlin
@Composable
fun PilgrimMap(
    points: List<LocationPoint>,
    modifier: Modifier = Modifier,
    followLatest: Boolean = false,
    initialCenter: LocationPoint? = null,
    bottomInsetDp: Dp = 0.dp,
)
```

Add the import: `import androidx.compose.ui.unit.Dp`

2. After the existing `paddingPx` definition (~line 61), add:

```kotlin
    val bottomInsetPx = with(LocalDensity.current) { bottomInsetDp.toPx().toDouble() }
```

3. Replace the `EdgeInsets(...)` constructor in the fit-bounds path (line 224) with:

```kotlin
                EdgeInsets(paddingPx, paddingPx, paddingPx + bottomInsetPx, paddingPx),
```

4. In the `easeTo` calls for follow-latest mode (line 210 and again at line 245), replace `.padding(current.padding)` with `.padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))` so the user puck stays above the sheet:

```kotlin
                    view.mapboxMap.easeTo(
                        CameraOptions.Builder()
                            .center(mapboxPoints.last())
                            .zoom(FOLLOW_ZOOM)
                            .bearing(current.bearing)
                            .pitch(current.pitch)
                            .padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))
                            .build(),
                        MapAnimationOptions.Builder().duration(FOLLOW_EASE_MS).build(),
                    )
```

(Apply the same `.padding(...)` change to BOTH `easeTo` calls.)

5. Also apply the bottom inset to the cold-start `setCamera` path that uses `initialCenter` (around line 264). Add `.padding(EdgeInsets(0.0, 0.0, bottomInsetPx, 0.0))` to that builder.

- [ ] **Step 2: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/PilgrimMap.kt
git commit -m "feat(map): add PilgrimMap.bottomInsetDp for sheet-aware camera padding"
```

---

### Task 7: `SheetStateController` — debounce via cancellation

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateController.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateControllerTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateControllerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetStateControllerTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Active emission triggers Minimized`() {
        val captures = mutableListOf<SheetState>()
        composeRule.setContent {
            SheetStateController(
                walkState = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L)),
                onUpdateState = { captures += it },
            )
        }
        composeRule.waitForIdle()
        assertEquals(listOf(SheetState.Minimized), captures)
    }

    @Test
    fun `Meditating emission triggers Expanded immediately`() {
        val captures = mutableListOf<SheetState>()
        composeRule.setContent {
            SheetStateController(
                walkState = WalkState.Meditating(
                    walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
                    meditationStartedAt = 1_000L,
                ),
                onUpdateState = { captures += it },
            )
        }
        composeRule.waitForIdle()
        assertEquals(listOf(SheetState.Expanded), captures)
    }

    @Test
    fun `Paused emission Expanded fires after 800ms`() {
        val captures = mutableListOf<SheetState>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SheetStateController(
                walkState = WalkState.Paused(
                    walk = WalkAccumulator(walkId = 1L, startedAt = 0L),
                    pausedAt = 5_000L,
                ),
                onUpdateState = { captures += it },
            )
        }
        composeRule.mainClock.advanceTimeBy(799L)
        assertEquals(emptyList<SheetState>(), captures)
        composeRule.mainClock.advanceTimeBy(2L)
        assertEquals(listOf(SheetState.Expanded), captures)
    }

    @Test
    fun `Paused then Active within debounce cancels Expanded and fires Minimized`() {
        val captures = mutableListOf<SheetState>()
        var state: WalkState by mutableStateOf<WalkState>(
            WalkState.Paused(WalkAccumulator(1L, 0L), pausedAt = 0L)
        )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            SheetStateController(walkState = state, onUpdateState = { captures += it })
        }
        composeRule.mainClock.advanceTimeBy(400L)
        // Within debounce, transition back to Active.
        state = WalkState.Active(WalkAccumulator(1L, 0L))
        composeRule.mainClock.advanceTimeBy(1_000L)
        // Expanded must NOT have been called; Minimized is the only entry.
        assertEquals(listOf(SheetState.Minimized), captures)
    }
}
```

(Imports: add `import androidx.compose.runtime.mutableStateOf` and `import androidx.compose.runtime.setValue`, plus `import androidx.compose.runtime.getValue`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.SheetStateControllerTest"`
Expected: FAIL with `Unresolved reference: SheetStateController`.

- [ ] **Step 3: Implement `SheetStateController`**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateController.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.domain.WalkState

private const val PAUSE_DEBOUNCE_MS = 800L

/**
 * Drives [SheetState] transitions from observed [walkState] changes.
 *
 * Pause-debounce is implemented purely through `LaunchedEffect`'s
 * cancel-on-key-change semantics: when the state flips back to Active
 * mid-debounce, the in-flight Paused coroutine is cancelled BEFORE
 * `delay()` returns, the new Active coroutine launches, and immediately
 * calls `Minimized`. There is no "re-check after delay" branch — that
 * pattern doesn't work because the captured `walkState` parameter is
 * whatever it was at launch time.
 *
 * Key on `walkState::class` so location-sample-driven Active → Active
 * recompositions don't re-fire the Minimized side-effect.
 */
@Composable
fun SheetStateController(
    walkState: WalkState,
    onUpdateState: (SheetState) -> Unit,
) {
    val onUpdate by rememberUpdatedState(onUpdateState)
    LaunchedEffect(walkState::class) {
        when (walkState) {
            is WalkState.Active -> onUpdate(SheetState.Minimized)
            is WalkState.Paused -> {
                delay(PAUSE_DEBOUNCE_MS)
                onUpdate(SheetState.Expanded)
            }
            is WalkState.Meditating -> onUpdate(SheetState.Expanded)
            // Idle (initialValue / cold-start) and Finished (about to nav
            // away) are no-ops; the screen pops away in those cases.
            WalkState.Idle, is WalkState.Finished -> Unit
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.SheetStateControllerTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateController.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/SheetStateControllerTest.kt
git commit -m "feat(walk): SheetStateController with cancellation-based pause debounce"
```

---

### Task 8: `WalkStatsSheet` shell — drag handle + minimized content

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetMinimizedTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetMinimizedTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

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
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkStatsSheetMinimizedTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `minimized sheet shows time distance and steps placeholder`() {
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Minimized,
                onStateChange = {},
                walkState = WalkState.Active(WalkAccumulator(1L, 0L, distanceMeters = 250.0)),
                totalElapsedMillis = 90_000L,
                distanceMeters = 250.0,
                walkMillis = 90_000L,
                talkMillis = 0L,
                meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle,
                audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
            )
        }
        composeRule.onNodeWithText("1:30").assertIsDisplayed()
        composeRule.onNodeWithText("0.25 km").assertIsDisplayed()
        composeRule.onNodeWithText("Steps").assertIsDisplayed()
    }

    @Test
    fun `tap on minimized invokes onStateChange Expanded`() {
        var newState: SheetState? = null
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Minimized,
                onStateChange = { newState = it },
                walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
                totalElapsedMillis = 0L,
                distanceMeters = 0.0,
                walkMillis = 0L,
                talkMillis = 0L,
                meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle,
                audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
            )
        }
        composeRule.onNodeWithText("Time").performClick()
        assertEquals(SheetState.Expanded, newState)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetMinimizedTest"`
Expected: FAIL with `Unresolved reference: WalkStatsSheet`.

- [ ] **Step 3: Create `WalkStatsSheet.kt` with shell + DragHandle + MinimizedContent**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Bottom-sheet overlay for the Active Walk screen. Two detents
 * ([SheetState.Minimized] / [SheetState.Expanded]); content for both is
 * always composed and switched via alpha to keep the active mic
 * recording from being torn down on a state flip.
 *
 * The sheet's measured height is constant (~340dp regardless of state)
 * so the map's `bottomInsetDp` stays stable. Visual consequence:
 * minimized state has unused vertical space above the content. Acceptable
 * trade-off vs. SubcomposeLayout-based per-state height measurement.
 */
@Composable
fun WalkStatsSheet(
    state: SheetState,
    onStateChange: (SheetState) -> Unit,
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canDrag = walkState is WalkState.Active
    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(modifier = modifier.fillMaxWidth()) {
        // Manual upward shadow: Compose Surface(elevation) casts shadow
        // downward; iOS uses y: -4 for an UPWARD shadow onto the map.
        // We emulate via a soft gradient strip drawn just above the
        // sheet's top edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(pilgrimColors.parchment)
                .navigationBarsPadding()
                .padding(bottom = PilgrimSpacing.normal),
        ) {
            DragHandle(canDrag = canDrag)
            // Always-mount both content variants (alpha-faded) so a state
            // flip doesn't tear down the active mic recording's
            // LaunchedEffects + audio observers.
            SheetContentSwitcher(
                state = state,
                minimizedContent = {
                    MinimizedContent(
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        onTap = { onStateChange(SheetState.Expanded) },
                    )
                },
                expandedContent = {
                    // ExpandedContent body added in Task 9.
                    Box(modifier = Modifier.fillMaxWidth())
                },
            )
        }
    }
}

@Composable
private fun SheetContentSwitcher(
    state: SheetState,
    minimizedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val showExpanded = state == SheetState.Expanded
    Box {
        Box(
            modifier = Modifier.then(
                if (showExpanded) Modifier.alpha(0f) else Modifier.alpha(1f)
            ),
        ) { minimizedContent() }
        Box(
            modifier = Modifier.then(
                if (showExpanded) Modifier.alpha(1f) else Modifier.alpha(0f)
            ),
        ) { expandedContent() }
    }
}

@Composable
private fun DragHandle(canDrag: Boolean) {
    val opacity = if (canDrag) 0.35f else 0.12f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 5.dp)
                .background(
                    color = pilgrimColors.fog.copy(alpha = opacity),
                    shape = RoundedCornerShape(percent = 50),
                ),
        )
    }
}

@Composable
private fun MinimizedContent(
    totalElapsedMillis: Long,
    distanceMeters: Double,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatColumn(
            value = WalkFormat.duration(totalElapsedMillis),
            label = stringResource(R.string.walk_stat_time),
        )
        StatColumn(
            value = WalkFormat.distance(distanceMeters),
            label = stringResource(R.string.walk_stat_distance),
        )
        StatColumn(
            value = "—",
            label = stringResource(R.string.walk_stat_steps),
        )
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
        androidx.compose.material3.Text(
            text = label,
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}
```

Add the missing import: `import androidx.compose.ui.draw.alpha`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetMinimizedTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetMinimizedTest.kt
git commit -m "feat(walk): WalkStatsSheet shell with drag handle + minimized content"
```

---

### Task 9: ExpandedContent — timer, caption, 3-stat row, time chips

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetExpandedTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetExpandedTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

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
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkStatsSheetExpandedTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `expanded sheet renders timer caption stats and chips`() {
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Expanded,
                onStateChange = {},
                walkState = WalkState.Active(WalkAccumulator(1L, 0L, distanceMeters = 1_234.0)),
                totalElapsedMillis = (1 * 3600 + 23 * 60 + 45) * 1_000L,
                distanceMeters = 1_234.0,
                walkMillis = 60_000L,
                talkMillis = 90_000L,
                meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle,
                audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
            )
        }
        // Timer
        composeRule.onNodeWithText("1:23:45").assertIsDisplayed()
        // Caption
        composeRule.onNodeWithText("every step is enough").assertIsDisplayed()
        // Distance value
        composeRule.onNodeWithText("1.23 km").assertIsDisplayed()
        // Two "—" placeholders for Steps + Ascent (in expanded; minimized
        // also has one but it's alpha=0 here).
        composeRule.onAllNodesWithText("—").assertCountEquals(2)
        // Chip values via WalkFormat.shortDuration: walk=1:00, talk=1:30, meditate="—"
        composeRule.onNodeWithText("1:00").assertIsDisplayed()
        composeRule.onNodeWithText("1:30").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetExpandedTest"`
Expected: FAIL — caption + chip values not rendered.

- [ ] **Step 3: Implement `ExpandedContent` + `TimeChip`**

In `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`, replace the placeholder `expandedContent = { Box(...) }` lambda inside `WalkStatsSheet` with `expandedContent = { ExpandedContent(...) }` (passing through all the relevant params). Then append these new private composables to the file:

```kotlin
@Composable
private fun ExpandedContent(
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = PilgrimSpacing.big),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text(
                text = WalkFormat.duration(totalElapsedMillis),
                style = pilgrimType.timer,
                color = pilgrimColors.ink,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.height(PilgrimSpacing.xs))
            androidx.compose.material3.Text(
                text = stringResource(R.string.walk_caption_every_step),
                style = pilgrimType.caption,
                color = pilgrimColors.fog.copy(alpha = 0.6f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatColumn(
                value = WalkFormat.distance(distanceMeters),
                label = stringResource(R.string.walk_stat_distance),
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = "—",
                label = stringResource(R.string.walk_stat_steps),
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = "—",
                label = stringResource(R.string.walk_stat_ascent),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            TimeChip(
                label = stringResource(R.string.walk_chip_walk),
                value = WalkFormat.shortDuration(walkMillis),
                active = walkState is WalkState.Active,
                modifier = Modifier.weight(1f),
            )
            TimeChip(
                label = stringResource(R.string.walk_chip_talk),
                value = WalkFormat.shortDuration(talkMillis),
                active = recorderState is VoiceRecorderUiState.Recording,
                modifier = Modifier.weight(1f),
            )
            TimeChip(
                label = stringResource(R.string.walk_chip_meditate),
                value = WalkFormat.shortDuration(meditateMillis),
                active = walkState is WalkState.Meditating,
                modifier = Modifier.weight(1f),
            )
        }
        // ActionButtonRow added in Task 10.
    }
}

@Composable
private fun TimeChip(
    label: String,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val border = if (active) pilgrimColors.dawn else pilgrimColors.fog.copy(alpha = 0.4f)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(pilgrimColors.parchmentSecondary)
            .border(1.dp, border, RoundedCornerShape(percent = 50))
            .padding(vertical = PilgrimSpacing.xs, horizontal = PilgrimSpacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
        androidx.compose.material3.Text(
            text = label,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}
```

Add imports: `import androidx.compose.foundation.border` and `import androidx.compose.ui.unit.dp` (likely already present).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetExpandedTest"`
Expected: PASS, 1 test.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetExpandedTest.kt
git commit -m "feat(walk): expanded sheet content — timer, caption, 3-stat row, time chips"
```

---

### Task 10: ActionButtonRow + CircularActionButton + MicActionButton

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetActionRowTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetActionRowTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkStatsSheetActionRowTest {

    @get:Rule val composeRule = createComposeRule()

    private fun expanded(walkState: WalkState, content: ActionRowProbes.() -> Unit) {
        val probes = ActionRowProbes()
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Expanded,
                onStateChange = {},
                walkState = walkState,
                totalElapsedMillis = 0L,
                distanceMeters = 0.0,
                walkMillis = 0L,
                talkMillis = 0L,
                meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle,
                audioLevel = 0f,
                recordingsCount = 0,
                onPause = { probes.pauseFired = true },
                onResume = { probes.resumeFired = true },
                onStartMeditation = { probes.meditateFired = true },
                onEndMeditation = { probes.endMeditationFired = true },
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = { probes.finishFired = true },
            )
        }
        probes.content()
    }

    private class ActionRowProbes {
        var pauseFired = false
        var resumeFired = false
        var meditateFired = false
        var endMeditationFired = false
        var finishFired = false
    }

    @Test
    fun `Active state — Pause and Meditate enabled, Resume hidden`() {
        expanded(WalkState.Active(WalkAccumulator(1L, 0L))) {
            composeRule.onNodeWithText("Pause").assertIsEnabled()
            composeRule.onNodeWithText("Meditate").assertIsEnabled()
        }
    }

    @Test
    fun `Paused state — Resume enabled, Meditate slot disabled`() {
        expanded(WalkState.Paused(WalkAccumulator(1L, 0L), pausedAt = 0L)) {
            composeRule.onNodeWithText("Resume").assertIsEnabled()
            composeRule.onNodeWithText("Meditate").assertIsNotEnabled()
        }
    }

    @Test
    fun `Meditating state — End meditation enabled, Pause slot disabled`() {
        expanded(
            WalkState.Meditating(
                walk = WalkAccumulator(1L, 0L),
                meditationStartedAt = 1_000L,
            )
        ) {
            // "End" appears twice (end-meditation + finish-walk). Use
            // assertHasClickAction on first match for smoke; production
            // labels diverge in Stage 9.5-B.
            composeRule.onNodeWithText("Pause").assertIsNotEnabled()
        }
    }

    @Test
    fun `Pause click fires onPause`() {
        var fired = false
        composeRule.setContent {
            WalkStatsSheet(
                state = SheetState.Expanded,
                onStateChange = {},
                walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
                totalElapsedMillis = 0L, distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
                recordingsCount = 0,
                onPause = { fired = true },
                onResume = {}, onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
            )
        }
        composeRule.onNodeWithText("Pause").performClick()
        assertTrue(fired)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetActionRowTest"`
Expected: FAIL — Pause/Meditate/Resume nodes not present.

- [ ] **Step 3: Implement `ActionButtonRow` + `CircularActionButton` + `MicActionButton`**

In `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`, add the `ActionButtonRow(...)` call inside `ExpandedContent`'s outermost `Column` (after the chip Row). Then append the new composables:

```kotlin
@Composable
private fun ActionButtonRow(
    walkState: WalkState,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        // Slot 1: Pause / Resume / disabled-Pause
        when (walkState) {
            is WalkState.Active -> CircularActionButton(
                label = stringResource(R.string.walk_action_pause),
                icon = androidx.compose.material.icons.Icons.Filled.Pause,
                color = pilgrimColors.stone,
                onClick = onPause,
                modifier = Modifier.weight(1f),
            )
            is WalkState.Paused -> CircularActionButton(
                label = stringResource(R.string.walk_action_resume),
                icon = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                color = pilgrimColors.stone,
                onClick = onResume,
                modifier = Modifier.weight(1f),
            )
            else -> CircularActionButton(
                label = stringResource(R.string.walk_action_pause),
                icon = androidx.compose.material.icons.Icons.Filled.Pause,
                color = pilgrimColors.fog,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        // Slot 2: Meditate / End-meditation / disabled-Meditate
        when (walkState) {
            is WalkState.Active -> CircularActionButton(
                label = stringResource(R.string.walk_action_meditate_short),
                icon = androidx.compose.material.icons.Icons.Outlined.SelfImprovement,
                color = pilgrimColors.dawn,
                onClick = onStartMeditation,
                modifier = Modifier.weight(1f),
            )
            is WalkState.Meditating -> CircularActionButton(
                label = stringResource(R.string.walk_action_end_meditation_short),
                icon = androidx.compose.material.icons.Icons.Filled.Stop,
                color = pilgrimColors.dawn,
                onClick = onEndMeditation,
                modifier = Modifier.weight(1f),
            )
            else -> CircularActionButton(
                label = stringResource(R.string.walk_action_meditate_short),
                icon = androidx.compose.material.icons.Icons.Outlined.SelfImprovement,
                color = pilgrimColors.fog,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        // Slot 3: Mic
        MicActionButton(
            recorderState = recorderState,
            audioLevel = audioLevel,
            walkState = walkState,
            onToggle = onToggleRecording,
            onPermissionDenied = onPermissionDenied,
            onDismissError = onDismissError,
            modifier = Modifier.weight(1f),
        )
        // Slot 4: Finish
        CircularActionButton(
            label = stringResource(R.string.walk_action_finish_short),
            icon = androidx.compose.material.icons.Icons.Filled.Stop,
            color = pilgrimColors.fog,
            onClick = onFinish,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CircularActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val effectiveColor = if (enabled) color else pilgrimColors.fog.copy(alpha = 0.4f)
    Column(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = effectiveColor.copy(alpha = 0.06f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .border(
                    1.5.dp,
                    effectiveColor,
                    androidx.compose.foundation.shape.CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveColor,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(PilgrimSpacing.xs))
        androidx.compose.material3.Text(
            text = label,
            style = pilgrimType.caption,
            color = effectiveColor,
            maxLines = 1,
        )
    }
}

/**
 * Circular variant of the existing [RecordControl]. Reuses the same
 * permission launcher + error-banner protocol; visually framed as a
 * circular pill matching the other action buttons in the row.
 */
@Composable
private fun MicActionButton(
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    walkState: WalkState,
    onToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val enabled = walkState is WalkState.Active || walkState is WalkState.Paused
    val isRecording = recorderState is VoiceRecorderUiState.Recording

    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onToggle() else onPermissionDenied()
    }

    val err = recorderState as? VoiceRecorderUiState.Error
    androidx.compose.runtime.LaunchedEffect(err) {
        if (err != null && err.kind != VoiceRecorderUiState.Kind.Cancelled) {
            kotlinx.coroutines.delay(4_000L)
            onDismissError()
        }
    }

    val color = if (isRecording) pilgrimColors.rust else pilgrimColors.stone
    val effectiveColor = if (enabled) color else pilgrimColors.fog.copy(alpha = 0.4f)

    Column(
        modifier = modifier.clickable(enabled = enabled) {
            if (isRecording ||
                org.walktalkmeditate.pilgrim.permissions.PermissionChecks
                    .isMicrophoneGranted(context)
            ) {
                onToggle()
            } else {
                permLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = effectiveColor.copy(alpha = if (isRecording) 0.18f else 0.06f),
                    shape = androidx.compose.foundation.shape.CircleShape,
                )
                .border(
                    1.5.dp,
                    effectiveColor,
                    androidx.compose.foundation.shape.CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                imageVector = if (isRecording) {
                    androidx.compose.material.icons.Icons.Filled.Stop
                } else {
                    androidx.compose.material.icons.Icons.Filled.Mic
                },
                contentDescription = null,
                tint = effectiveColor,
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(PilgrimSpacing.xs))
        androidx.compose.material3.Text(
            text = if (isRecording) "REC" else "Record",
            style = pilgrimType.caption,
            color = effectiveColor,
            maxLines = 1,
        )
    }
}
```

Verify these icons exist in `androidx.compose.material.icons.Icons.Filled` / `.Outlined` — `Pause`, `PlayArrow`, `Stop`, `Mic`, `SelfImprovement`. (`SelfImprovement` requires `androidx.compose.material:material-icons-extended` — confirm it's already on the classpath via `gradle/libs.versions.toml`. If not, add the dep + bump cache. Otherwise pick a stand-in like `Icons.Outlined.Spa` or use a vector drawable from `res/drawable/`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetActionRowTest"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetActionRowTest.kt
git commit -m "feat(walk): action button row — circular pills with disabled placeholders"
```

---

### Task 11: Drag gesture handling

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetDragGestureTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetDragGestureTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import androidx.compose.ui.Modifier

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkStatsSheetDragGestureTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `swipe up from minimized expands the sheet`() {
        var state by mutableStateOf(SheetState.Minimized)
        composeRule.setContent {
            WalkStatsSheet(
                state = state,
                onStateChange = { state = it },
                walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
                totalElapsedMillis = 0L, distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
                modifier = Modifier.testTag("walk-sheet"),
            )
        }
        composeRule.onNodeWithTag("walk-sheet").performTouchInput {
            swipeUp(durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertEquals(SheetState.Expanded, state)
    }

    @Test
    fun `swipe down from expanded collapses the sheet`() {
        var state by mutableStateOf(SheetState.Expanded)
        composeRule.setContent {
            WalkStatsSheet(
                state = state,
                onStateChange = { state = it },
                walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
                totalElapsedMillis = 0L, distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
                modifier = Modifier.testTag("walk-sheet"),
            )
        }
        composeRule.onNodeWithTag("walk-sheet").performTouchInput {
            swipeDown(durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertEquals(SheetState.Minimized, state)
    }

    @Test
    fun `drag does not transition when canDrag is false`() {
        var state by mutableStateOf(SheetState.Minimized)
        composeRule.setContent {
            WalkStatsSheet(
                state = state,
                onStateChange = { state = it },
                // Paused (NOT Active) → canDrag false
                walkState = WalkState.Paused(WalkAccumulator(1L, 0L), pausedAt = 0L),
                totalElapsedMillis = 0L, distanceMeters = 0.0,
                walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
                recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
                recordingsCount = 0,
                onPause = {}, onResume = {},
                onStartMeditation = {}, onEndMeditation = {},
                onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
                onFinish = {},
                modifier = Modifier.testTag("walk-sheet"),
            )
        }
        composeRule.onNodeWithTag("walk-sheet").performTouchInput {
            swipeUp(durationMillis = 150)
        }
        composeRule.waitForIdle()
        assertEquals(SheetState.Minimized, state)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetDragGestureTest"`
Expected: FAIL — sheet doesn't transition on swipe; no draggable wired up yet.

- [ ] **Step 3: Add drag gesture to `WalkStatsSheet`**

In `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`, replace the body of `WalkStatsSheet` (the outer `Box(modifier = ...)`) with the gesture-enabled version:

```kotlin
@Composable
fun WalkStatsSheet(
    state: SheetState,
    onStateChange: (SheetState) -> Unit,
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canDrag = walkState is WalkState.Active
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = remember(density) { with(density) { 40.dp.toPx() } }
    val flickPx = remember(density) { with(density) { 300.dp.toPx() } }
    val clampPx = remember(density) { with(density) { 100.dp.toPx() } }

    // mutableFloatStateOf avoids autoboxing on every drag tick.
    var dragOffset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }

    // rememberUpdatedState wraps state, canDrag, onStateChange so the
    // lambdas captured by rememberDraggableState read the LATEST values
    // even if the parent recomposes with new ones.
    val currentState by androidx.compose.runtime.rememberUpdatedState(state)
    val currentCanDrag by androidx.compose.runtime.rememberUpdatedState(canDrag)
    val currentOnStateChange by androidx.compose.runtime.rememberUpdatedState(onStateChange)

    val draggableState = androidx.compose.foundation.gestures
        .rememberDraggableState { delta ->
            if (!currentCanDrag) return@rememberDraggableState
            val proposed = (dragOffset + delta).coerceIn(-clampPx, clampPx)
            dragOffset = when {
                currentState == SheetState.Minimized && delta < 0 -> proposed
                currentState == SheetState.Expanded && delta > 0 -> proposed
                else -> dragOffset
            }
        }

    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .androidx.compose.ui.graphics.graphicsLayer { translationY = dragOffset }
            .androidx.compose.foundation.gestures.draggable(
                state = draggableState,
                orientation = androidx.compose.foundation.gestures.Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (!currentCanDrag) {
                        dragOffset = 0f
                        return@draggable
                    }
                    val shouldExpand = currentState == SheetState.Minimized &&
                        (dragOffset < -thresholdPx || velocity < -flickPx)
                    val shouldCollapse = currentState == SheetState.Expanded &&
                        (dragOffset > thresholdPx || velocity > flickPx)
                    when {
                        shouldExpand -> {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            currentOnStateChange(SheetState.Expanded)
                        }
                        shouldCollapse -> {
                            haptic.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            currentOnStateChange(SheetState.Minimized)
                        }
                    }
                    dragOffset = 0f
                },
            ),
    ) {
        // Upward shadow + body Column unchanged from Task 8.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(pilgrimColors.parchment)
                .navigationBarsPadding()
                .padding(bottom = PilgrimSpacing.normal),
        ) {
            DragHandle(canDrag = canDrag)
            SheetContentSwitcher(
                state = state,
                minimizedContent = {
                    MinimizedContent(
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        onTap = { onStateChange(SheetState.Expanded) },
                    )
                },
                expandedContent = {
                    ExpandedContent(
                        walkState = walkState,
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        walkMillis = walkMillis,
                        talkMillis = talkMillis,
                        meditateMillis = meditateMillis,
                        recorderState = recorderState,
                        audioLevel = audioLevel,
                        recordingsCount = recordingsCount,
                        onPause = onPause,
                        onResume = onResume,
                        onStartMeditation = onStartMeditation,
                        onEndMeditation = onEndMeditation,
                        onToggleRecording = onToggleRecording,
                        onPermissionDenied = onPermissionDenied,
                        onDismissError = onDismissError,
                        onFinish = onFinish,
                    )
                },
            )
            ActionButtonRow(
                walkState = walkState,
                recorderState = recorderState,
                audioLevel = audioLevel,
                recordingsCount = recordingsCount,
                onPause = onPause,
                onResume = onResume,
                onStartMeditation = onStartMeditation,
                onEndMeditation = onEndMeditation,
                onToggleRecording = onToggleRecording,
                onPermissionDenied = onPermissionDenied,
                onDismissError = onDismissError,
                onFinish = onFinish,
            )
        }
    }
}
```

(NOTE: the inline-namespaced calls like `.androidx.compose.ui.graphics.graphicsLayer` won't compile as written — fix by adding proper imports at the top:

```kotlin
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
```

— then replace the inline-namespaced sites with the bare names.)

Also: `ActionButtonRow` is currently inside `ExpandedContent` per Task 10. Either keep it there (preferred — it shouldn't render at all when the sheet is collapsed) and remove the duplicate call I just inlined into `WalkStatsSheet`, OR move it out of `ExpandedContent` to align with the layered behavior. Pick ONE location and keep it consistent (recommend leaving it inside `ExpandedContent` so the disabled-state buttons aren't double-rendered).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetDragGestureTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run prior sheet tests to verify nothing regressed**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheet*"`
Expected: All sheet tests PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetDragGestureTest.kt
git commit -m "feat(walk): drag gesture handling on WalkStatsSheet (40dp / 300dp/s flick)"
```

---

### Task 12: Saved-state preservation

**Files:**
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetSavedStateTest.kt` (new)
- (Implementation lives in ActiveWalkScreen; this verifies the `rememberSaveable { mutableStateOf(SheetState) }` Serializable-saver path works.)

- [ ] **Step 1: Write the test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetSavedStateTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState
import androidx.compose.material3.Text

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkStatsSheetSavedStateTest {

    @get:Rule val composeRule = createComposeRule()

    @Composable
    private fun Harness() {
        var state by rememberSaveable { mutableStateOf(SheetState.Expanded) }
        Text(text = "marker:${state.name}")
        WalkStatsSheet(
            state = state,
            onStateChange = { state = it },
            walkState = WalkState.Active(WalkAccumulator(1L, 0L)),
            totalElapsedMillis = 0L, distanceMeters = 0.0,
            walkMillis = 0L, talkMillis = 0L, meditateMillis = 0L,
            recorderState = VoiceRecorderUiState.Idle, audioLevel = 0f,
            recordingsCount = 0,
            onPause = {}, onResume = {},
            onStartMeditation = {}, onEndMeditation = {},
            onToggleRecording = {}, onPermissionDenied = {}, onDismissError = {},
            onFinish = {},
        )
    }

    @Test
    fun `sheet state survives recreation`() {
        val tester = StateRestorationTester(composeRule)
        tester.setContent { Harness() }
        composeRule.onNodeWithText("marker:Expanded").assertExists()
        // Drag-collapse via test affordance — call onStateChange directly
        // by tapping the minimized variant's "Time" text. Easier: just
        // simulate a swipe down.
        composeRule.onNodeWithText("marker:Expanded").performClick() // no-op; visible state unchanged
        // To exercise saved state, mutate via swipe:
        composeRule.onNode(androidx.compose.ui.test.hasTestTag("walk-sheet"))
            .performTouchInput { swipeDown(durationMillis = 150) }
        composeRule.onNodeWithText("marker:Minimized").assertExists()
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("marker:Minimized").assertExists()
    }
}
```

(Imports: `import androidx.compose.ui.test.performTouchInput`, `import androidx.compose.ui.test.swipeDown`. The Harness needs to add `Modifier.testTag("walk-sheet")` for the swipe target — pass it through if the production callsite doesn't already.)

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheetSavedStateTest"`
Expected: PASS — Kotlin enums are Serializable; rememberSaveable's bundle-saver handles them automatically.

If the test fails because the marker shows Expanded after restore, the `SheetState` enum needs an explicit Saver. (It shouldn't — but if so, add `import java.io.Serializable` to the enum file as a sanity check; the Kotlin enum already implements it.)

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheetSavedStateTest.kt
git commit -m "test(walk): assert SheetState survives StateRestorationTester recreation"
```

---

### Task 13: Rewrite `ActiveWalkScreen` to use the new layout

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt`

- [ ] **Step 1: Rewrite `ActiveWalkScreen`**

Replace the entire body of `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt` with:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.WalkStats
import org.walktalkmeditate.pilgrim.domain.isInProgress

private val SHEET_HEIGHT_DP = 340.dp

@Composable
fun ActiveWalkScreen(
    onFinished: (walkId: Long) -> Unit,
    onEnterMeditation: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // Navigation observer reads the passthrough flow, NOT uiState's
    // WhileSubscribed(5s) cache. Stage 5G stale-cache trap; see
    // WalkViewModel.walkState kdoc.
    val navWalkState by viewModel.walkState.collectAsStateWithLifecycle()
    val routePoints by viewModel.routePoints.collectAsStateWithLifecycle()
    val recorderState by viewModel.voiceRecorderState.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val recordingsCount by viewModel.recordingsCount.collectAsStateWithLifecycle()
    val talkMillis by viewModel.talkMillis.collectAsStateWithLifecycle()
    val initialCameraCenter by viewModel.initialCameraCenter.collectAsStateWithLifecycle()
    val meditateMillis = WalkStats.totalMeditatedMillis(ui.walkState, ui.nowMillis)

    val context = LocalContext.current
    BackHandler(enabled = ui.walkState.isInProgress) {
        (context as? Activity)?.moveTaskToBack(true)
    }

    LaunchedEffect(navWalkState::class) {
        when (val state = navWalkState) {
            is WalkState.Finished -> onFinished(state.walk.walkId)
            is WalkState.Meditating -> onEnterMeditation()
            else -> Unit
        }
    }

    var sheetState by rememberSaveable { mutableStateOf(SheetState.Expanded) }
    // Drive sheet auto-state from the PASSTHROUGH walkState so we don't
    // act on a stale uiState during the brief window after returning
    // from MeditationScreen (Stage 5G stale-cache trap, generalized).
    SheetStateController(
        walkState = navWalkState,
        onUpdateState = { sheetState = it },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        PilgrimMap(
            points = routePoints,
            followLatest = true,
            initialCenter = initialCameraCenter,
            // Sheet height is constant for 9.5-B (see WalkStatsSheet
            // kdoc). Map's bottom-inset uses that constant so the user
            // puck stays visible above the sheet in BOTH detents.
            bottomInsetDp = SHEET_HEIGHT_DP,
            modifier = Modifier.fillMaxSize(),
        )
        WalkStatsSheet(
            state = sheetState,
            onStateChange = { sheetState = it },
            walkState = ui.walkState,
            totalElapsedMillis = ui.totalElapsedMillis,
            distanceMeters = ui.distanceMeters,
            walkMillis = ui.activeWalkingMillis,
            talkMillis = talkMillis,
            meditateMillis = meditateMillis,
            recorderState = recorderState,
            audioLevel = audioLevel,
            recordingsCount = recordingsCount,
            onPause = viewModel::pauseWalk,
            onResume = viewModel::resumeWalk,
            onStartMeditation = viewModel::startMeditation,
            onEndMeditation = viewModel::endMeditation,
            onToggleRecording = viewModel::toggleRecording,
            onPermissionDenied = viewModel::emitPermissionDenied,
            onDismissError = viewModel::dismissRecorderError,
            onFinish = viewModel::finishWalk,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}
```

- [ ] **Step 2: Verify the build compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run all walk-screen tests to confirm no regressions**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.*"`
Expected: All PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt
git commit -m "feat(walk): rewrite ActiveWalkScreen — full-screen map + bottom sheet"
```

---

### Task 14: Delete `RecordControl.kt` (superseded)

**Files:**
- Delete: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/RecordControl.kt`

- [ ] **Step 1: Verify no remaining callers**

Run: `grep -rn "RecordControl\|recordControl" app/src/ --include='*.kt'`
Expected: NO matches in main sources (test files referencing the symbol are also fair to delete; check for any).

- [ ] **Step 2: Delete the file**

Run: `git rm app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/RecordControl.kt`

- [ ] **Step 3: Confirm build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(walk): delete RecordControl — superseded by MicActionButton"
```

---

### Task 15: Full test + lint sweep + manual on-device verification

**Files:** none new.

- [ ] **Step 1: Full unit-test sweep**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Lint**

Run: `./gradlew :app:lintDebug`
Expected: No new errors. New warnings reviewed and addressed if relevant.

- [ ] **Step 3: Build a debug APK + sideload to OnePlus 13**

Run:
```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p org.walktalkmeditate.pilgrim.debug -c android.intent.category.LAUNCHER 1
```

Walk through the manual verification list from spec §Verification:
1. Launch from Path tab → ACTIVE_WALK opens. Map fills entire screen. Sheet visible at bottom in Expanded state.
2. After ~5 s recording, sheet auto-collapses to Minimized.
3. Drag handle up → expands.
4. Drag down from expanded → collapses.
5. Tap Pause → sheet auto-expands after 800 ms; Resume replaces Pause; Meditate slot greys out.
6. Resume → sheet auto-collapses.
7. Tap Meditate → MeditationScreen opens; return → sheet visible.
8. Tap Mic → recording starts; mic outline thickens; Talk chip activates.
9. Walk-time chip increments while Active; pauses while Paused; pauses while Meditating.
10. Distance updates as user walks. Steps + Ascent stay at "—".
11. User puck stays visible above expanded sheet (bottomInsetDp wired).
12. Tap End → finishWalk → nav to walkSummary.
13. System Back → moveTaskToBack.
14. Rotate device → SheetState preserved.
15. Reduced-motion: sheet drag still works.
16. GPS-flap test: Pause then immediately Resume — sheet should NOT visibly expand (debounce cancels).

Document any failures in a follow-up task; do NOT mark this task complete until all items pass.

- [ ] **Step 4: Commit any device-QA tweaks (likely none)**

If small tweaks were needed (e.g., shadow opacity feels wrong, sheet height tuning), commit with `chore(walk): Stage 9.5-B device-QA polish`.

---

## Self-Review

**Spec coverage check:**
- §1 WalkStats.totalMeditatedMillis → Task 1 ✓
- §2 voiceRecordings + talkMillis + recordingsCount refactor → Task 5 ✓
- §3 SheetState enum → Task 4 ✓
- §4 ActiveWalkScreen rewrite → Task 13 ✓
- §5 SheetStateController → Task 7 ✓
- §6 WalkStatsSheet (drag handle, minimized, expanded, action row, mic, circular button, switcher, shadow) → Tasks 8, 9, 10, 11 ✓
- §7 string resources → Task 3 ✓
- §8 WalkFormat.shortDuration → Task 2 ✓
- §9 PilgrimMap.bottomInsetDp → Task 6 ✓
- §11 tests → Tasks 1-12 each include their test ✓
- §12 out-of-scope → no tasks (correct) ✓
- Verification → Task 15 ✓
- Files (delete RecordControl) → Task 14 ✓

**Placeholder scan:** No "TBD", no "implement later", no "similar to Task N" without code. The Mic icon dependency (`Icons.Outlined.SelfImprovement` requires `material-icons-extended`) has an explicit fallback note in Task 10 Step 3 — verify before assuming it's on the classpath.

**Type consistency:** `WalkStatsSheet` callsite signature in Task 8 matches Tasks 9, 10, 11, 13 (same parameter list, same names, same types). `SheetState` is a plain enum — used identically in all tasks. `WalkFormat.shortDuration(Long): String` — only one signature throughout.

**Known plan caveats** (worth flagging to the executor):
- Task 5's test sketch references a `fakeRepo.observeVoiceRecordingsCallCount` field that may not exist in the existing fake — replicate from `WalkViewModelTest`'s harness or add a counter to the local fake.
- Task 11's pseudo-code uses inline-namespaced calls for clarity; replace with proper imports during implementation.
- Task 12's `StateRestorationTester` test depends on the production-side `Modifier.testTag("walk-sheet")` being set in Task 11 — verify before running.
- Task 10 needs to verify `material-icons-extended` is available before importing `Icons.Outlined.SelfImprovement`. If not present, swap to a vector drawable from `res/drawable/`.

---

**Plan complete and saved to `docs/superpowers/plans/2026-04-26-stage-9-5-b-active-walk-layout.md`.**

Per autopilot Phase 4, this plan will be executed via subagent-driven-development: fresh subagent per task with two-stage review (spec coverage + code quality) between tasks.
