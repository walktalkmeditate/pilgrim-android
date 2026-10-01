// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.app.Application
import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.time.ZoneId
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.core.flags.FixedReleaseFlags
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/** "walk this again" builds the Way before the summary closes (parity spec F §6). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WalkAgainViewModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var h: HonorHarness
    private val viewModels = mutableListOf<WalkAgainViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        h = HonorHarness(folder.root)
    }

    @After
    fun tearDown() {
        runBlocking {
            withTimeout(10_000L) { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        }
        h.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a walk with a route builds its Way, and the overview opens`() = runTest(dispatcher) {
        val id = walkWithRoute(stepDegrees = 0.001)
        val vm = door(honorEnabled = true)

        vm.results.test(timeout = 10.seconds) {
            vm.walkAgain(id)
            assertEquals(WalkAgainResult(id, built = true), awaitItem())
        }
    }

    // pilgrim-ios #110, matched: the door shows for any route of two points,
    // but a route under 20 m builds no Way, so the summary closes and
    // nothing opens, with no alert.
    @Test
    fun `a route of two points under 20 m builds nothing, and nothing opens`() = runTest(dispatcher) {
        val id = walkWithRoute(stepDegrees = 0.000001)
        val vm = door(honorEnabled = true)

        vm.results.test(timeout = 10.seconds) {
            vm.walkAgain(id)
            assertEquals(WalkAgainResult(id, built = false), awaitItem())
        }
    }

    @Test
    fun `with the flag off the door does nothing`() = runTest(dispatcher) {
        val id = walkWithRoute(stepDegrees = 0.001)
        val vm = door(honorEnabled = false)

        vm.results.test(timeout = 1.seconds) {
            vm.walkAgain(id)
            expectNoEvents()
        }
    }

    private suspend fun walkWithRoute(stepDegrees: Double): Long {
        val id = h.db.walkDao().insert(
            Walk(uuid = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50", startTimestamp = 1_000_000L, endTimestamp = 1_600_000L),
        )
        (0..10).forEach { i ->
            h.db.routeDataSampleDao().insert(
                RouteDataSample(
                    walkId = id,
                    timestamp = 1_000_000L + i * 60_000L,
                    latitude = 0.0,
                    longitude = i * stepDegrees,
                ),
            )
        }
        return id
    }

    private fun door(honorEnabled: Boolean) = WalkAgainViewModel(
        releaseFlags = FixedReleaseFlags(honor = honorEnabled),
        ownWalkWays = OwnWalkWays(
            h.repository,
            VoiceRecordingFileSystem(context),
            Dispatchers.IO,
            { ZoneId.of("UTC") },
            { Locale.US },
        ),
    ).also { viewModels += it }
}
