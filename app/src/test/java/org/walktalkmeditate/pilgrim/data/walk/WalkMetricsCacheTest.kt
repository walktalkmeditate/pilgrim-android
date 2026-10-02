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
        sitting(id, start = 60_000L, end = 360_000L)

        cache.computeAndPersist(id)

        val w = walkDao.getById(id)!!
        assertNotNull(w.distanceMeters)
        assertTrue("expected ~222m, got ${w.distanceMeters}", w.distanceMeters!! in 200.0..240.0)
        assertEquals(300L, w.meditationSeconds)
    }

    @Test
    fun computeAndPersist_neverOverwritesCachesFilledSinceItStarted() = runTest {
        // An archive strip fills a walk's caches from its children, then
        // deletes them. A backfill pass that computed from the emptied walk
        // must not replace those stats with zeros.
        val id = walkDao.insert(
            Walk(startTimestamp = 0L, endTimestamp = 30 * 60_000L, distanceMeters = 5_000.0, meditationSeconds = 600L),
        )

        cache.computeAndPersist(id)

        val w = walkDao.getById(id)!!
        assertEquals(5_000.0, w.distanceMeters!!, 0.0)
        assertEquals(600L, w.meditationSeconds)
    }

    @Test
    fun nativeWalkWithOnlyMeditationEvents_cachesItsSitting() = runTest {
        // #223: native walks record sittings only as events. The cache
        // used to sum the (empty) activity_intervals table and store 0.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 20 * 60_000L))
        sitting(id, start = 2 * 60_000L, end = 7 * 60_000L)
        sitting(id, start = 10 * 60_000L, end = 11 * 60_000L)

        cache.computeAndPersist(id)

        assertEquals(6 * 60L, walkDao.getById(id)!!.meditationSeconds)
    }

    @Test
    fun meditatingRowsInActivityIntervals_areNotASittingSource() = runTest {
        // walk_events is the only source; MIGRATION_8_9 turned every
        // pre-9 MEDITATING row into events, so a row alone counts nothing.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 30 * 60_000L))
        db.activityIntervalDao().insert(
            ActivityInterval(
                walkId = id,
                activityType = ActivityType.MEDITATING,
                startTimestamp = 60_000L,
                endTimestamp = 360_000L,
            ),
        )

        cache.computeAndPersist(id)

        assertEquals(0L, walkDao.getById(id)!!.meditationSeconds)
    }

    @Test
    fun sittingFinishedMidMeditation_closesAtWalkEnd() = runTest {
        // The reducer writes no MEDITATION_END when the walk finishes
        // mid-sitting; the cache must still count the final stretch.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 10 * 60_000L))
        walkEventDao.insert(WalkEvent(walkId = id, timestamp = 7 * 60_000L, eventType = WalkEventType.MEDITATION_START))

        cache.computeAndPersist(id)

        assertEquals(3 * 60L, walkDao.getById(id)!!.meditationSeconds)
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
        // Active duration = 18 minutes. Corruption: the sitting claims 50 minutes.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 30 * 60_000L))
        sitting(id, start = 0L, end = 50 * 60_000L)
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
        sitting(id, start = 0L, end = 30 * 60_000L)
        walkEventDao.insert(
            WalkEvent(walkId = id, timestamp = 10 * 60_000L, eventType = WalkEventType.PAUSED),
        )

        cache.computeAndPersist(id)

        assertEquals(10 * 60L, walkDao.getById(id)!!.meditationSeconds)
    }

    @Test
    fun zeroDurationWalkWritesZeroMeditation() = runTest {
        // A finalized walk with start == end (instant abort) must
        // produce a deterministic zero, not crash on division and not
        // leave NULL — the cache row is the one source of truth for
        // downstream consumers.
        val id = walkDao.insert(Walk(startTimestamp = 1_000L, endTimestamp = 1_000L))
        sitting(id, start = 1_000L, end = 1_000L)

        cache.computeAndPersist(id)

        val w = walkDao.getById(id)!!
        assertEquals(0L, w.meditationSeconds)
    }

    @Test
    fun overlappingSittings_countTheirUnionOnce() = runTest {
        // Two overlapping sittings (an imported package's activities):
        // [1, 6] min and [4, 9] min merge to [1, 9] = 8 min, not 10.
        val id = walkDao.insert(Walk(startTimestamp = 0L, endTimestamp = 20 * 60_000L))
        sitting(id, start = 60_000L, end = 6 * 60_000L)
        sitting(id, start = 4 * 60_000L, end = 9 * 60_000L)

        cache.computeAndPersist(id)

        assertEquals(8 * 60L, walkDao.getById(id)!!.meditationSeconds)
    }

    private suspend fun sitting(walkId: Long, start: Long, end: Long) {
        walkEventDao.insert(WalkEvent(walkId = walkId, timestamp = start, eventType = WalkEventType.MEDITATION_START))
        walkEventDao.insert(WalkEvent(walkId = walkId, timestamp = end, eventType = WalkEventType.MEDITATION_END))
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
