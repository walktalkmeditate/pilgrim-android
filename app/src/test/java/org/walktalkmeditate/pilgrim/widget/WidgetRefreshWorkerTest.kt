// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WidgetRefreshWorkerTest {

    private lateinit var context: Context
    private lateinit var db: PilgrimDatabase
    private lateinit var walkRepository: WalkRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var widgetStateRepository: WidgetStateRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walkRepository = WalkRepository(
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
        val unique = "test_widget_${UUID.randomUUID()}"
        dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(context.filesDir, "datastore/$unique.preferences_pb") },
        )
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        widgetStateRepository = WidgetStateRepository(dataStore, json)
    }

    @After
    fun tearDown() {
        dataStoreScope.cancel()
        db.close()
    }

    private fun buildWorker(): WidgetRefreshWorker {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = WidgetRefreshWorker(
                appContext = appContext,
                params = workerParameters,
                walkRepository = walkRepository,
                widgetStateRepository = widgetStateRepository,
            )
        }
        return TestListenableWorkerBuilder<WidgetRefreshWorker>(context)
            .setWorkerFactory(factory)
            .build()
    }

    @Test
    fun `no finished walks writes Empty and returns success`() = runBlocking {
        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(WidgetState.Empty, widgetStateRepository.stateFlow.first())
    }

    @Test
    fun `finished walk with route samples writes LastWalk with computed distance + duration`() = runBlocking {
        val walk = walkRepository.startWalk(startTimestamp = 1_000L)
        val endTimestamp = 1_000L + 60 * 60 * 1000L // 1 hour
        walkRepository.finishWalk(walk, endTimestamp)
        // Two GPS points ~111m apart at the same latitude (1° lon = ~111km at equator).
        walkRepository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 2_000L, latitude = 0.0, longitude = 0.0),
        )
        walkRepository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 3_000L, latitude = 0.0, longitude = 0.001),
        )

        val worker = buildWorker()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val state = widgetStateRepository.stateFlow.first()
        assertTrue("expected LastWalk, got $state", state is WidgetState.LastWalk)
        val lastWalk = state as WidgetState.LastWalk
        assertEquals(walk.id, lastWalk.walkId)
        assertEquals(endTimestamp, lastWalk.endTimestampMs)
        // ~111m at the equator for 0.001° longitude diff. Allow loose tolerance.
        assertTrue(
            "expected distance ~111m, got ${lastWalk.distanceMeters}",
            lastWalk.distanceMeters in 100.0..130.0,
        )
        // No paused/meditated events — full hour of active walking.
        assertEquals(60 * 60 * 1000L, lastWalk.activeDurationMs)
    }

    @Test
    fun `most recent finished wins over older finished walks`() = runBlocking {
        val older = walkRepository.startWalk(startTimestamp = 1_000L)
        walkRepository.finishWalk(older, 2_000L)
        val newer = walkRepository.startWalk(startTimestamp = 10_000L)
        walkRepository.finishWalk(newer, 20_000L)

        val worker = buildWorker()
        worker.doWork()

        val state = widgetStateRepository.stateFlow.first()
        assertTrue(state is WidgetState.LastWalk)
        assertEquals(newer.id, (state as WidgetState.LastWalk).walkId)
    }
}
