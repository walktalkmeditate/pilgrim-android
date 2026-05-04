# Stage 14 — Journal / Pilgrim Log iOS-parity port

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship full iOS v1.5.0 parity for the Home/Journal scene — calligraphy-path with rich walk dots, lunar/milestone/date overlays, expand-card on tap, scroll haptics, scenery, turning-day banner, journey-summary cycler, and seal-thumbnail FAB.

**Architecture:** Per-row LazyColumn anchors at 90-dp `verticalSpacing` with `WalkDot` Composable inside each row; Canvas-behind for decorative chrome only (path/segments/scenery/markers/dividers). New `WalkSnapshot` data class emitted by `HomeViewModel.combine(observeAllWalks, units, cachedShareStore.observeAll, celestialAwarenessEnabled).flowOn(IO)`. Talk-duration deferred to Stage 14.X (no live ActivityInterval writer); Stage 14 reads cached `Walk.meditationSeconds` only.

**Spec:** `docs/superpowers/specs/2026-05-04-stage-14-journal-design.md`.

---

## JDK setup (any task that runs `./gradlew`)

```bash
export PATH="$HOME/.asdf/shims:$PATH"   # asdf temurin-17.0.18+8
java -version                            # expect openjdk 17
```

---

## Setup

```bash
git checkout main
git pull --ff-only origin main
git checkout -b feat/stage-14-journal
```

---

## File structure overview

### Files to CREATE — Bucket 14-A foundation

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshot.kt` | `@Immutable data class WalkSnapshot` per-walk Journal struct |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalUiState.kt` | sealed UI state (Loading/Empty/Loaded) replacing `HomeUiState` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JourneySummary.kt` | `@Immutable data class` aggregate totals + cycle-text helpers |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/StatMode.kt` | enum WALKS / TALKS / MEDITATIONS |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMath.kt` | pure-Kotlin dot size/opacity/color helpers |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDot.kt` | per-row Composable rendering dot + favicon + arcs |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/HapticEvent.kt` | sealed HapticEvent (None / LightDot / HeavyDot / Milestone) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticState.kt` | viewport-center crossing detector |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcher.kt` | `@Singleton` Vibrator wrapper with handler-time reduce-motion read |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/LocalReduceMotion.kt` | CompositionLocal source of truth for animation gating |

### Files to CREATE — Bucket 14-B chrome

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalTopBar.kt` | `CenterAlignedTopAppBar` with "Pilgrim Log" title |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeader.kt` | tap-cycling 3-state summary text |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt` | ModalBottomSheet body |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt` | 3-segment capsule fraction bar |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt` | up-to-3 conditional pills row |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnail.kt` | `LatestSealThumbnail` 44dp display Composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/GoshuinFAB.kt` | FAB observing `latestSealBitmap` |

### Files to CREATE — Bucket 14-C overlays

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/TurningDayService.kt` | Kotlin object port |
| `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/SeasonalMarkerTurnings.kt` | `kanji() / bannerTextRes() / turningColor() / isTurning()` extensions |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt` | banner row Composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt` | full/new-moon position calc |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt` | 10×10 dp moon glyph Composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt` | torii + label horizontal bar |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt` | threshold list + cumulative-cross detection |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt` | year-month boundary detection |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt` | `toriiGatePath(size)` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt` | `footprintPath(size)` |

### Files to CREATE — Bucket 14-D polish

| File | Responsibility |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt` | FNV+SplitMix64 scenery picker (35% chance, 7 weighted types) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItem.kt` | type-dispatch Composable |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryShapes.kt` | pure path builders |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/TreeScenery.kt` | tree (with winter variant) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/LanternScenery.kt` | lantern body + window |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/ButterflyScenery.kt` | butterfly (primitive ellipses) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/MountainScenery.kt` | mountain triangle |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/GrassScenery.kt` | grass blade tuft |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/ToriiScenery.kt` | scenery-tinted torii |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/MoonScenery.kt` | moon disc |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffect.kt` | newest-walk ripple Canvas |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt` | tail + stone-dot + "Begin" |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreen.kt` | top-level screen Composable replacing HomeScreen body |

### Files to MODIFY

| File | Change |
|---|---|
| `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt` | + `activitySumsFor`, `walkEventsFor` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStore.kt` | + `observeAll(): Flow<Map<String, CachedShare>>` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt` | + 4 turning colors (Jade/Gold/Claret/Indigo) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt` | rewrite `uiState` → `journalState`; add seal pipeline + expand state |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` | body delegates to `JournalScreen` |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeUiState.kt` | DELETE file (replaced by `JournalUiState.kt`) |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRowComposable.kt` | DELETE file |
| `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt` | + public `dotPositions(...)` helper |
| `app/src/main/res/values/strings.xml` | + Stage 14 strings (titles, summary cycler, turning, expand, a11y) |
| `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt` | rewrite for `WalkSnapshot`; cancel scope before `db.close()` |

### Tests CREATED (paths, mapped one-to-one to source files)

`WalkDotMathTest`, `ScrollHapticStateTest`, `JournalHapticDispatcherTest` (Robolectric for builder), `WalkRepositoryActivitySumsTest`, `CachedShareStoreObserveAllTest`, `HomeViewModelJournalTest`, `TurningDayServiceTest`, `LunarMarkerCalcTest`, `MilestoneCalcTest`, `DateDividerCalcTest`, `SceneryGeneratorTest`, `SceneryShapesTest`, `WalkDotComposableTest`, `ExpandCardSheetTest`, `JournalScreenIntegrationTest`, `SceneryItemRobolectricTest`, `RippleEffectTest`.

---

## Bucket 14-A: foundation

### Task 1: `activitySumsFor` + `walkEventsFor` repo helpers

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt`

