// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.NORTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.ROUTE_ID
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageHarness.Companion.waitUntil

/**
 * A link page's focus (stage-links spec Q4; iOS `applyFocus`,
 * `PilgrimageRouteView.swift:354-359@3b78728`) against the real manager and
 * catalog service: once, only once stages exist, the scroll and the prompt
 * independent, the prompt through the button's gate, and the latch kept in
 * the saved state. The world's route has two stages, 0 and 1. Waits are
 * wall-clock, as the page reads off the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageRouteFocusTest {

    @get:Rule val folder = TemporaryFolder()

    private lateinit var world: PilgrimageScreensWorld

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        world = PilgrimageScreensWorld(folder.root)
    }

    @After
    fun tearDown() {
        world.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a focus on a route not on the phone scrolls to its stage and asks to download first`() {
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(focus = 1))

        waitUntil("the focus lands") { vm.scrollTarget.value == 1 }
        assertEquals(PilgrimageRouteAlert.DOWNLOAD_FIRST, vm.alert.value)
    }

    /** Q5: the resolver's catalog is the service's, so the page is ready on its first frame. */
    @Test
    fun `a link page opened with the catalog in hand is ready at once`() {
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(focus = 0))

        assertTrue(vm.state.value is PilgrimageRouteUiState.Ready)
    }

    // iOS's guard waits for the stage list (`PilgrimageRouteView.swift:350-355@3b78728`).
    @Test
    fun `a failed preview keeps the focus, and try again applies it once`() {
        world.harness.stubs.unstub(world.harness.path("route.json"))
        world.holdCatalog()
        val saved = linkState(focus = 1)
        val vm = world.routeViewModel(saved = saved)
        waitUntil("the preview fails") {
            val page = (vm.state.value as? PilgrimageRouteUiState.Ready)?.page
            page != null && page.stagesFailure != null && !page.isLoadingStages && !page.isHeld
        }
        assertNull("no prompt over a failed preview", vm.alert.value)
        assertNull(vm.scrollTarget.value)
        assertNull(saved.get<Boolean>(PilgrimageRouteViewModel.KEY_FOCUS_APPLIED))

        world.harness.stub("route.json", PilgrimagePackageHarness.fixture("route.json"))
        vm.retryStages()

        waitUntil("try again applies the focus") { vm.scrollTarget.value == 1 }
        assertEquals(PilgrimageRouteAlert.DOWNLOAD_FIRST, vm.alert.value)
        vm.dismissAlert()
        vm.consumeScrollTarget()
        vm.retryStages()
        waitUntil("the second try settles") { (vm.state.value as? PilgrimageRouteUiState.Ready)?.page?.isLoadingStages == false }
        assertNull("once", vm.alert.value)
        assertNull(vm.scrollTarget.value)
    }

    // Review Focus 4's neighbour: the installed route's Update button is the answer, not a prompt.
    @Test
    fun `an installed route asks nothing, and scrolls even to a stage whose Way is missing`() {
        world.install()
        File(world.harness.waysDir, WayStore.stageWayId(ROUTE_ID, 1)).deleteRecursively()
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(focus = 1))

        waitUntil("the focus lands") { vm.scrollTarget.value == 1 }
        assertNull(vm.alert.value)
    }

    // Review Focus 4; the spec's "Lens disagreements": a focus the list doesn't hold.
    @Test
    fun `a stage the installed release doesn't list gets no scroll and no prompt`() {
        world.install()
        world.holdCatalog()
        val saved = linkState(focus = 5)

        val vm = world.routeViewModel(saved = saved)

        applied(saved)
        assertNull(vm.scrollTarget.value)
        assertNull(vm.alert.value)
    }

    @Test
    fun `a stage the list doesn't hold still asks to download a route not on the phone`() {
        world.holdCatalog()
        val saved = linkState(focus = 5)

        val vm = world.routeViewModel(saved = saved)

        applied(saved)
        assertNull(vm.scrollTarget.value)
        assertEquals(PilgrimageRouteAlert.DOWNLOAD_FIRST, vm.alert.value)
    }

    @Test
    fun `no focus, the catalog's page or a link past the route's end, scrolls nowhere and asks nothing`() {
        world.holdCatalog()
        val catalogPage = world.routeViewModel()
        val unfocused = world.routeViewModel(saved = linkState(focus = null))

        listOf(catalogPage, unfocused).forEach { vm ->
            settled(vm)
            assertNull(vm.alert.value)
            assertNull(vm.scrollTarget.value)
        }
    }

    // `PilgrimageRouteView.swift:185-192@3b78728`: "Download" is `beginInstall()`, so "Replace?" still asks.
    @Test
    fun `the prompt goes through the button's gate, so another route on the phone still asks Replace`() {
        world.stubIndex(PilgrimageScreensWorld.index(routes = listOf(ROUTE_ID to "Francés", NORTE_ID to "Norte")))
        world.harness.stubNorte()
        world.install(NORTE_ID)
        world.holdCatalog()
        val vm = world.routeViewModel(saved = linkState(focus = 0))
        waitUntil("the prompt") { vm.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }

        vm.confirmDownloadFirst()

        assertEquals(PilgrimageRouteAlert.REPLACE, vm.alert.value)
    }

    // Review Focus 2.
    @Test
    fun `a rotation or a restore after the prompt was answered never asks again`() {
        world.holdCatalog()
        val saved = linkState(focus = 1)
        val first = world.routeViewModel(saved = saved)
        waitUntil("the prompt") { first.alert.value == PilgrimageRouteAlert.DOWNLOAD_FIRST }
        first.dismissAlert()

        val restored = world.routeViewModel(saved = saved)
        settled(restored)

        assertNull(restored.alert.value)
        assertNull(restored.scrollTarget.value)
    }

    /** Q5's risk: a focus for a page that ends Gone raises nothing. */
    @Test
    fun `a focus for a page the catalog doesn't list raises nothing`() {
        world.holdCatalog()

        val vm = world.routeViewModel(saved = linkState(routeId = NORTE_ID, focus = 0))

        assertEquals(PilgrimageRouteUiState.Gone, vm.state.value)
        assertNull(vm.alert.value)
    }

    private fun linkState(routeId: String = ROUTE_ID, focus: Int?) = SavedStateHandle(
        mapOf(
            PilgrimageRouteViewModel.ARG_ROUTE_ID to routeId,
            PilgrimageRouteViewModel.ARG_FOCUS to (focus ?: PilgrimageRouteViewModel.NO_FOCUS),
        ),
    )

    /** The latch is written last, so a test that sees it sees the scroll and the prompt decided. */
    private fun applied(saved: SavedStateHandle) =
        waitUntil("the focus is applied") { saved.get<Boolean>(PilgrimageRouteViewModel.KEY_FOCUS_APPLIED) == true }

    private fun settled(vm: PilgrimageRouteViewModel) = waitUntil("the page settles") {
        val page = (vm.state.value as? PilgrimageRouteUiState.Ready)?.page
        page != null && page.route != null && !page.isLoadingStages && !page.isHeld
    }
}
