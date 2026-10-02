// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.ui.honor.HonorOverviewViewModel
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel

/**
 * Pin-tests for the bottom-nav membership rule + Routes.PATH constant,
 * and the Honor doors' back stack on a real NavController. The rest of
 * the Compose-Nav integration is covered by manual QA (spec section 14).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PilgrimNavHostTest {

    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `Routes_PATH constant value is path`() {
        assertEquals("path", Routes.PATH)
    }

    @Test
    fun `TAB_ROUTES contains exactly PATH HOME SETTINGS`() {
        val expected = setOf(Routes.PATH, Routes.HOME, Routes.SETTINGS)
        assertEquals(expected, TAB_ROUTES)
    }

    @Test
    fun `tab routes include PATH`() {
        assertTrue(Routes.PATH in TAB_ROUTES)
    }

    @Test
    fun `non-tab routes are excluded from TAB_ROUTES`() {
        // Spot-check immersive + pushed routes.
        assertFalse(Routes.ACTIVE_WALK in TAB_ROUTES)
        assertFalse(Routes.MEDITATION in TAB_ROUTES)
        assertFalse(Routes.GOSHUIN in TAB_ROUTES)
    }

    @Test
    fun `Begin opens the walk screen in Honor mode with the walk it follows`() {
        assertEquals("active_walk?mode=Honor&honorSource=42", Routes.activeWalk(WalkMode.Honor, 42L))
        assertEquals("active_walk?mode=Seek", Routes.activeWalk(WalkMode.Seek))
    }

    @Test
    fun `an Honor walk screen with no walk to follow is a plain walk screen`() {
        assertEquals(WalkMode.Honor, activeWalkMode(WalkMode.Honor, honorSourceWalkId = 42L))
        assertEquals(WalkMode.Wander, activeWalkMode(WalkMode.Honor, honorSourceWalkId = null))
        assertEquals(WalkMode.Seek, activeWalkMode(WalkMode.Seek, honorSourceWalkId = null))
    }

    @Test
    fun `only a summary whose host can open the overview asks for the door`() {
        assertEquals("walk_summary/7?walkAgain=true", Routes.walkSummary(7L, walkAgainDoor = true))
        assertEquals("walk_summary/7", Routes.walkSummary(7L))
        assertEquals("honor_overview/7", Routes.honorOverview(7L))
    }

    // F §6.2, §7.1, correction 1: each Honor step takes the place of the one
    // before it, so Back never lands on the summary or the overview.

    @Test
    fun `walk this again opens the overview in the summary's place, over its host`() {
        val nav = honorBackStack()

        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }

        onMain {
            assertEquals(Routes.HONOR_OVERVIEW_PATTERN, nav.currentBackStackEntry?.destination?.route)
            assertEquals(7L, nav.currentBackStackEntry?.arguments?.getLong(HonorOverviewViewModel.ARG_SOURCE_WALK_ID))
            assertEquals(Routes.HOME, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun `Begin opens the Honor walk screen in the overview's place`() {
        val nav = honorBackStack()

        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }
        onMain { nav.beginHonorWalk(7L) }

        onMain {
            val walk = nav.currentBackStackEntry!!
            assertEquals(Routes.ACTIVE_WALK, walk.destination.route)
            assertEquals(WalkMode.Honor.name, walk.arguments?.getString(Routes.ACTIVE_WALK_ARG_MODE))
            assertEquals(7L, walk.arguments?.getLong(Routes.ACTIVE_WALK_ARG_HONOR_SOURCE))
            assertEquals(Routes.HOME, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun `Close from the overview lands on the host`() {
        val nav = honorBackStack()

        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }
        onMain { nav.closeHonorOverview() }

        onMain { assertEquals(Routes.HOME, nav.currentBackStackEntry?.destination?.route) }
    }

    @Test
    fun `Back from the overview lands on the host`() {
        val nav = honorBackStack()

        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }
        onMain { nav.popBackStack() }

        onMain {
            assertEquals(Routes.HOME, nav.currentBackStackEntry?.destination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }

    private fun onMain(block: () -> Unit) = composeRule.runOnIdle(block)

    /**
     * The production routes and their arguments, an empty screen at each,
     * started on the Journal tab: the host a summary opens over.
     */
    private fun honorBackStack(): NavHostController {
        var nav: NavHostController? = null
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            NavHost(navController = controller, startDestination = Routes.HOME) {
                composable(Routes.HOME) {}
                composable(
                    route = Routes.WALK_SUMMARY_PATTERN,
                    arguments = listOf(
                        navArgument(WalkSummaryViewModel.ARG_WALK_ID) { type = NavType.LongType },
                        navArgument(Routes.WALK_SUMMARY_ARG_WALK_AGAIN) {
                            type = NavType.BoolType
                            defaultValue = false
                        },
                    ),
                ) {}
                composable(
                    route = Routes.HONOR_OVERVIEW_PATTERN,
                    arguments = listOf(
                        navArgument(HonorOverviewViewModel.ARG_SOURCE_WALK_ID) { type = NavType.LongType },
                    ),
                ) {}
                composable(
                    route = Routes.ACTIVE_WALK,
                    arguments = listOf(
                        navArgument(Routes.ACTIVE_WALK_ARG_MODE) {
                            type = NavType.StringType
                            defaultValue = WalkMode.Wander.name
                        },
                        navArgument(Routes.ACTIVE_WALK_ARG_HONOR_SOURCE) {
                            type = NavType.LongType
                            defaultValue = NO_HONOR_SOURCE
                        },
                    ),
                ) {}
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(nav)
    }
}
