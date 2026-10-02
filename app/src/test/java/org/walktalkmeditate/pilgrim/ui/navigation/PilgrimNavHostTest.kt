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
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
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
        assertEquals(
            "active_walk?mode=Honor&honorSource=42",
            Routes.activeWalk(WalkMode.Honor, HonorWayChoice.OwnWalk(42L)),
        )
        assertEquals(
            "active_walk?mode=Honor&honorWay=share%3AQoi4YmPHLN",
            Routes.activeWalk(WalkMode.Honor, HonorWayChoice.Stored(SHARED_WAY)),
        )
        assertEquals("active_walk?mode=Seek", Routes.activeWalk(WalkMode.Seek))
    }

    @Test
    fun `an Honor walk screen with no Way to follow is a plain walk screen`() {
        assertEquals(WalkMode.Honor, activeWalkMode(WalkMode.Honor, HonorWayChoice.OwnWalk(42L)))
        assertEquals(WalkMode.Honor, activeWalkMode(WalkMode.Honor, HonorWayChoice.Stored(SHARED_WAY)))
        assertEquals(WalkMode.Wander, activeWalkMode(WalkMode.Honor, honorWay = null))
        assertEquals(WalkMode.Seek, activeWalkMode(WalkMode.Seek, honorWay = null))
    }

    @Test
    fun `only a summary whose host can open the overview asks for the door`() {
        assertEquals("walk_summary/7?walkAgain=true", Routes.walkSummary(7L, walkAgainDoor = true))
        assertEquals("walk_summary/7", Routes.walkSummary(7L))
        assertEquals("honor_overview?sourceWalkId=7", Routes.honorOverview(HonorWayChoice.OwnWalk(7L)))
        assertEquals("honor_overview?wayId=share%3AQoi4YmPHLN", Routes.honorOverview(HonorWayChoice.Stored(SHARED_WAY)))
    }

    // S1 §6.3, S2 §4.3: where a Way an import just listed lands.

    @Test
    fun `a fetched Way opens over the screen showing, waits for the sheet or setup, parks behind a summary, and a walk drops it`() {
        assertEquals(FetchedWayLanding.PRESENT, fetchedWayLanding(listOf(Routes.PATH)))
        assertEquals(FetchedWayLanding.PRESENT, fetchedWayLanding(listOf(Routes.PATH, Routes.HOME)))
        assertEquals(FetchedWayLanding.PRESENT, fetchedWayLanding(listOf(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)))
        assertEquals(
            "the picker over the Ways sheet: the sheet isn't showing to take it",
            FetchedWayLanding.PRESENT,
            fetchedWayLanding(listOf(Routes.PATH, Routes.HONOR_WAYS, Routes.HONOR_OWN_WALKS)),
        )
        assertEquals(FetchedWayLanding.WAIT, fetchedWayLanding(listOf(Routes.PATH, Routes.HONOR_WAYS)))
        assertEquals(FetchedWayLanding.WAIT, fetchedWayLanding(listOf(Routes.WELCOME)))
        assertEquals(FetchedWayLanding.WAIT, fetchedWayLanding(listOf(Routes.PERMISSIONS)))
        assertEquals(FetchedWayLanding.WAIT, fetchedWayLanding(listOf(Routes.BREATH)))
        assertEquals(FetchedWayLanding.WAIT, fetchedWayLanding(emptyList()))
        assertEquals(FetchedWayLanding.PARK, fetchedWayLanding(listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN)))
        assertEquals(FetchedWayLanding.PARK, fetchedWayLanding(listOf(Routes.PATH, Routes.HOME, Routes.WALK_SUMMARY_PATTERN)))
        assertEquals(FetchedWayLanding.DROP, fetchedWayLanding(listOf(Routes.PATH, Routes.ACTIVE_WALK)))
        assertEquals(FetchedWayLanding.DROP, fetchedWayLanding(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION)))
    }

    // iOS `walkAgain` overwrites `pendingHonorWay`, so the own walk's overview wins.
    @Test
    fun `a Way parked behind a summary gives way to the overview walk this again opens in its place`() {
        val walkAgain = listOf(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)

        assertEquals(FetchedWayLanding.DROP, fetchedWayLanding(walkAgain, parkedBehindSummary = true))
        assertEquals(FetchedWayLanding.PRESENT, fetchedWayLanding(listOf(Routes.PATH, Routes.HOME), parkedBehindSummary = true))
    }

    @Test
    fun `the link routing reads setup, the walk screen, the sheet, a summary, and an overview off the back stack`() {
        assertTrue(honorLinkScreen(emptyList()).inSetup)
        assertTrue(honorLinkScreen(listOf(Routes.PERMISSIONS)).inSetup)
        assertFalse(honorLinkScreen(listOf(Routes.PATH)).inSetup)
        assertTrue(honorLinkScreen(listOf(Routes.PATH)).atPath)
        assertFalse(honorLinkScreen(listOf(Routes.PATH, Routes.HOME)).atPath)
        assertTrue("before Start too", honorLinkScreen(listOf(Routes.PATH, Routes.ACTIVE_WALK)).walkScreenUp)
        assertTrue(honorLinkScreen(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION)).walkScreenUp)
        assertTrue(honorLinkScreen(listOf(Routes.PATH, Routes.HONOR_WAYS, Routes.HONOR_OWN_WALKS)).waysSheetUp)
        assertTrue(honorLinkScreen(listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN, Routes.WALK_SHARE_PATTERN)).summaryUp)
        assertTrue(honorLinkScreen(listOf(Routes.PATH, Routes.HOME, Routes.HONOR_OVERVIEW_PATTERN)).overviewUp)
    }

    @Test
    fun `the one link toast shows on every tab and the walk screen, but not over a sitting or an overview`() {
        assertTrue(linkToastShows(listOf(Routes.PATH, Routes.ACTIVE_WALK)))
        assertFalse(linkToastShows(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION)))
        assertFalse(linkToastShows(listOf(Routes.PATH, Routes.ACTIVE_WALK, Routes.MEDITATION, Routes.SOUNDSCAPE_PICKER)))
        assertTrue("under the recovery banner, in the same host", linkToastShows(listOf(Routes.PATH)))
        assertTrue(linkToastShows(listOf(Routes.PATH, Routes.HOME)))
        assertTrue(linkToastShows(listOf(Routes.PATH, Routes.SETTINGS)))
        assertFalse(linkToastShows(listOf(Routes.PATH, Routes.HONOR_OVERVIEW_PATTERN)))
        assertTrue("under the summary's own window", linkToastShows(listOf(Routes.PATH, Routes.WALK_SUMMARY_PATTERN)))
        assertFalse(linkToastShows(emptyList()))
    }

    // Owner decision 5: a link from any other screen opens over the Path tab, so Close lands there.

    @Test
    fun `a fetched Way opens over the Path tab from another tab, and Close lands on Path`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigateToTab(Routes.HOME) }

        onMain { nav.openFetchedWayOverview(SHARED_WAY) }
        onMain {
            assertEquals(SHARED_WAY, nav.currentBackStackEntry?.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID))
            assertEquals(Routes.PATH, nav.previousBackStackEntry?.destination?.route)
        }
        onMain { nav.closeHonorOverview() }

        onMain { assertEquals(Routes.PATH, nav.currentBackStackEntry?.destination?.route) }
    }

    @Test
    fun `a fetched Way replaces an overview up over the Journal with one over Path`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigateToTab(Routes.HOME) }
        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }

        onMain { nav.openFetchedWayOverview(SHARED_WAY) }

        onMain { assertEquals(SHARED_WAY, nav.currentBackStackEntry?.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID)) }
        assertOnlyPathBeneath(nav)
    }

    @Test
    fun `a fetched Way takes the Ways sheet's place, and its picker's`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigate(Routes.HONOR_WAYS) }
        onMain { nav.navigate(Routes.HONOR_OWN_WALKS) }

        onMain { nav.openFetchedWayOverview(SHARED_WAY) }

        onMain { assertEquals(Routes.HONOR_OVERVIEW_PATTERN, nav.currentBackStackEntry?.destination?.route) }
        assertOnlyPathBeneath(nav)
    }

    // S2 §5 rows 8–9: a link for the share whose overview is up keeps that overview.
    @Test
    fun `only the shared Way's own overview, showing now, counts as already open`() {
        val nav = honorBackStack(start = Routes.PATH)
        onMain { nav.navigate(Routes.HONOR_WAYS) }
        onMain { nav.openStoredWayOverview(SHARED_WAY) }

        onMain {
            assertTrue(nav.showsStoredOverview(SHARED_WAY))
            assertFalse(nav.showsStoredOverview("share:Second1234"))
        }
        onMain { nav.closeHonorOverview() }
        onMain { assertFalse("no overview showing", nav.showsStoredOverview(SHARED_WAY)) }
    }

    @Test
    fun `an own walk's overview is never a shared Way's`() {
        val nav = honorBackStack()
        onMain { nav.navigate(Routes.walkSummary(7L, walkAgainDoor = true)) }
        onMain { nav.openHonorOverviewFromSummary(7L) }

        onMain { assertFalse(nav.showsStoredOverview(SHARED_WAY)) }
    }

    private fun assertOnlyPathBeneath(nav: NavHostController) {
        onMain { nav.popBackStack() }
        onMain {
            assertEquals(Routes.PATH, nav.currentBackStackEntry?.destination?.route)
            assertNull(nav.previousBackStackEntry)
        }
    }

    @Test
    fun `a shared row or a finished paste takes the Ways sheet's place, the picker's too`() {
        val nav = honorBackStack(start = Routes.PATH)

        onMain { nav.navigate(Routes.HONOR_WAYS) }
        onMain { nav.navigate(Routes.HONOR_OWN_WALKS) }
        onMain { nav.openStoredWayOverview(SHARED_WAY) }

        onMain {
            val overview = nav.currentBackStackEntry!!
            assertEquals(Routes.HONOR_OVERVIEW_PATTERN, overview.destination.route)
            assertEquals(SHARED_WAY, overview.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID))
            assertEquals(Routes.PATH, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun `a fetched Way replaces an overview already up, as iOS swaps the overview's Way`() {
        val nav = honorBackStack()

        onMain { nav.openHonorOverviewFromSummary(7L) }
        onMain { nav.openStoredWayOverview(SHARED_WAY) }

        onMain {
            assertEquals(SHARED_WAY, nav.currentBackStackEntry?.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID))
            assertEquals(Routes.HOME, nav.previousBackStackEntry?.destination?.route)
        }
    }

    @Test
    fun `a shared Way's Begin opens the walk screen with the listed Way, in the overview's place`() {
        val nav = honorBackStack()

        onMain { nav.openStoredWayOverview(SHARED_WAY) }
        onMain { nav.beginHonorWalk(HonorWayChoice.Stored(SHARED_WAY)) }

        onMain {
            val walk = nav.currentBackStackEntry!!
            assertEquals(Routes.ACTIVE_WALK, walk.destination.route)
            assertEquals(WalkMode.Honor.name, walk.arguments?.getString(Routes.ACTIVE_WALK_ARG_MODE))
            assertEquals(HonorWayChoice.Stored(SHARED_WAY), honorWayOf(walk.arguments))
            assertEquals(Routes.HOME, nav.previousBackStackEntry?.destination?.route)
        }
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
        onMain { nav.beginHonorWalk(HonorWayChoice.OwnWalk(7L)) }

        onMain {
            val walk = nav.currentBackStackEntry!!
            assertEquals(Routes.ACTIVE_WALK, walk.destination.route)
            assertEquals(WalkMode.Honor.name, walk.arguments?.getString(Routes.ACTIVE_WALK_ARG_MODE))
            assertEquals(7L, walk.arguments?.getLong(Routes.ACTIVE_WALK_ARG_HONOR_SOURCE))
            assertEquals(HonorWayChoice.OwnWalk(7L), honorWayOf(walk.arguments))
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
     * started on the Journal tab (the host a summary opens over) unless
     * [start] says otherwise.
     */
    private fun honorBackStack(start: String = Routes.HOME): NavHostController {
        var nav: NavHostController? = null
        composeRule.setContent {
            val controller = rememberNavController()
            SideEffect { nav = controller }
            NavHost(navController = controller, startDestination = start) {
                composable(Routes.HOME) {}
                composable(Routes.PATH) {}
                composable(Routes.HONOR_WAYS) {}
                composable(Routes.HONOR_OWN_WALKS) {}
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
                composable(route = Routes.HONOR_OVERVIEW_PATTERN, arguments = honorOverviewArguments) {}
                composable(route = Routes.ACTIVE_WALK, arguments = activeWalkArguments) {}
            }
        }
        composeRule.waitForIdle()
        return requireNotNull(nav)
    }

    private companion object {
        const val SHARED_WAY = "share:Qoi4YmPHLN"
    }
}
