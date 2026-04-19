# Stage 4-D Implementation Plan — Goshuin Milestone Celebrations

Spec: `docs/superpowers/specs/2026-04-19-stage-4d-milestones-design.md`.

Port iOS's milestone detection (4 of 5 types — defer `longestMeditation`), surface as a halo + label on goshuin grid cells, and a 2-pulse haptic + 0.5s extra hold on `SealRevealOverlay` when the just-finished walk is itself a milestone.

---

## Task 1 — `GoshuinMilestone.kt` (sealed class) + `Season.kt` (enum)

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestone.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.runtime.Immutable

/**
 * A walk-history milestone. Detected by [GoshuinMilestones.detect] from
 * the per-walk index + the full finished-walks snapshot. Surfaced on
 * the goshuin grid (halo + label) and on the seal-reveal overlay
 * (2-pulse haptic + extra hold).
 *
 * Ports 4 of iOS's 5 `GoshuinMilestones.Milestone` cases — see the
 * design spec for why `LongestMeditation` is deferred.
 *
 * `@Immutable` for Compose stability — the class hierarchy contains
 * only stable types ([Season] enum, [Int]) but the Compose compiler
 * doesn't auto-infer stability for sealed-class hierarchies without
 * the explicit annotation. Same lesson as Stage 4-C `GoshuinSeal`.
 */
@Immutable
sealed class GoshuinMilestone {
    data object FirstWalk : GoshuinMilestone()
    data object LongestWalk : GoshuinMilestone()
    data class NthWalk(val n: Int) : GoshuinMilestone()
    data class FirstOfSeason(val season: Season) : GoshuinMilestone()
}

/**
 * Hemisphere-aware season. Computed from the walk's start month +
 * device hemisphere via [GoshuinMilestones.seasonFor]. Mirrors iOS's
 * `SealTimeHelpers.season(for:latitude:)`.
 */
