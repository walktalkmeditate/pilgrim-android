# Stage 4-C Implementation Plan — Goshuin Collection Grid

Spec: `docs/superpowers/specs/2026-04-19-stage-4c-goshuin-grid-design.md`.

A new `GoshuinScreen` behind `Routes.GOSHUIN`, entered from a *View goshuin* button on Home. `LazyVerticalGrid` of seals most-recent-first, each cell a `SealRenderer` framed by a thin inked circle with a date caption, tappable to `WalkSummary`. Empty state + parchment patina (background tint that deepens with walk count).

---

## Task 1 — `GoshuinSeal.kt` + `GoshuinUiState.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinSeal.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import java.time.LocalDate
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec

/**
 * Per-walk row model for the goshuin grid. The VM builds one of these
 * per finished walk. The `@Composable` cell resolves [SealSpec.ink] via
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
 * using [walkDate] + the current hemisphere — the VM can't do it because
 * theme reads require `@Composable` scope.
 *
 * Mirrors [org.walktalkmeditate.pilgrim.ui.home.HomeWalkRow] in spirit:
 * precompute what the VM can, defer theme-dependent work to composition.
 */
data class GoshuinSeal(
    val walkId: Long,
    /** `ink = Color.Transparent` placeholder; resolved in [GoshuinScreen]. */
    val sealSpec: SealSpec,
    /** Device-local date of the walk, used for the seasonal ink shift. */
    val walkDate: LocalDate,
    /** Pre-formatted short date for the caption, e.g., `"Apr 19"`. */
    val shortDateLabel: String,
)
```

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinUiState.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

/**
 * Three-state load for the goshuin collection. Mirrors
 * [org.walktalkmeditate.pilgrim.ui.home.HomeUiState].
 *
 * [Loaded.totalCount] equals `seals.size` in Stage 4-C (no filtering
 * yet) but is a separate field so a future favicon filter can render
 * a subset while the parchment patina continues to reflect *lifetime*
 * practice, not the current view.
 */
sealed class GoshuinUiState {
    data object Loading : GoshuinUiState()
    data object Empty : GoshuinUiState()
    data class Loaded(
        val seals: List<GoshuinSeal>,
        val totalCount: Int,
    ) : GoshuinUiState()
}
```

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 2 — `GoshuinPatina.kt`: patina tint helper

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinPatina.kt`

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

/**
 * Parchment patina alpha for the goshuin screen background. Four
 * walk-count breakpoints ported from iOS's `GoshuinPageView.patinaColor`:
 *  - `0..10`   → 0f (no tint)
 *  - `11..30`  → 0.03
 *  - `31..70`  → 0.07
 *  - `71+`     → 0.12
 *
 * The goshuin screen applies this alpha to a `pilgrimColors.dawn`
 * overlay above the parchment background. The book visibly "ages" as
 * practice deepens — wabi-sabi reward, not UI decoration.
 *
 * Pure function, plain-JUnit-testable.
 */
internal fun patinaAlphaFor(walkCount: Int): Float = when {
    walkCount < PATINA_TIER_1_COUNT -> 0f
    walkCount < PATINA_TIER_2_COUNT -> PATINA_ALPHA_TIER_1
    walkCount < PATINA_TIER_3_COUNT -> PATINA_ALPHA_TIER_2
    else -> PATINA_ALPHA_TIER_3
}

internal const val PATINA_TIER_1_COUNT = 11
internal const val PATINA_TIER_2_COUNT = 31
internal const val PATINA_TIER_3_COUNT = 71
internal const val PATINA_ALPHA_TIER_1 = 0.03f
internal const val PATINA_ALPHA_TIER_2 = 0.07f
internal const val PATINA_ALPHA_TIER_3 = 0.12f
```

**Verify:** compiles.

---

