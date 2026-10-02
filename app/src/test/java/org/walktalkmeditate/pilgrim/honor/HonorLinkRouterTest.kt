// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.app.Application
import android.content.Intent
import android.net.Uri
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.ui.navigation.FetchedWayLanding
import org.walktalkmeditate.pilgrim.ui.navigation.Routes
import org.walktalkmeditate.pilgrim.ui.navigation.fetchedWayLanding
import org.walktalkmeditate.pilgrim.ui.navigation.honorLinkScreen
import org.walktalkmeditate.pilgrim.widget.DeepLinkTarget

/**
 * Honor links routed as iOS routes them (shared-walk spec S2 §3–§7, with
 * corrections 2–5): the hold through setup, the walk-screen refusal, the
 * toast-then-overview order, the parks, the races from
 * `MainCoordinatorHonorTests.swift@7c200bf`, and consume-once. The import
 * is the real coordinator's, each fetch held open until the test lands it,
 * as iOS's `heldImport` holds it. Screens are back stacks as the nav host
 * reports them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HonorLinkRouterTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val held = mutableMapOf<String, Continuation<Way>>()
    private val asked = mutableListOf<String>()
    private var trackerWalking = false
    private var probes = 0
    private val navHost = Any()
    private lateinit var imports: HonorImportCoordinator

    private fun TestScope.router(honorEnabled: Boolean = true): HonorLinkRouter {
        imports = HonorImportCoordinator(
            importShare = { id ->
                asked += id
                suspendCoroutine { held[id] = it }
            },
            honorEnabled = honorEnabled,
            scope = backgroundScope,
            media = { error("a link never gathers") },
        )
        return HonorLinkRouter(
            honorEnabled = honorEnabled,
            imports = imports,
            trackerWalking = {
                probes++
                trackerWalking
            },
            scope = backgroundScope,
        )
    }

    private fun HonorLinkRouter.at(vararg backStack: String) = screenChanged(honorLinkScreen(backStack.toList()), navHost)

    private fun land(id: String) = held.getValue(id).resume(way(id))

    private fun fail(id: String, error: Throwable) = held.getValue(id).resumeWithException(error)

    // S2 §3, correction 4: held in memory until the first arrival at Path; nothing shows meanwhile.

    @Test
    fun `a link during onboarding waits through welcome, permissions, and the breath, then opens at Path`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.WELCOME)

        links.open(link(FIRST), restored = false)
        links.at(Routes.PERMISSIONS)
        links.at(Routes.BREATH)
        assertTrue("nothing fetches before Path", asked.isEmpty())
        assertNull("nothing shows while it waits", links.toast.value)

        links.at(Routes.PATH)

        assertEquals(listOf(FIRST), asked)
        assertEquals(HonorLinkToast.Reaching, links.toast.value)
        assertFalse("already on Path", links.pathSwitch.value)
        assertEquals(HonorImportState.Fetching, imports.state.value)
    }

    @Test
    fun `the last link to arrive during onboarding is the one that opens`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.WELCOME)

        links.open(link(FIRST), restored = false)
        links.open(link(SECOND), restored = false)
        links.at(Routes.PATH)

        assertEquals(listOf(SECOND), asked)
    }

    @Test
    fun `a link before the nav host stands waits, as does a set-up launch stopped on Permissions`() = runTest(dispatcher) {
        val links = router()

        links.open(link(FIRST), restored = false)
        links.at(Routes.PERMISSIONS)
        assertTrue(asked.isEmpty())
        links.at(Routes.PATH)

        assertEquals(listOf(FIRST), asked)
    }

    @Test
    fun `a link waits again once the nav host leaves with its Activity`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        links.screenGone(navHost)

        links.open(link(FIRST), restored = false)
        assertTrue(asked.isEmpty())
        links.screenChanged(honorLinkScreen(listOf(Routes.PERMISSIONS)), owner = Any())
        links.screenChanged(honorLinkScreen(listOf(Routes.PATH)), owner = Any())

        assertEquals(listOf(FIRST), asked)
    }

    // iOS `testPendingShareIdIsDrainedOnce`.
    @Test
    fun `a held link opens once`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.WELCOME)
        links.open(link(FIRST), restored = false)

        links.at(Routes.PATH)
        links.at(Routes.PATH, Routes.HOME)
        links.at(Routes.PATH)

        assertEquals(listOf(FIRST), asked)
    }

    // S2 §6, correction 3; iOS `testOpenWayWhileWalkingSetsTheToast`.

    @Test
    fun `the walk screen before Start refuses the link, and nothing is remembered for after`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.ACTIVE_WALK)

        links.open(link(FIRST), restored = false)

        assertEquals(HonorLinkToast.FinishWalkFirst, links.toast.value)
        assertEquals("no import may start underneath a walk", HonorImportState.Idle, imports.state.value)
        assertFalse("the tab stays put under the walker", links.pathSwitch.value)
        links.at(Routes.PATH)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a sitting over the walk screen refuses it too`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION)

        links.open(link(FIRST), restored = false)

        assertEquals(HonorLinkToast.FinishWalkFirst, links.toast.value)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a cold start while tracker walks answers finish this walk first and does nothing else`() = runTest(dispatcher) {
        trackerWalking = true
        val links = router()
        links.at(Routes.PERMISSIONS)
        links.open(link(FIRST), restored = false)

        links.at(Routes.PATH)

        assertEquals(HonorLinkToast.FinishWalkFirst, links.toast.value)
        assertTrue(asked.isEmpty())
        assertFalse(links.pathSwitch.value)
        assertEquals("the probe only reads", 1, probes)
    }

    @Test
    fun `a second link during a walk is refused again, and the toast starts its five seconds over`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.ACTIVE_WALK)
        links.open(link(FIRST), restored = false)
        advanceTimeBy(4_000)

        links.open(link(SECOND), restored = false)
        advanceTimeBy(4_000)
        assertEquals(HonorLinkToast.FinishWalkFirst, links.toast.value)
        advanceTimeBy(1_001)

        assertNull(links.toast.value)
    }

    // Correction 2, S2 §4.4: the Path tab, the toast, the fetch, then the overview.

    @Test
    fun `a link from another tab switches to Path and says so, and its overview comes once the Way is listed`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME)

        links.open(link(FIRST), restored = false)
        assertTrue(links.pathSwitch.value)
        assertEquals(HonorLinkToast.Reaching, links.toast.value)
        assertNull("no overview while it fetches", imports.fetched.value)
        links.pathSwitchTaken()
        links.at(Routes.PATH)

        land(FIRST)

        assertNull("the toast goes as the Way lands", links.toast.value)
        assertEquals("share:$FIRST", imports.fetched.value)
        assertEquals(FetchedWayLanding.PRESENT, fetchedWayLanding(listOf(Routes.PATH)))
    }

    @Test
    fun `a failure answers in the toast, an unknown one as couldn't reach the walk`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)

        links.open(link(FIRST), restored = false)
        fail(FIRST, WayImportException(WayError.NOT_FOUND))
        assertEquals(HonorLinkToast.Failed(WayError.NOT_FOUND), links.toast.value)

        links.open(link(SECOND), restored = false)
        fail(SECOND, IOException("the store could not write"))
        assertEquals(HonorLinkToast.Failed(WayError.UNAVAILABLE), links.toast.value)
        assertNull(imports.fetched.value)
    }

    @Test
    fun `the toast lasts five seconds`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)

        links.open(link(FIRST), restored = false)
        advanceTimeBy(4_999)
        assertEquals(HonorLinkToast.Reaching, links.toast.value)
        advanceTimeBy(2)

        assertNull("a fetch that never resolves leaves no toast behind", links.toast.value)
    }

    // Correction 5, S2 §4.3: the Ways sheet closes itself; a summary waits for the walker.

    @Test
    fun `with the Ways sheet up the link says it inline, with no toast and no switch, and the sheet takes the Way`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HONOR_WAYS, Routes.HONOR_OWN_WALKS)

        links.open(link(FIRST), restored = false)
        assertNull(links.toast.value)
        assertFalse(links.pathSwitch.value)
        assertEquals(HonorImportState.Fetching, imports.state.value)
        links.at(Routes.PATH, Routes.HONOR_WAYS)
        land(FIRST)

        assertEquals("share:$FIRST", imports.fetched.value)
        assertEquals(FetchedWayLanding.WAIT, fetchedWayLanding(listOf(Routes.PATH, Routes.HONOR_WAYS)))
    }

    @Test
    fun `with the Ways sheet up a failure stays inline`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HONOR_WAYS)

        links.open(link(FIRST), restored = false)
        fail(FIRST, WayImportException(WayError.RETURNED_TO_TRAIL))

        assertNull(links.toast.value)
        assertEquals(HonorImportState.Failed(WayError.RETURNED_TO_TRAIL), imports.state.value)
    }

    // S1-D9, pilgrim-ios #113, matched: `chooseWay` clears the toast and the line, not the import.
    @Test
    fun `opening the Ways sheet clears the toast and leaves the import running`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        links.open(link(FIRST), restored = false)

        links.at(Routes.PATH, Routes.HONOR_WAYS)
        imports.chooseWay()

        assertNull(links.toast.value)
        assertEquals(HonorImportState.Idle, imports.state.value)
        land(FIRST)
        assertEquals("share:$FIRST", imports.fetched.value)
    }

    @Test
    fun `a paste whose sheet closed before it failed answers in the toast, as iOS's does`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HONOR_WAYS)
        imports.openWay(FIRST)

        links.at(Routes.PATH)
        fail(FIRST, WayImportException(WayError.NOT_FOUND))

        assertEquals(HonorLinkToast.Failed(WayError.NOT_FOUND), links.toast.value)
    }

    @Test
    fun `a summary from any host keeps its place, the toast under it, and the Way parks until it closes`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME, Routes.GOSHUIN, Routes.WALK_SUMMARY_PATTERN)

        links.open(link(FIRST), restored = false)
        assertFalse("the summary stays up over its host", links.pathSwitch.value)
        assertEquals(HonorLinkToast.Reaching, links.toast.value)
        land(FIRST)

        assertEquals(
            FetchedWayLanding.PARK,
            fetchedWayLanding(listOf(Routes.PATH, Routes.HOME, Routes.GOSHUIN, Routes.WALK_SUMMARY_PATTERN)),
        )
        assertEquals(
            "a share over the summary still parks it",
            FetchedWayLanding.PARK,
            fetchedWayLanding(listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN, Routes.WALK_SHARE_PATTERN)),
        )
        assertEquals(
            "the summary's Done lands on the Journal; the overview then opens over Path",
            FetchedWayLanding.PRESENT,
            fetchedWayLanding(listOf(Routes.PATH, Routes.HOME), parkedBehindSummary = true),
        )
    }

    @Test
    fun `an overview up shows the fetch in place and gives way to the new Way`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)

        links.open(link(FIRST), restored = false)
        assertFalse("the open overview isn't closed under the walker", links.pathSwitch.value)
        assertEquals(HonorImportState.Fetching, imports.state.value)
        land(FIRST)

        assertEquals(
            FetchedWayLanding.PRESENT,
            fetchedWayLanding(listOf(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)),
        )
    }

    // S2 §5 row 12: the newer link wins; the older one's result is discarded.
    @Test
    fun `a newer link cancels the older one`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)

        links.open(link(FIRST), restored = false)
        links.open(link(SECOND), restored = false)
        fail(FIRST, WayImportException(WayError.NOT_FOUND))
        land(SECOND)

        assertEquals(listOf(FIRST, SECOND), asked)
        assertEquals("share:$SECOND", imports.fetched.value)
        assertNull("the older failure never lands", links.toast.value)
    }

    @Test
    fun `a newer link starts the toast's five seconds over`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        links.open(link(FIRST), restored = false)
        advanceTimeBy(4_000)

        links.open(link(SECOND), restored = false)
        advanceTimeBy(4_000)

        assertEquals(HonorLinkToast.Reaching, links.toast.value)
    }

    // Correction 3; iOS `testImportResolvingDuringAWalkPresentsNoOverview`.
    @Test
    fun `a walk starting cancels the import silently, and a manifest resolving after opens nothing`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        links.open(link(FIRST), restored = false)

        links.at(Routes.PATH, Routes.ACTIVE_WALK)
        land(FIRST)

        assertNull("nothing interrupts a walk already under way", imports.fetched.value)
        assertEquals("the state is left as it was (iOS `startWalk`)", HonorImportState.Fetching, imports.state.value)
        assertEquals(
            "the toast rides onto the walk screen until its five seconds end (pilgrim-ios #113, matched)",
            HonorLinkToast.Reaching,
            links.toast.value,
        )
        assertEquals(FetchedWayLanding.DROP, fetchedWayLanding(listOf(Routes.PATH, Routes.ACTIVE_WALK)))
    }

    // iOS `testImportResolvingAfterBeginPresentsNoOverview`: Android's Begin opens the walk screen in the overview's place.
    @Test
    fun `a Begin landing while the fetch is in the air wins`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)
        links.open(link(FIRST), restored = false)

        links.at(Routes.PATH, Routes.HOME, Routes.ACTIVE_WALK)
        land(FIRST)

        assertNull("a Begin already in flight wins", imports.fetched.value)
    }

    // Consume once (S2 §9.1, resolution 12) and task restore (S2 open question 3).

    @Test
    fun `an Activity rebuilt from saved state never routes its intent's link`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)

        links.open(link(FIRST), restored = true)

        assertTrue("a task restored after a process death loses the link, as R18 says", asked.isEmpty())
        assertNull(links.toast.value)
    }

    @Test
    fun `a consumed intent carries no link to route again`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        val intent = link(FIRST)

        val consumed = linkConsumed(intent)
        links.open(consumed, restored = false)

        assertTrue(carriesLinkData(intent))
        assertFalse(carriesLinkData(consumed))
        assertNull(consumed.data)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `a VIEW intent carrying widget extras routes only as a link`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)
        val intent = link(FIRST)
            .putExtra(DeepLinkTarget.EXTRA_DEEP_LINK, DeepLinkTarget.DEEP_LINK_WALK_SUMMARY)
            .putExtra(DeepLinkTarget.EXTRA_WALK_ID, 42L)

        links.open(intent, restored = false)

        assertEquals(listOf(FIRST), asked)
        assertNull("the widget's extras are never read from a link", DeepLinkTarget.parse(intent))
    }

    @Test
    fun `an honor path that doesn't parse opens the app and does nothing`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH, Routes.HOME)

        links.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/abc")), restored = false)
        links.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/")), restored = false)

        assertTrue(asked.isEmpty())
        assertNull(links.toast.value)
        assertFalse(links.pathSwitch.value)
    }

    @Test
    fun `an explicit VIEW intent naming the walk host parses, as iOS's onOpenURL would`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)

        links.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://walk.pilgrimapp.org/$FIRST")), restored = false)

        assertEquals(listOf(FIRST), asked)
    }

    @Test
    fun `only a VIEW intent is a link`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.PATH)

        links.open(Intent(Intent.ACTION_SEND, Uri.parse("https://honor.pilgrimapp.org/$FIRST")), restored = false)

        assertTrue(asked.isEmpty())
    }

    @Test
    fun `with the release flag off a link does nothing`() = runTest(dispatcher) {
        val links = router(honorEnabled = false)
        links.at(Routes.PATH, Routes.HOME)

        links.open(link(FIRST), restored = false)
        links.route(SECOND)

        assertTrue(asked.isEmpty())
        assertNull(links.toast.value)
        assertFalse(links.pathSwitch.value)
        assertEquals(0, probes)
    }

    @Test
    fun `a waiting id hears when it leaves the hold, routed or replaced`() = runTest(dispatcher) {
        val links = router()
        links.at(Routes.WELCOME)
        val released = mutableListOf<String>()

        links.route(FIRST) { released += FIRST }
        links.route(SECOND) { released += SECOND }
        assertEquals("a newer link replaced it", listOf(FIRST), released)
        links.at(Routes.PATH)

        assertEquals(listOf(FIRST, SECOND), released)
    }

    private fun link(id: String) = Intent(Intent.ACTION_VIEW, Uri.parse("https://honor.pilgrimapp.org/$id"))

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
    }
}
