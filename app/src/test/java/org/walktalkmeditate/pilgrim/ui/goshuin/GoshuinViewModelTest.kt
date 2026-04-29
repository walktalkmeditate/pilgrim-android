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
import kotlin.time.Duration.Companion.seconds
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
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
            walkPhotoDao = db.walkPhotoDao(),
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
        GoshuinViewModel(
            repository,
            hemisphereRepo,
            org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository(),
        )

    @Test
    fun `Empty when repository has no walks`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.uiState.test(timeout = 5.seconds) {
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
        vm.uiState.test(timeout = 5.seconds) {
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
        vm.uiState.test(timeout = 5.seconds) {
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
        vm.uiState.test(timeout = 5.seconds) {
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
        vm.uiState.test(timeout = 5.seconds) {
            val loaded = awaitLoaded(this)
            // VM must not resolve the seasonal tint — theme reads are
            // @Composable-scoped. Composable-layer tests verify tinting.
            assertEquals(Color.Transparent, loaded.seals[0].sealSpec.ink)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sealSpec carries walk uuid, start timestamp, and duration`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 5.seconds) {
            val loaded = awaitLoaded(this)
            val spec = loaded.seals[0].sealSpec
            assertEquals(walk.uuid, spec.uuid)
            assertEquals(walk.startTimestamp, spec.startMillis)
            assertEquals(600.0, spec.durationSeconds, 0.0001)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `shortDateLabel is non-empty`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 5.seconds) {
            val loaded = awaitLoaded(this)
            assertTrue(
                "shortDateLabel='${loaded.seals[0].shortDateLabel}'",
                loaded.seals[0].shortDateLabel.isNotBlank(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hemisphere StateFlow proxies repository`() = runTest(dispatcher) {
        val vm = newViewModel()
        assertEquals(Hemisphere.Northern, vm.hemisphere.value)
        hemisphereRepo.setOverride(Hemisphere.Southern)
        // Repository's StateFlow collects on real Dispatchers.Default;
        // bridge to wall-clock same as HomeViewModelTest.
        val observed = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(3_000L) {
                vm.hemisphere.first { it == Hemisphere.Southern }
            }
        }
        assertEquals(Hemisphere.Southern, observed)
    }

    // --- Stage 4-D: milestone propagation -------------------------

    @Test
    fun `Loaded marks single finished walk as FirstWalk milestone`() = runTest(dispatcher) {
        val walk = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repository.finishWalk(walk, endTimestamp = 5_600_000L) }

        val vm = newViewModel()
        vm.uiState.test(timeout = 5.seconds) {
            val loaded = awaitLoaded(this)
            assertEquals(GoshuinMilestone.FirstWalk, loaded.seals[0].milestone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `Loaded marks longest walk among 3 with LongestWalk milestone`() = runTest(dispatcher) {
        // Three walks: w1 = oldest (FirstWalk wins by precedence),
        // w2 = max distance (LongestWalk), w3 = no milestone (not 1st,
        // not longest, not 10th, second walk in the same Winter-1970
        // season-year).
        val w1 = runBlocking { repository.startWalk(startTimestamp = 1_000_000L) }
        runBlocking {
            repository.recordLocation(RouteDataSample(walkId = w1.id, timestamp = 1_100_000L, latitude = 0.0, longitude = 0.0))
            repository.recordLocation(RouteDataSample(walkId = w1.id, timestamp = 1_200_000L, latitude = 0.0, longitude = 0.0001))
            repository.finishWalk(w1, endTimestamp = 1_600_000L)
        }
        val w2 = runBlocking { repository.startWalk(startTimestamp = 5_000_000L) }
        runBlocking {
            repository.recordLocation(RouteDataSample(walkId = w2.id, timestamp = 5_100_000L, latitude = 0.0, longitude = 0.0))
            repository.recordLocation(RouteDataSample(walkId = w2.id, timestamp = 5_200_000L, latitude = 0.0, longitude = 0.05))
            repository.finishWalk(w2, endTimestamp = 5_600_000L)
        }
        val w3 = runBlocking { repository.startWalk(startTimestamp = 9_000_000L) }
        runBlocking {
            repository.recordLocation(RouteDataSample(walkId = w3.id, timestamp = 9_100_000L, latitude = 0.0, longitude = 0.0))
            repository.recordLocation(RouteDataSample(walkId = w3.id, timestamp = 9_200_000L, latitude = 0.0, longitude = 0.005))
            repository.finishWalk(w3, endTimestamp = 9_600_000L)
        }

        val vm = newViewModel()
        vm.uiState.test(timeout = 5.seconds) {
            val loaded = awaitLoaded(this)
            val byId = loaded.seals.associateBy { it.walkId }
            assertEquals(GoshuinMilestone.FirstWalk, byId.getValue(w1.id).milestone)
            assertEquals(GoshuinMilestone.LongestWalk, byId.getValue(w2.id).milestone)
            assertNull(byId.getValue(w3.id).milestone)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private suspend fun awaitLoaded(
        turbine: app.cash.turbine.ReceiveTurbine<GoshuinUiState>,
    ): GoshuinUiState.Loaded {
        // Only drain `Loading`, not `Empty`. Skipping `Empty` would hide
        // a future regression where the finished-walk filter drops all
        // rows — the test would hang on `awaitItem()` waiting for a
        // `Loaded` that never arrives, instead of failing fast with a
        // clear cast exception.
        var item = turbine.awaitItem()
        while (item is GoshuinUiState.Loading) {
            item = turbine.awaitItem()
        }
        assertNotNull(item)
        return item as GoshuinUiState.Loaded
    }

    private companion object {
        const val HEMISPHERE_STORE_NAME = "goshuin-vm-hemisphere-test"
    }
}
