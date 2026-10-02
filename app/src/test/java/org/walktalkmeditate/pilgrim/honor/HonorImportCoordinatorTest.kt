// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

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
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.domain.honor.Way
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

    private val dispatcher = UnconfinedTestDispatcher()
    private val held = mutableMapOf<String, Continuation<Way>>()
    private val asked = mutableListOf<String>()

    private fun TestScope.coordinator(honorEnabled: Boolean = true) = HonorImportCoordinator(
        importShare = { id ->
            asked += id
            suspendCoroutine { held[id] = it }
        },
        honorEnabled = honorEnabled,
        scope = backgroundScope,
    )

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
