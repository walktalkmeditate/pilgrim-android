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
class WalkControllerSetIntentionTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: SetIntentionTestClock
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
        clock = SetIntentionTestClock(initial = 1_000L)
        controller = WalkController(repository, clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `setIntention persists trimmed text on the active walk`() = runTest {
        controller.startWalk(intention = null)
        val walkId = (controller.state.value as WalkState.Active).walk.walkId

        controller.setIntention("  walk well  ")

        val persisted = repository.getWalk(walkId)?.intention
        assertEquals("walk well", persisted)
    }

    @Test
    fun `setIntention clears the intention when blank`() = runTest {
        controller.startWalk(intention = "previous")
        val walkId = (controller.state.value as WalkState.Active).walk.walkId

        controller.setIntention("   ")

        val persisted = repository.getWalk(walkId)?.intention
        assertNull(persisted)
    }

    @Test
    fun `setIntention truncates at 140 chars`() = runTest {
        controller.startWalk(intention = null)
        val walkId = (controller.state.value as WalkState.Active).walk.walkId

        val longText = "x".repeat(200)
        controller.setIntention(longText)

        val persisted = repository.getWalk(walkId)?.intention
        assertEquals(140, persisted?.length)
    }

    @Test
    fun `setIntention from Idle is a no-op`() = runTest {
        controller.setIntention("nothing")
        // No walk to persist to; should not throw and state remains Idle.
        assertEquals(WalkState.Idle, controller.state.value)
    }
}

private class SetIntentionTestClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    @Suppress("unused")
    fun advanceTo(millis: Long) {
        current = millis
    }
}
