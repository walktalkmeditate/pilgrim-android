// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
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
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.WalkEventType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkMetricsCacheTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var walkDao: WalkDao
    private lateinit var walkEventDao: WalkEventDao
    private lateinit var walkRepository: WalkRepository
    private lateinit var cache: WalkMetricsCache

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        walkDao = db.walkDao()
        walkEventDao = db.walkEventDao()
        walkRepository = WalkRepository(
            database = db,
            walkDao = walkDao,
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = walkEventDao,
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
            voiceRecordingDao = db.voiceRecordingDao(),
            walkPhotoDao = db.walkPhotoDao(),
        )
        cache = WalkMetricsCache(walkRepository, walkDao, walkEventDao)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun computeAndPersist_writesDistanceAndMeditation() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 30 * 60_000L))
        // 3 samples ~111m apart in longitude at the equator → ~222m total.
        db.routeDataSampleDao().insert(routeSample(id, t = 0L, lat = 0.0, lng = 0.0))
        db.routeDataSampleDao().insert(routeSample(id, t = 60_000L, lat = 0.0, lng = 0.001))
        db.routeDataSampleDao().insert(routeSample(id, t = 120_000L, lat = 0.0, lng = 0.002))
        db.activityIntervalDao().insert(
            ActivityInterval(
                walkId = id,
                activityType = ActivityType.MEDITATING,
                startTimestamp = 60_000L,
                endTimestamp = 360_000L,
            ),
        )

        cache.computeAndPersist(id)

        val w = walkDao.getById(id)!!
        assertNotNull(w.distanceMeters)
        assertTrue("expected ~222m, got ${w.distanceMeters}", w.distanceMeters!! in 200.0..240.0)
        assertEquals(300L, w.meditationSeconds)
    }

    @Test
    fun computeAndPersist_skipsInProgressWalk() = runTest {
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = null))

        cache.computeAndPersist(id)

        val w = walkDao.getById(id)!!
        assertNull(w.distanceMeters)
        assertNull(w.meditationSeconds)
    }

    @Test
    fun computeMeditation_clampsToActiveDurationFromPauseEvents() = runTest {
        // 30-minute walk; user paused at 10min, resumed at 22min (12-min pause).
        // Active duration = 18 minutes. Corruption: meditation interval claims 50 minutes.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 30 * 60_000L))
        db.activityIntervalDao().insert(
            ActivityInterval(
                walkId = id,
                activityType = ActivityType.MEDITATING,
                startTimestamp = 0L,
                endTimestamp = 50 * 60_000L,
            ),
        )
        walkEventDao.insert(
            WalkEvent(walkId = id, timestamp = 10 * 60_000L, eventType = WalkEventType.PAUSED),
        )
        walkEventDao.insert(
            WalkEvent(walkId = id, timestamp = 22 * 60_000L, eventType = WalkEventType.RESUMED),
        )

        cache.computeAndPersist(id)

        val w = walkDao.getById(id)!!
        assertEquals(18 * 60L, w.meditationSeconds)
    }

    @Test
    fun computeActiveDuration_unpairedTrailingPauseClosedAtEnd() = runTest {
        // Walk paused 10min in, never resumed; ended at 20min.
        // Active = 10 min; pause closed at endTimestamp.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 20 * 60_000L))
        db.activityIntervalDao().insert(
            ActivityInterval(
                walkId = id,
                activityType = ActivityType.MEDITATING,
                startTimestamp = 0L,
                endTimestamp = 30 * 60_000L,
            ),
        )
        walkEventDao.insert(
            WalkEvent(walkId = id, timestamp = 10 * 60_000L, eventType = WalkEventType.PAUSED),
        )

        cache.computeAndPersist(id)

        assertEquals(10 * 60L, walkDao.getById(id)!!.meditationSeconds)
    }

    private fun routeSample(walkId: Long, t: Long, lat: Double, lng: Double) = RouteDataSample(
        walkId = walkId,
        timestamp = t,
        latitude = lat,
        longitude = lng,
        altitudeMeters = 0.0,
        horizontalAccuracyMeters = 5.0f,
        verticalAccuracyMeters = 5.0f,
        speedMetersPerSecond = 0.0f,
    )
}
