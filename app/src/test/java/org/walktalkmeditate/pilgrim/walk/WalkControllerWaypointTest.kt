// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

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
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkControllerWaypointTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: WaypointTestClock
    private lateinit var controller: WalkController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
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
        clock = WaypointTestClock(initial = 1_000L)
        controller = WalkController(repository, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `recordWaypoint from Idle is a no-op`() = runTest {
        controller.recordWaypoint()
        // No active walk → nothing to query, but verify by trying to read
        // any waypoint that might have been (incorrectly) inserted with
        // walkId=0. waypointsFor(0) returns empty.
        assertTrue(repository.waypointsFor(0L).isEmpty())
    }

    @Test
    fun `recordWaypoint from Active inserts a Waypoint with current lastLocation`() = runTest {
        val walk = controller.startWalk()
        controller.recordLocation(
            LocationPoint(timestamp = 2_000L, latitude = 40.0, longitude = -74.0),
        )
        clock.advanceTo(3_000L)
        controller.recordWaypoint()

        val waypoints = repository.waypointsFor(walk.id)
        assertEquals(1, waypoints.size)
        val wp = waypoints.single()
        assertEquals(walk.id, wp.walkId)
        assertEquals(3_000L, wp.timestamp)
        assertEquals(40.0, wp.latitude, 0.000_001)
        assertEquals(-74.0, wp.longitude, 0.000_001)
    }

    @Test
    fun `recordWaypoint from Paused inserts a Waypoint`() = runTest {
        val walk = controller.startWalk()
        controller.recordLocation(
            LocationPoint(timestamp = 2_000L, latitude = 40.0, longitude = -74.0),
        )
        controller.pauseWalk()
        clock.advanceTo(4_000L)
        controller.recordWaypoint()

        val waypoints = repository.waypointsFor(walk.id)
        assertEquals(1, waypoints.size)
        assertEquals(4_000L, waypoints.single().timestamp)
    }

    @Test
    fun `recordWaypoint from Meditating inserts a Waypoint`() = runTest {
        val walk = controller.startWalk()
        controller.recordLocation(
            LocationPoint(timestamp = 2_000L, latitude = 40.0, longitude = -74.0),
        )
        controller.startMeditation()
        clock.advanceTo(5_000L)
        controller.recordWaypoint()

        val waypoints = repository.waypointsFor(walk.id)
        assertEquals(1, waypoints.size)
        assertEquals(5_000L, waypoints.single().timestamp)
    }

    @Test
    fun `recordWaypoint with no GPS fix yet is a no-op`() = runTest {
        val walk = controller.startWalk()
        // No recordLocation call — accumulator.lastLocation is null.
        controller.recordWaypoint()
        assertTrue(repository.waypointsFor(walk.id).isEmpty())
    }

    @Test
    fun `recordWaypoint from Finished is a no-op`() = runTest {
        val walk = controller.startWalk()
        controller.recordLocation(
            LocationPoint(timestamp = 2_000L, latitude = 40.0, longitude = -74.0),
        )
        controller.finishWalk()
        controller.recordWaypoint()
        assertTrue(repository.waypointsFor(walk.id).isEmpty())
    }
}

private class WaypointTestClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
