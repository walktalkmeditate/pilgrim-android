// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.RELEASE
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.WALKED_AT
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.assertRefused
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.awaitBlocking
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.bytes
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.fixture
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.fixtureJson
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.fixtureText
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.with
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager.Phase
import org.walktalkmeditate.pilgrim.di.NetworkModule
import org.walktalkmeditate.pilgrim.domain.honor.WayJson

/**
 * Port of iOS `PilgrimagePackageManagerTests.swift@7c200bf`, names kept
 * (pilgrimage-stage spec P2, Test inventory): 21 of its 22, since
 * `testRemoveReplaceAndUpdateReachTheTilesManager` waits for Stage 21-3's
 * tiles manager. Every proof that no stage is left reads the store's
 * stage-id listing, never `list()`, which steps over stages (P2 C-9). Its
 * `+Lifecycle` and `+Streaming` cases, and the guard's, are their own files.
 *
 * Then the spec's additions: the file names at their widening, the
 * committed bytes, the temp set, the 50 MiB total at its edge (P1 C15,
 * P2 C-3), single flight (C-16), the owned download (P4 correction 2),
 * what a failed commit keeps and what a failed stream leaves (C-2), the
 * NFC name match (P1 A10), and the production client (the builder-test rule).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimagePackageManagerTest {

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

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `stage files are zero padded from zero`() {
        assertEquals("stage-00.json", PilgrimagePackageManager.stageFileName(0))
        assertEquals("stage-09.json", PilgrimagePackageManager.stageFileName(9))
        assertEquals("stage-32.json", PilgrimagePackageManager.stageFileName(32))
        assertEquals("stage-120.json", PilgrimagePackageManager.stageFileName(120))
    }

    @Test
    fun `download installs every stage and records the release`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()

        assertEquals("Initiation", h.wayStore.load("pilgrimage:camino-frances:0")?.stage?.theme)
        assertEquals("Descent", h.wayStore.load("pilgrimage:camino-frances:1")?.stage?.theme)
        val installed = requireNotNull(manager.installedBlocking()) { "nothing installed" }
        assertEquals("camino-frances", installed.routeId)
        assertEquals("v1.7.0", installed.release)
        assertEquals(2, installed.route.stages.size)
        assertEquals(Phase.Idle, manager.phase.value)
    }

    /** A StateFlow conflates, so each phase is read while a held file keeps it on screen, the last at the commit's guard. */
    @Test
    fun `progress counts the route file and every stage`() {
        val manager = h.makeManager()
        val routeFile = h.hold("route.json")
        val firstStage = h.hold("stage-00.json")
        val checks = AtomicInteger()
        val atCommit = CopyOnWriteArrayList<Phase>()
        h.signals.onCheck = { if (checks.incrementAndGet() == 2) atCommit += manager.phase.value }

        val download = manager.download(h.entry, RELEASE)
        routeFile.awaitArrival()
        assertEquals("busy before route.json's round trip", Phase.Downloading(done = 0, total = 3), manager.phase.value)
        routeFile.release()
        firstStage.awaitArrival()
        assertEquals("then again once it lands", Phase.Downloading(done = 1, total = 3), manager.phase.value)
        firstStage.release()
        download.awaitBlocking()

        assertEquals("both stages landed", listOf(Phase.Downloading(done = 3, total = 3)), atCommit.toList())
        assertEquals(Phase.Idle, manager.phase.value)
    }

    @Test
    fun `a stage that fails validation leaves nothing behind`() {
        val broken = fixtureText("stage-01.json").replace("\"frac\": 1.0", "\"frac\": 9.0")
        assertNotEquals(fixtureText("stage-01.json"), broken)
        h.stub("stage-01.json", broken.toByteArray())
        val manager = h.makeManager()

        assertRefused(PilgrimageError.NOT_WALKABLE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull("the first stage is rolled back too", h.wayStore.load("pilgrimage:camino-frances:0"))
        assertNull(manager.installedBlocking())
        assertEquals(Phase.Failed(PilgrimageError.NOT_WALKABLE), manager.phase.value)
    }

    @Test
    fun `a network failure midway reports an unfinished download`() {
        // stage-01 is not stubbed: the server drops the connection.
        h.stubs.unstub(h.path("stage-01.json"))
        val manager = h.makeManager()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(h.wayStore.load("pilgrimage:camino-frances:0"))
        assertEquals(Phase.Failed(PilgrimageError.INCOMPLETE), manager.phase.value)
    }

    /** The declared-length path: the stub declares a truthful length. The running cap is the streaming file's. */
    @Test
    fun `a stage file that declares more than the cap is refused before it is buffered`() {
        h.stub("stage-00.json", ByteArray(PilgrimageWayImporter.MAX_STAGE_BYTES + 1) { ' '.code.toByte() })
        val manager = h.makeManager()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }
    }

    @Test
    fun `a route file that does not match the catalog entry is refused`() {
        val mismatched = fixtureText("route.json").replace("\"stageCount\": 2", "\"stageCount\": 5")
        assertNotEquals(fixtureText("route.json"), mismatched)
        h.stub("route.json", mismatched.toByteArray())
        val manager = h.makeManager()

        assertRefused(PilgrimageError.NOT_WALKABLE) { manager.download(h.entry, RELEASE).awaitBlocking() }
    }

    @Test
    fun `download is refused while a walk is on`() {
        val manager = h.makeManager()
        h.signals.screenUp = true

        assertRefused(PilgrimageError.WALK_IN_PROGRESS) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(h.wayStore.load("pilgrimage:camino-frances:0"))
    }

    /**
     * The commit loop is the one place a half route could survive: a stage
     * saved before the disk filled would have no `route.json` to name it and
     * no `installed()` able to reach it.
     */
    @Test
    fun `a failed save mid commit rolls back every stage already written`() {
        val manager = h.makeManager()
        manager.saveStage = failingOnTheSecondSave()

        assertRefused(PilgrimageError.DISK_FULL) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull("the stage that did save is taken back up", h.wayStore.load("pilgrimage:camino-frances:0"))
        assertNull(h.wayStore.load("pilgrimage:camino-frances:1"))
        assertEquals("no orphan stage left in the store", emptyList<String>(), h.wayStore.stageWayIds())
        assertNull(manager.installedBlocking())
        assertFalse(routeFile().exists())
        assertFalse(releaseFile().exists())
        assertEquals(Phase.Failed(PilgrimageError.DISK_FULL), manager.phase.value)
    }

    /**
     * The rollback reads the live sessions (an Android addition) before it
     * retires anything; a failed read must still leave no install behind,
     * and the walker still hears the commit's own error.
     */
    @Test
    fun `a rollback whose live-session read fails still takes the route off the phone`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        assertNotNull(manager.installedBlocking())
        val saves = AtomicInteger()
        manager.saveStage = { way ->
            if (saves.incrementAndGet() == 2) {
                h.signals.liveIdsFailure = IOException("database closed")
                throw IOException("write failed: ENOSPC (No space left on device)")
            }
            h.wayStore.save(way)
        }

        assertRefused(PilgrimageError.DISK_FULL) { manager.download(h.entry, RELEASE).awaitBlocking() }

        h.signals.liveIdsFailure = null
        assertNull("a failed update never reads as installed", manager.installedBlocking())
        assertFalse(routeFile().exists())
        assertFalse(releaseFile().exists())
    }

    /** Only the declared length can refuse this: reading the body at all fails the test. */
    @Test
    fun `a declared length over the cap is refused before a byte of the body is read`() {
        val cap = 1_000L
        val body = object : okhttp3.ResponseBody() {
            override fun contentType(): okhttp3.MediaType? = null
            override fun contentLength(): Long = cap + 1
            override fun source(): okio.BufferedSource = throw AssertionError("the body was read")
        }
        val response = okhttp3.Response.Builder()
            .request(PilgrimagePackageManager.request("https://cdn.jsdelivr.net/x".toHttpUrl()))
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()

        assertRefused(PilgrimageError.INCOMPLETE) { PilgrimagePackageManager.readCapped(response, cap) }
    }

    /** The index's `bytes` is a hint the dataset wrote, not a promise the CDN keeps. */
    @Test
    fun `the whole package is bounded by real bytes, not the index's claim`() {
        val manager = h.makeManager()
        manager.maxPackageBytes = 1_000

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(manager.installedBlocking())
        assertEquals(emptyList<String>(), h.wayStore.stageWayIds())
    }

    /** A second download of the installed route overwrites its stages in place, so a rollback must reach every one. */
    @Test
    fun `a failed update rolls back the whole previous install, even at the same stage count`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        assertNotNull(manager.installedBlocking())
        manager.saveStage = failingOnTheSecondSave()

        assertRefused(PilgrimageError.DISK_FULL) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertEquals("no stage from the failed update or the prior install survives", emptyList<String>(), h.wayStore.stageWayIds())
        assertNull(manager.installedBlocking())
        assertFalse(routeFile().exists())
        assertFalse(releaseFile().exists())
    }

    /** The failed commit writes only indices 0 and 1; index 2 is past what it touched. */
    @Test
    fun `a failed update rolls back stages leftover from a larger previous install`() {
        h.stubThreeStagePackage()
        val manager = h.makeManager()
        manager.download(h.entryWithThreeStages, RELEASE).awaitBlocking()
        assertEquals(3, manager.installedBlocking()?.route?.stages?.size)
        h.stubWholePackage()
        manager.saveStage = failingOnTheSecondSave()

        assertRefused(PilgrimageError.DISK_FULL) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertEquals("the third stage from the larger install is gone too", emptyList<String>(), h.wayStore.stageWayIds())
        assertNull(manager.installedBlocking())
        assertFalse(routeFile().exists())
        assertFalse(releaseFile().exists())
    }

    /** route.json is held, so the first call is still in flight when the second is made. */
    @Test
    fun `a second download is refused while one is in flight`() {
        val routeFile = h.hold("route.json")
        val manager = h.makeManager()
        val first = manager.download(h.entry, RELEASE)
        routeFile.awaitArrival()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        routeFile.release()
        first.awaitBlocking()
        assertEquals("Initiation", h.wayStore.load("pilgrimage:camino-frances:0")?.stage?.theme)
        assertEquals("Descent", h.wayStore.load("pilgrimage:camino-frances:1")?.stage?.theme)
        assertNotNull(manager.installedBlocking())
    }

    @Test
    fun `replace only removes the first route once the second is complete`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.ledgers.save(
            PilgrimageLedger(ROUTE_ID).recorded(
                0, "Saint-Jean-Pied-de-Port to Roncesvalles", 24.2, HonorStageOutcome(1.0, arrived = true), WALKED_AT,
            ),
        )
        h.stubNorte()

        manager.replace(h.norte, RELEASE).awaitBlocking()

        assertNull("the first route's stages left", h.wayStore.load("pilgrimage:camino-frances:0"))
        assertNull(h.wayStore.load("pilgrimage:camino-frances:1"))
        assertNotNull(h.wayStore.load("pilgrimage:camino-norte:0"))
        assertEquals("camino-norte", manager.installedBlocking()?.routeId)
        assertEquals("what you walked of it is remembered if it comes back", 1, h.ledgers.load(ROUTE_ID)?.completedCount)
        assertFalse("the old route's package is gone, not just unreachable through installed()", routeFile().exists())
    }

    /** Replacing the route already held needs Update's shrink sweep and reconcile, not a bare download. */
    @Test
    fun `replace with the route already installed behaves like an update`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.seedTwoStageLedger()
        h.stubOneStagePackage("v1.8.0")

        manager.replace(h.entryWithOneStage, "v1.8.0").awaitBlocking()

        assertNull("stage 1's Way is orphaned by the shrink", h.wayStore.load("pilgrimage:camino-frances:1"))
        assertNotNull(h.wayStore.load("pilgrimage:camino-frances:0"))
        assertEquals("v1.8.0", manager.installedBlocking()?.release)
        val after = requireNotNull(h.ledgers.load(ROUTE_ID)) { "no ledger" }
        assertEquals("stage 1's entry was dropped, not left stale", setOf("0"), after.stages.keys)
        assertEquals(true, after.redrawNoticePending)
    }

    @Test
    fun `a failed replace leaves the first route untouched`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()

        // camino-norte is never stubbed, so every fetch fails.
        assertRefused(PilgrimageError.INCOMPLETE) { manager.replace(h.norte, RELEASE).awaitBlocking() }

        assertEquals("camino-frances", manager.installedBlocking()?.routeId)
        assertNotNull(h.wayStore.load("pilgrimage:camino-frances:0"))
        assertNull(h.wayStore.load("pilgrimage:camino-norte:0"))
    }

    @Test
    fun `update reconciles the ledger by stage identity`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.seedTwoStageLedger()
        // v1.8.0 redraws stage 1 under a new name.
        val redrawnRoute = fixtureText("route.json")
            .replace("\"name\": \"Roncesvalles to Zubiri\"", "\"name\": \"Roncesvalles to Larrasoaña\"")
        val redrawnStage = fixtureText("stage-01.json").replace("Roncesvalles to Zubiri", "Roncesvalles to Larrasoaña")
        assertNotEquals(fixtureText("route.json"), redrawnRoute)
        h.stub("route.json", redrawnRoute.toByteArray(), "v1.8.0")
        h.stub("stage-00.json", fixture("stage-00.json"), "v1.8.0")
        h.stub("stage-01.json", redrawnStage.toByteArray(), "v1.8.0")

        manager.update(h.entry, "v1.8.0").awaitBlocking()

        assertEquals("v1.8.0", manager.installedBlocking()?.release)
        val after = requireNotNull(h.ledgers.load(ROUTE_ID)) { "no ledger" }
        assertEquals(setOf("0"), after.stages.keys)
        assertEquals(21.9, after.carriedKm ?: 0.0, 0.01)
        assertEquals(true, after.redrawNoticePending)
    }

    /** A route that shrank leaves stage Ways above the new count, with nothing to reach them. */
    @Test
    fun `update sweeps stage ways the new package no longer covers`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.stubOneStagePackage("v1.8.0")

        manager.update(h.entryWithOneStage, "v1.8.0").awaitBlocking()

        assertNull("the tail stage above the new count is swept", h.wayStore.load(WayStore.stageWayId(ROUTE_ID, 1)))
        assertNotNull(h.wayStore.load(WayStore.stageWayId(ROUTE_ID, 0)))
        assertEquals(1, manager.installedBlocking()?.route?.stageCount)
    }

    @Test
    fun `remove takes the stages and keeps the ledger`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.ledgers.save(PilgrimageLedger(ROUTE_ID).recorded(0, "a", 24.2, HonorStageOutcome(1.0, arrived = true), WALKED_AT))

        manager.remove(ROUTE_ID).awaitBlocking()

        assertNull(h.wayStore.load("pilgrimage:camino-frances:0"))
        assertNull(h.wayStore.load("pilgrimage:camino-frances:1"))
        assertNull(manager.installedBlocking())
        assertEquals(1, h.ledgers.load(ROUTE_ID)?.completedCount)
    }

    @Test
    fun `replace, update and remove are all refused mid walk`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        h.signals.screenUp = true
        h.stubNorte()

        assertRefused(PilgrimageError.WALK_IN_PROGRESS, "replace") { manager.replace(h.norte, RELEASE).awaitBlocking() }
        assertRefused(PilgrimageError.WALK_IN_PROGRESS, "update") { manager.update(h.entry, "v1.8.0").awaitBlocking() }
        assertRefused(PilgrimageError.WALK_IN_PROGRESS, "remove") { manager.remove(ROUTE_ID).awaitBlocking() }
        assertEquals("nothing moved", "camino-frances", manager.installedBlocking()?.routeId)
    }

    @Test
    fun `the confirmations name the route and their own verb`() {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        assertEquals(
            "Replace the Camino Francés? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.",
            PilgrimagePackageManager.replaceConfirmation(resources, "Camino Francés"),
        )
        assertEquals(
            "Remove the Camino Francés? Its stages leave your phone; what you've walked of it is remembered if it comes back. Walks in your journal stay.",
            PilgrimagePackageManager.removeConfirmation(resources, "Camino Francés"),
        )
        assertFalse(
            "the Remove alert must not ask about replacing",
            PilgrimagePackageManager.removeConfirmation(resources, "x").startsWith("Replace"),
        )
    }

    // ---- The files a download writes ---------------------------------------------

    @Test
    fun `stage file names widen to three digits at 100, through the last index a route can have`() {
        assertEquals("stage-99.json", PilgrimagePackageManager.stageFileName(99))
        assertEquals("stage-100.json", PilgrimagePackageManager.stageFileName(100))
        assertEquals("stage-199.json", PilgrimagePackageManager.stageFileName(PilgrimageWayImporter.MAX_STAGE_COUNT - 1))
    }

    /** The anchored tag rule would read a trailing newline as nothing installed (P1 §2). */
    @Test
    fun `route json is committed as the bytes that arrived, and release txt is the bare tag`() {
        h.makeManager().download(h.entry, RELEASE).awaitBlocking()

        assertTrue(fixture("route.json").contentEquals(routeFile().readBytes()))
        assertTrue("v1.7.0".toByteArray(Charsets.UTF_8).contentEquals(releaseFile().readBytes()))
    }

    @Test
    fun `a release outside the tag rule is not walkable, and nothing is asked for`() {
        val manager = h.makeManager()

        assertRefused(PilgrimageError.NOT_WALKABLE) { manager.download(h.entry, "main").awaitBlocking() }

        assertEquals(0, h.server.requestCount)
        assertEquals(Phase.Failed(PilgrimageError.NOT_WALKABLE), manager.phase.value)
    }

    /** P2 A-6: `noBackupFilesDir`, never `cacheDir`, and outside the Ways tree, where no reader or sweep looks. */
    @Test
    fun `the temp set holds the wire route json and each stage in way json's encoding, outside the Ways tree`() {
        val secondStage = h.hold("stage-01.json")
        val manager = h.makeManager()
        val download = manager.download(h.entry, RELEASE)
        secondStage.awaitArrival()

        val sets = h.tempRoot.listFiles().orEmpty().toList()
        assertEquals(1, sets.size)
        val set = sets.single()
        assertTrue(set.name.startsWith(PilgrimagePackageManager.TEMP_SET_PREFIX))
        assertFalse(set.canonicalPath.startsWith(h.waysDir.canonicalPath))
        assertTrue(fixture("route.json").contentEquals(File(set, "route.json").readBytes()))
        val firstWay = PilgrimageWayImporter.way(from = fixture("stage-00.json"), routeId = ROUTE_ID, stageIndex = 0)
        assertEquals(WayJson.encode(firstWay), File(set, "0.way.json").readText())
        secondStage.release()
        download.awaitBlocking()

        assertEquals("swept once the download ends", emptyList<File>(), h.tempRoot.listFiles().orEmpty().toList())
    }

    @Test
    fun `the temp set is swept after a failure too`() {
        h.stub("stage-01.json") { MockResponse().setResponseCode(404) }
        val manager = h.makeManager()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertEquals(emptyList<File>(), h.tempRoot.listFiles().orEmpty().toList())
    }

    // ---- The 50 MiB total (P1 C15, P2 C-3) ---------------------------------------

    @Test
    fun `the package cap is 50 MiB, counted in bytes`() {
        assertEquals(52_428_800L, h.makeManager().maxPackageBytes)
    }

    /** Summed over route.json and both stages, checked after each whole file, `<=` passing. */
    @Test
    fun `a package of exactly its cap passes, and one byte over is refused`() {
        val total = listOf("route.json", "stage-00.json", "stage-01.json").sumOf { fixture(it).size.toLong() }
        val atTheCap = h.makeManager()
        atTheCap.maxPackageBytes = total
        atTheCap.download(h.entry, RELEASE).awaitBlocking()
        assertEquals("camino-frances", atTheCap.installedBlocking()?.routeId)
        atTheCap.remove(ROUTE_ID).awaitBlocking()

        val overTheCap = h.makeManager()
        overTheCap.maxPackageBytes = total - 1
        assertRefused(PilgrimageError.INCOMPLETE) { overTheCap.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(overTheCap.installedBlocking())
        assertEquals(emptyList<String>(), h.wayStore.stageWayIds())
    }

    // ---- Single flight (C-16) ---------------------------------------------------------

    @Test
    fun `a refused second download leaves the first one's phase alone, and nothing queues`() {
        val routeFile = h.hold("route.json")
        val manager = h.makeManager()
        val first = manager.download(h.entry, RELEASE)
        routeFile.awaitArrival()

        assertRefused(PilgrimageError.INCOMPLETE) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertEquals("the first download's progress stays on screen", Phase.Downloading(0, 3), manager.phase.value)
        routeFile.release()
        first.awaitBlocking()
        assertEquals("route.json and two stages, once: the refused call never ran later", 3, h.server.requestCount)
    }

    @Test
    fun `an update and a replace asked mid download meet the same refusal`() {
        val routeFile = h.hold("route.json")
        val manager = h.makeManager()
        val first = manager.download(h.entry, RELEASE)
        routeFile.awaitArrival()
        h.stubNorte()

        assertRefused(PilgrimageError.INCOMPLETE, "update") { manager.update(h.entry, "v1.8.0").awaitBlocking() }
        assertRefused(PilgrimageError.INCOMPLETE, "replace") { manager.replace(h.norte, RELEASE).awaitBlocking() }

        assertEquals(Phase.Downloading(0, 3), manager.phase.value)
        routeFile.release()
        first.awaitBlocking()
        assertEquals("camino-frances", manager.installedBlocking()?.routeId)
    }

    @Test
    fun `the route page is busy while a download runs, or a map save does`() {
        val routeFile = h.hold("route.json")
        val tiles = FakeTiles()
        val manager = h.makeManager(tiles)
        assertFalse(manager.isBusy)
        val download = manager.download(h.entry, RELEASE)
        routeFile.awaitArrival()
        assertTrue(manager.isBusy)
        routeFile.release()
        download.awaitBlocking()
        assertFalse(manager.isBusy)

        tiles.saving = true

        assertTrue(manager.isBusy)
    }

    // ---- The owned download (P4 correction 2) ----------------------------------------

    @Test
    fun `the route page's ViewModel going doesn't cancel the download it started`() {
        val routeFile = h.hold("route.json")
        val manager = h.makeManager()
        val viewModelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val handed = CompletableDeferred<Deferred<Unit>>()
        val page = viewModelScope.launch {
            val download = manager.download(h.entry, RELEASE)
            handed.complete(download)
            download.await()
        }
        routeFile.awaitArrival()

        runBlocking { page.cancelAndJoin() }
        routeFile.release()

        runBlocking { handed.await() }.awaitBlocking()
        assertTrue(page.isCancelled)
        assertEquals("camino-frances", manager.installedBlocking()?.routeId)
        viewModelScope.cancel()
    }

    // ---- What a failure keeps (C-2) -----------------------------------------------------

    /** The rollback retires `0 until max(previous, new)`: a walked stage keeps its `way.json`, and the ledger stays. */
    @Test
    fun `a failed commit keeps a walked stage's way json and leaves the ledger alone`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        val walk = UUID.randomUUID().toString()
        h.wayStore.link(walk, WayStore.stageWayId(ROUTE_ID, 0), arrival = null)
        h.seedTwoStageLedger()
        val ledgerBefore = h.wayStore.ledgerFile(ROUTE_ID)!!.readBytes()
        manager.saveStage = failingOnTheSecondSave()

        assertRefused(PilgrimageError.DISK_FULL) { manager.download(h.entry, RELEASE).awaitBlocking() }

        assertNull(manager.installedBlocking())
        assertEquals("the walked stage stays", listOf("pilgrimage:camino-frances:0"), h.wayStore.stageWayIds())
        assertEquals(WayStore.stageWayId(ROUTE_ID, 0), h.wayStore.wayLink(walk)?.wayId)
        assertTrue(ledgerBefore.contentEquals(h.wayStore.ledgerFile(ROUTE_ID)!!.readBytes()))
    }

    @Test
    fun `an update that fails while it streams leaves the old install exactly as it was`() {
        val manager = h.makeManager()
        manager.download(h.entry, RELEASE).awaitBlocking()
        val routeBefore = routeFile().readBytes()
        h.stubWholePackage("v1.8.0")
        h.stub("stage-01.json", fixtureText("stage-01.json").replace("\"frac\": 1.0", "\"frac\": 9.0").toByteArray(), "v1.8.0")

        assertRefused(PilgrimageError.NOT_WALKABLE) { manager.update(h.entry, "v1.8.0").awaitBlocking() }

        assertEquals("v1.7.0", manager.installedBlocking()?.release)
        assertTrue(routeBefore.contentEquals(routeFile().readBytes()))
        assertEquals(setOf("pilgrimage:camino-frances:0", "pilgrimage:camino-frances:1"), h.wayStore.stageWayIds().toSet())
    }

    // ---- The identity check (P1 §11, A10) -------------------------------------------------

    /** Swift's `==` is canonical equivalence; a plain Kotlin `==` would refuse this stage. */
    @Test
    fun `a stage named as its row is, but decomposed, passes as Swift's equality reads it`() {
        val composed = Normalizer.normalize("Saint-Jean to Roncesvallés", Normalizer.Form.NFC)
        val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
        assertNotEquals(composed, decomposed)
        val route = fixtureJson("route.json")
        val rows = route.getValue("stages").jsonArray
        val renamedRow = rows[0].jsonObject.with("name", JsonPrimitive(composed))
        h.stub("route.json", route.with("stages", JsonArray(listOf(renamedRow, rows[1]))).bytes())
        h.stub("stage-00.json", h.patchedStageZero(name = decomposed))
        val manager = h.makeManager()

        manager.download(h.entry, RELEASE).awaitBlocking()

        assertEquals("camino-frances", manager.installedBlocking()?.routeId)
    }

    // ---- The production client (the builder-test rule) --------------------------------------

    @Test
    fun `the production package client is its own - 30 s idle, 300 s a file, no connection retry, no cache`() {
        val client = NetworkModule.providePilgrimagePackageHttpClient()

        assertEquals(30_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(30_000, client.writeTimeoutMillis)
        assertEquals(300_000, client.callTimeoutMillis)
        assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
        assertFalse("redirects are the CDN host rule's", client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNotEquals("not the catalog's client", NetworkModule.providePilgrimageCatalogHttpClient().callTimeoutMillis, client.callTimeoutMillis)
    }

    @Test
    fun `the production client sends the request the manager builds`() {
        val url = requireNotNull(PilgrimageCatalogService.packageUrl(RELEASE, ROUTE_ID, "stage-00.json"))
        val request = PilgrimagePackageManager.request(url)
        assertEquals("GET", request.method)
        assertEquals(url, request.url)

        NetworkModule.providePilgrimagePackageHttpClient()
            .newCall(PilgrimagePackageManager.request(h.server.url(h.path("stage-00.json"))))
            .execute()
            .use { response -> assertTrue(fixture("stage-00.json").contentEquals(response.body.bytes())) }
    }

    /** Owner decision 6: its host rule is the CDN's, so even a hop back to the test server is off the host. */
    @Test
    fun `the production client keeps redirects on cdn jsdelivr net`() {
        h.stub("route.json") { MockResponse().setResponseCode(302).setHeader("Location", h.server.url("/moved/route.json")) }

        assertThrows(IOException::class.java) {
            NetworkModule.providePilgrimagePackageHttpClient()
                .newCall(PilgrimagePackageManager.request(h.server.url(h.path("route.json"))))
                .execute()
        }
        assertEquals(1, h.server.requestCount)
    }

    // ---- Helpers -------------------------------------------------------------------------------

    private fun routeFile(): File = requireNotNull(h.wayStore.routeFile(ROUTE_ID))

    private fun releaseFile(): File = requireNotNull(h.wayStore.releaseFile(ROUTE_ID))

    /** The second stage is where the disk runs out, as iOS's `CocoaError(.fileWriteOutOfSpace)`. */
    private fun failingOnTheSecondSave(): (org.walktalkmeditate.pilgrim.domain.honor.Way) -> Unit {
        val saves = AtomicInteger()
        return { way ->
            if (saves.incrementAndGet() == 2) throw IOException("write failed: ENOSPC (No space left on device)")
            h.wayStore.save(way)
        }
    }
}

internal fun PilgrimagePackageManager.installedBlocking(): PilgrimagePackageManager.Installed? = runBlocking { installed() }

/** Stage 21-3's tiles manager as far as the package manager reaches it. */
internal class FakeTiles : PilgrimageTiles {
    val removed = CopyOnWriteArrayList<String>()
    val regionsRemoved = CopyOnWriteArrayList<Pair<String, Int>>()
    @Volatile var saving = false
    @Volatile var onRemove: (String) -> Unit = {}
    @Volatile var onRemoveRegions: (String, Int) -> Unit = { _, _ -> }

    override fun remove(routeId: String) {
        removed += routeId
        onRemove(routeId)
    }

    override fun removeRegions(routeId: String, atOrAbove: Int) {
        regionsRemoved += routeId to atOrAbove
        onRemoveRegions(routeId, atOrAbove)
    }

    override val isSaving: Boolean get() = saving
}
