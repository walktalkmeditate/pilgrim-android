// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.turbine.test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
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
import org.walktalkmeditate.pilgrim.data.practice.FakePracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.location.FakeLocationSource
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HomeViewModelJournalTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var repo: WalkRepository
    private lateinit var hemisphereDataStore: DataStore<Preferences>
    private lateinit var hemisphereRepo: HemisphereRepository
    private lateinit var hemisphereScope: CoroutineScope
    private val dispatcher = UnconfinedTestDispatcher()
    private var vm: HomeViewModel? = null

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
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
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        hemisphereDataStore = PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(hemisphereStoreName) },
        )
        hemisphereScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        hemisphereRepo = HemisphereRepository(
            hemisphereDataStore,
            FakeLocationSource(),
            hemisphereScope,
        )
    }

    @After
    fun tearDown() {
        // Stage 7-A flake-fix: cancel viewModelScope BEFORE db.close().
        vm?.viewModelScope?.coroutineContext?.get(Job)?.cancel()
        db.close()
        hemisphereScope.coroutineContext[Job]?.cancel()
        context.preferencesDataStoreFile(hemisphereStoreName).delete()
        Dispatchers.resetMain()
    }

    @Test
    fun `journalState emits Empty when no finished walks`() = runTest(dispatcher) {
        val v = newVm()
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item is JournalUiState.Loading) item = awaitItem()
            assertEquals(JournalUiState.Empty, item)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `journalState emits Loaded with one snapshot for one finished walk`() = runTest(dispatcher) {
        val walk = runBlocking { repo.startWalk(startTimestamp = 5_000_000L) }
        runBlocking { repo.finishWalk(walk, endTimestamp = 5_600_000L) }
        val v = newVm()
        vm = v
        v.journalState.test(timeout = 10.seconds) {
            var item = awaitItem()
            while (item !is JournalUiState.Loaded) item = awaitItem()
            assertEquals(1, item.snapshots.size)
            assertEquals(walk.id, item.snapshots[0].id)
            assertTrue(item.snapshots[0].walkOnlyDurationSec >= 0L)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun newVm(): HomeViewModel {
        val clock = object : Clock {
            override fun now(): Long = 10_000_000L
        }
        val cachedShareStore = CachedShareStore(
            ApplicationProvider.getApplicationContext(),
            Json { ignoreUnknownKeys = true },
        )
        return HomeViewModel(
            context = ApplicationProvider.getApplicationContext(),
            repository = repo,
            clock = clock,
            hemisphereRepository = hemisphereRepo,
            unitsPreferences = FakeUnitsPreferencesRepository(),
            cachedShareStore = cachedShareStore,
            practicePreferences = FakePracticePreferencesRepository(),
            defaultDispatcher = dispatcher,
            ioDispatcher = dispatcher,
        )
    }

    private val hemisphereStoreName: String =
        "home-vm-journal-hemi-${java.util.UUID.randomUUID()}"
}
