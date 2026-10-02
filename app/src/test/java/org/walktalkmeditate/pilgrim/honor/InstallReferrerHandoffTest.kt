// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.ui.navigation.Routes
import org.walktalkmeditate.pilgrim.ui.navigation.honorLinkScreen

/**
 * R19's handoff (shared-walk spec S2 §10): AE5 and its edges. The Play
 * client is a fake that blocks until the test replies, as the real one
 * waits on the Play Store; the record is an in-memory store that two
 * handoffs share to stand for a process death between them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InstallReferrerHandoffTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val store = FakeReferrerStore()
    private val routed = mutableListOf<String>()
    private val releases = mutableListOf<() -> Unit>()
    private var setUp = false

    private fun TestScope.handoff(
        client: InstallReferrerClientAdapter,
        honorEnabled: Boolean = true,
        route: (String, () -> Unit) -> Unit = { id, released ->
            routed += id
            releases += released
        },
    ) = InstallReferrerHandoff(
        honorEnabled = honorEnabled,
        client = client,
        store = store,
        setupComplete = { setUp },
        route = route,
        scope = backgroundScope,
    )

    @Test
    fun `AE5 a fresh install from an honor page opens its Way once setup ends, like a held tap`() = runTest(dispatcher) {
        val imports = mutableListOf<String>()
        val coordinator = HonorImportCoordinator(
            importShare = { id ->
                imports += id
                awaitCancellation()
            },
            honorEnabled = true,
            scope = backgroundScope,
            media = { error("unused") },
        )
        val router = HonorLinkRouter(true, coordinator, trackerWalking = { false }, scope = backgroundScope)
        val navHost = Any()
        router.screenChanged(honorLinkScreen(listOf(Routes.WELCOME)), navHost)

        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID")), route = router::routeReferrer).handOff()
        assertTrue("held through setup", imports.isEmpty())
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = ID), store.record)
        router.screenChanged(honorLinkScreen(listOf(Routes.PATH)), navHost)

        assertEquals(listOf(ID), imports)
        assertEquals(HonorLinkToast.Reaching, router.toast.value)
        assertNull("the routing took it, so it opens once", store.record.pendingShareId)
    }

    @Test
    fun `a link the walker tapped on the first launch wins, and the referrer is marked read`() = runTest(dispatcher) {
        val imports = mutableListOf<String>()
        val coordinator = HonorImportCoordinator(
            importShare = { id ->
                imports += id
                awaitCancellation()
            },
            honorEnabled = true,
            scope = backgroundScope,
            media = { error("unused") },
        )
        val router = HonorLinkRouter(true, coordinator, trackerWalking = { false }, scope = backgroundScope)
        val navHost = Any()
        router.screenChanged(honorLinkScreen(listOf(Routes.WELCOME)), navHost)
        router.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/$TAPPED")), restored = false)

        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID")), route = router::routeReferrer).handOff()
        router.screenChanged(honorLinkScreen(listOf(Routes.PATH)), navHost)

        assertEquals(listOf(TAPPED), imports)
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = null), store.record)
    }

    @Test
    fun `AE5 still opens it after a process death in system Settings during onboarding`() = runTest(dispatcher) {
        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))).handOff()
        assertEquals(listOf(ID), routed)
        routed.clear()

        val relaunch = FakeReferrerClient()
        handoff(relaunch).handOff()

        assertEquals("the waiting id is handed over again", listOf(ID), routed)
        assertEquals("from disk, with no second read", 0, relaunch.reads)
        releases.last()()
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = null), store.record)
    }

    @Test
    fun `read and its waiting id are saved before the id is routed`() = runTest(dispatcher) {
        var savedFirst: InstallReferrerRecord? = null

        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID")), route = { _, _ -> savedFirst = store.record })
            .handOff()

        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = ID), savedFirst)
    }

    @Test
    fun `an install already set up, an updater's, marks it read and opens nothing`() = runTest(dispatcher) {
        setUp = true
        val client = FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))

        handoff(client).handOff()

        assertEquals(0, client.reads)
        assertTrue(routed.isEmpty())
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = null), store.record)
    }

    @Test
    fun `an organic install opens nothing`() = runTest(dispatcher) {
        handoff(FakeReferrerClient(InstallReferrerRead.Answered("utm_source=google-play&utm_medium=organic"))).handOff()

        assertTrue(routed.isEmpty())
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = null), store.record)
    }

    @Test
    fun `an install the Play Store has no referrer for opens nothing`() = runTest(dispatcher) {
        handoff(FakeReferrerClient(InstallReferrerRead.Answered(referrer = null))).handOff()

        assertTrue(routed.isEmpty())
        assertTrue(store.record.consumed)
    }

    @Test
    fun `a malformed referrer opens nothing`() = runTest(dispatcher) {
        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID%0A"))).handOff()

        assertTrue(routed.isEmpty())
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = null), store.record)
    }

    @Test
    fun `the referrer is decoded once, then its honor value must be a whole share id`() {
        assertEquals(ID, shareIdFromReferrer("honor=$ID"))
        assertEquals("as the worker encodes it", ID, shareIdFromReferrer("honor%3D$ID"))
        assertEquals(ID, shareIdFromReferrer("utm_source=google-play&honor=$ID"))
        listOf(
            "",
            "honor=",
            "honor=short",
            "honor=${ID}1",
            "honor%253D$ID",
            "honor=$ID%0A",
            "honor=%20$ID",
            "honor=$ID%",
            "honor=$ID&honor=Other12345",
            "honor=https://honor.pilgrimapp.org/$ID",
            "Honor=$ID",
            ID,
        ).forEach { assertNull(it, shareIdFromReferrer(it)) }
    }

    @Test
    fun `an unavailable service is retried with a fresh client and a bounded backoff`() = runTest(dispatcher) {
        val client = FakeReferrerClient(
            InstallReferrerRead.Retry,
            InstallReferrerRead.Retry,
            InstallReferrerRead.Answered("honor=$ID"),
        )
        val started = currentTime

        handoff(client).handOff()

        assertEquals(listOf(ID), routed)
        assertEquals("one client per attempt", 3, client.reads)
        assertEquals(1_000L + 2_000L, currentTime - started)
    }

    @Test
    fun `a service that never answers is left unread, for the next launch still in setup`() = runTest(dispatcher) {
        val client = FakeReferrerClient(*Array(4) { InstallReferrerRead.Retry })
        val started = currentTime

        assertFalse("still to read", handoff(client).handOff())
        assertEquals(4, client.reads)
        assertEquals(1_000L + 2_000L + 4_000L, currentTime - started)
        assertTrue(routed.isEmpty())
        assertFalse(store.record.consumed)

        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))).handOff()
        assertEquals(listOf(ID), routed)
    }

    // AE5: a first read begun in setup is no updater's, however late its answer comes.
    @Test
    fun `a Play Store silent until setup is done still hands off once, on a later launch`() = runTest(dispatcher) {
        handoff(FakeReferrerClient(*Array(4) { InstallReferrerRead.Retry })).handOff()
        assertTrue(store.record.firstReadInSetup)
        setUp = true

        val later = FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))
        handoff(later).handOff()

        assertEquals(1, later.reads)
        assertEquals(listOf(ID), routed)
        assertEquals(InstallReferrerRecord(consumed = true, pendingShareId = ID), store.record)
        releases.last()()
        handoff(FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))).handOff()
        assertEquals("only once", listOf(ID), routed)
    }

    @Test
    fun `a later Activity in the same process reads again when the first start got no answer`() = runTest(dispatcher) {
        val client = FakeReferrerClient(*Array(4) { InstallReferrerRead.Retry }, InstallReferrerRead.Answered("honor=$ID"))
        val handoff = handoff(client)

        handoff.start()
        advanceTimeBy(1_000L + 2_000L + 4_000L + 1)
        assertTrue(routed.isEmpty())
        handoff.start()

        assertEquals(5, client.reads)
        assertEquals(listOf(ID), routed)
    }

    @Test
    fun `a Play Store that binds and never answers counts as unavailable`() = runTest(dispatcher) {
        val client = FakeReferrerClient(HANGS, InstallReferrerRead.Answered("honor=$ID"))
        val started = currentTime

        handoff(client).handOff()

        assertEquals(listOf(ID), routed)
        assertEquals(2, client.reads)
        assertEquals(InstallReferrerHandoff.ATTEMPT_TIMEOUT_MILLIS + 1_000L, currentTime - started)
    }

    @Test
    fun `a referrer already read is never read again`() = runTest(dispatcher) {
        store.record = InstallReferrerRecord(consumed = true, pendingShareId = null)
        val client = FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))

        handoff(client).handOff()

        assertEquals(0, client.reads)
        assertTrue(routed.isEmpty())
    }

    @Test
    fun `with the release flag off it is never read`() = runTest(dispatcher) {
        val client = FakeReferrerClient(InstallReferrerRead.Answered("honor=$ID"))

        handoff(client, honorEnabled = false).start()

        assertEquals(0, client.reads)
        assertEquals(InstallReferrerRecord(consumed = false, pendingShareId = null), store.record)
    }

    @Test
    fun `it is read once per process, however many Activities start it`() = runTest(dispatcher) {
        val client = FakeReferrerClient(InstallReferrerRead.Answered("utm_source=google-play"))
        val handoff = handoff(client)

        handoff.start()
        handoff.start()

        assertEquals(1, client.reads)
    }

    /**
     * Each [read] is a fresh client's one attempt, answered by the next of
     * [replies] in turn: [HANGS] never answers, and past the last reply an
     * attempt blocks, as a Play Store that binds and goes quiet does.
     */
    private class FakeReferrerClient(vararg replies: InstallReferrerRead?) : InstallReferrerClientAdapter {
        private val replies = ArrayDeque(replies.toList())
        var reads = 0
            private set

        override suspend fun read(): InstallReferrerRead {
            reads++
            return replies.removeFirstOrNull() ?: awaitCancellation()
        }
    }

    private class FakeReferrerStore : InstallReferrerStore {
        var record = InstallReferrerRecord(consumed = false, pendingShareId = null)

        override suspend fun read() = record

        override suspend fun markFirstReadInSetup() {
            record = record.copy(firstReadInSetup = true)
        }

        override suspend fun consume(pendingShareId: String?) {
            record = InstallReferrerRecord(consumed = true, pendingShareId = pendingShareId)
        }

        override suspend fun clearPending() {
            record = record.copy(pendingShareId = null)
        }
    }

    private companion object {
        const val ID = "Qoi4YmPHLN"
        const val TAPPED = "Tapped1234"
        val HANGS: InstallReferrerRead? = null
    }
}