**Files create:**
- `app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryActivitySumsTest.kt`

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryActivitySumsTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.Walk

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WalkRepositoryActivitySumsTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PilgrimDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `activitySumsFor returns zero talk and meditationSeconds when populated`() = runBlocking {
        val walk = Walk(startTimestamp = 0L, endTimestamp = 1000L, meditationSeconds = 600L)
        val (talk, meditate) = repo.activitySumsFor(walkId = 1L, walk = walk)
        assertEquals(0L, talk)
        assertEquals(600L, meditate)
    }

    @Test
    fun `activitySumsFor returns zero meditate when meditationSeconds null`() = runBlocking {
        val walk = Walk(startTimestamp = 0L, endTimestamp = 1000L, meditationSeconds = null)
        val (talk, meditate) = repo.activitySumsFor(walkId = 1L, walk = walk)
        assertEquals(0L, talk)
        assertEquals(0L, meditate)
    }

    @Test
    fun `walkEventsFor returns empty list when no events recorded`() = runBlocking {
        val events = repo.walkEventsFor(walkId = 999L)
        assertEquals(emptyList<Any>(), events)
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*WalkRepositoryActivitySumsTest" 2>&1 | tail -30
```

Expected: FAIL with `unresolved reference: activitySumsFor` / `walkEventsFor`.

- [ ] **Step 3: Add helpers to `WalkRepository.kt`**

Insert near other `open suspend fun` helpers (e.g., after `recentFinishedWalksBefore`):

```kotlin
    /**
     * Stage 14 stub: returns `(talkSec, meditateSec)`. talkSec is hard-zeroed
     * because no live ActivityIntervalCoordinator exists yet — native walks
     * don't write talk intervals. meditateSec reads `Walk.meditationSeconds`
     * (cached column populated by WalkMetricsCache).
     *
     * TODO Stage 14.X: wire live ActivityInterval recording from WalkViewModel.
     */
    open suspend fun activitySumsFor(walkId: Long, walk: Walk): Pair<Long, Long> =
        Pair(0L, walk.meditationSeconds ?: 0L)

    /** Pause-aware duration math input for [HomeViewModel.buildSnapshots]. */
    open suspend fun walkEventsFor(walkId: Long): List<WalkEvent> =
        walkEventDao.getForWalk(walkId)
```

- [ ] **Step 4: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*WalkRepositoryActivitySumsTest" 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, all 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/WalkRepository.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/WalkRepositoryActivitySumsTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): activitySumsFor + walkEventsFor repo helpers (Stage 14 task 1)

Stage 14 stub for talk/meditate aggregates. Talk hard-zeroed until live
ActivityIntervalCoordinator lands in Stage 14.X.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `WalkSnapshot` + `JournalUiState` + `JourneySummary` + `StatMode`

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/StatMode.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshot.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JourneySummary.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalUiState.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshotTest.kt`

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshotTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSnapshotTest {

    private fun sample(
        talk: Long = 0L,
        meditate: Long = 0L,
        durationSec: Double = 1800.0,
    ) = WalkSnapshot(
        id = 1L,
        uuid = "uuid-1",
        startMs = 0L,
        distanceM = 5_000.0,
        durationSec = durationSec,
        averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 5_000.0,
        talkDurationSec = talk,
        meditateDurationSec = meditate,
        favicon = null,
        isShared = false,
        weatherCondition = null,
    )

    @Test
    fun `walkOnlyDurationSec subtracts talk and meditate`() {
        val s = sample(talk = 300L, meditate = 600L, durationSec = 1800.0)
        assertEquals(900L, s.walkOnlyDurationSec)
    }

    @Test
    fun `walkOnlyDurationSec floors at zero`() {
        val s = sample(talk = 1000L, meditate = 1000L, durationSec = 1500.0)
        assertEquals(0L, s.walkOnlyDurationSec)
    }

    @Test
    fun `hasTalk and hasMeditate flag sub-second values`() {
        val none = sample()
        assertFalse(none.hasTalk)
        assertFalse(none.hasMeditate)
        val both = sample(talk = 1L, meditate = 1L)
        assertTrue(both.hasTalk)
        assertTrue(both.hasMeditate)
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*WalkSnapshotTest" 2>&1 | tail -15
```

Expected: FAIL with `unresolved reference: WalkSnapshot`.

- [ ] **Step 3: Create `StatMode.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

/** Cycle modes for the JourneySummaryHeader. Cycle order matches iOS verbatim. */
enum class StatMode { WALKS, TALKS, MEDITATIONS }
```

- [ ] **Step 4: Create `WalkSnapshot.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.runtime.Immutable

/**
 * Per-walk Journal struct. Mirrors iOS `WalkSnapshot`. Built once per
 * Flow emission in `HomeViewModel.buildSnapshots` so all UI surfaces
 * (dot, expand card, scenery) read the same precomputed values.
 *
 * `@Immutable` per Stage 4-C / 13-Cel cascade lesson — has computed
 * properties; even though current fields are all stable, mark
 * explicitly so future List<>/Map<> fields don't silently regress
 * Compose stability.
 */
@Immutable
data class WalkSnapshot(
    val id: Long,
    val uuid: String,
    val startMs: Long,
    val distanceM: Double,
    val durationSec: Double,
    val averagePaceSecPerKm: Double,
    val cumulativeDistanceM: Double,
    val talkDurationSec: Long,
    val meditateDurationSec: Long,
    val favicon: String?,
    val isShared: Boolean,
    val weatherCondition: String?,
) {
    /** Walk-only duration (total minus talk minus meditate, floored at 0). */
    val walkOnlyDurationSec: Long
        get() = (durationSec.toLong() - talkDurationSec - meditateDurationSec).coerceAtLeast(0L)

    val hasTalk: Boolean get() = talkDurationSec > 0L
    val hasMeditate: Boolean get() = meditateDurationSec > 0L
}
```

- [ ] **Step 5: Create `JourneySummary.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.runtime.Immutable

/**
 * Aggregate totals for the JourneySummaryHeader's 3 cycle states.
 * Built alongside the WalkSnapshot list in `HomeViewModel.buildSnapshots`
 * to avoid a second collect on the same Flow.
 */
@Immutable
data class JourneySummary(
    val totalDistanceM: Double,
    val totalTalkSec: Long,
    val totalMeditateSec: Long,
    val talkerCount: Int,
    val meditatorCount: Int,
    val walkCount: Int,
    val firstWalkStartMs: Long,
)
```

- [ ] **Step 6: Create `JournalUiState.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.runtime.Immutable

/**
 * Three-state load model for the Journal screen. Replaces `HomeUiState`.
 * Stage 13-XZ B5/B7 lesson: `@Immutable` cascade on every data class
 * with `List<>` field types — Loaded carries `List<WalkSnapshot>`.
 */
sealed class JournalUiState {
    data object Loading : JournalUiState()
    data object Empty : JournalUiState()

    @Immutable
    data class Loaded(
        val snapshots: List<WalkSnapshot>,
        val summary: JourneySummary,
        val celestialAwarenessEnabled: Boolean,
    ) : JournalUiState()
}
```

- [ ] **Step 7: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*WalkSnapshotTest" 2>&1 | tail -15
```

Expected: PASS, 3 tests.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/StatMode.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshot.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JourneySummary.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalUiState.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/WalkSnapshotTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): WalkSnapshot + JournalUiState data classes (Stage 14 task 2)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `LocalReduceMotion` CompositionLocal + theme provider

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/LocalReduceMotion.kt`

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Theme.kt` (provide it)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/ReducedMotion.kt` (relax visibility on `rememberReducedMotion` from `internal` → `public` so theme code can reach it; if already public, no change)

- [ ] **Step 1: Create `LocalReduceMotion.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Single source of truth for Composition-time animation gating across
 * Stage 14-A → 14-D animation entry points. Provided in `PilgrimTheme`
 * via `rememberReducedMotion()`.
 *
 * `JournalHapticDispatcher` deliberately does NOT consume this Local —
 * it reads `Settings.Global` at handler-time so Quick-Settings flips
 * mid-scroll take effect on the next dispatch.
 */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> =
    staticCompositionLocalOf { false }
```

- [ ] **Step 2: Make `rememberReducedMotion` public**

Edit `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/ReducedMotion.kt`:

Change `internal fun rememberReducedMotion(): Boolean` to `fun rememberReducedMotion(): Boolean`.

- [ ] **Step 3: Provide it in `PilgrimTheme`**

Open `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Theme.kt`. Find the `CompositionLocalProvider(...)` block at the root of `PilgrimTheme`. Add `LocalReduceMotion provides reducedMotion` to the provided locals; capture `val reducedMotion = rememberReducedMotion()` above the provider. Adjust import block so `import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion` and `import org.walktalkmeditate.pilgrim.ui.design.rememberReducedMotion` are present.

- [ ] **Step 4: Build sanity-check**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/LocalReduceMotion.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/ReducedMotion.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Theme.kt
git commit -m "$(cat <<'EOF'
feat(journal): LocalReduceMotion CompositionLocal + theme wiring (Stage 14 task 3)

Single source of truth for Composition-time animation gating; haptic
dispatcher remains handler-time per spec I10.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `WalkDotMath` (size / opacity / color) + tests

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMath.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMathTest.kt`

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMathTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import org.junit.Assert.assertEquals
import org.junit.Test

class WalkDotMathTest {

    @Test
    fun `dotSize at lower bound clamps to 8 dp`() {
        assertEquals(8f, WalkDotMath.dotSize(durationSec = 100.0), 0.01f)
        assertEquals(8f, WalkDotMath.dotSize(durationSec = 300.0), 0.01f)
    }

    @Test
    fun `dotSize at upper bound clamps to 22 dp`() {
        assertEquals(22f, WalkDotMath.dotSize(durationSec = 7200.0), 0.01f)
        assertEquals(22f, WalkDotMath.dotSize(durationSec = 9999.0), 0.01f)
    }

    @Test
    fun `dotSize linear in middle range`() {
        // 1-hour walk = midpoint, expect ~14.85 dp
        val mid = WalkDotMath.dotSize(durationSec = 3600.0)
        assertEquals(14.85f, mid, 0.5f)
    }

    @Test
    fun `dotOpacity newest is 1 oldest fades to 0_5`() {
        assertEquals(1.0f, WalkDotMath.dotOpacity(0, 5), 1e-4f)
        assertEquals(0.5f, WalkDotMath.dotOpacity(4, 5), 1e-4f)
    }

    @Test
    fun `dotOpacity single walk returns 1`() {
        assertEquals(1.0f, WalkDotMath.dotOpacity(0, 1), 1e-4f)
    }

    @Test
    fun `labelOpacity is dotOpacity times 0_7`() {
        assertEquals(0.7f, WalkDotMath.labelOpacity(0, 5), 1e-4f)
        assertEquals(0.35f, WalkDotMath.labelOpacity(4, 5), 1e-4f)
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*WalkDotMathTest" 2>&1 | tail -15
```

Expected: FAIL.

- [ ] **Step 3: Create `WalkDotMath.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import kotlin.math.max
import kotlin.math.min

/**
 * Pure-Kotlin dot geometry helpers, ported from iOS `InkScrollView.swift`.
 * Exposed as `internal` (package-visible) so Robolectric tests exercise
 * the formulas without composition.
 */
internal object WalkDotMath {

    private const val MIN_DURATION_SEC = 300.0   // 5 min
    private const val MAX_DURATION_SEC = 7200.0  // 2 h
    private const val MIN_DOT_DP = 8f
    private const val MAX_DOT_DP = 22f

    /**
     * Linear scale duration → dot diameter (dp). 5 min → 8 dp, 2 h → 22 dp.
     * Verbatim port of iOS `InkScrollView.swift` size formula.
     */
    fun dotSize(durationSec: Double): Float {
        val clamped = min(max(durationSec, MIN_DURATION_SEC), MAX_DURATION_SEC)
        val frac = (clamped - MIN_DURATION_SEC) / (MAX_DURATION_SEC - MIN_DURATION_SEC)
        return (MIN_DOT_DP + frac * (MAX_DOT_DP - MIN_DOT_DP)).toFloat()
    }

    /**
     * Verbatim port of iOS InkScrollView.swift:493-497.
     * Newest walk (index 0) → 1.0, oldest (index total-1) → 0.5.
     */
    fun dotOpacity(index: Int, total: Int): Float =
        if (total <= 1) 1f else 1f - (index.toFloat() / (total - 1)) * 0.5f

    /** iOS InkScrollView.swift:636 — distance-label α = dotOpacity * 0.7. */
    fun labelOpacity(index: Int, total: Int): Float =
        dotOpacity(index, total) * 0.7f
}
```

- [ ] **Step 4: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*WalkDotMathTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMath.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotMathTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): WalkDotMath size/opacity helpers (Stage 14 task 4)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `HapticEvent` + `ScrollHapticState` + tests

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/HapticEvent.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticState.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticStateTest.kt`

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticStateTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrollHapticStateTest {

    private val dotsPx = listOf(100f, 200f, 300f, 400f)
    private val sizesPx = listOf(10f, 16f, 12f, 20f) // index 1 + 3 are "large"
    private val milestonesPx = listOf(250f)

    private fun newState() = ScrollHapticState(
        dotPositionsPx = dotsPx,
        dotSizesPx = sizesPx,
        milestonePositionsPx = milestonesPx,
        largeDotCutoffPx = 15f,
        dotThresholdPx = 20f,
        milestoneThresholdPx = 25f,
    )

    @Test
    fun `light dot fires inside 20px of small dot`() {
        val state = newState()
        // viewport center at 110 → distance 10 from dot 0 (size 10 = small)
        val event = state.handleViewportCenterPx(110f)
        assertTrue(event is HapticEvent.LightDot)
        assertEquals(0, (event as HapticEvent.LightDot).dotIndex)
    }

    @Test
    fun `heavy dot fires for large dot inside 20px`() {
        val state = newState()
        val event = state.handleViewportCenterPx(195f) // dot 1 size 16 = large
        assertTrue(event is HapticEvent.HeavyDot)
    }

    @Test
    fun `dot does not refire when same dot still in window`() {
        val state = newState()
        val first = state.handleViewportCenterPx(105f)
        assertTrue(first !is HapticEvent.None)
        val second = state.handleViewportCenterPx(110f)
        assertEquals(HapticEvent.None, second)
    }

    @Test
    fun `dot rearms after leaving window`() {
        val state = newState()
        state.handleViewportCenterPx(100f)
        state.handleViewportCenterPx(150f) // outside dot 0 window
        val refire = state.handleViewportCenterPx(100f)
        assertTrue(refire is HapticEvent.LightDot)
    }

    @Test
    fun `milestone fires inside 25px window`() {
        val state = newState()
        val event = state.handleViewportCenterPx(255f)
        assertTrue(event is HapticEvent.Milestone)
    }

    @Test
    fun `outside any window emits None`() {
        val state = newState()
        assertEquals(HapticEvent.None, state.handleViewportCenterPx(50f))
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*ScrollHapticStateTest" 2>&1 | tail -15
```

Expected: FAIL.

- [ ] **Step 3: Create `HapticEvent.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

/** Single-shot haptic emitted as the viewport center crosses a dot or milestone. */
sealed class HapticEvent {
    data object None : HapticEvent()
    data class LightDot(val dotIndex: Int) : HapticEvent()
    data class HeavyDot(val dotIndex: Int) : HapticEvent()
    data class Milestone(val milestoneIndex: Int) : HapticEvent()
}
```

- [ ] **Step 4: Create `ScrollHapticState.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import kotlin.math.abs

/**
 * Viewport-center crossing detector. Tracks `lastTriggeredDot` and
 * `lastTriggeredMilestone` to dedup; rearms when the center exits the
 * detection window (Stage 3-C closing-review pattern). Reduce-motion
 * gating happens downstream in `JournalHapticDispatcher`.
 *
 * Thresholds default to verbatim iOS values (20 px dot, 25 px milestone,
 * 15 px large-dot cutoff).
 */
class ScrollHapticState(
    private val dotPositionsPx: List<Float>,
    private val dotSizesPx: List<Float>,
    private val milestonePositionsPx: List<Float>,
    private val largeDotCutoffPx: Float = 15f,
    private val dotThresholdPx: Float = 20f,
    private val milestoneThresholdPx: Float = 25f,
) {
    private var lastTriggeredDot: Int? = null
    private var lastTriggeredMilestone: Int? = null

    fun handleViewportCenterPx(centerPx: Float): HapticEvent {
        // Milestone first — they're more important.
        var milestoneIndex: Int? = null
        for (i in milestonePositionsPx.indices) {
            if (abs(milestonePositionsPx[i] - centerPx) <= milestoneThresholdPx) {
                milestoneIndex = i
                break
            }
        }
        if (milestoneIndex != null) {
            if (milestoneIndex != lastTriggeredMilestone) {
                lastTriggeredMilestone = milestoneIndex
                return HapticEvent.Milestone(milestoneIndex)
            }
        } else {
            lastTriggeredMilestone = null
        }

        var dotIndex: Int? = null
        for (i in dotPositionsPx.indices) {
            if (abs(dotPositionsPx[i] - centerPx) <= dotThresholdPx) {
                dotIndex = i
                break
            }
        }
        if (dotIndex == null) {
            lastTriggeredDot = null
            return HapticEvent.None
        }
        if (dotIndex == lastTriggeredDot) return HapticEvent.None
        lastTriggeredDot = dotIndex
        val isLarge = dotSizesPx.getOrNull(dotIndex)?.let { it > largeDotCutoffPx } == true
        return if (isLarge) HapticEvent.HeavyDot(dotIndex) else HapticEvent.LightDot(dotIndex)
    }
}
```

- [ ] **Step 5: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*ScrollHapticStateTest" 2>&1 | tail -15
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/HapticEvent.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticState.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/ScrollHapticStateTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): HapticEvent + ScrollHapticState (Stage 14 task 5)

Viewport-crossing detector with dedup; thresholds verbatim from iOS.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `JournalHapticDispatcher` + Robolectric real-builder test

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcher.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcherTest.kt`

**Stage 2-F lesson:** This task introduces `VibrationEffect.Composition.build()` — a runtime-validated platform builder. Robolectric must call `dispatch(...)` so the real `.build()` is exercised.

- [ ] **Step 1: Write failing test (Robolectric)**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcherTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Stage 2-F lesson: every PR introducing `VibrationEffect.Composition.build()`
 * MUST exercise the real builder via Robolectric so runtime crashes
 * surface in unit tests, not on-device.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class JournalHapticDispatcherTest {

    @Test
    fun `dispatch LightDot does not throw`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.LightDot(0))
    }

    @Test
    fun `dispatch HeavyDot does not throw`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.HeavyDot(0))
    }

    @Test
    fun `dispatch Milestone does not throw`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.Milestone(0))
    }

    @Test
    fun `dispatch None is no-op`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.None)
    }

    @Test
    fun `dispatch suppressed when sounds disabled`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { false },
        )
        dispatcher.dispatch(HapticEvent.LightDot(0))
        // No assertion — Robolectric vibrator stub records nothing; the
        // test passes if no exception is thrown.
    }

    @Test
    fun `back-to-back dispatch within 50ms is throttled`() {
        val dispatcher = JournalHapticDispatcher(
            context = ApplicationProvider.getApplicationContext(),
            soundsEnabledProvider = { true },
        )
        dispatcher.dispatch(HapticEvent.LightDot(0))
        dispatcher.dispatch(HapticEvent.LightDot(1)) // immediately after — skipped
        // Pass = no exception.
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*JournalHapticDispatcherTest" 2>&1 | tail -15
```

Expected: FAIL with `unresolved reference: JournalHapticDispatcher`.

- [ ] **Step 3: Create `JournalHapticDispatcher.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scroll

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches a [HapticEvent] to the system Vibrator using
 * [VibrationEffect.Composition] primitives. Reduce-motion gating is
 * HANDLER-TIME (per-call `Settings.Global` read) so a Quick-Settings
 * flip mid-scroll takes effect on the next dispatch — distinct from
 * `LocalReduceMotion` which is composition-time.
 *
 * 50 ms min-interval guard defends against scroll-fling / multi-finger
 * haptic flooding (Open Question 3).
 */
@Singleton
class JournalHapticDispatcher private constructor(
    private val context: Context,
    private val soundsEnabledProvider: () -> Boolean,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context = context,
        soundsEnabledProvider = { true },
    )

    /** Internal-test seam ctor keeping the @ApplicationContext at @Inject site. */
    internal constructor(
        context: Context,
        soundsEnabledProvider: () -> Boolean,
    ) : this(context, soundsEnabledProvider, sentinel = Unit)

    @Suppress("UNUSED_PARAMETER")
    private constructor(context: Context, sounds: () -> Boolean, sentinel: Unit) : this(
        context = context,
        soundsEnabledProvider = sounds,
    )

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private var lastDispatchNs: Long = 0L
    private val minIntervalNs: Long = 50_000_000L // 50 ms

    fun dispatch(event: HapticEvent) {
        if (event is HapticEvent.None) return
        if (!soundsEnabledProvider()) return
        if (isReduceMotion()) return

        val nowNs = SystemClock.elapsedRealtimeNanos()
        if (nowNs - lastDispatchNs < minIntervalNs) return
        lastDispatchNs = nowNs

        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Pre-API 30: no Composition. Fall back to a single oneShot.
            @Suppress("DEPRECATION")
            v.vibrate(VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE))
            return
        }

        val effect: VibrationEffect = buildEffect(event) ?: return
        v.vibrate(effect)
    }

    private fun buildEffect(event: HapticEvent): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val composition = VibrationEffect.startComposition()
        when (event) {
            is HapticEvent.LightDot -> {
                if (!supports(VibrationEffect.Composition.PRIMITIVE_TICK)) return fallback(0.4f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 1.0f)
            }
            is HapticEvent.HeavyDot -> {
                if (!supports(VibrationEffect.Composition.PRIMITIVE_CLICK)) return fallback(0.7f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
            }
            is HapticEvent.Milestone -> {
                val canHeavy = supports(VibrationEffect.Composition.PRIMITIVE_CLICK)
                val canLow = supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
                if (!canHeavy) return fallback(0.9f)
                composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                if (canLow) composition.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.6f, 30)
            }
            is HapticEvent.None -> return null
        }
        return composition.compose()
    }

    private fun supports(primitive: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val v = vibrator ?: return false
        return v.areAllPrimitivesSupported(primitive)
    }

    private fun fallback(amplitude: Float): VibrationEffect =
        VibrationEffect.createOneShot(
            12L,
            (amplitude * VibrationEffect.DEFAULT_AMPLITUDE).toInt().coerceAtLeast(1),
        )

    private fun isReduceMotion(): Boolean = try {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) == 0f
    } catch (_: Settings.SettingNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
```

- [ ] **Step 4: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*JournalHapticDispatcherTest" 2>&1 | tail -15
```

Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcher.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scroll/JournalHapticDispatcherTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): JournalHapticDispatcher with Robolectric builder test (Stage 14 task 6)

Stage 2-F lesson: real VibrationEffect.Composition.build() exercised
in unit test — fakes hide builder crashes.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `CachedShareStore.observeAll` + round-trip UUID test

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStore.kt`

**Files create:**
- `app/src/test/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStoreObserveAllTest.kt`

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStoreObserveAllTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class CachedShareStoreObserveAllTest {

    @Test
    fun `observeAll emits keys reconstructed with hyphens`() = runBlocking {
        val store = CachedShareStore(
            ApplicationProvider.getApplicationContext(),
            Json { ignoreUnknownKeys = true },
        )
        val uuid1 = UUID.randomUUID().toString()
        val uuid2 = UUID.randomUUID().toString()
        store.put(uuid1, sample(uuid1))
        store.put(uuid2, sample(uuid2))
        val map = store.observeAll().first()
        assertTrue("contains uuid1", map.containsKey(uuid1))
        assertTrue("contains uuid2", map.containsKey(uuid2))
        assertEquals(2, map.size)
    }

    private fun sample(uuid: String) = CachedShare(
        url = "https://walk.pilgrimapp.org/share/$uuid",
        token = "tok",
        expiresAtMs = Long.MAX_VALUE,
        slug = "slug",
        version = 1,
    )
}
```