enum class Season { Spring, Summer, Autumn, Winter }
```

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 2 — `GoshuinMilestones.kt` (pure detector)

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestones.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

/**
 * Per-walk fields needed by the [GoshuinMilestones.detect] function.
 * A small DTO (rather than the full Walk Room entity) so detection
 * tests don't need to instantiate Room.
 */
data class WalkMilestoneInput(
    val walkId: Long,
    val uuid: String,
    val startTimestamp: Long,
    val distanceMeters: Double,
)

/**
 * Pure milestone detector. Ported from iOS's `GoshuinMilestones.swift`.
 *
 * Returns the highest-precedence milestone for the walk at [walkIndex]
 * (0-based, most-recent-first within [allFinished]) — or `null` when no
 * milestone applies. Multiple simultaneous milestones (e.g., walk #10
 * is also the longest) are resolved by explicit precedence:
 *
 *   1. [GoshuinMilestone.FirstWalk]
 *   2. [GoshuinMilestone.LongestWalk]
 *   3. [GoshuinMilestone.NthWalk]
 *   4. [GoshuinMilestone.FirstOfSeason]
 *
 * iOS uses `Set<Milestone>.first` which depends on Swift's hash-based
 * iteration order — non-deterministic across processes. Android fixes
 * this with explicit precedence above.
 */
object GoshuinMilestones {

    fun detect(
        walkIndex: Int,
        walk: WalkMilestoneInput,
        allFinished: List<WalkMilestoneInput>,
        hemisphere: Hemisphere,
    ): GoshuinMilestone? {
        // walkNumber: 1-based, where walkIndex 0 = newest = highest
        // walkNumber. iOS computed walkNumber = walkIndex + 1 from the
        // OLDEST-first page-view loop; same effective number expressed
        // via the most-recent-first list this codebase uses.
        val walkNumber = allFinished.size - walkIndex

        if (walkNumber == 1) return GoshuinMilestone.FirstWalk

        // Longest precedes nth so a walk that hits both shows the
        // more meaningful "Longest Walk" label. Tie-break: when two
        // walks share the same max distance, the most recent (lower
        // index) wins.
        val longestId = allFinished.maxByOrNull { it.distanceMeters }?.walkId
        if (longestId == walk.walkId && allFinished.size > 1) {
            return GoshuinMilestone.LongestWalk
        }

        if (walkNumber > 0 && walkNumber % 10 == 0) {
            return GoshuinMilestone.NthWalk(walkNumber)
        }

        // First-of-season: no other walk in the same season+year came
        // before this one. iOS's Calendar.current.component is the
        // local-time year; we use ZoneId.systemDefault() to match.
        val zone = ZoneId.systemDefault()
        val walkSeason = seasonFor(walk.startTimestamp, hemisphere)
        val walkYear = Instant.ofEpochMilli(walk.startTimestamp).atZone(zone).year
        val hasEarlierInSeason = allFinished.any { other ->
            other.walkId != walk.walkId &&
                other.startTimestamp < walk.startTimestamp &&
                seasonFor(other.startTimestamp, hemisphere) == walkSeason &&
                Instant.ofEpochMilli(other.startTimestamp).atZone(zone).year == walkYear
        }
        if (!hasEarlierInSeason) {
            return GoshuinMilestone.FirstOfSeason(walkSeason)
        }

        return null
    }

    /**
     * Month-based season selector. Mirrors iOS's
     * `SealTimeHelpers.season(for:latitude:)`. Hemisphere is the
     * device-level setting (so a walk that happened on the user's
     * vacation in Sydney still uses the device's home Hemisphere) —
     * matches the rest of this app's seasonal-color and journal
     * tinting behavior.
     */
    fun seasonFor(timestampMs: Long, hemisphere: Hemisphere): Season {
        val month = Instant.ofEpochMilli(timestampMs)
            .atZone(ZoneId.systemDefault())
            .monthValue
        val northern = hemisphere == Hemisphere.Northern
        return when (month) {
            3, 4, 5 -> if (northern) Season.Spring else Season.Autumn
            6, 7, 8 -> if (northern) Season.Summer else Season.Winter
            9, 10, 11 -> if (northern) Season.Autumn else Season.Spring
            else -> if (northern) Season.Winter else Season.Summer
        }
    }

    /**
     * Stable English label for the cell + reveal-overlay surfaces.
     * CLAUDE.md specifies English-only baseline; localization (Stage 10)
     * will re-route through `R.string.*` resources.
     */
    fun label(milestone: GoshuinMilestone): String = when (milestone) {
        GoshuinMilestone.FirstWalk -> "First Walk"
        GoshuinMilestone.LongestWalk -> "Longest Walk"
        is GoshuinMilestone.NthWalk -> "${ordinal(milestone.n)} Walk"
        is GoshuinMilestone.FirstOfSeason -> "First of ${seasonLabel(milestone.season)}"
    }

    private fun seasonLabel(season: Season): String = when (season) {
        Season.Spring -> "Spring"
        Season.Summer -> "Summer"
        Season.Autumn -> "Autumn"
        Season.Winter -> "Winter"
    }

    /**
     * 1 → "1st", 2 → "2nd", 3 → "3rd", 4 → "4th", 11 → "11th",
     * 21 → "21st", 100 → "100th". Matches iOS's `ordinal(_:)`.
     */
    internal fun ordinal(n: Int): String {
        val tens = (n / 10) % 10
        val ones = n % 10
        val suffix = if (tens == 1) {
            "th"
        } else when (ones) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 3 — `GoshuinMilestonesTest.kt`

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinMilestonesTest.kt`

Plain JUnit 4 (pure functions, no Android runtime).

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

class GoshuinMilestonesTest {

    private fun walk(
        id: Long,
        date: LocalDate,
        distance: Double = 1_000.0,
    ): WalkMilestoneInput {
        val ts = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return WalkMilestoneInput(
            walkId = id,
            uuid = "uuid-$id",
            startTimestamp = ts,
            distanceMeters = distance,
        )
    }

