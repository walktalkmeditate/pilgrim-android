// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.lifecycle.viewModelScope
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.honor.HonorImportCoordinator
import org.walktalkmeditate.pilgrim.honor.HonorImportState

/** The Ways sheet's model (shared-walk spec S4 §6–§7): the reset on opening, the shared list, and "Open". */
@OptIn(ExperimentalCoroutinesApi::class)
class HonorWaysViewModelTest {

    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private var clockMillis = 1_000_000_000_000L
    private lateinit var store: WayStore
    private val importScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val asked = mutableListOf<String>()
    private var held: Continuation<Way>? = null
    private val imports = HonorImportCoordinator(
        importShare = { id ->
            asked += id
            suspendCoroutine { held = it }
        },
        honorEnabled = true,
        scope = importScope,
    )
    private val viewModels = mutableListOf<HonorWaysViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = WayStore({ File(folder.root, "Ways") }, Clock { clockMillis }, syncDirectory = { true })
    }

    @After
    fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() } }
        importScope.cancel()
        Dispatchers.resetMain()
    }

    private fun sheet(zone: ZoneId = ZoneId.of("UTC")) =
        HonorWaysViewModel(store, imports, { zone }, { Locale.US }, dispatcher).also { viewModels += it }

    // iOS `chooseWay`, and S1-D9 matched: the import keeps running.
    @Test
    fun `opening the sheet clears the line and leaves an import in flight running`() = runTest(dispatcher) {
        imports.openWay("Qoi4YmPHLN")

        val vm = sheet()
        assertEquals(HonorImportState.Idle, vm.importState.value)
        held!!.resume(way("share:Qoi4YmPHLN", "a"))

        assertEquals("share:Qoi4YmPHLN", vm.fetched.value)
    }

    @Test
    fun `the list is shared Ways only, newest acceptance first, dated in the phone's zone`() = runTest(dispatcher) {
        store.save(way("share:Older12345", "the older share"))
        clockMillis += 1_000
        store.save(way("walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50", "an own walk", source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50")))
        clockMillis += 1_000
        store.save(way("share:Newer12345", "the newer share"))

        val rows = (sheet(zone = ZoneId.of("America/Los_Angeles")).shared.value as SharedWaysUiState.Loaded).rows

        assertEquals(listOf("the newer share", "the older share"), rows.map { it.title })
        assertEquals("Jul 31, 2026", rows.first().date)
        assertEquals(1 to 1, rows.first().voiceCount to rows.first().photoCount)
    }

    @Test
    fun `with no shared Way the list is empty, own walks or not`() = runTest(dispatcher) {
        store.save(way("walk:0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50", "an own walk", source = WaySource.OwnWalk("0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50")))

        assertEquals(SharedWaysUiState.Loaded(emptyList()), sheet().shared.value)
    }

    @Test
    fun `Open imports only what parses, and the id it parses to`() = runTest(dispatcher) {
        val vm = sheet()

        vm.open("not a walk link")
        assertTrue(asked.isEmpty())
        vm.open("  https://walk.pilgrimapp.org/Qoi4YmPHLN \n")

        assertEquals(listOf("Qoi4YmPHLN"), asked)
        assertEquals(HonorImportState.Fetching, vm.importState.value)
    }

    private fun way(id: String, title: String, source: WaySource = WaySource.Share(id.removePrefix("share:"), "https://walk.pilgrimapp.org/x")) = Way(
        id = id,
        source = source,
        title = title,
        // 03:00 UTC on Aug 1 is still Jul 31 in Los Angeles.
        departedAt = Instant.parse("2026-08-01T03:00:00Z"),
        tzIdentifier = null,
        expires = null,
        route = listOf(WayPoint(42.88, -8.545, 250.0, 0.0), WayPoint(42.88, -8.540, 250.0, 400.0)),
        totalDistanceMeters = 408.0,
        theirActiveSeconds = 400.0,
        moments = listOf(
            WayMoment(
                id = "voice-1", frac = 0.5, at = null,
                kind = WayMomentKind.Voice(0.6, 40.0, VoiceKind.SPOKEN, WayMedia.File("audio/1.m4a")),
            ),
            WayMoment(id = "photo-1", frac = 0.8, at = null, kind = WayMomentKind.Photo(WayMedia.File("photos/1.jpg"))),
        ),
        weather = null,
    )
}