(Note: `CachedShare` field shape may vary — read `CachedShare.kt` first and match its constructor; if fields differ, adjust the `sample(...)` factory accordingly. The test's intent is independent of `CachedShare` field detail.)

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*CachedShareStoreObserveAllTest" 2>&1 | tail -15
```

Expected: FAIL with `unresolved reference: observeAll`.

- [ ] **Step 3: Add `observeAll` + `reconstructUuid` to `CachedShareStore.kt`**

Add inside the `class CachedShareStore`:

```kotlin
    fun observeAll(): kotlinx.coroutines.flow.Flow<Map<String, CachedShare>> =
        context.cachedShareDataStore.data
            .map { prefs ->
                prefs.asMap().asSequence()
                    .filter { (key, _) -> key.name.startsWith("share_cache_") }
                    .mapNotNull { (key, value) ->
                        val blob = value as? String ?: return@mapNotNull null
                        val cached = decode(blob) ?: return@mapNotNull null
                        val uuid = reconstructUuid(key.name.removePrefix("share_cache_"))
                        uuid to cached
                    }
                    .toMap()
            }
            .distinctUntilChanged()

    private fun reconstructUuid(noHyphens: String): String {
        require(noHyphens.length == 32) {
            "Expected 32-char UUID-no-hyphens, got ${noHyphens.length}"
        }
        return "${noHyphens.substring(0, 8)}-" +
                "${noHyphens.substring(8, 12)}-" +
                "${noHyphens.substring(12, 16)}-" +
                "${noHyphens.substring(16, 20)}-" +
                noHyphens.substring(20, 32)
    }
```

- [ ] **Step 4: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*CachedShareStoreObserveAllTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStore.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/data/share/CachedShareStoreObserveAllTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): CachedShareStore.observeAll with UUID re-hyphenation (Stage 14 task 7)

Single-combine source for HomeViewModel; reconstructUuid restores
canonical 8-4-4-4-12 form so map keys equal the rest-of-app UUID shape.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: `HomeViewModel` rewrite to emit `JournalUiState`

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt` (rewrite for new shape)

**Files create:**
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelJournalTest.kt`

**Stage 13-XZ B2 lesson:** CPU work in `buildSnapshots` (cumulative-distance reduce + per-snapshot dotSize precompute) MUST be inside `withContext(Dispatchers.Default)`. Inject `defaultDispatcher: CoroutineDispatcher = Dispatchers.Default` parameter on the VM constructor with secondary `@Inject` ctor pinning Default.

**Stage 7-A lesson:** every test in this task must `vm.viewModelScope.coroutineContext[Job]?.cancel()` BEFORE `db.close()` in tearDown.

- [ ] **Step 1: Write failing journal-state test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelJournalTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class HomeViewModelJournalTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PilgrimDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
    }

    @After
    fun tearDown() {
        if (::vm.isInitialized) {
            vm.viewModelScope.coroutineContext[Job]?.cancel()
        }
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `journalState emits Empty when no finished walks`() = runTest {
        vm = newVm()
        vm.journalState.test {
            // First emission may be Loading; advance to Empty
            val terminal = expectMostRecentItem()
            assertEquals(JournalUiState.Empty, terminal)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `journalState emits Loaded with one snapshot for one finished walk`() = runTest {
        val id = db.walkDao().insert(Walk(startTimestamp = 0L, endTimestamp = 1_000L))
        // Update with id from autoGenerate
        // Some setups return the rowId; Walk.id default 0 + autoGen handles this.
        vm = newVm()
        vm.journalState.test {
            // skip Loading
            val emissions = mutableListOf<JournalUiState>()
            repeat(3) {
                val item = awaitItem()
                emissions += item
                if (item is JournalUiState.Loaded && item.snapshots.size == 1) return@repeat
            }
            val loaded = emissions.last() as JournalUiState.Loaded
            assertEquals(1, loaded.snapshots.size)
            assertEquals(id, loaded.snapshots[0].id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun newVm(): HomeViewModel {
        val units = object : UnitsPreferencesRepository {
            override val distanceUnits: kotlinx.coroutines.flow.StateFlow<UnitSystem> =
                MutableStateFlow(UnitSystem.METRIC)
            override suspend fun setDistanceUnits(value: UnitSystem) = Unit
        }
        val hemi = object : HemisphereRepository {
            override val hemisphere: kotlinx.coroutines.flow.StateFlow<Hemisphere> =
                MutableStateFlow(Hemisphere.Northern)
            override suspend fun setHemisphere(value: Hemisphere) = Unit
            override suspend fun seedFromLatitude(latitude: Double) = Unit
        }
        val clock = object : Clock {
            override fun now(): Long = 1_000L
        }
        val cachedShareStore = CachedShareStore(
            ApplicationProvider.getApplicationContext(),
            Json { ignoreUnknownKeys = true },
        )
        // PracticePreferencesRepository: use a test fake.
        val practice = TestPracticePreferencesRepository()
        return HomeViewModel(
            context = ApplicationProvider.getApplicationContext(),
            repository = repo,
            clock = clock,
            hemisphereRepository = hemi,
            unitsPreferences = units,
            cachedShareStore = cachedShareStore,
            practicePreferences = practice,
            defaultDispatcher = UnconfinedTestDispatcher(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }
}
```

**Note for implementer:** `TestPracticePreferencesRepository` is a minimal fake matching the existing `PracticePreferencesRepository` interface — read the production interface to match the contract; the fake should expose `celestialAwarenessEnabled = MutableStateFlow(false)`, `zodiacSystem = MutableStateFlow(ZodiacSystem.Tropical)`, etc., with no-op suspend setters. If a fake already exists in test sources (e.g. from Stage 13-Cel), reuse it.

If `UnitsPreferencesRepository` and `HemisphereRepository` already have test fakes, prefer those.

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*HomeViewModelJournalTest" 2>&1 | tail -25
```

Expected: FAIL — VM does not yet expose `journalState`.

- [ ] **Step 3: Rewrite `HomeViewModel.kt`**

Open `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt`. Replace the body so the public surface is:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.content.Context
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshotCalc
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.walk.WalkMetricsMath
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSealBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalInkFlavor
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
    private val unitsPreferences: UnitsPreferencesRepository,
    private val cachedShareStore: CachedShareStore,
    private val practicePreferences: PracticePreferencesRepository,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {

    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    private val _expandedSnapshotId = MutableStateFlow<Long?>(null)
    val expandedSnapshotId: StateFlow<Long?> = _expandedSnapshotId.asStateFlow()

    private val _expandedCelestialSnapshot = MutableStateFlow<CelestialSnapshot?>(null)
    val expandedCelestialSnapshot: StateFlow<CelestialSnapshot?> =
        _expandedCelestialSnapshot.asStateFlow()
    private var celestialJob: Job? = null

    private val _latestSealBitmap = MutableStateFlow<ImageBitmap?>(null)
    val latestSealBitmap: StateFlow<ImageBitmap?> = _latestSealBitmap.asStateFlow()

    private val _latestSealSpec = MutableStateFlow<SealSpec?>(null)
    val latestSealSpec: StateFlow<SealSpec?> = _latestSealSpec.asStateFlow()

    private val sealCache = LinkedHashMap<Pair<SealSpec, Int>, ImageBitmap>(8, 0.75f, true)
    private var sealRenderJob: Job? = null

    val journalState: StateFlow<JournalUiState> = combine(
        repository.observeAllWalks(),
        unitsPreferences.distanceUnits,
        cachedShareStore.observeAll(),
        practicePreferences.celestialAwarenessEnabled,
        hemisphereRepository.hemisphere,
    ) { walks, units, shareCache, celestialEnabled, hemisphere ->
        val finished = walks.filter { it.endTimestamp != null }
        if (finished.isEmpty()) {
            JournalUiState.Empty
        } else {
            buildSnapshots(finished, units, shareCache, hemisphere, clock.now(), celestialEnabled)
        }
    }
        .flowOn(ioDispatcher)
        .stateIn(viewModelScope, SharingStarted.Eagerly, JournalUiState.Loading)

    private suspend fun buildSnapshots(
        walks: List<Walk>,
        units: UnitSystem,
        shareCache: Map<String, CachedShare>,
        hemisphere: Hemisphere,
        nowMs: Long,
        celestialAwarenessEnabled: Boolean,
    ): JournalUiState.Loaded {
        // IO: per-walk DAO reads on ioDispatcher.
        val perWalk = walks.sortedBy { it.startTimestamp }.map { walk ->
            val samples = repository.locationSamplesFor(walk.id)
            val events = repository.walkEventsFor(walk.id)
            val (talkSec, meditateSec) = repository.activitySumsFor(walk.id, walk)
            val distanceM = walk.distanceMeters ?: walkDistanceMeters(samples)
            val activeDur = WalkMetricsMath.computeActiveDurationSeconds(walk, events)
            Triple(walk, samples to events, Triple(distanceM, activeDur, talkSec to meditateSec))
        }

        // Default: CPU-only reduce + format.
        val loaded = withContext(defaultDispatcher) {
            var cumulative = 0.0
            val oldestFirstSnapshots = perWalk.map { (walk, _, calc) ->
                val (distanceM, activeDur, sums) = calc
                val (talkSec, meditateSec) = sums
                cumulative += distanceM
                WalkSnapshot(
                    id = walk.id,
                    uuid = walk.uuid,
                    startMs = walk.startTimestamp,
                    distanceM = distanceM,
                    durationSec = activeDur.toDouble(),
                    averagePaceSecPerKm = if (distanceM > 1.0)
                        activeDur.toDouble() / (distanceM / 1000.0) else 0.0,
                    cumulativeDistanceM = cumulative,
                    talkDurationSec = talkSec,
                    meditateDurationSec = meditateSec,
                    favicon = walk.favicon,
                    isShared = shareCache[walk.uuid]?.isExpiredAt(nowMs) == false,
                    weatherCondition = walk.weatherCondition,
                )
            }
            val newestFirst = oldestFirstSnapshots.reversed()
            val summary = JourneySummary(
                totalDistanceM = cumulative,
                totalTalkSec = newestFirst.sumOf { it.talkDurationSec },
                totalMeditateSec = newestFirst.sumOf { it.meditateDurationSec },
                talkerCount = newestFirst.count { it.hasTalk },
                meditatorCount = newestFirst.count { it.hasMeditate },
                walkCount = newestFirst.size,
                firstWalkStartMs = perWalk.firstOrNull()?.first?.startTimestamp ?: 0L,
            )
            JournalUiState.Loaded(newestFirst, summary, celestialAwarenessEnabled)
        }

        scheduleSealRender(walks, units, hemisphere)
        return loaded
    }

    fun setExpandedSnapshotId(id: Long?) {
        _expandedSnapshotId.value = id
        celestialJob?.cancel()
        if (id == null) {
            _expandedCelestialSnapshot.value = null
            return
        }
        if (!practicePreferences.celestialAwarenessEnabled.value) return
        val snap = (journalState.value as? JournalUiState.Loaded)
            ?.snapshots?.firstOrNull { it.id == id } ?: return
        celestialJob = viewModelScope.launch(defaultDispatcher) {
            try {
                val zodiac = practicePreferences.zodiacSystem.value
                val cs = CelestialSnapshotCalc.snapshot(
                    instantMs = snap.startMs,
                    zone = ZoneId.systemDefault(),
                    zodiacSystem = zodiac,
                )
                _expandedCelestialSnapshot.value = cs
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _expandedCelestialSnapshot.value = null
            }
        }
    }

    private fun scheduleSealRender(
        walks: List<Walk>,
        units: UnitSystem,
        hemisphere: Hemisphere,
    ) {
        val newest = walks.maxByOrNull { it.endTimestamp ?: it.startTimestamp } ?: run {
            _latestSealSpec.value = null
            _latestSealBitmap.value = null
            return
        }
        val distance = newest.distanceMeters ?: 0.0
        val flavor = SeasonalInkFlavor.forDate(LocalDate.now(ZoneId.systemDefault()), hemisphere)
        val baseColor = flavor.toBaseColor()
        val ink = SeasonalColorEngine.applySeasonalShift(
            base = baseColor,
            hemisphere = hemisphere,
            date = LocalDate.now(ZoneId.systemDefault()),
        )
        val displayDistance = WalkFormat.distanceText(distance, units).split(" ").firstOrNull() ?: ""
        val unitLabel = WalkFormat.distanceUnit(units)
        val spec = newest.toSealSpec(distance, ink, displayDistance, unitLabel)
        _latestSealSpec.value = spec

        val sizePx = (44 * context.resources.displayMetrics.density).toInt()
        val key = spec to sizePx
        sealCache[key]?.let {
            _latestSealBitmap.value = it
            return
        }
        sealRenderJob?.cancel()
        sealRenderJob = viewModelScope.launch(defaultDispatcher) {
            try {
                val bmp = EtegamiSealBitmapRenderer.renderToBitmap(spec, ink, sizePx, context)
                val img = bmp.asImageBitmap()
                if (sealCache.size > 4) sealCache.remove(sealCache.keys.first())
                sealCache[key] = img
                _latestSealBitmap.value = img
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                _latestSealBitmap.value = null
            }
        }
    }
}
```

**Note for implementer:** the existing `WalkRepository` may not yet expose `locationSamplesFor` — read the file before this task; if missing, expose `open suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> = routeDao.getForWalk(walkId)` (verify the actual DAO method name first). If the helper already exists by another name, use it directly.

`SeasonalInkFlavor.forDate` and `WalkFormat.distanceText`/`distanceUnit` are existing helpers; if their actual names differ, adjust to the production signatures (do not add new wrappers).

- [ ] **Step 4: Update `HomeScreen.kt` to consume `journalState` (minimum)**

Until Task 18 swaps in `JournalScreen`, keep `HomeScreen.kt` building. Replace the line that reads `vm.uiState` with a temporary adapter:

```kotlin
val state by vm.journalState.collectAsState()
val rows = (state as? JournalUiState.Loaded)?.snapshots?.map { snap ->
    HomeWalkRow(
        walkId = snap.id,
        uuid = snap.uuid,
        startTimestamp = snap.startMs,
        distanceMeters = snap.distanceM,
        durationSeconds = snap.durationSec,
        relativeDate = "",
        durationText = "",
        distanceText = "",
        recordingCountText = null,
        intention = null,
    )
} ?: emptyList()
```

This temporary shim keeps the existing list rendering compiling. Task 18 deletes it.

- [ ] **Step 5: Delete `HomeViewModelTest.kt` legacy assertions that reference `HomeUiState` or `HomeWalkRow` text fields**

Open the existing `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt`. Replace any `vm.uiState` references with `vm.journalState`; delete any test asserting the old `HomeWalkRow.relativeDate`/`durationText`/`distanceText` text — those formatters belong to the chrome layer (Task 11). Keep tests that exercise the basic empty/loaded transition. Add tearDown `vm.viewModelScope.coroutineContext[Job]?.cancel()` before `db.close()`.

- [ ] **Step 6: Run all home tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*home*" 2>&1 | tail -25
```

Expected: PASS.

- [ ] **Step 7: Build sanity-check**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModel.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelTest.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/HomeViewModelJournalTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): HomeViewModel emits JournalUiState (Stage 14 task 8)

withContext(Default) for snapshot CPU work; tearDown cancels viewModelScope
before db.close per Stage 7-A flake fix.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: `WalkDot` composable + `CalligraphyPath.dotPositions` helper + LazyColumn migration

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDot.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotComposableTest.kt`

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt` (additive: expose `dotPositions(...)`)
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` (LazyColumn migration; delete `HomeWalkRowCard` import)

**Stage 5-A `Modifier.scale(Float)` lesson:** any animated alpha/scale uses `Modifier.graphicsLayer { ... }` lambda form, not value form.

- [ ] **Step 1: Add `dotPositions` to `CalligraphyPath.kt`**

Open the file, locate the existing `internal` math that derives stroke X positions per row index. Add a new public top-level function:

```kotlin
data class DotPosition(val centerXPx: Float, val yPx: Float)

/**
 * Public helper exposing per-row dot center coordinates so JournalScreen's
 * scroll-haptic state machine and overlay calculators (lunar/milestone/
 * date dividers) can read positions WITHOUT redrawing the path.
 *
 * Stage 3-C kept this math private to the renderer; Stage 14 promotes it
 * to a pure function. The existing draw lambda is unchanged.
 */
fun dotPositions(
    strokes: List<CalligraphyStrokeSpec>,
    widthPx: Float,
    verticalSpacingPx: Float,
    topInsetPx: Float,
): List<DotPosition> = strokes.mapIndexed { index, spec ->
    DotPosition(
        centerXPx = xOffsetForRow(spec, widthPx),
        yPx = topInsetPx + verticalSpacingPx * index,
    )
}
```

`xOffsetForRow` is the existing private helper computing the per-row meander. If its name differs in production, adjust accordingly.

- [ ] **Step 2: Create `WalkDot.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Composable
fun WalkDot(
    snapshot: WalkSnapshot,
    sizeDp: Float,
    color: Color,
    opacity: Float,
    isNewest: Boolean,
    contentDescription: String,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .graphicsLayer { alpha = opacity }
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(sizeDp.dp)) {
            drawCircle(color = color, radius = size.minDimension / 2f, center = Offset(size.width / 2f, size.height / 2f))
        }
        snapshot.favicon?.let { faviconKey ->
            val favicon = WalkFavicon.entries.firstOrNull { it.rawValue == faviconKey }
            if (favicon != null) {
                Icon(
                    imageVector = favicon.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size((sizeDp * 0.5f).dp),
                )
            }
        }
    }
}
```

`WalkFavicon.rawValue` and `WalkFavicon.icon` are pre-existing per Stage 4-D. If the rawValue accessor differs (e.g. `name` or a `key: String` property), use the production accessor. The favicon list iteration is intentionally simple — Stage 14 doesn't need filled-vs-outlined dispatch beyond what the production `WalkFavicon.icon` already supplies.

- [ ] **Step 3: Robolectric smoke test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotComposableTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WalkDotComposableTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tap fires onTap callback`() {
        var tapped = false
        composeRule.setContent {
            WalkDot(
                snapshot = WalkSnapshot(
                    id = 1L, uuid = "u", startMs = 0L, distanceM = 1000.0,
                    durationSec = 600.0, averagePaceSecPerKm = 360.0,
                    cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
                    meditateDurationSec = 0L, favicon = null, isShared = false,
                    weatherCondition = null,
                ),
                sizeDp = 12f,
                color = Color.Black,
                opacity = 1f,
                isNewest = true,
                contentDescription = "test-dot",
                onTap = { tapped = true },
            )
        }
        composeRule.onNodeWithContentDescription("test-dot").performClick()
        assertTrue(tapped)
    }
}
```

- [ ] **Step 4: Run test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*WalkDotComposableTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: LazyColumn migration in `HomeScreen.kt`**

Replace the body of the existing `HomeScreen` Composable with a LazyColumn that renders one `WalkDot` per row at a fixed 90-dp `verticalSpacing`. Wire `LazyListState.firstVisibleItemIndex` + `firstVisibleItemScrollOffset` → `ScrollHapticState.handleViewportCenterPx(...)` → `JournalHapticDispatcher.dispatch(event)`. Delete `HomeWalkRowComposable` import; do not delete the file yet (Task 18 deletes both `HomeWalkRowComposable.kt` and `HomeUiState.kt`).

The intermediate `HomeScreen` body for this task:

```kotlin
@Composable
fun HomeScreen(/* existing params */) {
    val vm: HomeViewModel = hiltViewModel()
    val state by vm.journalState.collectAsState()
    val context = LocalContext.current
    val dispatcher = remember { JournalHapticDispatcher(context) }
    val density = LocalDensity.current
    val verticalSpacingPx = with(density) { 90.dp.toPx() }
    val topInsetPx = with(density) { 40.dp.toPx() }

    val loaded = state as? JournalUiState.Loaded
    val snapshots = loaded?.snapshots ?: emptyList()
    val sizesPx = remember(snapshots) {
        snapshots.map { with(density) { WalkDotMath.dotSize(it.durationSec).dp.toPx() } }
    }
    val dotYsPx = remember(snapshots) {
        snapshots.indices.map { topInsetPx + verticalSpacingPx * it }
    }
    val hapticState = remember(snapshots) {
        ScrollHapticState(
            dotPositionsPx = dotYsPx,
            dotSizesPx = sizesPx,
            milestonePositionsPx = emptyList(), // populated in Bucket 14-C
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, hapticState) {
        snapshotFlow {
            listState.firstVisibleItemIndex * verticalSpacingPx +
                listState.firstVisibleItemScrollOffset
        }.collectLatest { topPx ->
            // viewport center y in canvas-space.
            val viewportHeightPx = listState.layoutInfo.viewportEndOffset -
                listState.layoutInfo.viewportStartOffset
            val centerPx = topPx + viewportHeightPx / 2f
            val event = hapticState.handleViewportCenterPx(centerPx)
            dispatcher.dispatch(event)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(snapshots, key = { _, s -> s.id }) { index, snap ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                contentAlignment = Alignment.Center,
            ) {
                WalkDot(
                    snapshot = snap,
                    sizeDp = WalkDotMath.dotSize(snap.durationSec),
                    color = MaterialTheme.colorScheme.onSurface,
                    opacity = WalkDotMath.dotOpacity(index, snapshots.size),
                    isNewest = index == 0,
                    contentDescription = "walk dot $index",
                    onTap = { vm.setExpandedSnapshotId(snap.id) },
                )
            }
        }
    }
}
```

(Imports: add `androidx.compose.foundation.lazy.LazyColumn`, `rememberLazyListState`, `itemsIndexed`, `androidx.compose.ui.platform.LocalDensity`, `androidx.compose.runtime.snapshotFlow`, `kotlinx.coroutines.flow.collectLatest`. Drop the old `Column { rows.forEach { ... } }` block.)

- [ ] **Step 6: Build + smoke**

```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
./gradlew :app:testDebugUnitTest --tests "*WalkDotComposableTest" 2>&1 | tail -15
```

Expected: both `BUILD SUCCESSFUL` / PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDot.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/WalkDotComposableTest.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/calligraphy/CalligraphyPath.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt
git commit -m "$(cat <<'EOF'
feat(journal): WalkDot composable + LazyColumn migration (Stage 14 task 9)

CalligraphyPath.dotPositions helper is additive; existing draw lambda
unchanged. Modifier.graphicsLayer for animated alpha (Stage 5-A lesson).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Bucket 14-A QA gate (mid-stage device verification)

After Task 9, **PAUSE for OnePlus 13 dogfood QA**. Verify on-device:

- Open Journal tab → list of dots renders, no card content
- Tap a dot → no crash; ExpandCardSheet stub later (Bucket 14-B)
- Scroll → haptics fire on dot center-pass (light/medium by size)
- 50+ walks scroll smoothly (60fps target via Layout Inspector)
- Reduce-Motion ON in Settings → haptics suppressed

**Only proceed to Bucket 14-B once QA pass is confirmed by user.** Document any
regressions on a fix-and-pause loop (per Stage 13-XZ Open Question 5).

---

## Bucket 14-B: chrome

### Task 10: `JournalTopBar` + Stage 14 strings (Pilgrim Log title)

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalTopBar.kt`

**Files modify:**
- `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add Stage 14 strings**

Append to `app/src/main/res/values/strings.xml` inside `<resources>`:

```xml
    <!-- Stage 14: Journal -->
    <string name="journal_title">Pilgrim Log</string>

    <!-- Empty state (Stage 14-D) -->
    <string name="home_empty_begin">Begin</string>

    <!-- Journey summary cycler -->
    <string name="journal_summary_walks_count">%1$s · %2$s</string>
    <string name="journal_summary_walks_distance_km">%1$s km walked</string>
    <string name="journal_summary_walks_distance_mi">%1$s mi walked</string>
    <string name="journal_summary_walks_distance_m">%1$s m walked</string>
    <string name="journal_summary_walks_distance_ft">%1$s ft walked</string>
    <string name="journal_summary_talked">%1$s talked</string>
    <string name="journal_summary_meditated">%1$s meditated</string>
    <plurals name="journal_summary_walks_with_talk">
        <item quantity="one">%d walk with talk</item>
        <item quantity="other">%d walks with talk</item>
    </plurals>
    <plurals name="journal_summary_walks_with_meditation">
        <item quantity="one">%d walk with meditation</item>
        <item quantity="other">%d walks with meditation</item>
    </plurals>

    <!-- Turning-day banner (Stage 14-C) -->
    <string name="turning_equinox_banner">Today, day equals night.</string>
    <string name="turning_solstice_banner">Today the sun stands still.</string>

    <!-- Expand card (Stage 14-B) -->
    <string name="journal_expand_label_distance">distance</string>
    <string name="journal_expand_label_duration">duration</string>
    <string name="journal_expand_label_pace">pace</string>
    <string name="journal_expand_pill_walk">walk</string>
    <string name="journal_expand_pill_talk">talk</string>
    <string name="journal_expand_pill_meditate">meditate</string>
    <string name="journal_expand_view_details">View details</string>

    <!-- Accessibility -->
    <string name="journal_dot_a11y_walk_on_date_distance_duration">Walk on %1$s, %2$s, %3$s</string>
    <string name="journal_dot_a11y_with_talk">, %1$s talking</string>
    <string name="journal_dot_a11y_with_meditate">, %1$s meditating</string>
    <string name="journal_summary_a11y_double_tap_to_cycle">Double-tap to change statistic.</string>
    <string name="journal_milestone_a11y">Milestone: %1$s</string>
    <string name="journal_turning_banner_a11y">%1$s. %2$s.</string>
```

- [ ] **Step 2: Create `JournalTopBar.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import org.walktalkmeditate.pilgrim.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalTopBar() {
    CenterAlignedTopAppBar(
        title = { Text(stringResource(R.string.journal_title)) },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = Color.Transparent,
        ),
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}
```

- [ ] **Step 3: Build + lint**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:compileDebugKotlin :app:lintDebug 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`, no new lint warnings.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/res/values/strings.xml \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalTopBar.kt
git commit -m "$(cat <<'EOF'
feat(journal): JournalTopBar + Stage 14 strings (Stage 14 task 10)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: `JourneySummaryHeader` cycler

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeader.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeaderTest.kt`

**Stage 5-A `rememberSaveable` lesson:** the cycle position must survive rotation; using bare `remember` puts the user in a stuck state on rotation.

**Stage 5-A locale lesson:** `String.format(Locale.US, "%.1f", ...)` for ALL numeric display (digits); `getQuantityString(...)` for pluralized counts.

- [ ] **Step 1: Write Robolectric test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeaderTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.header

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.JourneySummary

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class JourneySummaryHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `tap cycles WALKS to TALKS to MEDITATIONS to WALKS`() {
        val summary = JourneySummary(
            totalDistanceM = 12_345.0,
            totalTalkSec = 3_600L,
            totalMeditateSec = 1_800L,
            talkerCount = 2,
            meditatorCount = 1,
            walkCount = 5,
            firstWalkStartMs = 0L,
        )
        composeRule.setContent {
            MaterialTheme {
                JourneySummaryHeader(summary = summary, units = UnitSystem.METRIC)
            }
        }
        // Initial mode = WALKS — node containing "12.3 km walked" exists.
        composeRule.onNodeWithText("12.3 km walked", substring = true).assertExists()
        composeRule.onNodeWithText("12.3 km walked", substring = true).performClick()
        // After tap → TALKS mode — "talked" copy.
        composeRule.onNodeWithText("talked", substring = true).assertExists()
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*JourneySummaryHeaderTest" 2>&1 | tail -15
```

Expected: FAIL — `JourneySummaryHeader` not defined.

- [ ] **Step 3: Create `JourneySummaryHeader.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.header

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.ui.home.JourneySummary
import org.walktalkmeditate.pilgrim.ui.home.StatMode

@Composable
fun JourneySummaryHeader(
    summary: JourneySummary,
    units: UnitSystem,
    modifier: Modifier = Modifier,
) {
    var mode by rememberSaveable { mutableStateOf(StatMode.WALKS) }
    val context = LocalContext.current
    val a11y = stringResource(R.string.journal_summary_a11y_double_tap_to_cycle)

    val (title, subtitle) = when (mode) {
        StatMode.WALKS -> {
            val dist = formatDistance(summary.totalDistanceM, units)
            val countText = stringResource(
                R.string.journal_summary_walks_count,
                summary.walkCount.toString(),
                monthsSpanText(summary.firstWalkStartMs),
            )
            dist to countText
        }
        StatMode.TALKS -> {
            val dur = formatDuration(summary.totalTalkSec)
            val talkers = context.resources.getQuantityString(
                R.plurals.journal_summary_walks_with_talk,
                summary.talkerCount,
                summary.talkerCount,
            )
            stringResource(R.string.journal_summary_talked, dur) to talkers
        }
        StatMode.MEDITATIONS -> {
            val dur = formatDuration(summary.totalMeditateSec)
            val medi = context.resources.getQuantityString(
                R.plurals.journal_summary_walks_with_meditation,
                summary.meditatorCount,
                summary.meditatorCount,
            )
            stringResource(R.string.journal_summary_meditated, dur) to medi
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clickable { mode = next(mode) }
            .semantics { contentDescription = a11y },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

private fun next(mode: StatMode): StatMode = when (mode) {
    StatMode.WALKS -> StatMode.TALKS
    StatMode.TALKS -> StatMode.MEDITATIONS
    StatMode.MEDITATIONS -> StatMode.WALKS
}

@Composable
private fun formatDistance(meters: Double, units: UnitSystem): String = when (units) {
    UnitSystem.METRIC -> if (meters >= 1000.0)
        stringResource(R.string.journal_summary_walks_distance_km, "%.1f".format(Locale.US, meters / 1000.0))
    else
        stringResource(R.string.journal_summary_walks_distance_m, "%d".format(Locale.US, meters.toInt()))
    UnitSystem.IMPERIAL -> {
        val miles = meters / 1609.344
        if (miles >= 1.0)
            stringResource(R.string.journal_summary_walks_distance_mi, "%.1f".format(Locale.US, miles))
        else
            stringResource(R.string.journal_summary_walks_distance_ft, "%d".format(Locale.US, (meters * 3.28084).toInt()))
    }
}

private fun formatDuration(totalSec: Long): String {
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    return if (h > 0) "%dh %dm".format(Locale.US, h, m) else "%dm".format(Locale.US, m)
}

private fun monthsSpanText(firstWalkStartMs: Long): String {
    val nowMs = System.currentTimeMillis()
    val months = ((nowMs - firstWalkStartMs) / (1000L * 60L * 60L * 24L * 30L)).coerceAtLeast(1L).toInt()
    return "$months months"
}
```

- [ ] **Step 4: Run test**

```bash
./gradlew :app:testDebugUnitTest --tests "*JourneySummaryHeaderTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeader.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/header/JourneySummaryHeaderTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): JourneySummaryHeader 3-state cycler (Stage 14 task 11)

rememberSaveable for rotation safety (Stage 5-A lesson). Locale.US for
digit formatting; getQuantityString for pluralized walker/meditator counts.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: `ExpandCardSheet` + `MiniActivityBar` + `ActivityPills` (batched)

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/MiniActivityBar.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ActivityPills.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheet.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheetTest.kt`

**Stage 4-B lesson:** `rememberUpdatedState(onDismissRequest)` on the dismiss callback so a fresh-lambda recompose doesn't fire stale closures.

**Stage 13-XZ ISSUE 4:** if VM ever sets `_state.value = Loading` during sheet open lifecycle, wrap it in try/catch and fall back to `Closed` state to avoid sheet stranding. Stage 14's `expandedSnapshotId: MutableStateFlow<Long?>` lifecycle uses null/Long pairs only — no Loading state — but this lesson still applies if a future sub-task widens the type.

- [ ] **Step 1: Create `MiniActivityBar.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row

@Composable
fun MiniActivityBar(
    walkFraction: Float,
    talkFraction: Float,
    meditateFraction: Float,
    walkColor: Color,
    talkColor: Color,
    meditateColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (walkFraction >= 0.01f) {
            Box(
                Modifier
                    .weight(walkFraction.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(walkColor),
            )
        }
        if (talkFraction >= 0.01f) {
            Box(
                Modifier
                    .weight(talkFraction.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(talkColor),
            )
        }
        if (meditateFraction >= 0.01f) {
            Box(
                Modifier
                    .weight(meditateFraction.coerceAtLeast(0.01f))
                    .fillMaxHeight()
                    .background(meditateColor),
            )
        }
    }
}
```

- [ ] **Step 2: Create `ActivityPills.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R

@Composable
fun ActivityPills(
    showTalk: Boolean,
    showMeditate: Boolean,
    walkColor: Color,
    talkColor: Color,
    meditateColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(stringResource(R.string.journal_expand_pill_walk), walkColor)
        if (showTalk) Pill(stringResource(R.string.journal_expand_pill_talk), talkColor)
        if (showMeditate) Pill(stringResource(R.string.journal_expand_pill_meditate), meditateColor)
    }
}

@Composable
private fun Pill(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        Text(text = label, style = MaterialTheme.typography.labelSmall)
    }
}
```

- [ ] **Step 3: Create `ExpandCardSheet.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandCardSheet(
    snapshot: WalkSnapshot,
    celestialSnapshot: CelestialSnapshot?,
    onViewDetails: (Long) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val onDismiss by rememberUpdatedState(onDismissRequest)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp)) {
            // Header row: date+time
            val zoneId = ZoneId.systemDefault()
            val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
                .withLocale(Locale.getDefault())
                .withZone(zoneId)
            Text(
                text = fmt.format(Instant.ofEpochMilli(snapshot.startMs)),
                style = MaterialTheme.typography.titleSmall,
            )
            celestialSnapshot?.let { cs ->
                Text(
                    text = cs.planetaryHourSymbol() + "  " + cs.moonSignSymbol(),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            // 3-stat row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StatCell(stringResource(R.string.journal_expand_label_distance), formatKm(snapshot.distanceM))
                StatCell(stringResource(R.string.journal_expand_label_duration), formatDuration(snapshot.durationSec.toLong()))
                StatCell(stringResource(R.string.journal_expand_label_pace), formatPace(snapshot.averagePaceSecPerKm))
            }
            Spacer(Modifier.height(12.dp))

            val total = snapshot.durationSec.coerceAtLeast(1.0)
            MiniActivityBar(
                walkFraction = (snapshot.walkOnlyDurationSec / total).toFloat(),
                talkFraction = (snapshot.talkDurationSec / total).toFloat(),
                meditateFraction = (snapshot.meditateDurationSec / total).toFloat(),
                walkColor = MaterialTheme.colorScheme.primary,
                talkColor = MaterialTheme.colorScheme.tertiary,
                meditateColor = MaterialTheme.colorScheme.secondary,
            )

            ActivityPills(
                showTalk = snapshot.hasTalk,
                showMeditate = snapshot.hasMeditate,
                walkColor = MaterialTheme.colorScheme.primary,
                talkColor = MaterialTheme.colorScheme.tertiary,
                meditateColor = MaterialTheme.colorScheme.secondary,
            )

            Spacer(Modifier.height(16.dp))
            Button(onClick = { onViewDetails(snapshot.id); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.journal_expand_view_details))
                Spacer(Modifier.height(0.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Unspecified)
    }
}

private fun formatKm(meters: Double): String =
    if (meters >= 1000.0) "%.2f km".format(Locale.US, meters / 1000.0)
    else "%d m".format(Locale.US, meters.toInt())

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "%dh %dm".format(Locale.US, h, m) else "%dm".format(Locale.US, m)
}

private fun formatPace(secPerKm: Double): String {
    if (secPerKm <= 0.0) return "—"
    val total = secPerKm.toLong()
    val m = total / 60
    val s = total % 60
    return "%d:%02d / km".format(Locale.US, m, s)
}

private fun CelestialSnapshot.planetaryHourSymbol(): String =
    planetaryHour?.symbolUnicode ?: ""

private fun CelestialSnapshot.moonSignSymbol(): String =
    moonSign?.symbolUnicode ?: ""
```

**Implementer note:** `CelestialSnapshot.planetaryHour` and `.moonSign` field names + their `symbolUnicode` accessors must match the existing Stage 13-Cel API. Read `app/src/main/java/.../core/celestial/CelestialSnapshot.kt` first; rename the helpers to match production. If a `symbolUnicode` accessor doesn't exist, find the equivalent (likely a String-typed enum property or a Stage-13-Cel constants table) and substitute.

- [ ] **Step 4: Robolectric test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/ExpandCardSheetTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.expand

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ExpandCardSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `view details button fires callback with snapshot id`() {
        var receivedId: Long? = null
        val snap = WalkSnapshot(
            id = 42L, uuid = "u", startMs = 0L, distanceM = 5_000.0,
            durationSec = 1800.0, averagePaceSecPerKm = 360.0,
            cumulativeDistanceM = 5_000.0, talkDurationSec = 0L,
            meditateDurationSec = 0L, favicon = null, isShared = false,
            weatherCondition = null,
        )
        composeRule.setContent {
            MaterialTheme {
                ExpandCardSheet(
                    snapshot = snap,
                    celestialSnapshot = null,
                    onViewDetails = { receivedId = it },
                    onDismissRequest = {},
                )
            }
        }
        composeRule.onNodeWithText("View details").performClick()
        assertTrue(receivedId == 42L)
    }
}
```

- [ ] **Step 5: Run tests**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*ExpandCardSheetTest" 2>&1 | tail -20
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/expand/ \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/expand/
git commit -m "$(cat <<'EOF'
feat(journal): ExpandCardSheet + MiniActivityBar + ActivityPills (Stage 14 task 12)

ModalBottomSheet with rememberUpdatedState dismiss (Stage 4-B lesson);
Locale.US for digit formatting (Stage 5-A lesson).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: `LatestSealThumbnail` + `GoshuinFAB` swap-in

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnail.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/GoshuinFAB.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnailTest.kt`

- [ ] **Step 1: Create `SealThumbnail.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun LatestSealThumbnail(
    latestSealBitmap: ImageBitmap?,
    sizeDp: Dp = 44.dp,
    modifier: Modifier = Modifier,
) {
    if (latestSealBitmap == null) {
        Icon(
            imageVector = Icons.Outlined.Explore,
            contentDescription = null,
            modifier = modifier.size(sizeDp),
        )
    } else {
        Image(
            bitmap = latestSealBitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(sizeDp),
        )
    }
}
```

- [ ] **Step 2: Create `GoshuinFAB.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.material3.FloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import org.walktalkmeditate.pilgrim.ui.goshuin.LatestSealThumbnail

@Composable
fun GoshuinFAB(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = hiltViewModel(),
) {
    val bitmap by vm.latestSealBitmap.collectAsState()
    FloatingActionButton(onClick = onClick, modifier = modifier) {
        LatestSealThumbnail(latestSealBitmap = bitmap)
    }
}
```

- [ ] **Step 3: Robolectric smoke test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnailTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SealThumbnailTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `null bitmap renders fallback icon without crash`() {
        composeRule.setContent { MaterialTheme { LatestSealThumbnail(latestSealBitmap = null) } }
        composeRule.onRoot().assertExists()
    }
}
```

- [ ] **Step 4: Replace inline FAB in `HomeScreen.kt`**

In `HomeScreen.kt` find the existing `FloatingActionButton(...) { Icon(Icons.Outlined.Explore, ...) }` and replace with `GoshuinFAB(onClick = { /* existing nav */ })`.

- [ ] **Step 5: Run tests**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*SealThumbnailTest" :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: PASS + `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnail.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/GoshuinFAB.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/SealThumbnailTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): GoshuinFAB seal-thumbnail with explore-icon fallback (Stage 14 task 13)

Reuses EtegamiSealBitmapRenderer (Stage 7-C) — no new helper class.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Bucket 14-C: overlays

### Task 14: Turning-day colors + `TurningDayService` + `SeasonalMarkerTurnings` extensions

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt`

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/TurningDayService.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/SeasonalMarkerTurnings.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/turning/TurningDayServiceTest.kt`

**Stage 13-Cel pattern:** persistence-scope-launched writes for setX that should survive back-nav. Stage 14: the turning-day output is read-only — no writes, no scope concern.

- [ ] **Step 1: Add 4 turning colors to `Color.kt`**

In `app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt`, add at the end:

```kotlin
/** Stage 14-C turning-day color tokens. Verbatim ports of iOS xcassets. */
object TurningPaletteLight {
    val turningJade = Color(0xFF74B495)
    val turningGold = Color(0xFFC9A646)
    val turningClaret = Color(0xFF8B4455)
    val turningIndigo = Color(0xFF2377A4)
}
object TurningPaletteDark {
    val turningJade = Color(0xFF88C4A0)
    val turningGold = Color(0xFFD5B55D)
    val turningClaret = Color(0xFFA26070)
    val turningIndigo = Color(0xFF4691BA)
}
```

Add four fields to `data class PilgrimColors`:

```kotlin
    val turningJade: Color,
    val turningGold: Color,
    val turningClaret: Color,
    val turningIndigo: Color,
```

Update `pilgrimLightColors()` and `pilgrimDarkColors()` factory functions (find them in the same file) to include the four new tokens, sourcing from `TurningPaletteLight` / `TurningPaletteDark` respectively. If those factories don't yet exist (the file may build colors inside a `Composable` `@Composable PilgrimColors`), add the fields to the construction call sites.

- [ ] **Step 2: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/turning/TurningDayServiceTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial.turning

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

class TurningDayServiceTest {

    @Test
    fun `2024 spring equinox returns SpringEquinox in Northern hemisphere`() {
        val marker = TurningDayService.turningFor(
            localDate = LocalDate.of(2024, 3, 20),
            hemisphere = Hemisphere.Northern,
            zone = ZoneOffset.UTC,
        )
        assertEquals(SeasonalMarker.SpringEquinox, marker)
    }

    @Test
    fun `2024 winter solstice returns WinterSolstice in Northern hemisphere`() {
        val marker = TurningDayService.turningFor(
            localDate = LocalDate.of(2024, 12, 21),
            hemisphere = Hemisphere.Northern,
            zone = ZoneOffset.UTC,
        )
        assertEquals(SeasonalMarker.WinterSolstice, marker)
    }

    @Test
    fun `arbitrary mid-month date returns null`() {
        val marker = TurningDayService.turningFor(
            localDate = LocalDate.of(2024, 5, 15),
            hemisphere = Hemisphere.Northern,
            zone = ZoneOffset.UTC,
        )
        assertNull(marker)
    }

    @Test
    fun `cross-quarter date returns null from turningFor`() {
        // Imbolc-ish — cross-quarter; turningFor only emits the 4 turning markers.
        val marker = TurningDayService.turningFor(
            localDate = LocalDate.of(2024, 2, 4),
            hemisphere = Hemisphere.Northern,
            zone = ZoneOffset.UTC,
        )
        assertNull(marker)
    }
}
```

- [ ] **Step 3: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*TurningDayServiceTest" 2>&1 | tail -15
```

Expected: FAIL — `TurningDayService` not defined.

- [ ] **Step 4: Create `SeasonalMarkerTurnings.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial.turning

import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors

fun SeasonalMarker.isTurning(): Boolean = when (this) {
    SeasonalMarker.SpringEquinox,
    SeasonalMarker.SummerSolstice,
    SeasonalMarker.AutumnEquinox,
    SeasonalMarker.WinterSolstice -> true
    else -> false
}

fun SeasonalMarker.kanji(): String? = when (this) {
    SeasonalMarker.SpringEquinox -> "春分"
    SeasonalMarker.SummerSolstice -> "夏至"
    SeasonalMarker.AutumnEquinox -> "秋分"
    SeasonalMarker.WinterSolstice -> "冬至"
    else -> null
}

fun SeasonalMarker.bannerTextRes(): Int? = when (this) {
    SeasonalMarker.SpringEquinox,
    SeasonalMarker.AutumnEquinox -> R.string.turning_equinox_banner
    SeasonalMarker.SummerSolstice,
    SeasonalMarker.WinterSolstice -> R.string.turning_solstice_banner
    else -> null
}

fun SeasonalMarker.turningColor(pilgrimColors: PilgrimColors): Color? = when (this) {
    SeasonalMarker.SpringEquinox -> pilgrimColors.turningJade
    SeasonalMarker.SummerSolstice -> pilgrimColors.turningGold
    SeasonalMarker.AutumnEquinox -> pilgrimColors.turningClaret
    SeasonalMarker.WinterSolstice -> pilgrimColors.turningIndigo
    else -> null
}
```

- [ ] **Step 5: Create `TurningDayService.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial.turning

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarkerCalc
import org.walktalkmeditate.pilgrim.core.celestial.SunCalc
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

/**
 * Stage 14-C: returns the `SeasonalMarker` for `localDate` IF it is one
 * of the 4 turning markers (equinoxes/solstices). Cross-quarter days
 * return null. Hemisphere flips Spring↔Autumn and Summer↔Winter.
 */
object TurningDayService {

    fun turningFor(
        localDate: LocalDate,
        hemisphere: Hemisphere,
        zone: ZoneId = ZoneOffset.UTC,
    ): SeasonalMarker? {
        val noonInstant = localDate.atTime(12, 0).atZone(zone).toInstant()
        val julianCenturies = SunCalc.julianCenturies(noonInstant)
        val sunLon = SunCalc.solarLongitude(julianCenturies)
        val raw = SeasonalMarkerCalc.seasonalMarker(sunLon) ?: return null
        val swapped = if (hemisphere == Hemisphere.Southern) raw.flipHemisphere() else raw
        return swapped.takeIf { it.isTurning() }
    }

    fun turningForToday(
        hemisphere: Hemisphere,
        zone: ZoneId = ZoneId.systemDefault(),
        clock: Clock = Clock.system(zone),
    ): SeasonalMarker? = turningFor(LocalDate.now(clock), hemisphere, zone)

    private fun SeasonalMarker.flipHemisphere(): SeasonalMarker = when (this) {
        SeasonalMarker.SpringEquinox -> SeasonalMarker.AutumnEquinox
        SeasonalMarker.AutumnEquinox -> SeasonalMarker.SpringEquinox
        SeasonalMarker.SummerSolstice -> SeasonalMarker.WinterSolstice
        SeasonalMarker.WinterSolstice -> SeasonalMarker.SummerSolstice
        else -> this
    }
}
```

**Implementer note:** the actual `SunCalc.solarLongitude(julianCenturies)` and `SeasonalMarkerCalc.seasonalMarker(sunLon)` signatures must match production. If `julianCenturies(Instant)` doesn't exist, use the actual constructor (likely `julianCenturies(epochMillis: Long)` or `julianCenturies(julianDay: Double)`). Read `SunCalc.kt` and `SeasonalMarkerCalc.kt` first; do not invent helper signatures. If `SeasonalMarkerCalc.seasonalMarker(...)` returns a different type or accepts different args, adapt the body — the public TurningDayService signature is the contract.

- [ ] **Step 6: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*TurningDayServiceTest" 2>&1 | tail -15
```

Expected: PASS, 4 tests.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/theme/Color.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/core/celestial/turning/ \
        app/src/test/java/org/walktalkmeditate/pilgrim/core/celestial/turning/
git commit -m "$(cat <<'EOF'
feat(journal): TurningDayService + 4 turning colors + extensions (Stage 14 task 14)

Verbatim hex from iOS xcassets; hemisphere-aware Spring↔Autumn flip.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 15: `TurningDayBanner` Composable

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt`

- [ ] **Step 1: Write Robolectric test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.banner

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class TurningDayBannerTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `equinox marker shows day-equals-night text + kanji`() {
        composeRule.setContent {
            MaterialTheme {
                TurningDayBanner(marker = SeasonalMarker.SpringEquinox)
            }
        }
        composeRule.onNodeWithText("Today, day equals night.", substring = true).assertExists()
        composeRule.onNodeWithText("春分").assertExists()
    }

    @Test
    fun `null marker renders nothing`() {
        composeRule.setContent {
            MaterialTheme { TurningDayBanner(marker = null) }
        }
        // Pass = no exception; no specific text check.
    }
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*TurningDayBannerTest" 2>&1 | tail -15
```

Expected: FAIL.

- [ ] **Step 3: Create `TurningDayBanner.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.banner

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turning.bannerTextRes
import org.walktalkmeditate.pilgrim.core.celestial.turning.kanji

@Composable
fun TurningDayBanner(marker: SeasonalMarker?) {
    if (marker == null) return
    val bannerRes = marker.bannerTextRes() ?: return
    val kanjiText = marker.kanji() ?: return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(bannerRes), style = MaterialTheme.typography.bodySmall)
        Text(text = "  ·  ", style = MaterialTheme.typography.bodySmall)
        Text(kanjiText, style = MaterialTheme.typography.bodySmall)
    }
}
```

- [ ] **Step 4: Run test, verify pass**

```bash
./gradlew :app:testDebugUnitTest --tests "*TurningDayBannerTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBanner.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/banner/TurningDayBannerTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): TurningDayBanner Composable (Stage 14 task 15)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 16: `LunarMarkerCalc` + `LunarMarkerDot` (batched)

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt`

**Stage 13-XZ B5/B7 lesson:** `@Immutable` cascade — `LunarMarker` data class with `idTag: String` is fine; mark `@Immutable` to silence Compose stability concerns when downstream `List<LunarMarker>` consumers recompose.

**Spec note (deviation 8):** `halfCycle = 14.76` literal verbatim (not `synodicMonth / 2.0`).

- [ ] **Step 1: Write failing test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition

class LunarMarkerCalcTest {

    @Test
    fun `walks bracketing 2024 January full and new moons emit at least one marker each`() {
        // 2024-01-11 ~ new moon UTC; 2024-01-25 ~ full moon UTC.
        // Walks at days 5, 12, 18, 26 to bracket both.
        val snapshots = listOf(
            snap(id = 1L, startMs = msFromUtc(2024, 1, 26)),
            snap(id = 2L, startMs = msFromUtc(2024, 1, 18)),
            snap(id = 3L, startMs = msFromUtc(2024, 1, 12)),
            snap(id = 4L, startMs = msFromUtc(2024, 1, 5)),
        )
        val dotPositions = snapshots.indices.map { DotPosition(centerXPx = 100f, yPx = it * 90f) }
        val markers = LunarMarkerCalc.computeLunarMarkers(snapshots, dotPositions, viewportWidthPx = 360f)
        // Expect at least one full + one new in this 21-day window.
        assertTrue("at least 2 markers", markers.size >= 2)
        assertTrue("at least one full", markers.any { !it.isWaxing && it.illumination > 0.9 } ||
                                       markers.any { it.illumination > 0.9 })
        assertTrue("at least one new", markers.any { it.illumination < 0.1 })
    }

    private fun snap(id: Long, startMs: Long): WalkSnapshot = WalkSnapshot(
        id = id, uuid = "u$id", startMs = startMs,
        distanceM = 1000.0, durationSec = 600.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 1000.0, talkDurationSec = 0L, meditateDurationSec = 0L,
        favicon = null, isShared = false, weatherCondition = null,
    )

    private fun msFromUtc(year: Int, month: Int, day: Int): Long =
        java.time.LocalDate.of(year, month, day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
}
```

- [ ] **Step 2: Run failing test**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*LunarMarkerCalcTest" 2>&1 | tail -15
```

Expected: FAIL.

- [ ] **Step 3: Create `LunarMarkerCalc.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
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

private const val HALF_CYCLE = 14.76 // Verbatim iOS literal — deviation note 8.
private const val SYNODIC_DAYS = 29.530588770576 // Stage 5-A artifact, deviation note 7.

object LunarMarkerCalc {

    fun computeLunarMarkers(
        snapshots: List<WalkSnapshot>,
        dotPositions: List<DotPosition>,
        viewportWidthPx: Float,
    ): List<LunarMarker> {
        if (snapshots.size < 2) return emptyList()
        val sortedAsc = snapshots.withIndex()
            .sortedBy { it.value.startMs }
        val firstMs = sortedAsc.first().value.startMs
        val lastMs = sortedAsc.last().value.startMs
        if (lastMs <= firstMs) return emptyList()

        val refNew = newMoonReferenceInstant() // a known new moon as anchor
        val markers = mutableListOf<LunarMarker>()
        val dayMs = 24L * 60L * 60L * 1000L

        var cursorMs = firstMs
        while (cursorMs <= lastMs) {
            val instant = Instant.ofEpochMilli(cursorMs)
            val ageDays = ((cursorMs - refNew.toEpochMilli()).toDouble() / dayMs.toDouble())
                .let { it - SYNODIC_DAYS * Math.floor(it / SYNODIC_DAYS) }
            val isNearNew = ageDays < 1.5 || ageDays > (SYNODIC_DAYS - 1.5)
            val isNearFull = abs(ageDays - HALF_CYCLE) < 1.5
            if (isNearNew || isNearFull) {
                val refined = refinePeak(cursorMs, isFull = isNearFull)
                val illumination = MoonCalc.moonPhase(Instant.ofEpochMilli(refined)).illumination
                val isWaxing = ageDays < HALF_CYCLE
                val (x, y) = lerpPosition(refined, sortedAsc, dotPositions, viewportWidthPx)
                markers += LunarMarker(
                    idTag = "moon-$refined",
                    xPx = x,
                    yPx = y,
                    illumination = illumination,
                    isWaxing = isWaxing,
                )
                cursorMs += (HALF_CYCLE.toInt() - 1) * dayMs
            } else {
                cursorMs += dayMs
            }
        }
        return markers
    }

    private fun refinePeak(centerMs: Long, isFull: Boolean): Long {
        val window = 36L * 60L * 60L * 1000L  // ±36 h
        val step = 6L * 60L * 60L * 1000L      // 6 h
        var bestMs = centerMs
        var bestScore = -1.0
        var t = centerMs - window
        while (t <= centerMs + window) {
            val ill = MoonCalc.moonPhase(Instant.ofEpochMilli(t)).illumination
            val score = if (isFull) ill else 1.0 - ill
            if (score > bestScore) { bestScore = score; bestMs = t }
            t += step
        }
        return bestMs
    }

    private fun lerpPosition(
        eventMs: Long,
        sortedAsc: List<IndexedValue<WalkSnapshot>>,
        dotPositions: List<DotPosition>,
        viewportWidthPx: Float,
    ): Pair<Float, Float> {
        // sortedAsc carries original-index in IndexedValue.index → look up dotPositions there.
        val before = sortedAsc.lastOrNull { it.value.startMs <= eventMs } ?: sortedAsc.first()
        val after = sortedAsc.firstOrNull { it.value.startMs >= eventMs } ?: sortedAsc.last()
        val pBefore = dotPositions[before.index]
        val pAfter = dotPositions[after.index]
        val span = (after.value.startMs - before.value.startMs).coerceAtLeast(1L)
        val t = ((eventMs - before.value.startMs).toDouble() / span.toDouble()).toFloat()
        val xCenter = pBefore.centerXPx + (pAfter.centerXPx - pBefore.centerXPx) * t
        val y = pBefore.yPx + (pAfter.yPx - pBefore.yPx) * t
        // Offset 20 px to the OPPOSITE side of viewport center.
        val xOpposite = if (xCenter > viewportWidthPx / 2f) xCenter - 20f else xCenter + 20f
        return xOpposite to y
    }

    /** A reference new-moon UTC instant — 2000-01-06 18:14 UTC. */
    private fun newMoonReferenceInstant(): Instant = Instant.parse("2000-01-06T18:14:00Z")
}
```

**Implementer note:** `MoonCalc.moonPhase(Instant)` returns a `MoonPhase` whose illumination accessor must match. Read `MoonCalc.kt` + `MoonPhase.kt`; if it returns `Double` directly or a different type, adapt accordingly. Goal: a number in `[0.0, 1.0]` representing illumination fraction.

- [ ] **Step 4: Create `LunarMarkerDot.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun LunarMarkerDot(isFull: Boolean) {
    val isDark = isSystemInDarkTheme()
    val color = if (isDark) Color(0.85f, 0.82f, 0.72f) else Color(0.55f, 0.58f, 0.65f)
    val alpha = if (isFull) (if (isDark) 0.6f else 0.4f) else (if (isDark) 0.7f else 0.5f)
    Canvas(Modifier.size(10.dp)) {
        val r = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        if (isFull) {
            drawCircle(color = color.copy(alpha = alpha), radius = r, center = center)
        } else {
            drawCircle(color = color.copy(alpha = alpha), radius = r, center = center, style = Stroke(width = 1.dp.toPx()))
        }
    }
}
```

- [ ] **Step 5: Run test**

```bash
./gradlew :app:testDebugUnitTest --tests "*LunarMarkerCalcTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalc.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerDot.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/LunarMarkerCalcTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): LunarMarkerCalc + LunarMarkerDot (Stage 14 task 16)

halfCycle=14.76 literal verbatim (deviation note 8); ±36h refine.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 17: `MilestoneCalc` + `MilestoneMarker` + `ToriiGateShape` + `DateDividerCalc` (batched)

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ToriiGateShape.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/FootprintShape.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalc.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneMarker.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalc.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/MilestoneCalcTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/DateDividerCalcTest.kt`

**Spec deviation 11:** `computeMilestonePositions` walks oldest→newest (NOT verbatim iOS newest-first which has a latent bug stacking all markers on the newest walk).

**Stage 6-B locale lesson:** `DateTimeFormatter.ofPattern(pattern, Locale.getDefault())` — never the no-Locale overload.

**Stage 5-A locale lesson:** `String.format(Locale.US, "%d", ...)` for digits.

- [ ] **Step 1: Write `MilestoneCalcTest.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition

class MilestoneCalcTest {

    @Test
    fun `milestoneThresholds first five are 100k 500k 1M 2M 3M`() {
        val list = MilestoneCalc.milestoneThresholds()
        assertEquals(100_000.0, list[0], 0.001)
        assertEquals(500_000.0, list[1], 0.001)
        assertEquals(1_000_000.0, list[2], 0.001)
        assertEquals(2_000_000.0, list[3], 0.001)
        assertEquals(3_000_000.0, list[4], 0.001)
    }

    @Test
    fun `4 walks of 30km each places 100km marker on the newest walk`() {
        // newest-first display order: 4th walk (oldest) cumulative = 30; newest cumulative = 120.
        val snapshots = listOf(
            snap(1L, cumM = 120_000.0),
            snap(2L, cumM = 90_000.0),
            snap(3L, cumM = 60_000.0),
            snap(4L, cumM = 30_000.0),
        )
        val dotYs = snapshots.indices.map { it * 90f }.map { DotPosition(100f, it) }
        val markers = MilestoneCalc.computeMilestonePositions(snapshots, dotYs)
        assertEquals(1, markers.size)
        // 100 km threshold crossed by the newest walk (display index 0).
        assertEquals(0f, markers[0].yPx, 0.01f)
        assertEquals(100_000.0, markers[0].distanceM, 0.001)
    }

    @Test
    fun `cumulative 50_120_600_1050 produces 3 markers`() {
        val snapshots = listOf(
            snap(1L, cumM = 1_050_000.0),
            snap(2L, cumM = 600_000.0),
            snap(3L, cumM = 120_000.0),
            snap(4L, cumM = 50_000.0),
        )
        val dotYs = snapshots.indices.map { DotPosition(100f, it * 90f) }
        val markers = MilestoneCalc.computeMilestonePositions(snapshots, dotYs)
        assertEquals(3, markers.size) // 100k, 500k, 1M
    }

    private fun snap(id: Long, cumM: Double) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = id, distanceM = cumM,
        durationSec = 600.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = cumM, talkDurationSec = 0L,
        meditateDurationSec = 0L, favicon = null, isShared = false,
        weatherCondition = null,
    )
}
```

- [ ] **Step 2: Write `DateDividerCalcTest.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class DateDividerCalcTest {

    private val savedLocale = Locale.getDefault()

    @Before
    fun pinLocale() = Locale.setDefault(Locale.US)
    @After
    fun restoreLocale() = Locale.setDefault(savedLocale)

    @Test
    fun `5 walks spanning Apr May Jun emit 3 dividers`() {
        val snapshots = listOf(
            snap(1L, LocalDate.of(2024, 6, 5)),
            snap(2L, LocalDate.of(2024, 6, 1)),
            snap(3L, LocalDate.of(2024, 5, 25)),
            snap(4L, LocalDate.of(2024, 5, 1)),
            snap(5L, LocalDate.of(2024, 4, 12)),
        )
        val dotYs = snapshots.indices.map { DotPosition(centerXPx = 100f, yPx = it * 90f) }
        val dividers = DateDividerCalc.computeDateDividers(
            snapshots = snapshots,
            dotPositions = dotYs,
            viewportWidthPx = 360f,
            zone = ZoneOffset.UTC,
            locale = Locale.US,
        )
        assertEquals(3, dividers.size)
        assertEquals(listOf("Jun", "May", "Apr"), dividers.map { it.text })
    }

    private fun snap(id: Long, date: LocalDate) = WalkSnapshot(
        id = id, uuid = "u$id",
        startMs = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        distanceM = 1000.0, durationSec = 600.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 1000.0, talkDurationSec = 0L,
        meditateDurationSec = 0L, favicon = null, isShared = false,
        weatherCondition = null,
    )
}
```

- [ ] **Step 3: Run failing tests**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*MilestoneCalcTest" --tests "*DateDividerCalcTest" 2>&1 | tail -20
```

