// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
class WalkControllerWaypointLabelIconTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var controller: WalkController

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
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
        controller = WalkController(repository = repository, clock = object : Clock {
            override fun now(): Long = 1_000L
        })
    }

    @After fun tearDown() = db.close()

    @Test fun `recordWaypoint with label and icon persists both fields`() = runBlocking {
        controller.startWalk()
        controller.recordLocation(LocationPoint(latitude = 1.0, longitude = 2.0, timestamp = 100L))

        controller.recordWaypoint(label = "Peaceful", icon = "leaf")

        val walkId = repository.allWalks().first().id
        val waypoints = repository.waypointsFor(walkId)
        assertEquals(1, waypoints.size)
        assertEquals("Peaceful", waypoints[0].label)
        assertEquals("leaf", waypoints[0].icon)
    }

    @Test fun `recordWaypoint no-arg keeps label and icon null (notification path parity)`() = runBlocking {
        controller.startWalk()
        controller.recordLocation(LocationPoint(latitude = 1.0, longitude = 2.0, timestamp = 100L))

        controller.recordWaypoint()

        val walkId = repository.allWalks().first().id
        val waypoints = repository.waypointsFor(walkId)
        assertEquals(1, waypoints.size)
        assertNull(waypoints[0].label)
        assertNull(waypoints[0].icon)
    }
}
