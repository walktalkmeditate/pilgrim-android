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
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkEventType
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
    fun `startWalk after finishing creates a fresh walk with a new id`() = runTest {
        val first = controller.startWalk(intention = "silence")
        clock.advanceTo(2_000L)
        controller.finishWalk()

        clock.advanceTo(5_000L)
        val second = controller.startWalk(intention = "grief")

        assertTrue("second walk should have a different id", second.id != first.id)
        val state = controller.state.value as WalkState.Active
        assertEquals(second.id, state.walk.walkId)
        assertEquals(0.0, state.walk.distanceMeters, 0.0)
        assertEquals(5_000L, state.walk.startedAt)
    }

    @Test
    fun `startWalk while already active throws`() = runTest {
        controller.startWalk()
        try {
            controller.startWalk()
            throw AssertionError("expected IllegalStateException")
        } catch (expected: IllegalStateException) {
            // Expected.
        }
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

    @Test
    fun `restoreActiveWalk returns null when no walk is in progress`() = runTest {
        val restored = controller.restoreActiveWalk()

        assertNull(restored)
        assertTrue(controller.state.value is WalkState.Idle)
    }

    @Test
    fun `restoreActiveWalk rebuilds Active state with distance and lastLocation`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L, intention = "silence")
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_100L, latitude = 0.0, longitude = 0.0),
        )
        repository.recordLocation(
            RouteDataSample(walkId = walk.id, timestamp = 1_200L, latitude = 0.0, longitude = 0.001),
        )
        val fresh = WalkController(repository, clock)

        val restored = fresh.restoreActiveWalk()

        assertEquals(walk.id, restored?.id)
        val state = fresh.state.value as WalkState.Active
        assertEquals(walk.id, state.walk.walkId)
        assertEquals(1_000L, state.walk.startedAt)
        // 0.001 degree at equator ≈ 111.32 meters.
        assertEquals(111.32, state.walk.distanceMeters, 0.5)
        assertEquals(1_200L, state.walk.lastLocation?.timestamp)
    }

    @Test
    fun `restoreActiveWalk rebuilds Paused state when last event is PAUSED without RESUMED`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 1_200L, eventType = WalkEventType.PAUSED),
        )
        val fresh = WalkController(repository, clock)

        fresh.restoreActiveWalk()

        val state = fresh.state.value as WalkState.Paused
        assertEquals(1_200L, state.pausedAt)
    }

    @Test
    fun `restoreActiveWalk folds completed pause durations into totals`() = runTest {
        val walk = repository.startWalk(startTimestamp = 1_000L)
        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 1_200L, eventType = WalkEventType.PAUSED),
        )
        repository.recordEvent(
            WalkEvent(walkId = walk.id, timestamp = 1_500L, eventType = WalkEventType.RESUMED),
        )
        val fresh = WalkController(repository, clock)

        fresh.restoreActiveWalk()

        val state = fresh.state.value as WalkState.Active
        assertEquals(300L, state.walk.totalPausedMillis)
    }

    @Test
    fun `restoreActiveWalk is a no-op when controller already has non-Idle state`() = runTest {
        controller.startWalk()
        val activeIdBefore = (controller.state.value as WalkState.Active).walk.walkId

        val restored = controller.restoreActiveWalk()

        assertNull(restored)
        assertEquals(activeIdBefore, (controller.state.value as WalkState.Active).walk.walkId)
    }
}

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