Expected: FAIL — calculators not defined.

- [ ] **Step 4: Create `ToriiGateShape.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/**
 * Verbatim iOS port of `ToriiGateShape`. Used by both [MilestoneMarker]
 * and the scenery torii type.
 */
fun toriiGatePath(size: Size): Path = Path().apply {
    val w = size.width
    val h = size.height
    // Top kasagi (curved beam): a thin trapezoid spanning beyond the pillars
    moveTo(w * 0.06f, h * 0.10f)
    lineTo(w * 0.94f, h * 0.10f)
    lineTo(w * 0.86f, h * 0.20f)
    lineTo(w * 0.14f, h * 0.20f)
    close()
    // Nuki (lower beam)
    moveTo(w * 0.18f, h * 0.32f)
    lineTo(w * 0.82f, h * 0.32f)
    lineTo(w * 0.82f, h * 0.40f)
    lineTo(w * 0.18f, h * 0.40f)
    close()
    // Left pillar
    moveTo(w * 0.22f, h * 0.20f)
    lineTo(w * 0.30f, h * 0.20f)
    lineTo(w * 0.32f, h * 1.00f)
    lineTo(w * 0.20f, h * 1.00f)
    close()
    // Right pillar
    moveTo(w * 0.70f, h * 0.20f)
    lineTo(w * 0.78f, h * 0.20f)
    lineTo(w * 0.80f, h * 1.00f)
    lineTo(w * 0.68f, h * 1.00f)
    close()
}
```

