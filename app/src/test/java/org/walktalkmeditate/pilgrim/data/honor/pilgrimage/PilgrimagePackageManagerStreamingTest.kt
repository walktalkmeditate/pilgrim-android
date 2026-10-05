// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.RELEASE
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.assertRefused
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.chunked
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.declared
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.fixture
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager.Phase

/**
 * Port of iOS `PilgrimagePackageManagerTests+Streaming.swift@7c200bf` (2,
 * names kept): a response that never says how long it is, and one that
 * isn't a 200. Then each file's cap at its edge on both paths, the
 * declared length and the count while streaming (`<=` passes, P1 §3.4),
 * the other statuses, and a redirect off the CDN (owner decision 6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimagePackageManagerStreamingTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var h: PilgrimagePackageHarness

    @Before
    fun setUp() {
        h = PilgrimagePackageHarness(folder.root)
    }

    @After
    fun tearDown() {
        h.close()
    }

    // ---- iOS's tests ------------------------------------------------------------

    /** No `Content-Length` passes the check before the drain, so only the count while streaming can refuse it. */
    @Test
    fun `a file that never declares its length is refused by the cap counted while streaming`() {
        h.stub("route.json") { chunked(spaces(PilgrimageWayImporter.MAX_ROUTE_BYTES + 1)) }
        val manager = h.makeManager()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(manager.installedBlocking())
        assertEquals("nothing of a refused package is kept", emptyList<String>(), h.wayStore.stageWayIds())
    }

    /** A tag the index named that the CDN doesn't carry; the body is valid, so only the status refuses it. */
    @Test
    fun `a stage served as not found is an unfinished download`() {
        h.stub("stage-01.json") { declared(fixture("stage-01.json")).setResponseCode(404) }
        val manager = h.makeManager()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(manager.installedBlocking())
        assertEquals("nothing reaches the store until every file has landed", emptyList<String>(), h.wayStore.stageWayIds())
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
    }

    // ---- Each file's cap at its edge -------------------------------------------------

    /** JSON allows trailing whitespace, so a padded file still parses: exactly the cap passes the fetch and the importer. */
    @Test
    fun `a route file of exactly 512 KiB passes on both paths, and one byte more is refused on both`() {
        val atTheCap = padded(fixture("route.json"), PilgrimageWayImporter.MAX_ROUTE_BYTES)
        val overTheCap = padded(fixture("route.json"), PilgrimageWayImporter.MAX_ROUTE_BYTES + 1)

        h.stub("route.json") { declared(atTheCap) }
        installsThenRemoves("declared, at the cap")
        h.stub("route.json") { chunked(atTheCap) }
        installsThenRemoves("streamed, at the cap")
        h.stub("route.json") { declared(overTheCap) }
        refusedAsIncomplete("declared, one over")
        h.stub("route.json") { chunked(overTheCap) }
        refusedAsIncomplete("streamed, one over")
    }

    @Test
    fun `a stage file of exactly 2 MiB passes, and one byte more is refused on both paths`() {
        val atTheCap = padded(fixture("stage-00.json"), PilgrimageWayImporter.MAX_STAGE_BYTES)
        val overTheCap = padded(fixture("stage-00.json"), PilgrimageWayImporter.MAX_STAGE_BYTES + 1)

        h.stub("stage-00.json") { chunked(atTheCap) }
        installsThenRemoves("streamed, at the cap")
        h.stub("stage-00.json") { declared(overTheCap) }
        refusedAsIncomplete("declared, one over")
        h.stub("stage-00.json") { chunked(overTheCap) }
        refusedAsIncomplete("streamed, one over")
    }

    // ---- Status and redirects ------------------------------------------------------------

    @Test
    fun `any status but 200 is an unfinished download`() {
        listOf(204, 206, 302, 500, 503).forEach { status ->
            h.stub("route.json") { declared(fixture("route.json")).setResponseCode(status) }
            refusedAsIncomplete("status $status")
        }
    }

    @Test
    fun `a redirect off the CDN host is refused, and nothing reaches the store`() {
        h.stub("route.json") { MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.invalid/route.json") }

        refusedAsIncomplete("off the host")

        assertEquals(1, h.server.requestCount)
    }

    private fun installsThenRemoves(message: String) {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        assertEquals(message, ROUTE_ID, manager.installedBlocking()?.routeId)
        manager.remove(ROUTE_ID).awaitBlocking()
    }

    private fun refusedAsIncomplete(message: String) {
        val manager = h.makeManager()
        assertRefused(PilgrimageError.INCOMPLETE, message) { manager.download(h.entry, RELEASE).awaitBlocking() }
        assertNull(message, manager.installedBlocking())
        assertEquals(message, emptyList<String>(), h.wayStore.stageWayIds())
    }

    private fun spaces(size: Int) = ByteArray(size) { ' '.code.toByte() }

    private fun padded(bytes: ByteArray, size: Int) = bytes + spaces(size - bytes.size)
}
