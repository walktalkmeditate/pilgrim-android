// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Exercises the WalkViewModel action surface. Uses UnconfinedTestDispatcher
 * so coroutines launched from viewModelScope run inline; StandardTestDispatcher
 * requires advanceUntilIdle which hangs against our 1-Hz ticker flow.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkViewModelTest {

    private lateinit var db: PilgrimDatabase
    private lateinit var repository: WalkRepository
    private lateinit var clock: FakeClock
    private lateinit var controller: WalkController
    private lateinit var viewModel: WalkViewModel
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
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
        )
        clock = FakeClock(initial = 1_000L)
        controller = WalkController(repository, clock)
        viewModel = WalkViewModel(context, controller, repository, clock)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `startWalk dispatches Start and the controller becomes Active`() = runTest(dispatcher) {
        controller.state.test {
            assertTrue(awaitItem() is WalkState.Idle)

            viewModel.startWalk(intention = "silence")

            assertTrue(awaitItem() is WalkState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pauseWalk and resumeWalk transition controller state correctly`() = runTest(dispatcher) {
        controller.state.test {
            assertTrue(awaitItem() is WalkState.Idle)

            viewModel.startWalk()
            assertTrue(awaitItem() is WalkState.Active)

            clock.advanceTo(2_000L)
            viewModel.pauseWalk()
            assertTrue(awaitItem() is WalkState.Paused)

            clock.advanceTo(2_500L)
            viewModel.resumeWalk()
            assertTrue(awaitItem() is WalkState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `finishWalk transitions controller state to Finished`() = runTest(dispatcher) {
        controller.state.test {
            assertTrue(awaitItem() is WalkState.Idle)

            viewModel.startWalk()
            assertTrue(awaitItem() is WalkState.Active)

            clock.advanceTo(5_000L)
            viewModel.finishWalk()

            assertTrue(awaitItem() is WalkState.Finished)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `uiState emits Active while subscribed`() = runTest(dispatcher) {
        viewModel.uiState.test {
            assertTrue(awaitItem().walkState is WalkState.Idle)

            viewModel.startWalk()

            assertTrue(awaitItem().walkState is WalkState.Active)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `restoreActiveWalk returns null when no walk is persisted`() = runTest(dispatcher) {
        assertNull(viewModel.restoreActiveWalk())
    }

    @Test
    fun `restoreActiveWalk returns the persisted walk when one exists`() = runTest(dispatcher) {
        val walk = repository.startWalk(startTimestamp = 500L, intention = "grief")

        val restored = viewModel.restoreActiveWalk()

        assertEquals(walk.id, restored?.id)
    }
}

private class FakeClock(initial: Long) : Clock {
    private var current: Long = initial
    override fun now(): Long = current
    fun advanceTo(millis: Long) {
        current = millis
    }
}