## Task 3 — `GoshuinPatinaTest.kt`

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinPatinaTest.kt`

Plain JUnit 4, no Robolectric (pure function).

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import org.junit.Assert.assertEquals
import org.junit.Test

class GoshuinPatinaTest {

    @Test fun `zero walks → no tint`() {
        assertEquals(0f, patinaAlphaFor(0), 0f)
    }

    @Test fun `ten walks → no tint (below tier 1)`() {
        assertEquals(0f, patinaAlphaFor(10), 0f)
    }

    @Test fun `eleven walks → tier 1 alpha`() {
        assertEquals(PATINA_ALPHA_TIER_1, patinaAlphaFor(11), 0f)
    }

    @Test fun `thirty walks → tier 1 alpha (below tier 2 boundary)`() {
        assertEquals(PATINA_ALPHA_TIER_1, patinaAlphaFor(30), 0f)
    }

    @Test fun `thirty-one walks → tier 2 alpha`() {
        assertEquals(PATINA_ALPHA_TIER_2, patinaAlphaFor(31), 0f)
    }

    @Test fun `seventy walks → tier 2 alpha (below tier 3 boundary)`() {
        assertEquals(PATINA_ALPHA_TIER_2, patinaAlphaFor(70), 0f)
    }

    @Test fun `seventy-one walks → tier 3 alpha`() {
        assertEquals(PATINA_ALPHA_TIER_3, patinaAlphaFor(71), 0f)
    }

    @Test fun `five hundred walks → tier 3 alpha (no higher tier)`() {
        assertEquals(PATINA_ALPHA_TIER_3, patinaAlphaFor(500), 0f)
    }

    @Test fun `negative count clamps to zero tint`() {
        // Defensive — a malformed count shouldn't produce a bogus alpha.
        assertEquals(0f, patinaAlphaFor(-5), 0f)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*GoshuinPatinaTest"`

---

## Task 4 — `GoshuinViewModel.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModel.kt`

Pattern cribbed from `HomeViewModel` + `WalkSummaryViewModel`. Observes `repository.observeAllWalks()`, filters finished, maps each walk to a `GoshuinSeal` (with `ink = Color.Transparent` placeholder resolved in the composable layer). Proxies `hemisphere` as a sibling `StateFlow` so the grid can tint per cell without re-emitting the full list on hemisphere flip.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/**
 * Backing VM for the goshuin collection grid. Observes all walks,
 * filters finished (endTimestamp != null), maps each to a
 * [GoshuinSeal] with a pre-built [org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec]
 * (ink = Transparent placeholder). Ordered most-recent-first by
 * endTimestamp.
 *
 * The per-walk seasonal ink shift happens in the composable layer
 * because `LocalPilgrimColors` is a theme read — matches Stage 4-B's
 * `WalkSummaryScreen.specForReveal` pattern.
 *
 * Distance per walk is computed from the GPS samples via
 * [walkDistanceMeters], mirroring [org.walktalkmeditate.pilgrim.ui.home.HomeViewModel.mapToRow].
 * This is an N+1 query per collection load; for current walk counts it
 * is a non-issue. If device QA flags jank at scale, a focused
 * [Walk.distanceMeters] cache is a separate stage.
 */
@HiltViewModel
class GoshuinViewModel @Inject constructor(
    private val repository: WalkRepository,
    hemisphereRepository: HemisphereRepository,
) : ViewModel() {

    /** Proxy of [HemisphereRepository.hemisphere]. Separate from [uiState]
     *  so a hemisphere flip doesn't re-emit the whole seal list — the
     *  `@Composable` re-keys per-cell on hemisphere change. */
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    val uiState: StateFlow<GoshuinUiState> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks
                .filter { it.endTimestamp != null }
                .sortedWith(compareByDescending<Walk> { it.endTimestamp }.thenByDescending { it.id })
            if (finished.isEmpty()) {
                GoshuinUiState.Empty
            } else {
                val seals = finished.map { mapToSeal(it) }
                GoshuinUiState.Loaded(seals = seals, totalCount = seals.size)
            }
        }
        .stateIn(
            scope = viewModelScope,
            // WhileSubscribed matches HomeViewModel — stops the Room
            // collector when the user navigates away for ≥5s; unit tests
            // without subscribers don't leave viewModelScope hanging.
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = GoshuinUiState.Loading,
        )

    private suspend fun mapToSeal(walk: Walk): GoshuinSeal {
        val samples = repository.locationSamplesFor(walk.id).map {
            LocationPoint(timestamp = it.timestamp, latitude = it.latitude, longitude = it.longitude)
        }
        val distance = walkDistanceMeters(samples)
        val distanceLabel = WalkFormat.distanceLabel(distance)
        val sealSpec = walk.toSealSpec(
            distanceMeters = distance,
            ink = Color.Transparent,                  // resolved in composable
            displayDistance = distanceLabel.value,
            unitLabel = distanceLabel.unit,
        )
        val walkDate = Instant.ofEpochMilli(walk.startTimestamp)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
        return GoshuinSeal(
            walkId = walk.id,
            sealSpec = sealSpec,
            walkDate = walkDate,
            shortDateLabel = SHORT_DATE_FORMATTER.format(walkDate),
        )
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
        // Locale-sensitive: `"Apr 19"` on US, `"19 avr."` on French.
        private val SHORT_DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("MMM d", Locale.getDefault())
    }
}
```

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 5 — `GoshuinViewModelTest.kt`

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinViewModelTest.kt`

