// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.threads.DossierSenses
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * U9: the two bounded Room queries `DossierSenses`' senses feed on —
 * `routeSamplesNear` (the Android equivalent of iOS `routeFixNear`,
 * parity spec `docs/parity/2026-08-26-threads-senses-port.md`) and
 * `walkSensesSnapshot`. Both are suspend (off-main), unlike iOS's
 * `@MainActor`-bound `walkSensesSnapshot` — Room forbids main-thread
 * queries by design.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SenseInputsQueryTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var routeDao: RouteDataSampleDao
    private lateinit var walkDao: WalkDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        routeDao = db.routeDataSampleDao()
        walkDao = db.walkDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun newWalk(startTimestamp: Long, intention: String? = null, weatherCondition: String? = null): Long =
        walkDao.insert(Walk(startTimestamp = startTimestamp, intention = intention, weatherCondition = weatherCondition))

    private fun sample(walkId: Long, timestamp: Long, lat: Double = 35.0, lng: Double = 139.0, accuracy: Float? = 5.0f) =
        RouteDataSample(walkId = walkId, timestamp = timestamp, latitude = lat, longitude = lng, horizontalAccuracyMeters = accuracy)

    // --- RouteDataSampleDao.routeSamplesNear ------------------------------------

    @Test fun `routeSamplesNear returns only samples inside the window, ascending by timestamp`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        routeDao.insert(sample(walkId, timestamp = 500L))
        routeDao.insert(sample(walkId, timestamp = 1_500L))
        routeDao.insert(sample(walkId, timestamp = 9_999_999L))

        val rows = routeDao.routeSamplesNear(windowStart = 400L, windowEnd = 2_000L)

        assertEquals(listOf(500L, 1_500L), rows.map { it.timestamp })
    }

    @Test fun `routeSamplesNear is inclusive at both window edges`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        routeDao.insert(sample(walkId, timestamp = 1_000L))
        routeDao.insert(sample(walkId, timestamp = 2_000L))

        val rows = routeDao.routeSamplesNear(windowStart = 1_000L, windowEnd = 2_000L)

        assertEquals(2, rows.size)
    }

    @Test fun `routeSamplesNear projects latitude, longitude, and horizontalAccuracyMeters`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        routeDao.insert(sample(walkId, timestamp = 1_000L, lat = 12.5, lng = -8.25, accuracy = 3.5f))

        val row = routeDao.routeSamplesNear(windowStart = 0L, windowEnd = 2_000L).single()

        assertEquals(12.5, row.latitude, 1e-9)
        assertEquals(-8.25, row.longitude, 1e-9)
        assertEquals(3.5f, row.horizontalAccuracyMeters)
    }

    @Test fun `routeSamplesNear does not filter on accuracy — a low-accuracy fix inside the window still returns`() = runTest {
        // Parity spec: accuracy has NO SQL-side counterpart — qualifies()
        // re-checks it downstream in DossierSenses.
        val walkId = newWalk(startTimestamp = 1_000L)
        routeDao.insert(sample(walkId, timestamp = 1_000L, accuracy = 500.0f))

        val rows = routeDao.routeSamplesNear(windowStart = 0L, windowEnd = 2_000L)

        assertEquals(1, rows.size)
        assertEquals(500.0f, rows.single().horizontalAccuracyMeters)
    }

    @Test fun `routeSamplesNear returns a null accuracy as null, not a sentinel`() = runTest {
        val walkId = newWalk(startTimestamp = 1_000L)
        routeDao.insert(sample(walkId, timestamp = 1_000L, accuracy = null))

        val row = routeDao.routeSamplesNear(windowStart = 0L, windowEnd = 2_000L).single()

        assertEquals(null, row.horizontalAccuracyMeters)
    }

    @Test fun `routeSamplesNear spans across walks — not scoped to a single walk_id`() = runTest {
        val walkA = newWalk(startTimestamp = 1_000L)
        val walkB = newWalk(startTimestamp = 2_000L)
        routeDao.insert(sample(walkA, timestamp = 1_000L))
        routeDao.insert(sample(walkB, timestamp = 1_100L))

        val rows = routeDao.routeSamplesNear(windowStart = 0L, windowEnd = 2_000L)

        assertEquals(2, rows.size)
    }

    @Test fun `the caller shares DossierSenses HYGIENE_MAX_GAP_SECONDS for the window bound, not a hand-copied literal`() {
        // Not a DAO behavior test — a guard against a future edit
        // desynchronizing the query bound from qualifies()'s own gate.
        assertEquals(90.0, DossierSenses.HYGIENE_MAX_GAP_SECONDS, 0.0)
    }

    // --- WalkDao.walkSensesSnapshot ----------------------------------------------

    @Test fun `walkSensesSnapshot projects walkId, startDate, intention, weatherCondition in range`() = runTest {
        val walkId = newWalk(startTimestamp = 5_000L, intention = "presence", weatherCondition = "clear")

        val row = walkDao.walkSensesSnapshot(from = 0L, to = 10_000L).single()

        assertEquals(walkId, row.walkId)
        assertEquals(5_000L, row.startTimestamp)
        assertEquals("presence", row.intention)
        assertEquals("clear", row.weatherCondition)
    }

    @Test fun `walkSensesSnapshot excludes walks outside the from-to range`() = runTest {
        newWalk(startTimestamp = 1_000L)
        newWalk(startTimestamp = 50_000L)

        val rows = walkDao.walkSensesSnapshot(from = 10_000L, to = 20_000L)

        assertTrue(rows.isEmpty())
    }

    @Test fun `walkSensesSnapshot is inclusive at both range edges`() = runTest {
        newWalk(startTimestamp = 1_000L)
        newWalk(startTimestamp = 2_000L)

        val rows = walkDao.walkSensesSnapshot(from = 1_000L, to = 2_000L)

        assertEquals(2, rows.size)
    }

    @Test fun `walkSensesSnapshot orders ascending by startDate`() = runTest {
        newWalk(startTimestamp = 3_000L)
        newWalk(startTimestamp = 1_000L)
        newWalk(startTimestamp = 2_000L)

        val rows = walkDao.walkSensesSnapshot(from = 0L, to = 10_000L)

        assertEquals(listOf(1_000L, 2_000L, 3_000L), rows.map { it.startTimestamp })
    }

    @Test fun `walkSensesSnapshot row converts to core WalkSnapshotRow with Instant conversion`() = runTest {
        newWalk(startTimestamp = 7_000L, intention = "release", weatherCondition = "wind")

        val row = walkDao.walkSensesSnapshot(from = 0L, to = 10_000L).single().toWalkSnapshotRow()

        assertEquals(java.time.Instant.ofEpochMilli(7_000L), row.startDate)
        assertEquals("release", row.intention)
        assertEquals("wind", row.weatherCondition)
    }
}