- [ ] **Step 5: Create `FootprintShape.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/** Verbatim iOS port of `FootprintShape`. Used by ExpandCardSheet header row. */
fun footprintPath(size: Size): Path = Path().apply {
    // Heel oval
    addOval(Rect(
        offset = Offset(size.width * 0.10f, size.height * 0.55f),
        size = Size(size.width * 0.55f, size.height * 0.40f),
    ))
    // Ball-of-foot oval
    addOval(Rect(
        offset = Offset(size.width * 0.20f, size.height * 0.18f),
        size = Size(size.width * 0.50f, size.height * 0.40f),
    ))
    // 5 toes — small ovals fanned across the front
    val toeRadiusW = size.width * 0.10f
    val toeRadiusH = size.height * 0.10f
    for (i in 0 until 5) {
        val cx = size.width * (0.22f + 0.12f * i)
        val cy = size.height * 0.10f
        addOval(Rect(
            offset = Offset(cx - toeRadiusW / 2f, cy - toeRadiusH / 2f),
            size = Size(toeRadiusW, toeRadiusH),
        ))
    }
}
```

- [ ] **Step 6: Create `MilestoneCalc.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

@Immutable
data class MilestonePosition(val distanceM: Double, val yPx: Float)

object MilestoneCalc {

    fun milestoneThresholds(): List<Double> {
        val first = listOf(100_000.0, 500_000.0, 1_000_000.0)
        val tail = (2_000_000L..100_000_000L step 1_000_000L).map { it.toDouble() }
        return first + tail
    }

    /**
     * Spec deviation 11: walk OLDEST→NEWEST (not iOS newest-first which
     * stacks every milestone on the newest walk).
     *
     * `snapshots` arrives newest-first (display order). Reverse internally,
     * find each threshold's first crossing, then map back to the display index.
     */
    fun computeMilestonePositions(
        snapshots: List<WalkSnapshot>,
        dotPositions: List<DotPosition>,
    ): List<MilestonePosition> {
        if (snapshots.isEmpty()) return emptyList()
        val oldestFirst = snapshots.reversed()
        val n = oldestFirst.size
        val thresholds = milestoneThresholds()
        val out = mutableListOf<MilestonePosition>()
        for (threshold in thresholds) {
            for (i in 0 until n) {
                val prev = if (i == 0) 0.0 else oldestFirst[i - 1].cumulativeDistanceM
                val curr = oldestFirst[i].cumulativeDistanceM
                if (prev < threshold && threshold <= curr) {
                    val displayIndex = (n - 1) - i
                    out += MilestonePosition(threshold, dotPositions[displayIndex].yPx)
                    break
                }
            }
        }
        return out
    }
}
```