Mirror `HomeViewModelTest`'s setup. Same in-memory Room + HemisphereRepository scaffolding.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoshuinViewModelTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var hemisphereDataStore: DataStore<Preferences>
    private lateinit var fakeLocation: FakeLocationSource
    private lateinit var hemisphereRepo: HemisphereRepository
    private lateinit var hemisphereScope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            database = db,
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
        )
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME) },
        )
        fakeLocation = FakeLocationSource()
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereRepo = HemisphereRepository(hemisphereDataStore, fakeLocation, hemisphereScope)
    }

    @After
    fun tearDown() {
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(HEMISPHERE_STORE_NAME).delete()
        Dispatchers.resetMain()
    }

    private fun newViewModel(): GoshuinViewModel =
        GoshuinViewModel(repository, hemisphereRepo)

    @Test
    fun `Empty when repository has no finished walks`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.uiState.test {
            var item = awaitItem()
            while (item is GoshuinUiState.Loading) item = awaitItem()
            assertEquals(GoshuinUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Empty when only in-progress walks exist`() = runTest(dispatcher) {
        // Unfinished walk (endTimestamp = null) must not appear.
        runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }

        val vm = newViewModel()
        vm.uiState.test {
            var item = awaitItem()
            while (item is GoshuinUiState.Loading) item = awaitItem()
            assertEquals(GoshuinUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded with one seal when one finished walk exists`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals(1, loaded.seals.size)
            assertEquals(walk.id, loaded.seals[0].walkId)
            assertEquals(1, loaded.totalCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `seals ordered most-recent-end-first`() = runTest(dispatcher) {
        val older = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
        runBlocking { repository.finishWalk(older, endTimestamp = 1_600_000L) }
        val newer = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(newer, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertEquals(listOf(newer.id, older.id), loaded.seals.map { it.walkId })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sealSpec ink is Transparent placeholder`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test {
            val loaded = awaitLoaded(this)
            // VM must not resolve the seasonal tint — that's composable
            // work. Composable-layer tests can verify the tinted value.
            assertEquals(Color.Transparent, loaded.seals[0].sealSpec.ink)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sealSpec carries walk uuid and start timestamp for geometry seed`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test {
            val loaded = awaitLoaded(this)
            val spec = loaded.seals[0].sealSpec
            assertEquals(walk.uuid, spec.uuid)
            assertEquals(walk.startTimestamp, spec.startMillis)
            assertTrue(
                "durationSeconds must match finish - start",
                spec.durationSeconds == 600.0,
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shortDateLabel is non-empty`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test {
            val loaded = awaitLoaded(this)
            assertTrue(loaded.seals[0].shortDateLabel.isNotBlank())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hemisphere StateFlow proxies repository`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertEquals(Hemisphere.Northern, vm.hemisphere.value)
        hemisphereRepo.setOverride(Hemisphere.Southern)
        // Repository's StateFlow collects on real Dispatchers.Default,
        // bridge to wall-clock same as HomeViewModelTest.
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                vm.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    // --- helpers ---------------------------------------------------

    private suspend fun awaitLoaded(
        turbine: app.cash.turbine.ReceiveTurbine<GoshuinUiState>,
    ): GoshuinUiState.Loaded {
        var item = turbine.awaitItem()
        while (item is GoshuinUiState.Loading || item is GoshuinUiState.Empty) {
            item = turbine.awaitItem()
        }
        assertNotNull(item)
        return item as GoshuinUiState.Loaded
    }

    private companion object {
        const val HEMISPHERE_STORE_NAME = "goshuin-vm-hemisphere-test"
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*GoshuinViewModelTest"`

---

## Task 6 — `GoshuinScreen.kt`

**Create:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreen.kt`

The composable surface. Structure: Scaffold → Box (patina overlay + content) → Column (title + content). Content is a `LazyVerticalGrid` or an empty state depending on `uiState`. Per-cell seasonal tint uses `remember(seal.sealSpec, baseInk, seal.walkDate, hemisphere)` to avoid recomposing HSV math on scroll.

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRenderer
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

private val CELL_SEAL_SIZE = 140.dp
private val CELL_FRAME_SIZE = 148.dp
private const val SEAL_FRAME_ALPHA = 0.04f

/**
 * Stage 4-C: browsable collection of every earned goshuin seal. Enters
 * from Home's *View goshuin* button. Tapping a seal navigates to that
 * walk's summary via [onSealTap].
 *
 * Layers (bottom → top):
 *  1. Scaffold with `pilgrimColors.parchment` background.
 *  2. Dawn-tinted patina overlay (alpha from [patinaAlphaFor]) so the
 *     page visibly *ages* as the user accumulates walks.
 *  3. Column: header (title + back) → content.
 *  4. Content: Loading spinner / Empty state / LazyVerticalGrid of seals.
 *
 * Seasonal-ink resolution for each cell happens inside the cell via
 * `remember(sealSpec, baseInk, walkDate, hemisphere)` — matches
 * Stage 4-B's WalkSummaryScreen pattern and avoids rebuilding specs on
 * unrelated recomposition.
 */
@Composable
fun GoshuinScreen(
    onBack: () -> Unit,
    onSealTap: (Long) -> Unit,
    viewModel: GoshuinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()

    val totalCount = (uiState as? GoshuinUiState.Loaded)?.totalCount ?: 0
    val patinaAlpha = patinaAlphaFor(totalCount)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        // Patina overlay — full-screen Box tinted `dawn` at the
        // count-tier alpha. Fills above parchment but below the content
        // so text/seals render with full contrast.
        if (patinaAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pilgrimColors.dawn.copy(alpha = patinaAlpha)),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            GoshuinHeader(onBack = onBack)
            Spacer(Modifier.height(PilgrimSpacing.normal))

            when (val s = uiState) {
                is GoshuinUiState.Loading -> GoshuinLoading()
                is GoshuinUiState.Empty -> GoshuinEmpty()
                is GoshuinUiState.Loaded -> GoshuinGrid(
                    seals = s.seals,
                    hemisphere = hemisphere,
                    onSealTap = onSealTap,
                )
            }
        }
    }
}

@Composable
private fun GoshuinHeader(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = PilgrimSpacing.normal)) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.goshuin_back_content_description),
                tint = pilgrimColors.ink,
            )
        }
        Text(
            text = stringResource(R.string.goshuin_title),
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
            modifier = Modifier.align(Alignment.Center).padding(vertical = PilgrimSpacing.small),
        )
    }
}

@Composable
private fun GoshuinLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp),
            strokeWidth = 2.dp,
            color = pilgrimColors.stone,
        )
    }
}

@Composable
private fun GoshuinEmpty() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Faded seal placeholder — renders a deterministic-but-generic
        // spec at ink.alpha = 0.10 so the shape is recognizable as
        // "something a seal will appear here" without feeling empty.
        val placeholderSpec = remember {
            SealSpec(
                uuid = PLACEHOLDER_UUID,
                startMillis = 0L,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                displayDistance = "",
                unitLabel = "",
                ink = Color.Transparent,               // replaced below
            )
        }
        val fadedInk = pilgrimColors.fog.copy(alpha = PLACEHOLDER_ALPHA)
        SealRenderer(
            spec = placeholderSpec.copy(ink = fadedInk),
            modifier = Modifier.size(CELL_SEAL_SIZE),
        )
        Spacer(Modifier.height(PilgrimSpacing.normal))
        Text(
            text = stringResource(R.string.goshuin_empty_caption),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun GoshuinGrid(
    seals: List<GoshuinSeal>,
    hemisphere: Hemisphere,
    onSealTap: (Long) -> Unit,
) {
    val gridState = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = PilgrimSpacing.normal,
            end = PilgrimSpacing.normal,
            top = PilgrimSpacing.small,
            bottom = PilgrimSpacing.big,
        ),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        items(
            items = seals,
            // Stable key by walkId — avoids cell recomposition thrash
            // on unrelated list updates. Scroll position survives too.
            key = { seal -> seal.walkId },
        ) { seal ->
            GoshuinSealCell(
                seal = seal,
                hemisphere = hemisphere,
                onClick = { onSealTap(seal.walkId) },
            )
        }
    }
}

@Composable
private fun GoshuinSealCell(
    seal: GoshuinSeal,
    hemisphere: Hemisphere,
    onClick: () -> Unit,
) {
    val baseInk = pilgrimColors.rust
    val frameColor = pilgrimColors.ink.copy(alpha = SEAL_FRAME_ALPHA)

    // Per-cell seasonal tint — matches WalkSummaryScreen.specForReveal.
    val tintedSpec = remember(seal.sealSpec, baseInk, seal.walkDate, hemisphere) {
        val tintedInk = SeasonalColorEngine.applySeasonalShift(
            base = baseInk,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = seal.walkDate,
            hemisphere = hemisphere,
        )
        seal.sealSpec.copy(ink = tintedInk)
    }

    // Indication = null suppresses the material ripple — the cell
    // is a quiet button, not a raised surface. interactionSource must
    // still be remembered to avoid re-allocation per recompose.
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = PilgrimSpacing.small),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier.size(CELL_FRAME_SIZE),
            contentAlignment = Alignment.Center,
        ) {
            // Thin ink-outline circle behind the seal — "stamp on paper."
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
            SealRenderer(
                spec = tintedSpec,
                modifier = Modifier.size(CELL_SEAL_SIZE),
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = seal.shortDateLabel,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
    }
}

private const val PLACEHOLDER_UUID = "goshuin-empty-placeholder"
private const val PLACEHOLDER_ALPHA = 0.10f
```

**Verify:** `./gradlew :app:compileDebugKotlin` — watch for Icons.AutoMirrored availability (material-icons-extended on the classpath; if not, fall back to `Icons.Filled.ArrowBack`).

---

## Task 7 — Strings

**Edit:** `app/src/main/res/values/strings.xml`

Add four strings after `home_action_resume_walk`:

```xml
    <string name="home_action_view_goshuin">View goshuin</string>
    <string name="goshuin_title">Goshuin</string>
    <string name="goshuin_empty_caption">Your goshuin will fill as you walk</string>
    <string name="goshuin_back_content_description">Back</string>
```

**Verify:** `./gradlew :app:compileDebugResources`

---

## Task 8 — Home entry button

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/home/HomeScreen.kt`

Add `onEnterGoshuin: () -> Unit` param to `HomeScreen`. Place a new outlined/secondary button below the existing *Start a walk* button.

At the param list (line 73-78):

```kotlin
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterGoshuin: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
)
```

In the Column body, after the existing Start-a-walk Button and before `Spacer(Modifier.height(PilgrimSpacing.big)); BatteryExemptionCard`:

```kotlin
        Spacer(Modifier.height(PilgrimSpacing.normal))

        OutlinedButton(
            onClick = onEnterGoshuin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_action_view_goshuin))
        }
```

Add the import: `import androidx.compose.material3.OutlinedButton`.

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 9 — Nav host: new route + wire Home button

**Edit:** `app/src/main/java/org/walktalkmeditate/pilgrim/ui/navigation/PilgrimNavHost.kt`

In the `Routes` object, add:

```kotlin
    const val GOSHUIN = "goshuin"
```

In `NavHost { }`, add after the `composable(Routes.WALK_SUMMARY_PATTERN)` block:

```kotlin
        composable(Routes.GOSHUIN) {
            GoshuinScreen(
                onBack = { navController.popBackStack() },
                onSealTap = { walkId ->
                    // launchSingleTop: guard against a double-tap rapidly
                    // committing two identical WALK_SUMMARY entries, same
                    // as the Home→Summary wiring.
                    navController.navigate(Routes.walkSummary(walkId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
```

In the `composable(Routes.HOME)` block, update the `HomeScreen` call to pass `onEnterGoshuin`:

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
                onEnterGoshuin = {
                    navController.navigate(Routes.GOSHUIN) {
                        launchSingleTop = true
                    }
                },
            )
        }
```

Add the import: `import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinScreen`.

**Verify:** `./gradlew :app:compileDebugKotlin`

---

## Task 10 — `GoshuinScreenTest.kt` (Robolectric smoke)

**Create:** `app/src/test/java/org/walktalkmeditate/pilgrim/ui/goshuin/GoshuinScreenTest.kt`

Composition-shape tests only — SealRenderer Canvas draws are stubbed under Robolectric (Stage 3-C lesson); asserting on root existence + visible text is the reliable surface.

To avoid wiring up Hilt + ViewModel in a Compose test, expose an internal overload that takes the state directly:

**Edit `GoshuinScreen.kt` again:** add an internal helper that renders the content without the ViewModel (composable-only), used by both the public entry point and tests.

```kotlin
@Composable
internal fun GoshuinScreenContent(
    uiState: GoshuinUiState,
    hemisphere: Hemisphere,
    onBack: () -> Unit,
    onSealTap: (Long) -> Unit,
) {
    val totalCount = (uiState as? GoshuinUiState.Loaded)?.totalCount ?: 0
    val patinaAlpha = patinaAlphaFor(totalCount)

    Box(
        modifier = Modifier.fillMaxSize().background(pilgrimColors.parchment),
    ) {
        if (patinaAlpha > 0f) {
            Box(
                modifier = Modifier.fillMaxSize().background(pilgrimColors.dawn.copy(alpha = patinaAlpha)),
            )
        }
        Column(modifier = Modifier.fillMaxSize()) {
            GoshuinHeader(onBack = onBack)
            Spacer(Modifier.height(PilgrimSpacing.normal))
            when (uiState) {
                is GoshuinUiState.Loading -> GoshuinLoading()
                is GoshuinUiState.Empty -> GoshuinEmpty()
                is GoshuinUiState.Loaded -> GoshuinGrid(
                    seals = uiState.seals,
                    hemisphere = hemisphere,
                    onSealTap = onSealTap,
                )
            }
        }
    }
}
```

And refactor `GoshuinScreen` to delegate:

```kotlin
@Composable
fun GoshuinScreen(
    onBack: () -> Unit,
    onSealTap: (Long) -> Unit,
    viewModel: GoshuinViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()
    GoshuinScreenContent(
        uiState = uiState,
        hemisphere = hemisphere,
        onBack = onBack,
        onSealTap = onSealTap,
    )
}
```

Now the test:

```kotlin
// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.goshuin

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GoshuinScreenTest {

    @get:Rule val composeRule = createComposeRule()

    private fun seal(id: Long, date: LocalDate = LocalDate.of(2026, 4, 19)): GoshuinSeal =
        GoshuinSeal(
            walkId = id,
            sealSpec = SealSpec(
                uuid = "uuid-$id",
                startMillis = 1_700_000_000_000L + id,
                distanceMeters = 5_000.0,
                durationSeconds = 1_800.0,
                displayDistance = "5.00",
                unitLabel = "km",
                ink = Color.Transparent,
            ),
            walkDate = date,
            shortDateLabel = "Apr 19",
        )

    @Test fun `Empty state shows caption`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Empty,
                        hemisphere = Hemisphere.Northern,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Your goshuin will fill as you walk").assertIsDisplayed()
    }

    @Test fun `Loaded state renders date captions`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loaded(
                            seals = listOf(seal(1L), seal(2L)),
                            totalCount = 2,
                        ),
                        hemisphere = Hemisphere.Northern,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `Loading state composes without crashing`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Loading,
                        hemisphere = Hemisphere.Northern,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onRoot().assertExists()
    }

    @Test fun `title is shown in header`() {
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Empty,
                        hemisphere = Hemisphere.Northern,
                        onBack = {},
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Goshuin").assertIsDisplayed()
    }

    @Test fun `back button click fires onBack`() {
        var backCalls = 0
        composeRule.setContent {
            PilgrimTheme {
                Box(Modifier.size(400.dp, 800.dp)) {
                    GoshuinScreenContent(
                        uiState = GoshuinUiState.Empty,
                        hemisphere = Hemisphere.Northern,
                        onBack = { backCalls++ },
                        onSealTap = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Back")  // matches content-description for semantic node
            .performClick()
        // If content-description doesn't surface as text-matchable, swap to
        // onNodeWithContentDescription("Back").performClick() in the fix-up.
        assert(backCalls == 1)
    }
}
```

**Verify:** `./gradlew :app:testDebugUnitTest --tests "*GoshuinScreenTest"`

If the back-button content-description lookup doesn't match via `onNodeWithText`, switch to `onNodeWithContentDescription("Back")` (import `androidx.compose.ui.test.onNodeWithContentDescription`).

---

## Task 11 — Full build + test

Run:

```bash
./gradlew :app:lintDebug :app:testDebugUnitTest
```

**Verify:** green. No new lint warnings (watch for:
- unused imports
- magic numbers (each has an explanatory constant)
- icon variant warnings
).

---

## Task 12 — Manual pre-commit smoke

- Audit the diff for stale references, leftover debug logging, or commented-out code.
- `git diff --stat` — confirm file count matches the 8 new + 3 modified from the spec.
- Confirm no `// TODO` without a Stage-reference comment.
