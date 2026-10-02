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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.FakeUnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.walk.honor.HonorHarness

/** The "Walk again" picker: which walks it lists, how a row reads, and what a tap does (parity spec F §5). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class OwnWalkPickerModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dispatcher = UnconfinedTestDispatcher()
    private val utc = ZoneId.of("UTC")
    private lateinit var h: HonorHarness
    private val viewModels = mutableListOf<OwnWalkPickerViewModel>()

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

    private fun walk(
        id: Long,
        startTimestamp: Long,
        distanceMeters: Double?,
        intention: String? = null,
        finished: Boolean = true,
    ) = Walk(
        id = id,
        uuid = "00000000-0000-4000-8000-%012d".format(id),
        startTimestamp = startTimestamp,
        endTimestamp = if (finished) startTimestamp + 600_000L else null,
        intention = intention,
        distanceMeters = distanceMeters,
    )

    @Test
    fun `it lists finished walks with a stored distance above zero, newest first`() {
        val walks = listOf(
            walk(1, startTimestamp = 1_000L, distanceMeters = 500.0),
            walk(2, startTimestamp = 3_000L, distanceMeters = 0.0),
            walk(3, startTimestamp = 2_000L, distanceMeters = 800.0),
            walk(4, startTimestamp = 4_000L, distanceMeters = null),
            walk(5, startTimestamp = 5_000L, distanceMeters = 900.0, finished = false),
        )

        assertEquals(listOf(3L, 1L), OwnWalkPickerModel.eligible(walks).map { it.id })
    }

    @Test
    fun `a row is the trimmed intention, else the medium date, over the distance in the walker's units`() {
        val named = walk(1, startTimestamp = 1_700_000_000_000L, distanceMeters = 1_100.0, intention = "  for her  ")
        val unnamed = walk(2, startTimestamp = 1_700_000_000_000L, distanceMeters = 1_100.0, intention = "   ")

        val metric = OwnWalkPickerModel.rows(listOf(named, unnamed), UnitSystem.Metric, utc, Locale.US)
        val imperial = OwnWalkPickerModel.rows(listOf(named), UnitSystem.Imperial, utc, Locale.US)

        assertEquals("for her", metric[0].title)
        assertEquals("Nov 14, 2023", metric[1].title)
        assertEquals("1.10 km", metric[0].distance)
        assertEquals("0.68 mi", imperial[0].distance)
    }

    @Test
    fun `the list loads newest first`() = runTest(dispatcher) {
        h.db.walkDao().insert(walk(1, startTimestamp = 1_000L, distanceMeters = 500.0, intention = "older"))
        h.db.walkDao().insert(walk(2, startTimestamp = 2_000L, distanceMeters = 800.0, intention = "newer"))
        val vm = picker()

        val loaded = wallClock {
            vm.state.first { it is OwnWalkPickerUiState.Loaded } as OwnWalkPickerUiState.Loaded
        }

        assertEquals(listOf("newer", "older"), loaded.rows.map { it.title })
    }

    @Test
    fun `a walk with a route opens its overview, and the picker raises no alert`() = runTest(dispatcher) {
        val id = insertWalkWithRoute(stepDegrees = 0.001)
        val vm = picker()

        vm.picked.test(timeout = 10.seconds) {
            vm.pick(id)
            assertEquals(id, awaitItem())
        }
        assertFalse(vm.showsUnwalkable.value)
    }

    // pilgrim-ios #110, matched: an archived walk keeps its distance but not
    // its route, so the picker lists it and every tap ends in the alert.
    @Test
    fun `an archived walk is listed, and a tap on it raises the alert and keeps the picker up`() = runTest(dispatcher) {
        h.db.walkDao().insert(walk(7, startTimestamp = 1_000L, distanceMeters = 2_400.0))
        val vm = picker()
        val loaded = wallClock {
            vm.state.first { it is OwnWalkPickerUiState.Loaded } as OwnWalkPickerUiState.Loaded
        }
        assertEquals(listOf(7L), loaded.rows.map { it.walkId })

        vm.showsUnwalkable.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            vm.pick(7L)
            assertTrue(awaitItem())
        }
        vm.picked.test(timeout = 1.seconds) { expectNoEvents() }

        vm.dismissUnwalkable()
        assertFalse(vm.showsUnwalkable.value)
    }

    @Test
    fun `a route under 20 m raises the alert too`() = runTest(dispatcher) {
        val id = insertWalkWithRoute(stepDegrees = 0.000001)
        val vm = picker()

        vm.showsUnwalkable.test(timeout = 10.seconds) {
            assertFalse(awaitItem())
            vm.pick(id)
            assertTrue(awaitItem())
        }
    }

    private suspend fun insertWalkWithRoute(stepDegrees: Double): Long {
        val id = h.db.walkDao().insert(walk(0, startTimestamp = 1_000_000L, distanceMeters = 1_100.0))
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

    private fun picker(): OwnWalkPickerViewModel =
        OwnWalkPickerViewModel(
            repository = h.repository,
            unitsPreferences = FakeUnitsPreferencesRepository(),
            ownWalkWays = OwnWalkWays(
                h.repository,
                VoiceRecordingFileSystem(context),
                Dispatchers.IO,
                { utc },
                { Locale.US },
            ),
            zone = { utc },
            locale = { Locale.US },
        ).also { viewModels += it }

    /** Room answers on its own threads, so a wait runs on the wall clock, not the test's virtual one. */
    private suspend fun <T> wallClock(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(10_000L) { block() } }
}
