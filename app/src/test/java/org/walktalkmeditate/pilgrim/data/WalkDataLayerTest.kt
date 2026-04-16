// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

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
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkEventType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkDataLayerTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PilgrimDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = WalkRepository(
            walkDao = db.walkDao(),
            routeDao = db.routeDataSampleDao(),
            altitudeDao = db.altitudeSampleDao(),
            walkEventDao = db.walkEventDao(),
            activityIntervalDao = db.activityIntervalDao(),
            waypointDao = db.waypointDao(),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `start walk persists and can be retrieved`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L, intention = "silence")

        assertTrue("id should be assigned", walk.id > 0L)
        val retrieved = repository.getWalk(walk.id)
        assertNotNull(retrieved)
        assertEquals(1_000L, retrieved?.startTimestamp)
        assertEquals("silence", retrieved?.intention)
        assertNull("active walks have null end_timestamp", retrieved?.endTimestamp)
    }

    @Test
    fun `finishing a walk sets end_timestamp`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)

        repository.finishWalk(walk, endTimestamp = 2_500L)

        val retrieved = repository.getWalk(walk.id)
        assertEquals(2_500L, retrieved?.endTimestamp)
    }

    @Test
    fun `getActiveWalk returns only the unfinished walk`() = runTest {
        val finished = repository.startWalk(startTimestamp = 1_000L)
        repository.finishWalk(finished, endTimestamp = 2_000L)
        val active = repository.startWalk(startTimestamp = 3_000L)

        val returned = repository.getActiveWalk()

        assertEquals(active.id, returned?.id)
    }

    @Test
    fun `walk event enum round trips through converter`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)

        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 1_500L, eventType = WalkEventType.PAUSED),
        )
        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 1_800L, eventType = WalkEventType.MEDITATION_START),
        )

        val events = repository.eventsFor(walk.id)

        assertEquals(2, events.size)
        assertEquals(WalkEventType.PAUSED, events[0].eventType)
        assertEquals(WalkEventType.MEDITATION_START, events[1].eventType)
    }

    @Test
    fun `deleting a walk cascades to child samples`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_100L, latitude = 35.0, longitude = 139.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_200L, latitude = 35.001, longitude = 139.001),
        )
        assertEquals(2, repository.locationSamplesFor(walk.id).size)

        repository.deleteWalk(walk)

        assertEquals(0, repository.locationSamplesFor(walk.id).size)
        assertNull(repository.getWalk(walk.id))
    }
}
