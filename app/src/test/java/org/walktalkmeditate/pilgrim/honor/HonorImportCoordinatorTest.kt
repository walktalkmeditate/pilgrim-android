// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.walktalkmeditate.pilgrim.data.honor.FakeWayMediaDownloadScheduler
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.data.honor.WayMediaReport
import org.walktalkmeditate.pilgrim.data.honor.WayMediaWork
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.VoiceKind
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMoment
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The import half of iOS's coordinator (shared-walk spec S1 §6.3): the
 * races from `MainCoordinatorHonorTests.swift@7c200bf` that need no
 * routing, the failure mapping, the sheet's reset, and the overview's
 * gather and close. Each import is held open until the test lands it, as
 * iOS's `heldImport` holds it, and it resolves even once cancelled, as
 * iOS's fetch does after its body arrives (S1 §8.10): what a cancelled
 * import may not do is write state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HonorImportCoordinatorTest {

    @get:Rule val folder = TemporaryFolder()

    private val dispatcher = UnconfinedTestDispatcher()
    private val held = mutableMapOf<String, Continuation<Way>>()
    private val asked = mutableListOf<String>()
    private val scheduler = FakeWayMediaDownloadScheduler()
    private val store by lazy { WayStore({ File(folder.root, "Ways") }, syncDirectory = { true }) }
    private var downloader: WayMediaDownloader? = null

    private fun TestScope.coordinator(honorEnabled: Boolean = true): HonorImportCoordinator {
        val media = WayMediaDownloader(store, scheduler, backgroundScope, dispatcher).also { downloader = it }
        return HonorImportCoordinator(
            importShare = { id ->
                asked += id
                suspendCoroutine { held[id] = it }
            },
            honorEnabled = honorEnabled,
            scope = backgroundScope,
            media = { media },
        )
    }

    private fun land(id: String) = held.getValue(id).resume(way(id))

    private fun fail(id: String, error: Throwable) = held.getValue(id).resumeWithException(error)

    @Test
    fun `an import that lands clears the line and offers its Way to the screen that opens it`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.openWay(FIRST)
        assertEquals(HonorImportState.Fetching, imports.state.value)
        land(FIRST)

        assertEquals(HonorImportState.Idle, imports.state.value)
        assertEquals("share:$FIRST", imports.fetched.value)
        imports.consumeFetched("share:$FIRST")
        assertNull(imports.fetched.value)
    }

    @Test
    fun `a newer link cancels the older fetch, which writes no state when it lands`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.openWay(FIRST)
        imports.openWay(SECOND)
        land(FIRST)

        assertEquals(HonorImportState.Fetching, imports.state.value)
        assertNull(imports.fetched.value)
        land(SECOND)
        assertEquals("share:$SECOND", imports.fetched.value)
        assertEquals(listOf(FIRST, SECOND), asked)
    }

    @Test
    fun `a cancelled fetch's failure writes no state either`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.openWay(FIRST)
        imports.openWay(SECOND)
        fail(FIRST, WayImportException(WayError.NOT_FOUND))

        assertEquals(HonorImportState.Fetching, imports.state.value)
    }

    @Test
    fun `each failure the importer names is the line, and anything else is couldn't reach the walk`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.openWay(FIRST)
        fail(FIRST, WayImportException(WayError.RETURNED_TO_TRAIL))
        assertEquals(HonorImportState.Failed(WayError.RETURNED_TO_TRAIL), imports.state.value)

        imports.openWay(SECOND)
        fail(SECOND, IOException("the store could not write"))
        assertEquals(
            "a failed save reads as unavailable (pilgrim-ios #114, matched)",
            HonorImportState.Failed(WayError.UNAVAILABLE),
            imports.state.value,
        )
        assertNull(imports.fetched.value)
    }

    // iOS `startWalk`: the import is cancelled and the state left as it was.
    @Test
    fun `a walk starting drops the import in flight, and its Way never opens`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.openWay(FIRST)
        imports.cancelImport()
        land(FIRST)

        assertEquals(HonorImportState.Fetching, imports.state.value)
        assertNull(imports.fetched.value)
    }

    // S1-D9, matched: `chooseWay` resets the line but not the import.
    @Test
    fun `opening the Ways sheet resets the line and leaves the import running`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.openWay(FIRST)
        imports.chooseWay()
        assertEquals(HonorImportState.Idle, imports.state.value)
        land(FIRST)

        assertEquals("share:$FIRST", imports.fetched.value)
    }

    @Test
    fun `with the release flag off nothing imports`() = runTest(dispatcher) {
        val imports = coordinator(honorEnabled = false)

        imports.openWay(FIRST)

        assertEquals(HonorImportState.Idle, imports.state.value)
        assertTrue(asked.isEmpty())
    }

    // iOS `gather` and `handleOverviewDismiss`.
    @Test
    fun `an overview gathers its Way, and a real close hands the state back`() = runTest(dispatcher) {
        val imports = coordinator()
        val overview = Any()

        imports.gather(way(FIRST), overview)
        assertEquals(
            "a share with no media download yet is ready at once",
            HonorImportState.Ready,
            imports.state.value,
        )
        imports.overviewClosed(overview)

        assertEquals(HonorImportState.Idle, imports.state.value)
    }

    @Test
    fun `an own walk's overview is ready at once`() = runTest(dispatcher) {
        val imports = coordinator()

        imports.gather(way(FIRST).copy(id = "walk:$SOURCE_UUID", source = WaySource.OwnWalk(SOURCE_UUID)), Any())

        assertEquals(HonorImportState.Ready, imports.state.value)
    }

    @Test
    fun `an overview another has replaced changes nothing as it goes`() = runTest(dispatcher) {
        val imports = coordinator()
        val outgoing = Any()
        val incoming = Any()
        imports.gather(way(FIRST), outgoing)
        imports.gather(way(SECOND), incoming)
        imports.openWay(THIRD)

        imports.overviewClosed(outgoing)

        assertEquals(HonorImportState.Fetching, imports.state.value)
    }

    // S1 §8.16: a second link while an overview is up holds Begin with its fetch.
    @Test
    fun `a link while an overview is up shows the fetch there, and its failure stays until the close`() = runTest(dispatcher) {
        val imports = coordinator()
        val overview = Any()
        imports.gather(way(FIRST), overview)

        imports.openWay(SECOND)
        assertEquals(HonorImportState.Fetching, imports.state.value)
        fail(SECOND, WayImportException(WayError.UNAVAILABLE))
        assertEquals(HonorImportState.Failed(WayError.UNAVAILABLE), imports.state.value)

        imports.overviewClosed(overview)
        assertEquals(HonorImportState.Idle, imports.state.value)
    }

    // Shared-walk spec S4 §8: the gathering states, the reducer's precedence, and the overview's two buttons.

    private fun withVoices(shareId: String, count: Int): Way = way(shareId).copy(
        moments = (1..count).map {
            WayMoment(
                id = "voice-$it",
                frac = it / 10.0,
                at = null,
                kind = WayMomentKind.Voice(it / 10.0, 3.0, VoiceKind.SPOKEN, WayMedia.File("audio/$it.m4a")),
            )
        },
    ).also(store::save)

    private fun report(wayId: String, state: WayMediaWork.State, unfinished: Int, failures: List<String> = emptyList(), diskFull: Boolean = false) =
        scheduler.report(wayId, state, WayMediaReport(accepted = 4, unfinished = unfinished, failures = failures, diskFull = diskFull))

    @Test
    fun `a share's overview shows its gathering at once, seeded from what is here, and follows it to ready`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        File(folder.root, "Ways/${way.id}/media/audio/1.m4a").apply { parentFile!!.mkdirs() }.writeBytes(byteArrayOf(1))

        imports.gather(way, Any())
        assertEquals("the first state is set before gather returns", HonorImportState.Gathering(0.25), imports.state.value)

        report(way.id, WayMediaWork.State.RUNNING, unfinished = 1)
        assertEquals(HonorImportState.Gathering(0.75), imports.state.value)
        report(way.id, WayMediaWork.State.SUCCEEDED, unfinished = 0)
        assertEquals(HonorImportState.Ready, imports.state.value)
    }

    @Test
    fun `failures after the gather read some voices didn't arrive, and try again replaces the round`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        imports.gather(way, Any())
        report(way.id, WayMediaWork.State.SUCCEEDED, unfinished = 0, failures = listOf("audio/2.m4a"))
        assertEquals(HonorImportState.MediaMissing(listOf("audio/2.m4a")), imports.state.value)

        imports.retryMedia(way)

        assertEquals("back to gathering, seeded from disk", HonorImportState.Gathering(0.0), imports.state.value)
        assertEquals(listOf(false, true), scheduler.gathers.map { it.replace })
    }

    // Spec correction 6: the tap only drops the watch; Begin was enabled all along.
    @Test
    fun `walk without the missing voices clears the line, and no later download moves it back`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        imports.gather(way, Any())
        report(way.id, WayMediaWork.State.SUCCEEDED, unfinished = 0, failures = listOf("audio/2.m4a"))

        imports.walkWithoutMissingVoices()
        assertEquals(HonorImportState.Ready, imports.state.value)
        imports.retryMedia(way)
        report(way.id, WayMediaWork.State.SUCCEEDED, unfinished = 0, failures = listOf("audio/3.m4a"))

        assertEquals(HonorImportState.Ready, imports.state.value)
    }

    @Test
    fun `disk full outranks a gather still going`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        imports.gather(way, Any())

        report(way.id, WayMediaWork.State.RUNNING, unfinished = 2, failures = listOf("audio/2.m4a"), diskFull = true)

        assertEquals(HonorImportState.Failed(WayError.DISK_FULL), imports.state.value)
        assertTrue("the other files keep going", way.id in downloader!!.gathers.value.active)
    }

    @Test
    fun `a close stops the watch, not the transfers`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        val overview = Any()
        imports.gather(way, overview)

        imports.overviewClosed(overview)
        report(way.id, WayMediaWork.State.RUNNING, unfinished = 1)

        assertEquals(HonorImportState.Idle, imports.state.value)
        assertTrue(scheduler.cancels.isEmpty())
        assertEquals(0.75, downloader!!.gathers.value.progress[way.id]!!, 1e-9)
    }

    @Test
    fun `a walk starting drops the watch and leaves the transfers and the state`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        imports.gather(way, Any())

        imports.cancelImport()
        report(way.id, WayMediaWork.State.SUCCEEDED, unfinished = 0)

        assertEquals(HonorImportState.Gathering(0.0), imports.state.value)
        assertTrue(scheduler.cancels.isEmpty())
    }

    // S4-D4, pilgrim-ios #113, matched: the live watch can overwrite a second link's fetching line.
    @Test
    fun `a download change during a second link's fetch recomputes the shown Way's state, as on iOS`() = runTest(dispatcher) {
        val imports = coordinator()
        val way = withVoices(FIRST, 4)
        imports.gather(way, Any())
        imports.openWay(SECOND)
        assertEquals(HonorImportState.Fetching, imports.state.value)

        report(way.id, WayMediaWork.State.SUCCEEDED, unfinished = 0)

        assertEquals(HonorImportState.Ready, imports.state.value)
    }

    private fun way(shareId: String) = Way(
        id = "share:$shareId",
        source = WaySource.Share(id = shareId, pageUrl = "https://walk.pilgrimapp.org/$shareId"),
        title = "Rúa do Franco → Obradoiro",
        departedAt = Instant.parse("2026-08-01T07:00:00Z"),
        tzIdentifier = "Europe/Madrid",
        expires = Instant.parse("2099-01-01T00:00:00Z"),
        route = listOf(WayPoint(42.88, -8.545, 250.0, 0.0), WayPoint(42.88, -8.540, 250.0, 400.0)),
        totalDistanceMeters = 408.0,
        theirActiveSeconds = 540.0,
        moments = emptyList(),
        weather = null,
        spans = emptyList(),
    )

    private companion object {
        const val FIRST = "Qoi4YmPHLN"
        const val SECOND = "Second1234"
        const val THIRD = "Third12345"
        const val SOURCE_UUID = "0e8d6f8a-5b1c-4f1e-9a53-2f1d8c7b6a50"
    }
}
