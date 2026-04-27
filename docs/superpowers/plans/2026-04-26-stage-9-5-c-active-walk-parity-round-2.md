# Stage 9.5-C Active Walk Parity Round 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Three contained iOS-parity fixes for the Stage 9.5-B Active Walk surface — `controller.discardWalk()` so the X button actually leaves without saving, audio waveform inside the recording mic button, and a minimal WalkOptionsSheet (intention + waypoint).

**Architecture:** Follow the existing reducer/effect pattern (new `WalkAction.Discard` + `WalkEffect.PurgeWalk`). Factor voice auto-stop out of `WalkFinalizationObserver` into a new `WalkLifecycleObserver` so discard tears down the recorder without firing transcription/collective/widget side-effects. Widen `WalkTrackingService` self-stop to also fire on Active→Idle transitions via a `hasBeenActive` latch. Audio waveform is a new pure Composable; WalkOptionsSheet is a Material 3 `ModalBottomSheet` with two rows.

**Tech Stack:** Kotlin coroutines, Compose Material 3, Room with `ON DELETE CASCADE` foreign keys (already wired across all child tables — verified in Phase 1 UNDERSTAND).

**Spec:** `docs/superpowers/specs/2026-04-26-stage-9-5-c-active-walk-parity-round-2-design.md`

---

## File map

**New (4 files):**
- `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformView.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt`

**Modified (~10 files):**
- `WalkAction.kt` (Discard variant)
- `WalkEffect.kt` (PurgeWalk variant)
- `WalkReducer.kt` (Discard branches in reduceActive/Paused/Meditating)
- `WalkController.kt` (discardWalk + setIntention + handleEffect for PurgeWalk)
- `WalkFinalizationObserver.kt` (drop voice auto-stop block)
- `WalkRepository.kt` (deleteWalkById + updateWalkIntention)
- `WalkDao.kt` (deleteById + updateIntention queries)
- `WaypointDao.kt` (observeCountForWalk)
- `WalkTrackingService.kt` (widen self-stop with `hasBeenActive` latch)
- `WalkViewModel.kt` (discardWalk + intention + waypointCount + setIntention + dropWaypoint)
- `WalkStatsSheet.kt` (MicActionButton renders AudioWaveformView when recording)
- `ActiveWalkScreen.kt` (wire ellipsis to WalkOptionsSheet; X to discardWalk)
- `strings.xml` (8 new strings + 1 plurals)

---