    @Test fun `first walk - is FirstWalk milestone`() {
        val w = walk(1L, LocalDate.of(2026, 4, 19))
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = w, allFinished = listOf(w), hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstWalk, m)
    }

    @Test fun `tenth walk - NthWalk(10)`() {
        val list = (1..10).map { walk(it.toLong(), LocalDate.of(2026, 1, it)) }.reversed()
        val tenth = list.first()
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = tenth, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.NthWalk(10), m)
    }

    @Test fun `twentieth walk - NthWalk(20)`() {
        val list = (1..20).map { walk(it.toLong(), LocalDate.of(2026, 1, 1).plusDays((it - 1).toLong())) }.reversed()
        val twentieth = list.first()
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = twentieth, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.NthWalk(20), m)
    }

    @Test fun `seventh walk in middle of list - no milestone`() {
        // 7 walks total, the middle one (index 3 = 4th from newest = walk #4
        // in 1-based numbering) has neither first/longest/nth/firstOfSeason.
        // All distances equal so longest is the most-recent (index 0), not
        // the middle.
        val list = (1..7).map { walk(it.toLong(), LocalDate.of(2026, 1, 1).plusDays((it - 1).toLong()), distance = 1000.0) }.reversed()
        val middle = list[3]
        val m = GoshuinMilestones.detect(walkIndex = 3, walk = middle, allFinished = list, hemisphere = Hemisphere.Northern)
        assertNull(m)
    }

    @Test fun `second walk - not FirstWalk`() {
        val w1 = walk(1L, LocalDate.of(2026, 1, 1))
        val w2 = walk(2L, LocalDate.of(2026, 1, 2))
        val list = listOf(w2, w1)
        val m = GoshuinMilestones.detect(walkIndex = 1, walk = w1, allFinished = list, hemisphere = Hemisphere.Northern)
        // w1 IS the firstWalk (oldest, walkNumber == 1)
        assertEquals(GoshuinMilestone.FirstWalk, m)
        val mNew = GoshuinMilestones.detect(walkIndex = 0, walk = w2, allFinished = list, hemisphere = Hemisphere.Northern)
        // w2 is the second walk - no milestone (not first, not 10th, only 2 walks so longest tie-break needs >1 walks; same distance → most recent wins → w2 IS the longest among 2)
        assertEquals(GoshuinMilestone.LongestWalk, mNew)
    }

    @Test fun `longest walk - LongestWalk milestone`() {
        val short = walk(1L, LocalDate.of(2026, 1, 1), distance = 1_000.0)
        val long = walk(2L, LocalDate.of(2026, 1, 2), distance = 5_000.0)
        val medium = walk(3L, LocalDate.of(2026, 1, 3), distance = 2_000.0)
        val list = listOf(medium, long, short)
        val m = GoshuinMilestones.detect(walkIndex = 1, walk = long, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.LongestWalk, m)
    }

    @Test fun `longest walk tiebreaker - most recent wins via maxByOrNull stability`() {
        // Two walks with identical max distance. `maxByOrNull` returns the
        // FIRST element with the max — most-recent-first list means the
        // newer walk wins.
        val newer = walk(2L, LocalDate.of(2026, 1, 2), distance = 5_000.0)
        val older = walk(1L, LocalDate.of(2026, 1, 1), distance = 5_000.0)
        val list = listOf(newer, older)
        val mNewer = GoshuinMilestones.detect(walkIndex = 0, walk = newer, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.LongestWalk, mNewer)
        val mOlder = GoshuinMilestones.detect(walkIndex = 1, walk = older, allFinished = list, hemisphere = Hemisphere.Northern)
        // Older is NOT the longest by tiebreaker, so falls through.
        // 2 walks total, walkNumber for older = 1 → FirstWalk wins precedence.
        assertEquals(GoshuinMilestone.FirstWalk, mOlder)
    }

    @Test fun `first of season - first spring walk of year`() {
        val springWalk = walk(1L, LocalDate.of(2026, 3, 21))
        val list = listOf(springWalk)
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = springWalk, allFinished = list, hemisphere = Hemisphere.Northern)
        // First walk overall, so FirstWalk wins. firstOfSeason ALSO holds
        // but precedence picks FirstWalk.
        assertEquals(GoshuinMilestone.FirstWalk, m)
    }

    @Test fun `first of season - second spring walk same year does not count`() {
        // Add a winter walk first so it's not the first walk overall, then
        // two spring walks. The first spring walk gets firstOfSeason; the
        // second doesn't.
        val winter = walk(1L, LocalDate.of(2026, 1, 15), distance = 100.0)
        val spring1 = walk(2L, LocalDate.of(2026, 3, 21), distance = 100.0)
        val spring2 = walk(3L, LocalDate.of(2026, 4, 1), distance = 100.0)
        // Make a 4th to dodge LongestWalk on a 3-walk list with equal distances
        val winter2 = walk(4L, LocalDate.of(2026, 1, 16), distance = 9_999.0)
        val list = listOf(winter2, spring2, spring1, winter)
        val mSpring1 = GoshuinMilestones.detect(walkIndex = 2, walk = spring1, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstOfSeason(Season.Spring), mSpring1)
        val mSpring2 = GoshuinMilestones.detect(walkIndex = 1, walk = spring2, allFinished = list, hemisphere = Hemisphere.Northern)
        assertNull(mSpring2)
    }

    @Test fun `first of season - across years marks each year's first separately`() {
        val s2026 = walk(1L, LocalDate.of(2026, 3, 21), distance = 100.0)
        val s2027 = walk(2L, LocalDate.of(2027, 3, 21), distance = 100.0)
        val winter = walk(3L, LocalDate.of(2027, 1, 1), distance = 9_999.0)
        val list = listOf(s2027, winter, s2026)
        val m2026 = GoshuinMilestones.detect(walkIndex = 2, walk = s2026, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstWalk, m2026)
        val m2027 = GoshuinMilestones.detect(walkIndex = 0, walk = s2027, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstOfSeason(Season.Spring), m2027)
    }

    @Test fun `precedence - FirstWalk overrides LongestWalk on a single walk`() {
        val solo = walk(1L, LocalDate.of(2026, 1, 1), distance = 5_000.0)
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = solo, allFinished = listOf(solo), hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.FirstWalk, m)
    }

    @Test fun `precedence - LongestWalk overrides NthWalk(10)`() {
        // 10 walks where the 10th is also the longest. Precedence picks
        // LongestWalk, not NthWalk(10).
        val list = (1..10).map { i ->
            walk(i.toLong(), LocalDate.of(2026, 1, i), distance = if (i == 10) 9_999.0 else 1_000.0)
        }.reversed()
        val tenth = list.first()
        val m = GoshuinMilestones.detect(walkIndex = 0, walk = tenth, allFinished = list, hemisphere = Hemisphere.Northern)
        assertEquals(GoshuinMilestone.LongestWalk, m)
    }

    @Test fun `seasonFor - northern hemisphere months map correctly`() {
        val zone = ZoneId.systemDefault()
        fun ts(year: Int, month: Int) = LocalDate.of(year, month, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(Season.Spring, GoshuinMilestones.seasonFor(ts(2026, 4), Hemisphere.Northern))
        assertEquals(Season.Summer, GoshuinMilestones.seasonFor(ts(2026, 7), Hemisphere.Northern))
        assertEquals(Season.Autumn, GoshuinMilestones.seasonFor(ts(2026, 10), Hemisphere.Northern))
        assertEquals(Season.Winter, GoshuinMilestones.seasonFor(ts(2026, 1), Hemisphere.Northern))
    }

    @Test fun `seasonFor - southern hemisphere flips`() {
        val zone = ZoneId.systemDefault()
        fun ts(year: Int, month: Int) = LocalDate.of(year, month, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(Season.Autumn, GoshuinMilestones.seasonFor(ts(2026, 4), Hemisphere.Southern))
        assertEquals(Season.Winter, GoshuinMilestones.seasonFor(ts(2026, 7), Hemisphere.Southern))
        assertEquals(Season.Spring, GoshuinMilestones.seasonFor(ts(2026, 10), Hemisphere.Southern))
        assertEquals(Season.Summer, GoshuinMilestones.seasonFor(ts(2026, 1), Hemisphere.Southern))
    }

    @Test fun `ordinal - teens use th`() {
        assertEquals("11th", GoshuinMilestones.ordinal(11))
        assertEquals("12th", GoshuinMilestones.ordinal(12))
        assertEquals("13th", GoshuinMilestones.ordinal(13))
        assertEquals("113th", GoshuinMilestones.ordinal(113))
    }

    @Test fun `ordinal - regular suffixes`() {
        assertEquals("1st", GoshuinMilestones.ordinal(1))
        assertEquals("2nd", GoshuinMilestones.ordinal(2))
        assertEquals("3rd", GoshuinMilestones.ordinal(3))
        assertEquals("4th", GoshuinMilestones.ordinal(4))
        assertEquals("21st", GoshuinMilestones.ordinal(21))
        assertEquals("22nd", GoshuinMilestones.ordinal(22))
        assertEquals("23rd", GoshuinMilestones.ordinal(23))
        assertEquals("100th", GoshuinMilestones.ordinal(100))
        assertEquals("101st", GoshuinMilestones.ordinal(101))
    }

    @Test fun `label - exhaustive coverage`() {
        assertEquals("First Walk", GoshuinMilestones.label(GoshuinMilestone.FirstWalk))
        assertEquals("Longest Walk", GoshuinMilestones.label(GoshuinMilestone.LongestWalk))
        assertEquals("10th Walk", GoshuinMilestones.label(GoshuinMilestone.NthWalk(10)))
        assertEquals("21st Walk", GoshuinMilestones.label(GoshuinMilestone.NthWalk(21)))
        assertEquals("First of Spring", GoshuinMilestones.label(GoshuinMilestone.FirstOfSeason(Season.Spring)))
        assertEquals("First of Winter", GoshuinMilestones.label(GoshuinMilestone.FirstOfSeason(Season.Winter)))
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*GoshuinMilestonesTest"`

---

## Task 4 — Add `milestone` field to `GoshuinSeal`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinSeal.kt`

```kotlin
@Immutable
data class GoshuinSeal(
    val walkId: Long,
    val sealSpec: SealSpec,
    val walkDate: LocalDate,
    val shortDateLabel: String,
    val milestone: GoshuinMilestone? = null,   // NEW
)
```

Default-null param keeps every existing test that constructs a `GoshuinSeal` working without modification.

**Verify:** compiles.

---

## Task 5 — `GoshuinViewModel.mapToSeal` builds distance map + calls detector

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt`

Refactor the `.map { walks -> ... }` block to:
1. Build `List<Pair<Walk, Double>>` (walk + computed distance) once
2. Build `List<WalkMilestoneInput>` for the detector
3. Call `mapToSeal` with the walk's index + the snapshot

```kotlin
val uiState: StateFlow<GoshuinUiState> = repository.observeAllWalks()
    .map { walks ->
        val finished = walks
            .filter { it.endTimestamp != null }
            .sortedWith(
                compareByDescending<Walk> { it.endTimestamp }
                    .thenByDescending { it.id },
            )
        if (finished.isEmpty()) {
            GoshuinUiState.Empty
        } else {
            // Compute distance per finished walk once. mapToSeal uses
            // it both for the SealSpec and for the milestone detector
            // (LongestWalk needs every walk's distance to find the max).
            val distances = finished.associate { walk ->
                walk.id to walkDistanceMeters(samplesFor(walk.id))
            }
            val milestoneInputs = finished.map { walk ->
                WalkMilestoneInput(
                    walkId = walk.id,
                    uuid = walk.uuid,
                    startTimestamp = walk.startTimestamp,
                    distanceMeters = distances.getValue(walk.id),
                )
            }
            val hemisphere = hemisphere.value
            val seals = finished.mapIndexed { index, walk ->
                mapToSeal(
                    walk = walk,
                    distance = distances.getValue(walk.id),
                    milestone = GoshuinMilestones.detect(
                        walkIndex = index,
                        walk = milestoneInputs[index],
                        allFinished = milestoneInputs,
                        hemisphere = hemisphere,
                    ),
                )
            }
            GoshuinUiState.Loaded(seals = seals, totalCount = seals.size)
        }
    }
    .stateIn(...)
```

Refactor `mapToSeal` to take pre-computed values:

```kotlin
private fun mapToSeal(
    walk: Walk,
    distance: Double,
    milestone: GoshuinMilestone?,
): GoshuinSeal {
    val distanceLabel = WalkFormat.distanceLabel(distance)
    val sealSpec = walk.toSealSpec(
        distanceMeters = distance,
        ink = Color.Transparent,
        displayDistance = distanceLabel.value,
        unitLabel = distanceLabel.unit,
    )
    val walkDate = Instant.ofEpochMilli(walk.startTimestamp)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val shortDateFormatter = DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    return GoshuinSeal(
        walkId = walk.id,
        sealSpec = sealSpec,
        walkDate = walkDate,
        shortDateLabel = shortDateFormatter.format(walkDate),
        milestone = milestone,
    )
}

// Pull the sample-load + LocationPoint mapping into a tiny helper so
// the new distance-map build inside the .map block stays readable.
private suspend fun samplesFor(walkId: Long): List<LocationPoint> =
    repository.locationSamplesFor(walkId).map {
        LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
    }
```

Important details:
- Use `hemisphere.value` (a `StateFlow<Hemisphere>`) inside the suspend `.map { }` block. The cell composable already keys on `hemisphere` for tint, so the milestone detection's hemisphere snapshot doesn't need to drive recomposition — it just needs to be the current value at flow-emission time. A subsequent `hemisphere` change re-emits cells via the per-cell `remember(... hemisphere ...)` only for tint, NOT for milestone — in practice, a hemisphere flip rarely happens (user crosses equator) and recomputing milestones lazily on the next walk-finish is acceptable.
- Actually: detecting `firstOfSeason` differently after a hemisphere flip would surprise the user mid-collection (a "First of Spring" cell becomes "First of Autumn"). Acceptable per spec — same as the rest of the seasonal-color system already behaves on hemisphere change.

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 6 — `GoshuinViewModelTest` extension

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModelTest.kt`

Append two cases after the existing tests:

```kotlin
@Test
fun `Loaded marks single finished walk as FirstWalk milestone`() = runTest(dispatcher) {
    val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
    runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

    val vm = newViewModel()
    vm.uiState.test {
        val loaded = awaitLoaded(this)
        assertEquals(GoshuinMilestone.FirstWalk, loaded.seals[0].milestone)
        cancelAndIgnoreRemainingEvents()
    }
}

@Test
fun `Loaded marks longest walk among 3 with LongestWalk milestone`() = runTest(dispatcher) {
    // Three walks, distances [near-zero, max, mid]. The walk with max
    // distance gets LongestWalk; the oldest gets FirstWalk; the third
    // has no milestone.
    val w1 = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
    runBlocking {
        repository.recordLocation(RouteDataSample(walkId = w1.id, timestamp = 1_100_000L, latitude = 0.0, longitude = 0.0))
        repository.recordLocation(RouteDataSample(walkId = w1.id, timestamp = 1_200_000L, latitude = 0.0, longitude = 0.0001)) // ~11m
        repository.finishWalk(w1, endTimestamp = 1_600_000L)
    }
    val w2 = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
    runBlocking {
        repository.recordLocation(RouteDataSample(walkId = w2.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0))
        repository.recordLocation(RouteDataSample(walkId = w2.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.05)) // ~5km
        repository.finishWalk(w2, endTimestamp = 5_600_000L)
    }
    val w3 = runBlocking { repository.startWalk(startTimestamp = 9_000_000L) }
    runBlocking {
        repository.recordLocation(RouteDataSample(walkId = w3.id, timestamp = 9_100_000L, latitude = 0.0, longitude = 0.0))
        repository.recordLocation(RouteDataSample(walkId = w3.id, timestamp = 9_200_000L, latitude = 0.0, longitude = 0.005)) // ~500m
        repository.finishWalk(w3, endTimestamp = 9_600_000L)
    }

    val vm = newViewModel()
    vm.uiState.test {
        val loaded = awaitLoaded(this)
        // List is most-recent-first: [w3, w2, w1]
        val byId = loaded.seals.associateBy { it.walkId }
        assertEquals(GoshuinMilestone.FirstWalk, byId.getValue(w1.id).milestone)
        assertEquals(GoshuinMilestone.LongestWalk, byId.getValue(w2.id).milestone)
        // w3: not 1st, not longest, not 10th, only walk in summer 2-1970
        // hmm the timestamps are all very early epoch — January 1970.
        // All three walks are in the same season-year (Winter 1970), so
        // w1 already claimed "first of Winter". w3 is the second walk of
        // the same season → null milestone. Verify:
        assertNull(byId.getValue(w3.id).milestone)
        cancelAndIgnoreRemainingEvents()
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*GoshuinViewModelTest"`

---

## Task 7 — `GoshuinScreen.GoshuinSealCell` halo + label swap

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreen.kt`

Add three constants near the existing cell-size constants:

```kotlin
private val CELL_HALO_SIZE = 156.dp
private val CELL_HALO_STROKE = 2.dp
private const val CELL_HALO_ALPHA = 0.5f
```

Update `GoshuinSealCell` to:
1. Optionally render a halo Box (only when `seal.milestone != null`)
2. Swap the date caption for the milestone label, with `dawn` color when present

```kotlin
@Composable
private fun GoshuinSealCell(
    seal: GoshuinSeal,
    hemisphere: Hemisphere,
    onClick: () -> Unit,
) {
    val baseInk = pilgrimColors.rust
    val frameColor = pilgrimColors.ink.copy(alpha = SEAL_FRAME_ALPHA)
    val haloColor = pilgrimColors.dawn.copy(alpha = CELL_HALO_ALPHA)

    val tintedSpec = remember(seal.sealSpec, baseInk, seal.walkDate, hemisphere) { /* unchanged */ }
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(vertical = PilgrimSpacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(CELL_HALO_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            // Milestone halo — outermost ring, only when milestone present.
            if (seal.milestone != null) {
                Box(
                    modifier = Modifier
                        .size(CELL_HALO_SIZE)
                        .drawBehind {
                            drawCircle(
                                color = haloColor,
                                radius = size.minDimension / 2f,
                                style = Stroke(width = CELL_HALO_STROKE.toPx()),
                            )
                        },
                )
            }
            // Existing thin ink-outline frame.
            Box(
                modifier = Modifier
                    .size(CELL_FRAME_SIZE)
                    .drawBehind {
                        drawCircle(
                            color = frameColor,
                            radius = size.minDimension / 2f,
                            style = Stroke(width = 1.dp.toPx()),
                        )
                    },
            )
            SealRenderer(spec = tintedSpec, modifier = Modifier.size(CELL_SEAL_SIZE))
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = seal.milestone?.let(GoshuinMilestones::label) ?: seal.shortDateLabel,
            style = pilgrimType.caption,
            color = if (seal.milestone != null) pilgrimColors.dawn else pilgrimColors.fog,
        )
    }
}
```

The outer `Box` size grows from `CELL_FRAME_SIZE` (148dp) to `CELL_HALO_SIZE` (156dp) to give the halo room. The seal + frame still center within it. Grid cell padding stays at `PilgrimSpacing.small` × 2 vertical, so the layout won't shift dramatically — milestone cells get ~8dp wider inner content but the column slots remain equal-width.

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 8 — Extend `GoshuinScreenTest` with milestone label assertion

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreenTest.kt`

Append after existing tests:

```kotlin
@Test fun `Loaded - milestone cell shows milestone label`() {
    val sealWithMilestone = seal(id = 7L).copy(
        shortDateLabel = "should-not-show",
        milestone = GoshuinMilestone.FirstWalk,
    )
    composeRule.setContent {
        PilgrimTheme {
            Box(Modifier.size(400.dp, 800.dp)) {
                GoshuinScreenContent(
                    uiState = GoshuinUiState.Loaded(
                        seals = listOf(sealWithMilestone),
                        totalCount = 1,
                    ),
                    hemisphere = Hemisphere.Northern,
                    onBack = {},
                    onSealTap = {},
                )
            }
        }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("First Walk").assertIsDisplayed()
}
```

(Don't assert visual halo presence — Robolectric Canvas is a stub. Spec-divergence prevention: the label IS the test surface.)

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*GoshuinScreenTest"`

---

## Task 9 — `WalkSummary` carries `milestone`, VM populates it

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModel.kt`

Add field to `WalkSummary` data class:

```kotlin
data class WalkSummary(
    val walk: Walk,
    val totalElapsedMillis: Long,
    val activeWalkingMillis: Long,
    val totalPausedMillis: Long,
    val totalMeditatedMillis: Long,
    val distanceMeters: Double,
    val paceSecondsPerKm: Double?,
    val waypointCount: Int,
    val routePoints: List<LocationPoint>,
    val sealSpec: SealSpec,
    val milestone: GoshuinMilestone? = null,    // NEW
)
```

Inside `buildState()`, after computing `distance` for the current walk and BEFORE `Loaded(WalkSummary(...))`, load all walks + their distances and call the detector. To keep the cost bounded:

```kotlin
private suspend fun buildState(): WalkSummaryUiState {
    val walk = repository.getWalk(walkId) ?: return WalkSummaryUiState.NotFound
    if (walk.endTimestamp == null) return WalkSummaryUiState.NotFound
    val samples = repository.locationSamplesFor(walkId)
    val events = repository.eventsFor(walkId)
    val waypoints = repository.waypointsFor(walkId)

    val points = samples.map {
        LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
    }
    val distance = walkDistanceMeters(points)
    // ... existing code through distanceLabel + sealSpec ...

    // Compute the milestone for this walk against the user's whole
    // history. One additional `allWalks` query + N sample loads (same
    // pattern as GoshuinViewModel; per-walk distance is required for
    // LongestWalk detection). Acceptable per spec — milestone detection
    // is a once-per-summary-load cost, not a hot path.
    val milestone = detectMilestoneFor(walk, distance)

    return WalkSummaryUiState.Loaded(
        WalkSummary(
            walk = walk,
            // ... existing fields ...
            sealSpec = sealSpec,
            milestone = milestone,
        ),
    )
}

private suspend fun detectMilestoneFor(currentWalk: Walk, currentDistance: Double): GoshuinMilestone? {
    val finished = repository.allWalks()
        .filter { it.endTimestamp != null }
        .sortedWith(
            compareByDescending<Walk> { it.endTimestamp }
                .thenByDescending { it.id },
        )
    val inputs = finished.map { walk ->
        val d = if (walk.id == currentWalk.id) {
            currentDistance
        } else {
            walkDistanceMeters(repository.locationSamplesFor(walk.id).map {
                LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
            })
        }
        WalkMilestoneInput(walkId = walk.id, uuid = walk.uuid, startTimestamp = walk.startTimestamp, distanceMeters = d)
    }
    val currentIndex = finished.indexOfFirst { it.id == currentWalk.id }
    if (currentIndex < 0) return null
    return GoshuinMilestones.detect(
        walkIndex = currentIndex,
        walk = inputs[currentIndex],
        allFinished = inputs,
        hemisphere = hemisphere.value,
    )
}
```

Imports needed: `org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone`, `GoshuinMilestones`, `WalkMilestoneInput`.

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 10 — Extend `WalkSummaryViewModelTest`

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryViewModelTest.kt`

Append a test case:

```kotlin
@Test
fun `summary carries FirstWalk milestone for the only finished walk`() = runTest(dispatcher) {
    val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
    runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

    val vm = newViewModel(walkId = walk.id)
    vm.state.test {
        val loaded = awaitLoaded(this)
        assertEquals(GoshuinMilestone.FirstWalk, loaded.summary.milestone)
        cancelAndIgnoreRemainingEvents()
    }
}
```

(Use the existing `awaitLoaded` helper if present; otherwise add the same minimal helper as `GoshuinViewModelTest`.)

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*WalkSummaryViewModelTest"`

---

## Task 11 — `SealRevealOverlay` accepts `isMilestone`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRevealOverlay.kt`

Add the `isMilestone` parameter (default `false` to preserve existing call sites + tests). Update the LaunchedEffect to fire 2 pulses + extra hold when milestone:

```kotlin
@Composable
fun SealRevealOverlay(
    spec: SealSpec,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sealSizeDp: Int = DEFAULT_SEAL_SIZE_DP,
    isMilestone: Boolean = false,    // NEW
) {
    // ... existing state setup ...

    LaunchedEffect(Unit) {
        if (phase == SealRevealPhase.Hidden) {
            phase = SealRevealPhase.Pressing
        }
        delay(PRESS_DURATION_MS.toLong())
        if (phase != SealRevealPhase.Pressing) return@LaunchedEffect
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (isMilestone) {
            // Milestone celebration: 2nd pulse 120ms after the first.
            // The body reads the double-tap as distinct from a non-
            // milestone reveal even with the phone in a pocket. Spec:
            // the +120ms latency before phase=Revealed is imperceptible
            // to the visual flow.
            delay(MILESTONE_PULSE_GAP_MS)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        phase = SealRevealPhase.Revealed
        val hold = HOLD_DURATION_MS + if (isMilestone) MILESTONE_HOLD_BONUS_MS else 0L
        delay(hold)
        if (phase == SealRevealPhase.Revealed) {
            phase = SealRevealPhase.Dismissing
        }
    }
    // ... rest unchanged ...
}

// New constants near the existing ones:
private const val MILESTONE_PULSE_GAP_MS = 120L
private const val MILESTONE_HOLD_BONUS_MS = 500L
```

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 12 — Wire `isMilestone` through `WalkSummaryScreen`

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/walk/WalkSummaryScreen.kt`

Inside the existing `if (showReveal && loaded != null)` block, pass the milestone flag:

```kotlin
SealRevealOverlay(
    spec = specForReveal,
    onDismiss = { showReveal = false },
    isMilestone = loaded.summary.milestone != null,
)
```

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 13 — Extend `SealRevealOverlayTest`

**Edit:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/design/seals/SealRevealOverlayTest.kt`

Append:

```kotlin
@Test fun `overlay renders with isMilestone flag`() {
    composeRule.setContent {
        Box(Modifier.size(400.dp, 800.dp)) {
            SealRevealOverlay(
                spec = testSpec(),
                onDismiss = {},
                isMilestone = true,
            )
        }
    }
    composeRule.waitForIdle()
    composeRule.onRoot().assertExists()
}
```

(Animation timing + double-haptic firing aren't asserted — Robolectric haptic-feedback is a stub, same convention as the other animation tests.)

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*SealRevealOverlayTest"`

---

## Task 14 — Full build + test

```bash
export PATH="$HOME/.asdf/shims:$PATH" JAVA_HOME="$(asdf where java 2>/dev/null)"
cd <worktree>
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug
```

**Verify:** all green; ~16 new test cases pass. No new lint warnings.

---

## Task 15 — Pre-commit audit

- `git diff --stat` — confirm the file count matches the spec's "What's on the commit" list (3 new + 6 modified + 4 test edits).
- Verify no `// TODO` / no commented-out code.
- Verify all new files have the SPDX header.
- Verify no OutRun references.