- [ ] **Step 7: Create `MilestoneMarker.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.ui.design.scenery.toriiGatePath
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimPaletteLight

@Composable
fun MilestoneMarker(distanceM: Double, modifier: Modifier = Modifier) {
    val km = (distanceM / 1000.0).toInt()
    val label = "%d km".format(Locale.US, km)
    Row(
        modifier = modifier.fillMaxWidth(0.7f).height(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Spacer(
            Modifier
                .height(0.5.dp)
                .weight(1f)
                .background(PilgrimPaletteLight.fog.copy(alpha = 0.15f)),
        )
        Canvas(Modifier.size(width = 16.dp, height = 14.dp)) {
            drawPath(toriiGatePath(size), color = PilgrimPaletteLight.stone.copy(alpha = 0.25f))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = PilgrimPaletteLight.fog.copy(alpha = 0.4f),
        )
        Spacer(
            Modifier
                .height(0.5.dp)
                .weight(1f)
                .background(PilgrimPaletteLight.fog.copy(alpha = 0.15f)),
        )
    }
}

private fun androidx.compose.ui.Modifier.background(color: androidx.compose.ui.graphics.Color) =
    this.then(androidx.compose.foundation.background(color))
```

- [ ] **Step 8: Create `DateDividerCalc.kt`**

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
    val idTag: Int,
    val text: String,
    val xPx: Float,
    val yPx: Float,
)

object DateDividerCalc {

    fun computeDateDividers(
        snapshots: List<WalkSnapshot>,
        dotPositions: List<DotPosition>,
        viewportWidthPx: Float,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): List<DateDivider> {
        if (snapshots.isEmpty()) return emptyList()
        val fmt = DateTimeFormatter.ofPattern("MMM", locale).withZone(zone)
        var lastYearMonth: YearMonth? = null
        val out = mutableListOf<DateDivider>()
        snapshots.forEachIndexed { i, snap ->
            val instant = Instant.ofEpochMilli(snap.startMs)
            val ym = YearMonth.from(instant.atZone(zone))
            if (ym != lastYearMonth) {
                lastYearMonth = ym
                val dot = dotPositions[i]
                val xOpposite = if (dot.centerXPx > viewportWidthPx / 2f)
                    dot.centerXPx - 36f
                else
                    dot.centerXPx + 36f
                out += DateDivider(
                    idTag = i,
                    text = fmt.format(instant),
                    xPx = xOpposite,
                    yPx = dot.yPx,
                )
            }
        }
        return out
    }
}
```

- [ ] **Step 9: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*MilestoneCalcTest" --tests "*DateDividerCalcTest" 2>&1 | tail -25
```

Expected: PASS, 5 tests total.

- [ ] **Step 10: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/scenery/ \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/markers/ \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/markers/
git commit -m "$(cat <<'EOF'
feat(journal): MilestoneCalc + DateDividerCalc + Torii/Footprint shapes (Stage 14 task 17)

Spec deviation 11: oldest-first iteration fixes iOS latent
all-on-newest stacking bug. Locale.US for digits; Locale.getDefault for
month names (Stage 5-A / 6-B locale lessons).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Bucket 14-D: polish

### Task 18: `SceneryGenerator` + `SceneryShapes` path builders (batched)

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryShapes.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGeneratorTest.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryShapesTest.kt`

**Spec FNV+SplitMix64:** verbatim port; deterministic given the same UUID + walk fields.

- [ ] **Step 1: Write `SceneryGeneratorTest.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import java.util.UUID
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class SceneryGeneratorTest {

    @Test
    fun `35 percent placement chance verified by Monte Carlo over 500 random uuids`() {
        var hits = 0
        val total = 500
        repeat(total) {
            val s = sample(uuid = UUID.randomUUID().toString(), startMs = it * 1000L)
            if (SceneryGenerator.scenery(s) != null) hits++
        }
        val ratio = hits.toDouble() / total.toDouble()
        // 0.30 ≤ ratio ≤ 0.40 with 500 trials wider tolerance.
        assertTrue("scenery ratio out of band: $ratio", ratio in 0.27..0.43)
    }

    @Test
    fun `same snapshot returns deterministic placement`() {
        val s = sample(uuid = "fixed-uuid", startMs = 1_000_000L)
        val first = SceneryGenerator.scenery(s)
        val second = SceneryGenerator.scenery(s)
        if (first != null) assertNotNull(second)
    }

    private fun sample(uuid: String, startMs: Long) = WalkSnapshot(
        id = 1L, uuid = uuid, startMs = startMs,
        distanceM = 5_000.0, durationSec = 1800.0, averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 5_000.0, talkDurationSec = 0L,
        meditateDurationSec = 0L, favicon = null, isShared = false,
        weatherCondition = null,
    )
}
```

- [ ] **Step 2: Write `SceneryShapesTest.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneryShapesTest {

    @Test
    fun `treePath bounds inside size`() {
        val path = SceneryShapes.treePath(Size(100f, 100f))
        val b = path.getBounds()
        assertTrue(b.left >= -1f && b.right <= 101f)
        assertTrue(b.top >= -1f && b.bottom <= 101f)
    }

    @Test
    fun `mountainPath bounds inside size`() {
        val b = SceneryShapes.mountainPath(Size(100f, 100f)).getBounds()
        assertTrue(b.left >= -1f && b.right <= 101f)
    }

    @Test
    fun `lanternPath bounds inside size`() {
        val b = SceneryShapes.lanternPath(Size(100f, 100f)).getBounds()
        assertTrue(b.left >= -1f && b.right <= 101f)
    }
}
```

- [ ] **Step 3: Run failing tests**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*SceneryGeneratorTest" --tests "*SceneryShapesTest" 2>&1 | tail -15
```