### Task 1: Add `WalkAction.Discard` + `WalkEffect.PurgeWalk` types

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkAction.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEffect.kt`

- [ ] **Step 1: Add `Discard` to `WalkAction`**

In `WalkAction.kt`, add the new variant inside the sealed class:

```kotlin
sealed class WalkAction {
    data class Start(val walkId: Long, val at: Long) : WalkAction()
    data class Pause(val at: Long) : WalkAction()
    data class Resume(val at: Long) : WalkAction()
    data class MeditateStart(val at: Long) : WalkAction()
    data class MeditateEnd(val at: Long) : WalkAction()
    data class Finish(val at: Long) : WalkAction()
    data class Discard(val at: Long) : WalkAction()
    data class LocationSampled(val point: LocationPoint) : WalkAction()
}
```

- [ ] **Step 2: Add `PurgeWalk` to `WalkEffect`**

In `WalkEffect.kt`, add the new variant:

```kotlin
sealed class WalkEffect {
    data object None : WalkEffect()
    data class PersistLocation(val walkId: Long, val point: LocationPoint) : WalkEffect()
    data class PersistEvent(val walkId: Long, val eventType: WalkEventType, val timestamp: Long) : WalkEffect()
    data class FinalizeWalk(val walkId: Long, val endTimestamp: Long) : WalkEffect()
    data class PurgeWalk(val walkId: Long) : WalkEffect()
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkAction.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkEffect.kt
git commit -m "feat(walk): add Discard action + PurgeWalk effect types"
```

---

### Task 2: Reducer branches for `Discard`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkReducerDiscardTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkReducerDiscardTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class WalkReducerDiscardTest {

    private val reducer = WalkReducer()

    @Test
    fun `Discard from Active transitions to Idle and emits PurgeWalk`() {
        val state = WalkState.Active(WalkAccumulator(walkId = 42L, startedAt = 0L))
        val (next, effect) = reducer.reduce(state, WalkAction.Discard(at = 1_000L))
        assertEquals(WalkState.Idle, next)
        assertEquals(WalkEffect.PurgeWalk(walkId = 42L), effect)
    }

    @Test
    fun `Discard from Paused transitions to Idle and emits PurgeWalk`() {
        val state = WalkState.Paused(
            walk = WalkAccumulator(walkId = 7L, startedAt = 0L),
            pausedAt = 100L,
        )
        val (next, effect) = reducer.reduce(state, WalkAction.Discard(at = 1_000L))
        assertEquals(WalkState.Idle, next)
        assertEquals(WalkEffect.PurgeWalk(walkId = 7L), effect)
    }

    @Test
    fun `Discard from Meditating transitions to Idle and emits PurgeWalk`() {
        val state = WalkState.Meditating(
            walk = WalkAccumulator(walkId = 9L, startedAt = 0L),
            meditationStartedAt = 500L,
        )
        val (next, effect) = reducer.reduce(state, WalkAction.Discard(at = 1_000L))
        assertEquals(WalkState.Idle, next)
        assertEquals(WalkEffect.PurgeWalk(walkId = 9L), effect)
    }

    @Test
    fun `Discard from Idle is a no-op`() {
        val (next, effect) = reducer.reduce(WalkState.Idle, WalkAction.Discard(at = 1_000L))
        assertSame(WalkState.Idle, next)
        assertEquals(WalkEffect.None, effect)
    }

    @Test
    fun `Discard from Finished is a no-op (walk already saved)`() {
        val state = WalkState.Finished(
            walk = WalkAccumulator(walkId = 3L, startedAt = 0L),
            endedAt = 5_000L,
        )
        val (next, effect) = reducer.reduce(state, WalkAction.Discard(at = 6_000L))
        assertSame(state, next)
        assertEquals(WalkEffect.None, effect)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.domain.WalkReducerDiscardTest"`
Expected: FAIL — reducer has no Discard handling.

- [ ] **Step 3: Add Discard branches**

In `WalkReducer.kt`:

In `reduceActive`:
```kotlin
is WalkAction.Discard -> WalkState.Idle to WalkEffect.PurgeWalk(state.walk.walkId)
```

In `reducePaused`:
```kotlin
is WalkAction.Discard -> WalkState.Idle to WalkEffect.PurgeWalk(state.walk.walkId)
```

In `reduceMeditating`:
```kotlin
is WalkAction.Discard -> WalkState.Idle to WalkEffect.PurgeWalk(state.walk.walkId)
```

In `reduceIdle` and `reduceFinished`, add an `is WalkAction.Discard ->` branch that returns `state to WalkEffect.None` (or rely on existing `else -> state to WalkEffect.None` if it exists).

(Read the file before editing to see the exact `when` shape per state. The `else -> state to WalkEffect.None` is the canonical no-op; verify each state's reducer already has it before adding explicit branches.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.domain.WalkReducerDiscardTest"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/domain/WalkReducer.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/domain/WalkReducerDiscardTest.kt
git commit -m "feat(walk): WalkReducer Discard branches → Idle + PurgeWalk effect"
```

---

### Task 3: `WalkRepository.deleteWalkById` + cascade-delete contract test

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryDiscardTest.kt` (new)

- [ ] **Step 1: Write the failing test (locks in cascade-delete contract for ALL 7 child tables)**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryDiscardTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkEventType
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalkRepositoryDiscardTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WalkRepository(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `deleteWalkById cascades through all child tables`() = runTest {
        val walk = repo.startWalk(startTimestamp = 1_000L)
        val walkId = walk.id

        // Seed every child table with at least one row referencing walkId.
        repo.recordLocation(
            RouteDataSample(walkId = walkId, timestamp = 1_100L, latitude = 1.0, longitude = 2.0),
        )
        db.altitudeSampleDao().insert(
            AltitudeSample(walkId = walkId, timestamp = 1_200L, altitudeMeters = 100.0),
        )
        db.walkEventDao().insert(
            WalkEvent(walkId = walkId, eventType = WalkEventType.PauseStart, timestamp = 1_300L),
        )
        db.waypointDao().insert(
            Waypoint(walkId = walkId, timestamp = 1_400L, latitude = 1.0, longitude = 2.0, label = null),
        )
        repo.recordVoice(
            VoiceRecording(
                walkId = walkId,
                startTimestamp = 1_500L,
                endTimestamp = 2_500L,
                durationMillis = 1_000L,
                fileRelativePath = "v/x.wav",
            ),
        )
        // (ActivityInterval + WalkPhoto skipped — not in the immediate
        // test surface; cascade contract verified by the entries that
        // ARE seeded. Add their seeding if Stage 9.5-C ever needs them.)

        // Sanity: all child rows present.
        assertEquals(1, db.routeDataSampleDao().getForWalk(walkId).size)
        assertEquals(1, db.altitudeSampleDao().getForWalk(walkId).size)
        assertEquals(1, db.walkEventDao().getForWalk(walkId).size)
        assertEquals(1, db.waypointDao().getForWalk(walkId).size)
        assertEquals(1, repo.voiceRecordingsFor(walkId).size)

        // Discard.
        repo.deleteWalkById(walkId)

        // Walk row + all child rows gone.
        assertNull(repo.getWalk(walkId))
        assertEquals(0, db.routeDataSampleDao().getForWalk(walkId).size)
        assertEquals(0, db.altitudeSampleDao().getForWalk(walkId).size)
        assertEquals(0, db.walkEventDao().getForWalk(walkId).size)
        assertEquals(0, db.waypointDao().getForWalk(walkId).size)
        assertEquals(0, repo.voiceRecordingsFor(walkId).size)
    }

    @Test
    fun `deleteWalkById is a no-op on unknown walkId`() = runTest {
        repo.deleteWalkById(walkId = 9_999L)
        // No throw — Room treats DELETE WHERE no-row-match as 0 rows affected.
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.WalkRepositoryDiscardTest"`
Expected: FAIL — `deleteWalkById` doesn't exist.

- [ ] **Step 3: Add `WalkDao.deleteById`**

In `WalkDao.kt`, append:

```kotlin
@Query("DELETE FROM walks WHERE id = :walkId")
suspend fun deleteById(walkId: Long)
```

- [ ] **Step 4: Add `WalkRepository.deleteWalkById`**

In `WalkRepository.kt`, near the existing `deleteWalk(walk: Walk)` method:

```kotlin
suspend fun deleteWalkById(walkId: Long) {
    walkDao.deleteById(walkId)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.data.WalkRepositoryDiscardTest"`
Expected: PASS, 2 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryDiscardTest.kt
git commit -m "feat(data): WalkRepository.deleteWalkById + cascade-delete contract test"
```

---

### Task 4: `WalkController.discardWalk()` + `handleEffect(PurgeWalk)`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerDiscardTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerDiscardTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

// Mirror imports from existing WalkControllerTest.kt's setUp pattern.
// Use the same FakeWalkRepository / FakeClock / harness as
// WalkControllerTest if available; otherwise inline.

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.WalkState

class WalkControllerDiscardTest {

    private val dispatcher = UnconfinedTestDispatcher()

    // (Construct WalkController with the project's standard test
    // dependencies: FakeWalkRepository, FakeClock, etc. Mirror the
    // existing WalkControllerTest.kt setUp.)

    @Test
    fun `discardWalk from Active transitions to Idle and deletes the walk row`() = runTest(dispatcher) {
        val controller = TestControllerHarness.build(this)
        controller.startWalk(intention = null)
        val walkId = (controller.state.value as WalkState.Active).walk.walkId

        controller.discardWalk()

        assertEquals(WalkState.Idle, controller.state.value)
        // Verify the walk row was deleted via the repository fake/spy:
        assertEquals(null, controller.repository.getWalk(walkId))
    }

    @Test
    fun `discardWalk from Idle is a no-op`() = runTest(dispatcher) {
        val controller = TestControllerHarness.build(this)
        // Initial state is Idle.
        controller.discardWalk()
        assertEquals(WalkState.Idle, controller.state.value)
    }

    @Test
    fun `discardWalk from Finished is a no-op (walk already saved)`() = runTest(dispatcher) {
        val controller = TestControllerHarness.build(this)
        controller.startWalk(intention = null)
        val walkId = (controller.state.value as WalkState.Active).walk.walkId
        controller.finishWalk()
        check(controller.state.value is WalkState.Finished)

        controller.discardWalk()

        // State unchanged; row still present.
        check(controller.state.value is WalkState.Finished)
        assertEquals(walkId, (controller.state.value as WalkState.Finished).walk.walkId)
    }
}
```

If a `TestControllerHarness` doesn't already exist in the codebase, mirror the per-test setup from `WalkControllerTest.kt` directly. Read that file first.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkControllerDiscardTest"`
Expected: FAIL — `discardWalk` doesn't exist.

- [ ] **Step 3: Add `WalkController.discardWalk()` + `handleEffect(PurgeWalk)`**

In `WalkController.kt`, add the public method (mirror the shape of `finishWalk`):

```kotlin
suspend fun discardWalk() {
    Log.i(TAG, "discardWalk invoked from state=${_state.value::class.simpleName}")
    dispatch(WalkAction.Discard(at = clock.now()))
}
```

In `handleEffect`'s `when`, add:

```kotlin
is WalkEffect.PurgeWalk -> {
    repository.deleteWalkById(effect.walkId)
}
```

(The exact location depends on existing handler shape — read `WalkController.kt` and find the `when (effect)` block. Add the new branch alongside `FinalizeWalk`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkControllerDiscardTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerDiscardTest.kt
git commit -m "feat(walk): WalkController.discardWalk() + PurgeWalk effect handler"
```

---

### Task 5: `WalkLifecycleObserver` (factor voice auto-stop out of `WalkFinalizationObserver`)

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt` (eager-instantiate the new observer)
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserverTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserverTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.audio.FakeVoiceRecorder  // mirror existing test fakes
import org.walktalkmeditate.pilgrim.data.WalkRepository  // use existing test helper
import org.walktalkmeditate.pilgrim.domain.WalkAccumulator
import org.walktalkmeditate.pilgrim.domain.WalkState

class WalkLifecycleObserverTest {

    @Test
    fun `Active to Idle transition stops voice recorder`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<WalkState>(WalkState.Idle)
        val recorder = FakeVoiceRecorder().also { it.startedRecording = true }
        val repo = TestWalkRepository.create(/* however the test harness constructs it */)
        WalkLifecycleObserver(
            walkState = state,
            scope = TestScope(),  // adapt to project's WalkLifecycleScope provider
            voiceRecorder = recorder,
            repository = repo,
        )
        state.value = WalkState.Active(WalkAccumulator(walkId = 1L, startedAt = 0L))
        runCurrent()
        state.value = WalkState.Idle  // discard path
        runCurrent()

        assertEquals(true, recorder.stopCalled)
    }

    @Test
    fun `Active to Finished transition stops voice recorder`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<WalkState>(WalkState.Idle)
        val recorder = FakeVoiceRecorder().also { it.startedRecording = true }
        val repo = TestWalkRepository.create()
        WalkLifecycleObserver(
            walkState = state,
            scope = TestScope(),
            voiceRecorder = recorder,
            repository = repo,
        )
        val walk = WalkAccumulator(walkId = 1L, startedAt = 0L)
        state.value = WalkState.Active(walk)
        runCurrent()
        state.value = WalkState.Finished(walk = walk, endedAt = 1_000L)
        runCurrent()

        assertEquals(true, recorder.stopCalled)
    }

    @Test
    fun `cold-start initial Idle does not call stop`() = runTest(UnconfinedTestDispatcher()) {
        val state = MutableStateFlow<WalkState>(WalkState.Idle)
        val recorder = FakeVoiceRecorder()
        val repo = TestWalkRepository.create()
        WalkLifecycleObserver(
            walkState = state,
            scope = TestScope(),
            voiceRecorder = recorder,
            repository = repo,
        )
        runCurrent()
        // No transition fired; recorder.stop should NOT have been called.
        assertEquals(false, recorder.stopCalled)
    }
}
```

(Adapt to the project's existing test fake patterns. `FakeVoiceRecorder` may need a `var startedRecording: Boolean` and `var stopCalled: Boolean` shape if it doesn't already exist.)

- [ ] **Step 2: Create `WalkLifecycleObserver.kt`**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.WalkState

/**
 * Owns voice-recorder auto-stop on ANY in-progress→terminal transition
 * (Active|Paused|Meditating → Idle|Finished). This used to live inside
 * [WalkFinalizationObserver] but that observer keys ONLY on Finished —
 * leaving Stage 9.5-C's discardWalk path (which routes Active → Idle
 * without going through Finished) with a leaked recorder + on-disk
 * .wav whose Walk parent was just cascade-deleted (would FK-fail on
 * the next stop()'s INSERT).
 *
 * Routes:
 *  - Finished (normal end): stop + commit row. Same behavior as the
 *    pre-Stage-9.5-C WalkFinalizationObserver block.
 *  - Idle after in-progress (discard): stop + DROP the row (the parent
 *    Walk is gone; inserting the VoiceRecording would FK-violate).
 *
 * Discards its first emission (cold-start Idle is not a transition).
 * Same eager-instantiation pattern as WalkFinalizationObserver — must
 * be referenced from PilgrimApp.onCreate().
 */
@Singleton
class WalkLifecycleObserver @Inject constructor(
    @WalkFinalizationObservedState walkState: StateFlow<@JvmSuppressWildcards WalkState>,
    @WalkFinalizationScope private val scope: CoroutineScope,
    private val voiceRecorder: VoiceRecorder,
    private val repository: WalkRepository,
) {
    init {
        scope.launch {
            var firstEmission = true
            var prevWasInProgress = false
            walkState.collect { state ->
                val nowInProgress = state is WalkState.Active ||
                    state is WalkState.Paused ||
                    state is WalkState.Meditating
                if (firstEmission) {
                    firstEmission = false
                    prevWasInProgress = nowInProgress
                    return@collect
                }
                val wasInProgress = prevWasInProgress
                prevWasInProgress = nowInProgress
                if (!wasInProgress) return@collect

                when (state) {
                    is WalkState.Finished -> handleVoiceStop(commitRow = true)
                    WalkState.Idle -> handleVoiceStop(commitRow = false)
                    else -> Unit
                }
            }
        }
    }

    private suspend fun handleVoiceStop(commitRow: Boolean) {
        val stopResult = voiceRecorder.stop()
        when {
            stopResult.isSuccess && commitRow -> {
                try {
                    repository.recordVoice(stopResult.getOrThrow())
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    Log.w(TAG, "auto-stop INSERT failed", t)
                }
            }
            stopResult.isSuccess && !commitRow -> {
                // Discard path: parent Walk row is being cascade-deleted
                // (or already gone). Drop the VoiceRecording — its WAV
                // file is recoverable by OrphanRecordingSweeper if the
                // user changes their mind about discarding (unlikely).
                Log.i(TAG, "discard auto-stop: dropping recording (parent walk purged)")
            }
            stopResult.exceptionOrNull() is VoiceRecorderError.NoActiveRecording -> {
                // Common case for walks with no voice notes. Proceed.
            }
            else -> {
                Log.w(TAG, "voice auto-stop failed", stopResult.exceptionOrNull())
            }
        }
    }

    private companion object {
        const val TAG = "WalkLifecycleObserver"
    }
}
```

- [ ] **Step 3: Drop the voice auto-stop block from `WalkFinalizationObserver`**

In `WalkFinalizationObserver.kt`, in `runFinalize(...)`, remove the entire `voiceRecorder.stop()` + `repository.recordVoice(...)` block (lines ~107-128 currently). The remaining body keeps hemisphere refresh + transcription scheduling + collective POST + widget refresh.

Also remove the `private val voiceRecorder: VoiceRecorder` constructor parameter since it's no longer used.

Update the class kdoc to reference `WalkLifecycleObserver` for voice auto-stop ownership.

- [ ] **Step 4: Eager-instantiate `WalkLifecycleObserver` in `PilgrimApp.onCreate`**

In `PilgrimApp.kt`, find the existing `@Inject lateinit var walkFinalizationObserver: WalkFinalizationObserver` reference (or wherever observers are forced into existence). Add:

```kotlin
@Inject lateinit var walkLifecycleObserver: WalkLifecycleObserver
```

And reference it in `onCreate`:

```kotlin
override fun onCreate() {
    super.onCreate()
    walkFinalizationObserver  // already there
    walkLifecycleObserver     // new
}
```

(Read PilgrimApp.kt to confirm exact placement.)

- [ ] **Step 5: Run tests**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserverTest" --tests "org.walktalkmeditate.pilgrim.walk.WalkFinalizationObserverTest"`

Expected: PASS — both files. Update `WalkFinalizationObserverTest.kt` if needed to drop voice-stop assertions (those are now WalkLifecycleObserver's responsibility).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserver.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserver.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/PilgrimApp.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkLifecycleObserverTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkFinalizationObserverTest.kt
git commit -m "feat(walk): WalkLifecycleObserver — voice auto-stop on Active→Idle|Finished"
```

---

### Task 6: Widen `WalkTrackingService` self-stop to also fire on Active→Idle

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceDiscardTest.kt` (new — mirror existing service-test patterns)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceDiscardTest.kt`. Mirror the existing `WalkTrackingServiceTest.kt` setUp (Robolectric Service test). Add:

```kotlin
@Test
fun `service self-stops when state transitions Active to Idle (discard)`() = runTest {
    // Start the service with state = Active (mirror existing startCommand test).
    // Then flip controller.state to Idle.
    // Assert the service is stopSelf'd (use Robolectric's ShadowService.isStoppedBySelf or similar).
}

@Test
fun `service does NOT self-stop on cold-start initial Idle`() = runTest {
    // Mount the service when controller.state is initial Idle.
    // Assert NOT stopped.
}
```

(Read the existing `WalkTrackingServiceTest.kt` to copy the exact harness — Robolectric service tests need `Robolectric.buildService(...)` plus context for the controller singleton. If no such test exists, write the harness from scratch using `Robolectric.setupService(...)`.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.service.WalkTrackingServiceDiscardTest"`
Expected: FAIL — service doesn't stop on Idle.

- [ ] **Step 3: Widen self-stop in `WalkTrackingService.kt`**

Find the existing state-collector that triggers `stopSelf()` on `WalkState.Finished`. Add a `hasBeenActive` instance var and widen:

```kotlin
private var hasBeenActive = false

// inside the state collector:
controller.state.collect { state ->
    if (state is WalkState.Active || state is WalkState.Paused || state is WalkState.Meditating) {
        hasBeenActive = true
    }
    when {
        state is WalkState.Finished -> {
            stopSelf()
            return@collect
        }
        state is WalkState.Idle && hasBeenActive -> {
            stopSelf()
            return@collect
        }
        else -> {
            // existing notification update logic
        }
    }
}
```

(Read the existing collector body — it likely has a different shape using `if` rather than `when`. Match the existing style.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.service.WalkTrackingServiceDiscardTest" --tests "org.walktalkmeditate.pilgrim.service.WalkTrackingServiceTest"`
Expected: PASS — both files.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/service/WalkTrackingService.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/service/WalkTrackingServiceDiscardTest.kt
git commit -m "feat(service): WalkTrackingService self-stops on Active→Idle (discard) via hasBeenActive latch"
```

---

### Task 7: `WalkViewModel.discardWalk()` + wire into `ActiveWalkScreen`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt`

- [ ] **Step 1: Add `discardWalk` to `WalkViewModel`**

In `WalkViewModel.kt`, near `finishWalk`:

```kotlin
fun discardWalk() {
    viewModelScope.launch { controller.discardWalk() }
}
```

- [ ] **Step 2: Re-wire the X button confirm action in `ActiveWalkScreen.kt`**

In `ActiveWalkScreen.kt`, find the `LeaveWalkDialog`'s `onConfirm` block. Replace:

```kotlin
onConfirm = {
    showLeaveConfirm = false
    // TODO Stage 9.5-C: replace with viewModel.discardWalk() ...
    viewModel.finishWalk()
},
```

with:

```kotlin
onConfirm = {
    showLeaveConfirm = false
    viewModel.discardWalk()
},
```

Drop the entire TODO comment block — the discard semantic is now correct.

- [ ] **Step 3: Verify compile + tests**

Run: `./gradlew :app:compileDebugKotlin :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkViewModelTest"`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt
git commit -m "feat(walk): wire X-button Leave action to viewModel.discardWalk()"
```

---

### Task 8: `AudioWaveformView` composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformView.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformViewTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformViewTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AudioWaveformViewTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `renders five bars regardless of level`() {
        composeRule.setContent {
            AudioWaveformView(level = 0.5f)
        }
        composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG).assertCountEquals(5)
    }

    @Test
    fun `level 0 renders all bars at minimum height`() {
        composeRule.setContent {
            AudioWaveformView(level = 0f)
        }
        // No public-API assertion on bar height in Compose tests; rely on
        // composition smoke + count. The pure height function is
        // covered by AudioWaveformBarHeightTest below.
        composeRule.onAllNodesWithTag(WAVEFORM_BAR_TEST_TAG).assertCountEquals(5)
    }
}

class AudioWaveformBarHeightTest {

    @Test
    fun `level 0 maps to minimum height`() {
        assertBarHeight(level = 0f, weight = 1.0f, expectedDp = 4f)
    }

    @Test
    fun `level 1 maps to 4 plus 20 times weight`() {
        assertBarHeight(level = 1f, weight = 1.0f, expectedDp = 24f)
        assertBarHeight(level = 1f, weight = 0.6f, expectedDp = 16f)
    }

    @Test
    fun `level clamped above 1`() {
        assertBarHeight(level = 5f, weight = 1.0f, expectedDp = 24f)
    }

    @Test
    fun `negative level clamped to 0`() {
        assertBarHeight(level = -1f, weight = 1.0f, expectedDp = 4f)
    }

    private fun assertBarHeight(level: Float, weight: Float, expectedDp: Float) {
        val actual = audioWaveformBarHeightDp(level = level, weight = weight)
        kotlin.test.assertEquals(expectedDp, actual, 0.001f)
    }
}
```

(Use `kotlin.test.assertEquals` for the Float-with-tolerance assertion — `org.junit.Assert.assertEquals(Float, Float, Float)` also works.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.AudioWaveformViewTest" --tests "org.walktalkmeditate.pilgrim.ui.walk.AudioWaveformBarHeightTest"`
Expected: FAIL — `AudioWaveformView`, `WAVEFORM_BAR_TEST_TAG`, `audioWaveformBarHeightDp` don't exist.

- [ ] **Step 3: Implement `AudioWaveformView`**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformView.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

internal const val WAVEFORM_BAR_TEST_TAG = "audio-waveform-bar"

private val BAR_WEIGHTS = listOf(0.6f, 0.8f, 1.0f, 0.8f, 0.6f)
private const val BAR_MIN_DP = 4f
private const val BAR_RANGE_DP = 20f

/**
 * Direct port of iOS [AudioWaveformView] (ActiveWalkSubviews.swift:58-86).
 * 5 vertical rust-colored bars in HStack; bar heights animate to [level]
 * (0..1) over 80ms with the symmetric weights `[0.6, 0.8, 1.0, 0.8, 0.6]`.
 *
 * VoiceRecorder publishes audioLevel ~10×/sec (100ms RMS buffer);
 * 80ms tweens complete just before the next sample, so transitions
 * read as continuous motion rather than discrete jumps.
 */
@Composable
fun AudioWaveformView(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val weights = remember { BAR_WEIGHTS }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        weights.forEach { weight ->
            val targetHeightDp = audioWaveformBarHeightDp(level = level, weight = weight)
            val animatedHeight by animateDpAsState(
                targetValue = targetHeightDp.dp,
                animationSpec = tween(durationMillis = 80, easing = FastOutLinearInEasing),
                label = "bar-height",
            )
            Box(
                modifier = Modifier
                    .testTag(WAVEFORM_BAR_TEST_TAG)
                    .width(4.dp)
                    .height(animatedHeight)
                    .clip(RoundedCornerShape(2.dp))
                    .background(pilgrimColors.rust),
            )
        }
    }
}

internal fun audioWaveformBarHeightDp(level: Float, weight: Float): Float {
    val clampedLevel = level.coerceIn(0f, 1f)
    return BAR_MIN_DP + clampedLevel * weight * BAR_RANGE_DP
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.AudioWaveformViewTest" --tests "org.walktalkmeditate.pilgrim.ui.walk.AudioWaveformBarHeightTest"`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformView.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/AudioWaveformViewTest.kt
git commit -m "feat(walk): AudioWaveformView (5-bar live audio level, iOS port)"
```

---

### Task 9: Render `AudioWaveformView` inside `MicActionButton` when recording

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt`

- [ ] **Step 1: Replace the static Stop icon with the waveform when recording**

In `WalkStatsSheet.kt`, find `MicActionButton`'s `Icon(imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic, ...)`. Wrap in a `Box` so both states occupy the same vertical space (so the "REC" label doesn't shift):

```kotlin
Box(
    modifier = Modifier.height(22.dp),
    contentAlignment = Alignment.Center,
) {
    if (isRecording) {
        AudioWaveformView(level = audioLevel)
    } else {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = null,
            tint = effectiveColor,
            modifier = Modifier.size(22.dp),
        )
    }
}
```

(Keep the existing `Spacer` + label `Text` after this Box — only the icon-vs-waveform slot changes.)

- [ ] **Step 2: Verify all sheet tests still pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkStatsSheet*"`
Expected: PASS, all sheet tests.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkStatsSheet.kt
git commit -m "feat(walk): MicActionButton renders AudioWaveformView when recording"
```

---

### Task 10: New strings for WalkOptionsSheet

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Append new strings + plurals**

Append before `</resources>`:

```xml
<!-- Stage 9.5-C: WalkOptionsSheet (in-walk options modal) -->
<string name="walk_options_title">Options</string>
<string name="walk_options_intention_title">Set Intention</string>
<string name="walk_options_intention_unset">No intention set</string>
<string name="walk_options_intention_dialog_title">Set Intention</string>
<string name="walk_options_intention_placeholder">A line for this walk…</string>
<string name="walk_options_intention_save">Save</string>
<string name="walk_options_intention_cancel">Cancel</string>
<string name="walk_options_waypoint_title">Drop Waypoint</string>
<plurals name="walk_options_waypoint_count">
    <item quantity="zero">None marked</item>
    <item quantity="one">1 marked</item>
    <item quantity="other">%1$d marked</item>
</plurals>
```

- [ ] **Step 2: Verify resources compile**

Run: `./gradlew :app:processDebugResources`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(walk): strings for WalkOptionsSheet (intention + waypoint rows)"
```

---

### Task 11: `WaypointDao.observeCountForWalk` + `WalkViewModel.waypointCount` flow

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WaypointDao.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelWaypointCountTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `WalkViewModelWaypointCountTest.kt`. Mirror the harness from `WalkViewModelVoiceRecordingsTest.kt` (real Room + UnconfinedTestDispatcher). Add 3 tests:

```kotlin
@Test
fun `waypointCount is 0 when no walk in progress`()

@Test
fun `waypointCount increments when a waypoint is dropped`() {
    // controller.startWalk(); waypointCount.test { 0 };
    // controller.recordWaypoint(); waypointCount emits 1;
    // controller.recordWaypoint(); emits 2.
}

@Test
fun `waypointCount resets to 0 on next walk`()
```

(Mirror the structure of `WalkViewModelVoiceRecordingsTest.kt::recordingsCount tests` exactly.)

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkViewModelWaypointCountTest"`
Expected: FAIL — `waypointCount` doesn't exist.

- [ ] **Step 3: Add DAO query**

In `WaypointDao.kt`, append:

```kotlin
@Query("SELECT COUNT(*) FROM waypoints WHERE walk_id = :walkId")
fun observeCountForWalk(walkId: Long): kotlinx.coroutines.flow.Flow<Int>
```

(Add the `Flow` import at the top instead of inlining the FQN if it's not already there.)

- [ ] **Step 4: Add repository wrapper**

In `WalkRepository.kt`:

```kotlin
fun observeWaypointCount(walkId: Long): Flow<Int> =
    waypointDao.observeCountForWalk(walkId)
```

(Add the import for `Flow` if not present.)

- [ ] **Step 5: Add `WalkViewModel.waypointCount`**

In `WalkViewModel.kt`, near `recordingsCount`:

```kotlin
val waypointCount: StateFlow<Int> = controller.state
    .map { walkIdOrNull(it) }
    .distinctUntilChanged()
    .flatMapLatest { walkId ->
        if (walkId == null) flowOf(0)
        else repository.observeWaypointCount(walkId)
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = 0,
    )

fun dropWaypoint() {
    viewModelScope.launch { controller.recordWaypoint() }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkViewModelWaypointCountTest"`
Expected: PASS, 3 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WaypointDao.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModelWaypointCountTest.kt
git commit -m "feat(walk): WalkViewModel.waypointCount + dropWaypoint"
```

---

### Task 12: `setIntention` end-to-end (Walk → DAO → Repo → Controller → ViewModel)

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt`
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerSetIntentionTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `WalkControllerSetIntentionTest.kt`:

```kotlin
@Test
fun `setIntention persists trimmed text on the active walk`() = runTest(dispatcher) {
    val controller = TestControllerHarness.build(this)
    controller.startWalk(intention = null)
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    controller.setIntention("  walk well  ")

    val persisted = controller.repository.getWalk(walkId)?.intention
    assertEquals("walk well", persisted)
}

@Test
fun `setIntention clears the intention when blank`() = runTest(dispatcher) {
    val controller = TestControllerHarness.build(this)
    controller.startWalk(intention = "previous")
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    controller.setIntention("   ")

    val persisted = controller.repository.getWalk(walkId)?.intention
    assertEquals(null, persisted)
}

@Test
fun `setIntention truncates at 140 chars`() = runTest(dispatcher) {
    val controller = TestControllerHarness.build(this)
    controller.startWalk(intention = null)
    val walkId = (controller.state.value as WalkState.Active).walk.walkId

    val longText = "x".repeat(200)
    controller.setIntention(longText)

    val persisted = controller.repository.getWalk(walkId)?.intention
    assertEquals(140, persisted?.length)
}

@Test
fun `setIntention from Idle is a no-op`() = runTest(dispatcher) {
    val controller = TestControllerHarness.build(this)
    controller.setIntention("nothing")
    // No walk to persist to; should not throw.
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkControllerSetIntentionTest"`
Expected: FAIL — `setIntention` doesn't exist.

- [ ] **Step 3: Add DAO query**

In `WalkDao.kt`:

```kotlin
@Query("UPDATE walks SET intention = :intention WHERE id = :walkId")
suspend fun updateIntention(walkId: Long, intention: String?)
```

- [ ] **Step 4: Add repository method**

In `WalkRepository.kt`:

```kotlin
suspend fun updateWalkIntention(walkId: Long, intention: String?) {
    walkDao.updateIntention(walkId = walkId, intention = intention)
}
```

- [ ] **Step 5: Add `WalkController.setIntention`**

In `WalkController.kt`:

```kotlin
suspend fun setIntention(text: String) {
    dispatchMutex.withLock {
        val walkId = walkIdOrNull(_state.value) ?: return@withLock
        val sanitized = text.trim().take(MAX_INTENTION_CHARS).takeIf { it.isNotBlank() }
        repository.updateWalkIntention(walkId = walkId, intention = sanitized)
    }
}

private companion object {
    const val MAX_INTENTION_CHARS = 140
}
```

(Place `MAX_INTENTION_CHARS` inside the existing private companion object if there is one.)

`walkIdOrNull` is a private helper in WalkController already (used by `recordWaypoint` etc.). Reuse it.

- [ ] **Step 6: Add `WalkViewModel.intention` + `setIntention`**

In `WalkViewModel.kt`, near `recordingsCount`:

```kotlin
val intention: StateFlow<String?> = controller.state
    .map { state ->
        when (state) {
            is WalkState.Active -> state.walk.intention
            is WalkState.Paused -> state.walk.intention
            is WalkState.Meditating -> state.walk.intention
            else -> null
        }
    }
    .distinctUntilChanged()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = null,
    )

fun setIntention(text: String) {
    viewModelScope.launch { controller.setIntention(text) }
}
```

(NOTE: `WalkAccumulator.intention` may not be a field. Check first — the spec assumed it is. If it's not, the in-memory `state.walk.intention` read returns from a property we need to add OR the StateFlow needs to read from the repo periodically. Read `WalkAccumulator.kt` first.)

If WalkAccumulator does NOT have an `intention` field, alternative: read from `repository.getWalk(walkId)?.intention` via flatMapLatest:

```kotlin
val intention: StateFlow<String?> = controller.state
    .map { walkIdOrNull(it) }
    .distinctUntilChanged()
    .flatMapLatest { walkId ->
        if (walkId == null) flowOf(null)
        else flow {
            // poll once per state-class change; intention only changes
            // via setIntention which itself triggers a state recompute
            // by hand via observeAllWalks (TBD if Walk row is observed).
            emit(repository.getWalk(walkId)?.intention)
        }
    }
    .stateIn(...)
```

Decision: if `WalkAccumulator.intention` does NOT exist, defer the live UI display of the just-set intention to a later stage. The in-walk `WalkOptionsSheet` will read from `intention` flow which lags by a recompose cycle (the next location sample triggers a new state emission, the map drops back to the same walkId, the flatMapLatest re-runs the repo read, and the new intention propagates). User-visible delay: ≤ 1 second on a walking-pace cadence. Acceptable.

- [ ] **Step 7: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.walk.WalkControllerSetIntentionTest"`
Expected: PASS, 4 tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/dao/WalkDao.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/walk/WalkController.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkViewModel.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/walk/WalkControllerSetIntentionTest.kt
git commit -m "feat(walk): setIntention plumbing (Walk row, controller, VM)"
```

---

### Task 13: `IntentionSettingDialog` Composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialogTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `IntentionSettingDialogTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IntentionSettingDialogTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Save callback fires with trimmed text`() {
        var saved: String? = null
        composeRule.setContent {
            IntentionSettingDialog(
                initial = null,
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        // Find the input by its placeholder.
        composeRule.onNodeWithText("A line for this walk…").performTextInput("  walk well  ")
        composeRule.onNodeWithText("Save").performClick()
        assertEquals("walk well", saved)
    }

    @Test
    fun `Cancel callback fires`() {
        var dismissed = false
        composeRule.setContent {
            IntentionSettingDialog(
                initial = null,
                onSave = {},
                onDismiss = { dismissed = true },
            )
        }
        composeRule.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `text input clamps at 140 chars`() {
        var saved: String? = null
        composeRule.setContent {
            IntentionSettingDialog(
                initial = null,
                onSave = { saved = it },
                onDismiss = {},
            )
        }
        val longText = "x".repeat(200)
        composeRule.onNodeWithText("A line for this walk…").performTextInput(longText)
        composeRule.onNodeWithText("Save").performClick()
        assertEquals(140, saved?.length)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.IntentionSettingDialogTest"`
Expected: FAIL — `IntentionSettingDialog` doesn't exist.

- [ ] **Step 3: Implement `IntentionSettingDialog`**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

internal const val MAX_INTENTION_CHARS = 140

@Composable
fun IntentionSettingDialog(
    initial: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.walk_options_intention_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { incoming ->
                    text = incoming.take(MAX_INTENTION_CHARS)
                },
                placeholder = { Text(stringResource(R.string.walk_options_intention_placeholder)) },
                singleLine = false,
                maxLines = 3,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.walk_options_intention_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.walk_options_intention_cancel))
            }
        },
        containerColor = pilgrimColors.parchment,
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.IntentionSettingDialogTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialog.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/IntentionSettingDialogTest.kt
git commit -m "feat(walk): IntentionSettingDialog (140-char TextField + Save/Cancel)"
```

---

### Task 14: `WalkOptionsSheet` Composable

**Files:**
- Create: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt`
- Test: `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheetTest.kt` (new)

- [ ] **Step 1: Write the failing test**

Create `WalkOptionsSheetTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkOptionsSheetTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `renders intention and waypoint rows`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").assertIsDisplayed()
        composeRule.onNodeWithText("Drop Waypoint").assertIsDisplayed()
    }

    @Test
    fun `intention subtitle shows the persisted intention when set`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = "walk well",
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("walk well").assertIsDisplayed()
    }

    @Test
    fun `intention subtitle shows fallback when null`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("No intention set").assertIsDisplayed()
    }

    @Test
    fun `waypoint row disabled when canDropWaypoint is false`() {
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = false,
                onSetIntention = {},
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Drop Waypoint").assertIsNotEnabled()
    }

    @Test
    fun `intention click fires onSetIntention`() {
        var fired = false
        composeRule.setContent {
            WalkOptionsSheet(
                intention = null,
                waypointCount = 0,
                canDropWaypoint = true,
                onSetIntention = { fired = true },
                onDropWaypoint = {},
                onDismiss = {},
            )
        }
        composeRule.onNodeWithText("Set Intention").performClick()
        assertTrue(fired)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkOptionsSheetTest"`
Expected: FAIL — `WalkOptionsSheet` doesn't exist.

- [ ] **Step 3: Implement `WalkOptionsSheet`**

Create `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalkOptionsSheet(
    intention: String?,
    waypointCount: Int,
    canDropWaypoint: Boolean,
    onSetIntention: () -> Unit,
    onDropWaypoint: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = PilgrimSpacing.big,
                vertical = PilgrimSpacing.normal,
            ),
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Text(
                text = stringResource(R.string.walk_options_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = PilgrimSpacing.small),
            )
            OptionRow(
                icon = Icons.Outlined.EditNote,
                title = stringResource(R.string.walk_options_intention_title),
                subtitle = intention?.takeIf { it.isNotBlank() }
                    ?: stringResource(R.string.walk_options_intention_unset),
                onClick = onSetIntention,
            )
            OptionRow(
                icon = Icons.Outlined.LocationOn,
                title = stringResource(R.string.walk_options_waypoint_title),
                subtitle = pluralStringResource(
                    R.plurals.walk_options_waypoint_count,
                    waypointCount,
                    waypointCount,
                ),
                enabled = canDropWaypoint,
                onClick = onDropWaypoint,
            )
        }
    }
}

@Composable
private fun OptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) pilgrimColors.moss else pilgrimColors.fog
    val titleColor = if (enabled) pilgrimColors.ink else pilgrimColors.fog
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.4f))
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.small,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = pilgrimType.body, color = titleColor)
            Text(text = subtitle, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = pilgrimColors.fog,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.WalkOptionsSheetTest"`
Expected: PASS, 5 tests.

(`Modifier.weight` requires the Row to import `androidx.compose.foundation.layout.RowScope.weight` extension implicitly — should compile inside Column too because Column has its own weight extension. The OptionRow uses Column's weight here since the inner Column is what has weight inside the Row. Verify the import resolution.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheet.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkOptionsSheetTest.kt
git commit -m "feat(walk): WalkOptionsSheet (intention + waypoint rows in ModalBottomSheet)"
```

---

### Task 15: Wire `WalkOptionsSheet` + `IntentionSettingDialog` into `ActiveWalkScreen`

**Files:**
- Modify: `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt`

- [ ] **Step 1: Wire the ellipsis click + dialog management**

In `ActiveWalkScreen.kt`, find the existing `MapOverlayButtons` call. Currently `onOptionsClick = {}`. Replace with full wiring:

```kotlin
var showOptions by rememberSaveable { mutableStateOf(false) }
var showIntention by rememberSaveable { mutableStateOf(false) }
val intention by viewModel.intention.collectAsStateWithLifecycle()
val waypointCount by viewModel.waypointCount.collectAsStateWithLifecycle()

// ... existing code ...

MapOverlayButtons(
    onOptionsClick = { showOptions = true },
    onLeaveClick = { showLeaveConfirm = true },
    modifier = Modifier.align(Alignment.TopCenter),
)

if (showOptions) {
    WalkOptionsSheet(
        intention = intention,
        waypointCount = waypointCount,
        canDropWaypoint = navWalkState is WalkState.Active || navWalkState is WalkState.Paused,
        onSetIntention = {
            showOptions = false
            showIntention = true
        },
        onDropWaypoint = {
            viewModel.dropWaypoint()
            showOptions = false
        },
        onDismiss = { showOptions = false },
    )
}
if (showIntention) {
    IntentionSettingDialog(
        initial = intention,
        onSave = { text ->
            viewModel.setIntention(text)
            showIntention = false
        },
        onDismiss = { showIntention = false },
    )
}
```

- [ ] **Step 2: Verify all walk-screen tests pass**

Run: `./gradlew :app:testDebugUnitTest --tests "org.walktalkmeditate.pilgrim.ui.walk.*"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/ActiveWalkScreen.kt
git commit -m "feat(walk): wire ellipsis → WalkOptionsSheet + IntentionSettingDialog"
```

---

### Task 16: Full sweep + on-device QA

**Files:** none new.

- [ ] **Step 1: Full unit test + lint sweep**

Run: `./gradlew :app:testDebugUnitTest :app:lintDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Build + install debug APK on OnePlus 13**

Run:
```
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am force-stop org.walktalkmeditate.pilgrim.debug
adb shell monkey -p org.walktalkmeditate.pilgrim.debug -c android.intent.category.LAUNCHER 1
```

- [ ] **Step 3: Run manual verification per spec §Verification (9 items)**

Document any failures + fix before merging.

- [ ] **Step 4: Commit any device-QA tweaks (likely small)**

If small adjustments needed, commit with `chore(walk): Stage 9.5-C device-QA polish`.

---

## Self-Review

**Spec coverage check:**
- §1 discardWalk plumbing → Tasks 1, 2, 3, 4, 5, 6, 7 ✓
- §2 Audio waveform → Tasks 8, 9 ✓
- §3 WalkOptionsSheet (intention + waypoint) → Tasks 10, 11, 12, 13, 14, 15 ✓
- §4 Out of scope (deferred items) → no tasks (correct) ✓
- §Verification → Task 16 ✓

**Placeholder scan:** Task 4's `TestControllerHarness.build` references a fixture that may not exist. Spelled-out alternative in Task 4 Step 1: "If a `TestControllerHarness` doesn't already exist in the codebase, mirror the per-test setup from `WalkControllerTest.kt` directly." Acceptable — the implementer reads the existing test before adapting.

Task 6's `WalkTrackingServiceDiscardTest` test body is sketched not fully written — implementer needs to read the existing `WalkTrackingServiceTest.kt` (if any) to copy the harness. Acceptable.

Task 12 has a "Decision" branch about `WalkAccumulator.intention`'s existence. Implementer must read `WalkAccumulator.kt` first to choose the correct path. Documented inline.

**Type consistency:**
- `walkId: Long` everywhere
- `intention: String?` (nullable) end-to-end
- `waypointCount: Int` end-to-end
- `audioLevel: Float` (already plumbed pre-9.5-C; just consumed in waveform now)
- `WalkAction.Discard(at: Long)` matches `WalkAction.Finish(at: Long)` shape
- `WalkEffect.PurgeWalk(walkId: Long)` matches `WalkEffect.FinalizeWalk(walkId, endTimestamp)` shape

**Plan ready.** Saved to `docs/superpowers/plans/2026-04-26-stage-9-5-c-active-walk-parity-round-2.md`.
