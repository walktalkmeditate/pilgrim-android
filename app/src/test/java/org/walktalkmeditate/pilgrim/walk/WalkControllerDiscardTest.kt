// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.walk

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.WalkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkControllerDiscardTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: DiscardFakeClock
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
        clock = DiscardFakeClock(initial = 1_000L)
        controller = WalkController(repository, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `discardWalk from Active transitions to Idle and deletes the walk row`() = runTest {
        val walk = controller.startWalk()
        val walkId = walk.id

        controller.discardWalk()

        assertEquals(WalkState.Idle, controller.state.value)
        assertNull(repository.getWalk(walkId))
    }

    @Test
    fun `discardWalk from Idle is a no-op`() = runTest {
        controller.discardWalk()
        assertEquals(WalkState.Idle, controller.state.value)
    }

    @Test
    fun `discardWalk from Finished is a no-op (walk already saved)`() = runTest {
        val walk = controller.startWalk()
        clock.advanceTo(2_000L)
        controller.finishWalk()
        assertTrue(controller.state.value is WalkState.Finished)

        controller.discardWalk()

        val state = controller.state.value
        assertTrue(state is WalkState.Finished)
        assertEquals(walk.id, (state as WalkState.Finished).walk.walkId)
    }
}

private class DiscardFakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