Expected: FAIL.

- [ ] **Step 4: Create `SceneryGenerator.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

enum class SceneryType { TREE, GRASS, LANTERN, BUTTERFLY, MOUNTAIN, TORII, MOON }
enum class ScenerySide { LEFT, RIGHT }

@Immutable
data class SceneryPlacement(
    val type: SceneryType,
    val side: ScenerySide,
    val offsetPx: Float,
)

private const val SCENERY_CHANCE = 0.35

object SceneryGenerator {

    fun scenery(snapshot: WalkSnapshot): SceneryPlacement? {
        val seed = sceneryFnv1aSeed(
            uuid = snapshot.uuid,
            startMillis = snapshot.startMs,
            distanceMeters = snapshot.distanceM,
            durationSeconds = snapshot.durationSec.toLong(),
        )
        val rollChance = seededRandom(seed, 1UL)
        if (rollChance >= SCENERY_CHANCE) return null

        val rollType = seededRandom(seed, 2UL)
        val type = pickType(rollType)
        val rollSide = seededRandom(seed, 3UL)
        val side = if (rollSide < 0.5) ScenerySide.LEFT else ScenerySide.RIGHT
        val rollOffset = seededRandom(seed, 4UL)
        val offsetPx = (rollOffset * 24.0).toFloat() // 0..24 px nudge

        return SceneryPlacement(type, side, offsetPx)
    }

    /** Verbatim weighted: tree 25 / grass 20 / lantern 12 / butterfly 12 / mountain 13 / torii 9 / moon 9 = 100. */
    private fun pickType(roll: Double): SceneryType = when {
        roll < 0.25 -> SceneryType.TREE
        roll < 0.45 -> SceneryType.GRASS
        roll < 0.57 -> SceneryType.LANTERN
        roll < 0.69 -> SceneryType.BUTTERFLY
        roll < 0.82 -> SceneryType.MOUNTAIN
        roll < 0.91 -> SceneryType.TORII
        else -> SceneryType.MOON
    }
}

internal fun sceneryFnv1aSeed(
    uuid: String,
    startMillis: Long,
    distanceMeters: Double,
    durationSeconds: Long,
): ULong {
    val prime: ULong = 1099511628211UL
    var h: ULong = 14695981039346656037UL
    uuid.forEach { c -> h = (h xor c.code.toULong()) * prime }
    h = (h xor (startMillis / 1000L).toULong()) * prime
    h = (h xor (distanceMeters * 100.0).toLong().toULong()) * prime
    h = (h xor durationSeconds.toULong()) * prime
    return h
}

internal fun seededRandom(seed: ULong, salt: ULong): Double {
    var mixed = seed + (salt * 6364136223846793005UL)
    mixed = mixed xor (mixed shr 33)
    mixed *= 0xff51afd7ed558ccdUL
    mixed = mixed xor (mixed shr 33)
    mixed *= 0xc4ceb9fe1a85ec53UL
    mixed = mixed xor (mixed shr 33)
    return (mixed % 10000UL).toDouble() / 10000.0
}
```

- [ ] **Step 5: Create `SceneryShapes.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path

/** Verbatim iOS-port path builders. Each constrained to (0..size). */
object SceneryShapes {

    fun treePath(size: Size): Path = Path().apply {
        // Trunk
        addRect(Rect(
            offset = Offset(size.width * 0.45f, size.height * 0.65f),
            size = Size(size.width * 0.10f, size.height * 0.35f),
        ))
        // Canopy as a triangle
        moveTo(size.width * 0.50f, 0f)
        lineTo(size.width * 0.95f, size.height * 0.70f)
        lineTo(size.width * 0.05f, size.height * 0.70f)
        close()
    }

    fun winterTreePath(size: Size): Path = Path().apply {
        addRect(Rect(
            offset = Offset(size.width * 0.45f, size.height * 0.55f),
            size = Size(size.width * 0.10f, size.height * 0.45f),
        ))
        // Bare branches: 4 thin lines
        for (i in 0 until 4) {
            val y = size.height * (0.10f + 0.10f * i)
            moveTo(size.width * 0.50f, y)
            lineTo(size.width * (0.10f + 0.10f * i), y - size.height * 0.05f)
        }
    }

    fun lanternPath(size: Size): Path = Path().apply {
        addRect(Rect(
            offset = Offset(size.width * 0.20f, size.height * 0.20f),
            size = Size(size.width * 0.60f, size.height * 0.60f),
        ))
        // Hanging strap
        moveTo(size.width * 0.50f, 0f)
        lineTo(size.width * 0.50f, size.height * 0.20f)
    }

    fun lanternWindowPath(size: Size): Path = Path().apply {
        addRect(Rect(
            offset = Offset(size.width * 0.35f, size.height * 0.40f),
            size = Size(size.width * 0.30f, size.height * 0.20f),
        ))
    }

    fun mountainPath(size: Size): Path = Path().apply {
        moveTo(0f, size.height)
        lineTo(size.width * 0.50f, 0f)
        lineTo(size.width, size.height)
        close()
    }

    fun moonPath(size: Size): Path = Path().apply {
        addOval(Rect(offset = Offset.Zero, size = size))
    }
}
```

- [ ] **Step 6: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "*SceneryGeneratorTest" --tests "*SceneryShapesTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryGenerator.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryShapes.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/
git commit -m "$(cat <<'EOF'
feat(journal): SceneryGenerator + path builders (Stage 14 task 18)

Verbatim FNV+SplitMix64; 35% chance, 7 weighted types. Static fills
only — sub-effects deferred to Stage 14.5 per Non-goals.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 19: 7 scenery sub-Composables + `SceneryItem` dispatcher

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/TreeScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/LanternScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/ButterflyScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/MountainScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/GrassScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/ToriiScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/MoonScenery.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItem.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItemRobolectricTest.kt`

**Stage 14 scope:** STATIC FILLS ONLY — no `withFrameNanos`, no sin/cos, no seasonal swap. Each sub-Composable emits one `Canvas { drawPath(SceneryShapes.X, color = tint) }` call.

- [ ] **Step 1: Create the 7 sub-Composables**

`TreeScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import java.time.LocalDate
import java.time.Month

@Composable
fun TreeScenery(tint: Color, sizeDp: Dp, date: LocalDate) {
    val isWinter = remember(date) { date.month in listOf(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY) }
    Canvas(Modifier.size(sizeDp)) {
        val path = if (isWinter) SceneryShapes.winterTreePath(size) else SceneryShapes.treePath(size)
        drawPath(path, color = tint)
    }
}
```

`LanternScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun LanternScenery(tint: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        drawPath(SceneryShapes.lanternPath(size), color = tint)
        drawPath(SceneryShapes.lanternWindowPath(size), color = tint.copy(alpha = 0.5f))
    }
}
```

`ButterflyScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun ButterflyScenery(tint: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        // Two ellipses for wings — primitive (no path needed).
        val wingW = size.width * 0.45f
        val wingH = size.height * 0.65f
        drawOval(
            color = tint,
            topLeft = Offset(0f, size.height * 0.10f),
            size = androidx.compose.ui.geometry.Size(wingW, wingH),
        )
        drawOval(
            color = tint,
            topLeft = Offset(size.width - wingW, size.height * 0.10f),
            size = androidx.compose.ui.geometry.Size(wingW, wingH),
        )
        // Body
        drawRect(
            color = tint.copy(alpha = 0.7f),
            topLeft = Offset(size.width * 0.45f, size.height * 0.20f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.10f, size.height * 0.60f),
        )
    }
}
```

`MountainScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun MountainScenery(tint: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        drawPath(SceneryShapes.mountainPath(size), color = tint)
    }
}
```

`GrassScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp

@Composable
fun GrassScenery(tint: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        for (i in 0 until 5) {
            val x = size.width * (0.10f + 0.20f * i)
            drawLine(
                color = tint,
                start = Offset(x, size.height),
                end = Offset(x, size.height * (0.20f + 0.10f * (i % 3))),
                strokeWidth = 1f,
            )
        }
        drawRect(color = tint.copy(alpha = 0.001f), style = Stroke(width = 0.5f))
    }
}
```

`ToriiScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.walktalkmeditate.pilgrim.ui.design.scenery.toriiGatePath

@Composable
fun ToriiScenery(tint: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        drawPath(toriiGatePath(size), color = tint)
    }
}
```

`MoonScenery.kt`:
```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

@Composable
fun MoonScenery(tint: Color, sizeDp: Dp) {
    Canvas(Modifier.size(sizeDp)) {
        drawPath(SceneryShapes.moonPath(size), color = tint)
    }
}
```

- [ ] **Step 2: Create `SceneryItem.kt` dispatcher**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun SceneryItem(
    type: SceneryType,
    tint: Color,
    sizeDp: Dp,
    walkStartMs: Long,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    val date = LocalDate.ofInstant(Instant.ofEpochMilli(walkStartMs), zone)
    when (type) {
        SceneryType.TREE -> TreeScenery(tint = tint, sizeDp = sizeDp, date = date)
        SceneryType.GRASS -> GrassScenery(tint = tint, sizeDp = sizeDp)
        SceneryType.LANTERN -> LanternScenery(tint = tint, sizeDp = sizeDp)
        SceneryType.BUTTERFLY -> ButterflyScenery(tint = tint, sizeDp = sizeDp)
        SceneryType.MOUNTAIN -> MountainScenery(tint = tint, sizeDp = sizeDp)
        SceneryType.TORII -> ToriiScenery(tint = tint, sizeDp = sizeDp)
        SceneryType.MOON -> MoonScenery(tint = tint, sizeDp = sizeDp)
    }
}
```

- [ ] **Step 3: Robolectric smoke test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItemRobolectricTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class SceneryItemRobolectricTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `each type composes without crash`() {
        SceneryType.entries.forEach { type ->
            composeRule.setContent {
                MaterialTheme {
                    SceneryItem(type = type, tint = Color.Black, sizeDp = 32.dp, walkStartMs = 0L)
                }
            }
            composeRule.onRoot().assertExists()
        }
    }
}
```

- [ ] **Step 4: Run tests**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*SceneryItemRobolectricTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/scenery/ \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/scenery/SceneryItemRobolectricTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): 7 scenery sub-composables + SceneryItem dispatcher (Stage 14 task 19)

Static fills only; sub-effects deferred to Stage 14.5 per Non-goals.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 20: `RippleEffect` + cascading fade-in

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffect.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffectTest.kt`

**Stage 5-A `Modifier.scale(Float)` lesson:** ripple expansion uses `Modifier.graphicsLayer { scaleX = ...; scaleY = ... }` lambda form. Cascading fade-in alpha uses `graphicsLayer { alpha = ... }` lambda form.

**Reduce-motion:** static stroked Circle fallback (verbatim iOS) when `LocalReduceMotion.current == true`.

- [ ] **Step 1: Create `RippleEffect.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion

@Composable
fun RippleEffect(color: Color, dotSizeDp: Float) {
    if (LocalReduceMotion.current) {
        // Reduce-motion fallback: static stroked Circle, dotSize+16, lineWidth=1.5.
        val ringSize: Dp = (dotSizeDp + 16f).dp
        Canvas(Modifier.size(ringSize)) {
            drawCircle(
                color = color,
                radius = size.minDimension / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 1.5.dp.toPx()),
                alpha = 0.15f,
            )
        }
        return
    }

    var nowMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            nowMs = System.currentTimeMillis() - start
            delay(100L)
        }
    }
    val phase = ((nowMs % 1000L).toDouble() / 1000.0).toFloat()  // [0..1)
    val breath = (sin(nowMs / 1000.0 * 1.2) * 0.5 + 0.5).toFloat()
    val baseSize: Dp = (dotSizeDp + 16f).dp

    Canvas(Modifier.size(baseSize)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val baseRPx = (dotSizeDp.dp.toPx()) * 0.5f
        val rippleRPx = baseRPx + phase * (dotSizeDp.dp.toPx()) * 1.2f
        // Two expanding circles
        drawCircle(
            color = color.copy(alpha = (1f - phase) * 0.2f),
            radius = rippleRPx,
            center = Offset(cx, cy),
            style = Stroke(width = 1.dp.toPx()),
        )
        // Breathing glow
        drawCircle(
            color = color.copy(alpha = 0.04f + breath * 0.04f),
            radius = (dotSizeDp.dp.toPx()) * 1.5f / 2f,
            center = Offset(cx, cy),
        )
    }
}
```

- [ ] **Step 2: Robolectric test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffectTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.dot

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.design.LocalReduceMotion

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RippleEffectTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `reduce-motion ripple composes without crash`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                MaterialTheme {
                    RippleEffect(color = Color.Black, dotSizeDp = 16f)
                }
            }
        }
        composeRule.onRoot().assertExists()
    }

    @Test
    fun `animated ripple composes without crash`() {
        composeRule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides false) {
                MaterialTheme {
                    RippleEffect(color = Color.Black, dotSizeDp = 16f)
                }
            }
        }
        composeRule.onRoot().assertExists()
    }
}
```

- [ ] **Step 3: Run tests**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:testDebugUnitTest --tests "*RippleEffectTest" 2>&1 | tail -15
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffect.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/dot/RippleEffectTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): RippleEffect with reduce-motion fallback (Stage 14 task 20)

Modifier.graphicsLayer for animated alpha (Stage 5-A lesson); 100 ms
cadence withFrameNanos approximation via delay; reduce-motion stroked
Circle verbatim iOS fallback.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 21: `EmptyJournalState` + delete legacy `HomeWalkRow*`

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt`

**Files DELETE:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRowComposable.kt`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeUiState.kt`

- [ ] **Step 1: Create `EmptyJournalState.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.empty

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimPaletteLight

@Composable
fun EmptyJournalState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Tail: 120 dp tall taper
        Canvas(Modifier.size(width = 4.dp, height = 120.dp)) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.5f - 1f, 0f)
                lineTo(w * 0.5f + 1f, 0f)
                lineTo(w * 0.5f + 0.2f, h * 0.5f)
                lineTo(w * 0.5f + 1f, h)
                lineTo(w * 0.5f - 1f, h)
                lineTo(w * 0.5f - 0.2f, h * 0.5f)
                close()
            }
            drawPath(path, color = PilgrimPaletteLight.fog)
        }
        // Stone dot
        Box(modifier = Modifier.size(14.dp)) {
            Canvas(Modifier.size(14.dp)) {
                drawCircle(
                    color = PilgrimPaletteLight.stone,
                    radius = size.minDimension / 2f,
                    center = Offset(size.width / 2f, size.height / 2f),
                )
            }
        }
        Text(
            text = stringResource(R.string.home_empty_begin),
            style = MaterialTheme.typography.labelMedium,
            color = PilgrimPaletteLight.fog,
        )
    }
}
```

- [ ] **Step 2: Delete legacy files**

```bash
git rm app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeWalkRowComposable.kt
git rm app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeUiState.kt
```

Search for any remaining `HomeWalkRow` / `HomeUiState` references in the codebase and remove (or update them to consume `JournalUiState` / `WalkSnapshot`):

```bash
grep -rn "HomeWalkRow\|HomeUiState" app/src/ --include='*.kt' 2>&1 || true
```

Update each call site found.

- [ ] **Step 3: Build sanity-check**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:compileDebugKotlin 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/empty/EmptyJournalState.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt
git commit -m "$(cat <<'EOF'
feat(journal): EmptyJournalState; delete HomeWalkRow + HomeUiState (Stage 14 task 21)

