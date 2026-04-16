// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
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
import org.walktalkmeditate.pilgrim.data.entity.WalkEventType
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkControllerTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: FakeClock
    private lateinit var controller: WalkController

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
        clock = FakeClock(initial = 1_000L)
        controller = WalkController(repository, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `startWalk persists a Walk and transitions state to Active`() = runTest {
        val walk = controller.startWalk(intention = "silence")

        assertTrue("walk should have persistent id", walk.id > 0)
        val persisted = repository.getWalk(walk.id)
        assertEquals("silence", persisted?.intention)

        val state = controller.state.value
        assertTrue(state is WalkState.Active)
        assertEquals(walk.id, (state as WalkState.Active).walk.walkId)
    }

    @Test
    fun `recordLocation persists a sample and accumulates distance`() = runTest {
        val walk = controller.startWalk()

        controller.recordLocation(LocationPoint(timestamp = 1_100L, latitude = 0.0, longitude = 0.0))
        controller.recordLocation(LocationPoint(timestamp = 1_200L, latitude = 0.0, longitude = 0.001))

        val samples = repository.locationSamplesFor(walk.id)
        assertEquals(2, samples.size)

        val active = controller.state.value as WalkState.Active
        assertEquals(111.32, active.walk.distanceMeters, 0.5)
    }

    @Test
    fun `pause and resume persist events and resume updates cumulative paused time`() = runTest {
        controller.startWalk()
        clock.advanceTo(2_000L)

        controller.pauseWalk()
        clock.advanceTo(2_500L)
        controller.resumeWalk()

        val walk = repository.getActiveWalk()
        assertNotNull(walk)
        val events = repository.eventsFor(walk!!.id)
        assertEquals(2, events.size)
        assertEquals(WalkEventType.PAUSED, events[0].eventType)
        assertEquals(WalkEventType.RESUMED, events[1].eventType)

        val active = controller.state.value as WalkState.Active
        assertEquals(500L, active.walk.totalPausedMillis)
    }

    @Test
    fun `finishWalk updates end_timestamp in Room and transitions to Finished`() = runTest {
        val walk = controller.startWalk()
        clock.advanceTo(5_000L)

        controller.finishWalk()

        val persisted = repository.getWalk(walk.id)
        assertEquals(5_000L, persisted?.endTimestamp)
        assertTrue(controller.state.value is WalkState.Finished)
    }

    @Test
    fun `meditation start and end persist events and accumulate meditation time`() = runTest {
        controller.startWalk()
        clock.advanceTo(2_000L)
        controller.startMeditation()
        clock.advanceTo(3_500L)
        controller.endMeditation()

        val walk = repository.getActiveWalk()!!
        val events = repository.eventsFor(walk.id)
        assertEquals(WalkEventType.MEDITATION_START, events[0].eventType)
        assertEquals(WalkEventType.MEDITATION_END, events[1].eventType)

        val active = controller.state.value as WalkState.Active
        assertEquals(1_500L, active.walk.totalMeditatedMillis)
    }
}

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
