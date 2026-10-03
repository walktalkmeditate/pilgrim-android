// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.app.Application
import android.view.KeyEvent
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.walktalkmeditate.pilgrim.ui.navigation.Routes
import org.walktalkmeditate.pilgrim.ui.navigation.closePilgrimageRoute
import org.walktalkmeditate.pilgrim.ui.navigation.closePilgrimages
import org.walktalkmeditate.pilgrim.ui.navigation.honorPilgrimageArguments
import org.walktalkmeditate.pilgrim.ui.navigation.honorSheet
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimTheme

/**
 * System Back on the real route sheet (pilgrimage-stage spec P4 §1.3, A-1,
 * owner decision 8): the catalog and the route page as the app's host
 * wires them, each in its own sheet window over the Ways sheet's route,
 * and Back sent to the top window as the system sends it. Back returns to
 * the catalog, where a swipe would close both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimageSheetsBackTest {

    @get:Rule val folder = TemporaryFolder()

    @get:Rule val composeRule = createComposeRule()

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
    fun `system Back on the route sheet returns to the catalog, which shows again`() {
        val nav = pilgrimageSheets()
        composeRule.runOnIdle { nav.navigate(Routes.HONOR_WAYS) }
        composeRule.runOnIdle { nav.navigate(Routes.HONOR_PILGRIMAGES) }
        composeRule.waitUntil(WAIT_MILLIS) { world.catalogs.catalog.value != null }
        composeRule.onNodeWithText(ROUTE_NAME, substring = true).performClick()
        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.HONOR_PILGRIMAGE_PATTERN }
        composeRule.onNodeWithText("Download").assertIsDisplayed()

        pressBackOnTheTopWindow()

        composeRule.waitUntil(WAIT_MILLIS) { nav.currentBackStackEntry?.destination?.route == Routes.HONOR_PILGRIMAGES }
        composeRule.runOnIdle { assertEquals(Routes.HONOR_WAYS, nav.previousBackStackEntry?.destination?.route) }
        composeRule.onNodeWithText(ROUTE_NAME, substring = true).assertIsDisplayed()
    }

    /** A key-down and key-up of Back to the newest window, the route sheet's, as the system sends it with no predictive Back. */
    private fun pressBackOnTheTopWindow() {
        composeRule.runOnIdle {
            val decor = requireNotNull(ShadowDialog.getShownDialogs().last().window).decorView
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            decor.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
    }

    /** Path, the Ways sheet's route with nothing drawn, and the two pilgrimage sheets wired as the app's host wires them. */
    private fun pilgrimageSheets(): NavHostController {
        world.holdCatalog()
        var nav: NavHostController? = null
        composeRule.setContent {
            PilgrimTheme {
                val controller = rememberNavController()
                SideEffect { nav = controller }
                NavHost(navController = controller, startDestination = Routes.PATH) {
                    composable(Routes.PATH) {}
                    honorSheet(Routes.HONOR_WAYS) {}
                    honorSheet(Routes.HONOR_PILGRIMAGES) {
                        PilgrimageCatalogSheet(
                            onClosed = controller::closePilgrimages,
                            onOpenRoute = { routeId -> controller.navigate(Routes.honorPilgrimage(routeId)) { launchSingleTop = true } },
                            viewModel = remember { world.catalogViewModel() },
                        )
                    }
                    honorSheet(Routes.HONOR_PILGRIMAGE_PATTERN, arguments = honorPilgrimageArguments) { entry ->
                        val routeId = requireNotNull(entry.arguments?.getString(PilgrimageRouteViewModel.ARG_ROUTE_ID))
                        PilgrimageRouteSheet(
                            onBack = controller::closePilgrimageRoute,
                            onClosed = controller::closePilgrimages,
                            onOpenStage = {},
                            viewModel = remember(routeId) { world.routeViewModel(routeId = routeId) },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(nav)
    }

    private companion object {
        /** The world's index names its one route "Camino de Santiago (Francés)". */
        const val ROUTE_NAME = "Camino de Santiago"
        const val WAIT_MILLIS = 10_000L
    }
}