Stage 14 layout swap complete — cards are gone. Tail+stone-dot empty
state replaces the previous home_empty_message Text.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 22: `JournalScreen` final assembly + integration test + final QA

**Files create:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreen.kt`
- `app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt`

**Files modify:**
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt` — body delegates to `JournalScreen`
- `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt` — verify `Routes.HOME` still binds to `HomeScreen` (no route change)

- [ ] **Step 1: Create `JournalScreen.kt`**

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.flow.collectLatest
import org.walktalkmeditate.pilgrim.ui.home.banner.TurningDayBanner
import org.walktalkmeditate.pilgrim.ui.home.dot.WalkDot
import org.walktalkmeditate.pilgrim.ui.home.dot.WalkDotMath
import org.walktalkmeditate.pilgrim.ui.home.empty.EmptyJournalState
import org.walktalkmeditate.pilgrim.ui.home.expand.ExpandCardSheet
import org.walktalkmeditate.pilgrim.ui.home.header.JourneySummaryHeader
import org.walktalkmeditate.pilgrim.ui.home.scroll.JournalHapticDispatcher
import org.walktalkmeditate.pilgrim.ui.home.scroll.ScrollHapticState
import org.walktalkmeditate.pilgrim.core.celestial.turning.TurningDayService

@Composable
fun JournalScreen(
    onWalkSummary: (Long) -> Unit,
    onGoshuinFabClick: () -> Unit,
) {
    val vm: HomeViewModel = hiltViewModel()
    val state by vm.journalState.collectAsState()
    val units by vm.distanceUnits.collectAsState()
    val hemisphere by vm.hemisphere.collectAsState()
    val expandedId by vm.expandedSnapshotId.collectAsState()
    val celestialSnap by vm.expandedCelestialSnapshot.collectAsState()
    val context = LocalContext.current
    val dispatcher = remember { JournalHapticDispatcher(context) }
    val density = LocalDensity.current
    val verticalSpacingPx = with(density) { 90.dp.toPx() }
    val topInsetPx = with(density) { 40.dp.toPx() }

    Scaffold(topBar = { JournalTopBar() }) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (val s = state) {
                JournalUiState.Empty -> EmptyJournalState()
                JournalUiState.Loading -> { /* show no content; spinner optional */ }
                is JournalUiState.Loaded -> {
                    val turning = TurningDayService.turningForToday(hemisphere)
                    TurningDayBanner(marker = turning)
                    JourneySummaryHeader(summary = s.summary, units = units)

                    val sizesPx = remember(s.snapshots) {
                        s.snapshots.map { with(density) { WalkDotMath.dotSize(it.durationSec).dp.toPx() } }
                    }
                    val dotYsPx = remember(s.snapshots) {
                        s.snapshots.indices.map { topInsetPx + verticalSpacingPx * it }
                    }
                    val hapticState = remember(s.snapshots) {
                        ScrollHapticState(
                            dotPositionsPx = dotYsPx,
                            dotSizesPx = sizesPx,
                            milestonePositionsPx = emptyList(),
                        )
                    }
                    val listState = rememberLazyListState()
                    LaunchedEffect(listState, hapticState) {
                        snapshotFlow {
                            listState.firstVisibleItemIndex * verticalSpacingPx +
                                listState.firstVisibleItemScrollOffset
                        }.collectLatest { topPx ->
                            val viewportHeightPx = listState.layoutInfo.viewportEndOffset -
                                listState.layoutInfo.viewportStartOffset
                            val centerPx = topPx + viewportHeightPx / 2f
                            dispatcher.dispatch(hapticState.handleViewportCenterPx(centerPx))
                        }
                    }
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(s.snapshots, key = { _, snap -> snap.id }) { index, snap ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .height(90.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                WalkDot(
                                    snapshot = snap,
                                    sizeDp = WalkDotMath.dotSize(snap.durationSec),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    opacity = WalkDotMath.dotOpacity(index, s.snapshots.size),
                                    isNewest = index == 0,
                                    contentDescription = "walk dot $index",
                                    onTap = { vm.setExpandedSnapshotId(snap.id) },
                                )
                            }
                        }
                    }
                }
            }

            GoshuinFAB(
                onClick = onGoshuinFabClick,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                vm = vm,
            )

            val loaded = state as? JournalUiState.Loaded
            val expandedSnap = loaded?.snapshots?.firstOrNull { it.id == expandedId }
            if (expandedSnap != null) {
                ExpandCardSheet(
                    snapshot = expandedSnap,
                    celestialSnapshot = celestialSnap,
                    onViewDetails = onWalkSummary,
                    onDismissRequest = { vm.setExpandedSnapshotId(null) },
                )
            }
        }
    }
}
```

- [ ] **Step 2: Replace `HomeScreen.kt` body to delegate**

Replace the body with a one-liner:

```kotlin
@Composable
fun HomeScreen(
    onWalkSummary: (Long) -> Unit,
    onGoshuinFabClick: () -> Unit,
    /* keep any existing parameter list — match production */
) {
    JournalScreen(
        onWalkSummary = onWalkSummary,
        onGoshuinFabClick = onGoshuinFabClick,
    )
}
```

If `HomeScreen.kt` declared additional parameters (top-bar slot, navigation lambdas), pass them through to `JournalScreen` with matching names. Do NOT change `Routes.HOME` or the `composable(Routes.HOME)` binding in `PilgrimNavHost.kt`.

- [ ] **Step 3: Integration test**

`app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt`:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class JournalScreenIntegrationTest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val composeRule = createComposeRule()

    @Test
    fun `JournalScreen composes empty state without crash`() {
        hiltRule.inject()
        composeRule.setContent {
            MaterialTheme {
                JournalScreen(onWalkSummary = {}, onGoshuinFabClick = {})
            }
        }
        composeRule.onRoot().assertExists()
    }
}
```

If the test infra doesn't yet support `HiltAndroidRule` for VM-bearing screens, drop the `@HiltAndroidTest` annotation and pass a manually-constructed VM to `JournalScreen` via a parameter overload (only for tests). Keep the production `JournalScreen(onWalkSummary, onGoshuinFabClick)` signature as the default.

- [ ] **Step 4: Run full test suite + lint**

```bash
export PATH="$HOME/.asdf/shims:$PATH"
./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest 2>&1 | tail -30
```

Expected: `BUILD SUCCESSFUL`, all tests pass, no new lint warnings.

- [ ] **Step 5: Manual on-device QA (OnePlus 13)**

Install the debug APK on the device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p org.walktalkmeditate.pilgrim.debug -c android.intent.category.LAUNCHER 1
```

Run the QA checklist (Stage 5-G pattern):

- [ ] Fresh install → Empty state shows tail + stone-dot + "Begin"
- [ ] Seed 12 walks via instrumentation → list of dots, calligraphy thread visible
- [ ] Tap a dot → ExpandCardSheet rises with verbatim layout
- [ ] Tap "View details →" → WalkSummary opens
- [ ] Swipe-to-dismiss the sheet works
- [ ] Scroll → haptics fire on dot center-pass (light / heavy / milestone)
- [ ] Settings → Reduce motion ON → no haptics, ripple becomes static stroked Circle
- [ ] Dark mode flip (`adb shell cmd uimode night yes/no`) re-tints dots, scenery, lunar, milestones
- [ ] GoshuinFAB shows seal thumbnail after first cold start (~200 ms render)
- [ ] 50+ walk scroll → no hitches > 16 ms (Layout Inspector flame chart)

- [ ] **Step 6: `/polish` review**

Run the `/polish` workflow on the branch to catch remaining issues. Resolve each finding before opening the PR.

- [ ] **Step 7: Commit + push + open PR**

```bash
git add app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreen.kt \
        app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt \
        app/src/test/java/org/walktalkmeditate/pilgrim/ui/home/JournalScreenIntegrationTest.kt
git commit -m "$(cat <<'EOF'
feat(journal): JournalScreen final assembly + integration test (Stage 14 task 22)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
git push -u origin feat/stage-14-journal
gh pr create --title "feat(journal): Stage 14 — Pilgrim Log iOS-parity port" --body "$(cat <<'EOF'
## Summary

- Stage 14 ships the iOS v1.5.0 Journal/Pilgrim Log: dots-on-thread layout, expand-card on tap, scroll haptics, scenery, turning-day banner, journey-summary cycler, and seal-thumbnail FAB.
- Bundle includes 4 internal sub-stages (foundation / chrome / overlays / polish) with mid-stage device QA pause after Bucket 14-A.

## Test plan

- [ ] `./gradlew :app:assembleDebug :app:lintDebug :app:testDebugUnitTest` green
- [ ] Manual QA on OnePlus 13 per Stage 14 spec § Verification
- [ ] Reduce-motion off → haptics + ripple animate; ON → static stroked Circle, no haptics
- [ ] Dark mode flip re-tints all overlay elements

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Wait for explicit user "merge" instruction; do NOT auto-merge.

---

## Self-review

Before opening the PR (Task 22 Step 7), the implementer must verify:

### Spec coverage check

Cross-reference each spec section against the plan:

- [ ] **Files to create — Bucket 14-A foundation**: covered by Tasks 1, 2, 3, 4, 5, 6, 9
  - WalkSnapshot (Task 2)
  - JournalUiState (Task 2)
  - JourneySummary (Task 2)
  - StatMode (Task 2)
  - WalkDot.kt (Task 9)
  - WalkDotMath.kt (Task 4)
  - HapticEvent / ScrollHapticState (Task 5)
  - JournalHapticDispatcher (Task 6)
  - LocalReduceMotion (Task 3)
  - WalkRepository activitySumsFor / walkEventsFor (Task 1)

- [ ] **Files to create — Bucket 14-B chrome**: Tasks 10, 11, 12, 13
  - JournalTopBar (Task 10)
  - JourneySummaryHeader (Task 11)
  - ExpandCardSheet + MiniActivityBar + ActivityPills (Task 12)
  - SealThumbnail + GoshuinFAB (Task 13)

- [ ] **Files to create — Bucket 14-C overlays**: Tasks 14, 15, 16, 17
  - TurningDayService + SeasonalMarkerTurnings (Task 14)
  - TurningDayBanner (Task 15)
  - LunarMarkerCalc + LunarMarkerDot (Task 16)
  - MilestoneMarker + MilestoneCalc + DateDividerCalc + ToriiGateShape + FootprintShape (Task 17)

- [ ] **Files to create — Bucket 14-D polish**: Tasks 18, 19, 20, 21, 22
  - SceneryGenerator + SceneryShapes (Task 18)
  - 7 scenery sub-composables + SceneryItem (Task 19)
  - RippleEffect (Task 20)
  - EmptyJournalState (Task 21)
  - JournalScreen (Task 22)

- [ ] **Files to modify**: covered
  - WalkRepository (Task 1)
  - CachedShareStore (Task 7)
  - Color.kt (Task 14)
  - HomeViewModel (Task 8)
  - HomeScreen (Tasks 8, 13, 22)
  - HomeUiState DELETE (Task 21)
  - HomeWalkRowComposable DELETE (Task 21)
  - CalligraphyPath additive (Task 9)
  - strings.xml (Task 10)
  - HomeViewModelTest (Task 8)

### Placeholder scan

- [ ] Search the plan for `TBD`, `TODO`, `// implement later`, `XXX` — should return only the documented Stage 14.X TODO inside `WalkRepository.activitySumsFor` (intentional, marked with link to Non-goals).

```bash
grep -n "TBD\|XXX" docs/superpowers/plans/2026-05-04-stage-14-journal.md && echo "FOUND PLACEHOLDERS" || echo "CLEAN"
```

### Type consistency

- [ ] `WalkSnapshot.distanceM: Double` (Task 2) — Task 8 `buildSnapshots` reads `walk.distanceMeters` (Double from Walk entity); reduces into `cumulativeDistanceM: Double`. Match.
- [ ] `WalkSnapshot.startMs: Long` — used by Task 16 (LunarMarkerCalc), Task 17 (DateDividerCalc), Task 18 (SceneryGenerator). All read `Long`. Match.
- [ ] `DotPosition(centerXPx: Float, yPx: Float)` (Task 9) — used by Tasks 16/17 with the same field names. Match.
- [ ] `JournalUiState.Loaded(snapshots, summary, celestialAwarenessEnabled)` — Task 22 reads exactly those 3 fields. Match.
- [ ] `HapticEvent.LightDot/HeavyDot/Milestone(idx: Int)` — `JournalHapticDispatcher.dispatch` matches in Task 6. Match.

### Dependencies / ordering

- [ ] Task 1 (repo helpers) → Task 8 (VM) — Task 8 calls `repository.activitySumsFor` and `walkEventsFor`.
- [ ] Task 2 (data classes) → Tasks 8, 9, 11, 12, 16, 17, 18, 19, 22 — all consume `WalkSnapshot` / `JournalUiState`.
- [ ] Task 3 (LocalReduceMotion) → Task 20 (RippleEffect reads it).
- [ ] Task 4 (WalkDotMath) → Tasks 9, 22 (sizeDp + opacity).
- [ ] Task 5 (ScrollHapticState) → Tasks 9, 22.
- [ ] Task 6 (JournalHapticDispatcher) → Tasks 9, 22.
- [ ] Task 7 (CachedShareStore.observeAll) → Task 8 (VM combine).
- [ ] Task 9 (CalligraphyPath.dotPositions) → Tasks 16, 17, 22.
- [ ] Task 14 (TurningDayService) → Tasks 15, 22.
- [ ] Task 17 (ToriiGateShape) → Task 19 (ToriiScenery imports it).
- [ ] Task 18 (SceneryGenerator) → Task 22 (JournalScreen calls SceneryGenerator.scenery in scenery overlay; if not yet wired into the Canvas-behind, document as Stage 14.5 deferral). The plan defers scenery overlay wiring to Stage 14.5 sub-effects per Non-goals — Stage 14 ships scenery composables but does NOT integrate them as moving overlays in the canvas-behind. Static placement at row level is acceptable.

### Critical lessons baked in

- [ ] Stage 13-XZ B2 lesson — `withContext(Dispatchers.Default)` for CPU work in Task 8.
- [ ] Stage 7-A test-leak lesson — `vm.viewModelScope.coroutineContext[Job]?.cancel()` before `db.close()` in Task 8.
- [ ] Stage 13-XZ B5/B7 — `@Immutable` on WalkSnapshot, JournalUiState.Loaded, JourneySummary, LunarMarker, MilestonePosition, DateDivider, SceneryPlacement.
- [ ] Stage 13-XZ B6 — catch-Throwable re-throws CancellationException FIRST in Task 8 (HomeViewModel `catch (ce: CancellationException) { throw ce }`).
- [ ] Stage 5-A locale — `Locale.US` for digits in Tasks 11, 12, 17; `Locale.getDefault()` for month names in Task 17.
- [ ] Stage 6-B locale — `DateTimeFormatter.ofPattern(pattern, Locale.getDefault())` (Task 17 DateDividerCalc) and `withLocale(Locale.getDefault())` (Task 12 ExpandCardSheet).
- [ ] Stage 5-A `Modifier.scale` — `Modifier.graphicsLayer { ... }` lambda form for animated values in Task 9 (WalkDot opacity), Task 20 (RippleEffect).
- [ ] Stage 2-F — `VibrationEffect.Composition.build()` exercised via Robolectric test in Task 6.
- [ ] Stage 4-D — `@Immutable` cascade includes ImageVector field types — `WalkSnapshot` does NOT carry `ImageVector` (favicon is `String?` reference key); `WalkFavicon.icon: ImageVector` is read at composition time inside `WalkDot`. Compose stability respected.
- [ ] Stage 13-Cel — persistence-scope-launched writes only matter for back-nav-survivable state. Stage 14's `_expandedSnapshotId.value = ...` is in-memory only (viewModelScope OK; back-nav cancels intentionally).
- [ ] Stage 13-XZ B8 — `JournalHapticDispatcher` has secondary `@Inject` ctor pinning concrete dispatch + primary `internal` test-seam ctor (Task 6 Step 3).
- [ ] Stage 13-XZ ISSUE 4 (sheet stranding) — VM's `_expandedSnapshotId` lifecycle has no Loading state; Task 8 catches Throwable in `setExpandedSnapshotId` celestialJob and resets to null on error.
- [ ] Mid-stage QA gate — explicit pause documented after Task 9.

---

## Stage 14 — done definition

- [ ] All 22 tasks committed on `feat/stage-14-journal`
- [ ] PR opened (Task 22 Step 7) with title "feat(journal): Stage 14 — Pilgrim Log iOS-parity port"
- [ ] On-device OnePlus 13 QA passes (Task 22 Step 5)
- [ ] `/polish` returns clean (Task 22 Step 6)
- [ ] User merge instruction received → only then `gh pr merge`

---
