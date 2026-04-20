// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinScreen
import org.walktalkmeditate.pilgrim.ui.home.HomeScreen
import org.walktalkmeditate.pilgrim.ui.meditation.MeditationScreen
import org.walktalkmeditate.pilgrim.ui.onboarding.PermissionsScreen
import org.walktalkmeditate.pilgrim.ui.walk.ActiveWalkScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel

object Routes {
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
    const val ACTIVE_WALK = "active_walk"
    const val GOSHUIN = "goshuin"
    const val MEDITATION = "meditation"
    private const val WALK_SUMMARY_PREFIX = "walk_summary"
    const val WALK_SUMMARY_PATTERN = "$WALK_SUMMARY_PREFIX/{${WalkSummaryViewModel.ARG_WALK_ID}}"
    fun walkSummary(walkId: Long): String = "$WALK_SUMMARY_PREFIX/$walkId"
}

@Composable
fun PilgrimNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    // Always start at PERMISSIONS; auto-navigate to HOME when onboarding
    // state arrives. See the polish-pass comment below for why we don't
    // read onboardingComplete into startDestination directly.
    NavHost(
        navController = navController,
        startDestination = Routes.PERMISSIONS,
        modifier = modifier,
    ) {
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onComplete = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
                viewModel = permissionsViewModel,
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                permissionsViewModel = permissionsViewModel,
                onEnterActiveWalk = { navController.navigate(Routes.ACTIVE_WALK) },
                onEnterWalkSummary = { walkId ->
                    // launchSingleTop: if the user double-taps a row
                    // faster than the first nav visually commits, the
                    // same walkId-routed entry is reused instead of
                    // stacking. A different walkId still pushes a new
                    // entry, so Home → Summary(1) → Home → Summary(2)
                    // behaves normally.
                    navController.navigate(Routes.walkSummary(walkId)) {
                        launchSingleTop = true
                    }
                },
                onEnterGoshuin = {
                    // Same double-tap guard as onEnterWalkSummary.
                    navController.navigate(Routes.GOSHUIN) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.ACTIVE_WALK) {
            ActiveWalkScreen(
                onFinished = { walkId ->
                    navController.navigate(Routes.walkSummary(walkId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
                },
                onEnterMeditation = {
                    // launchSingleTop protects against a double-fire
                    // of the state-class observer if the reducer
                    // briefly bounces through Meditating during a
                    // restored session. Without it, two MEDITATION
                    // entries could stack.
                    navController.navigate(Routes.MEDITATION) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.MEDITATION) {
            MeditationScreen(
                onEnded = {
                    // Pop back to ActiveWalk. If the walk was finished
                    // externally (state went straight Meditating →
                    // Finished), ActiveWalk's state observer will then
                    // fire onFinished on its next composition, cleanly
                    // chaining to the summary screen — two hops but
                    // correct.
                    navController.popBackStack(Routes.ACTIVE_WALK, inclusive = false)
                },
            )
        }
        composable(
            route = Routes.WALK_SUMMARY_PATTERN,
            arguments = listOf(
                navArgument(WalkSummaryViewModel.ARG_WALK_ID) { type = NavType.LongType },
            ),
        ) {
            WalkSummaryScreen(
                onDone = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
            )
        }
        composable(Routes.GOSHUIN) {
            GoshuinScreen(
                onBack = { navController.popBackStack() },
                onSealTap = { walkId ->
                    // launchSingleTop only dedupes the SAME route
                    // string; two different walkIds tapped within
                    // ~100ms (real double-tap jitter) would each get
                    // a distinct `walk_summary/{id}` route and stack:
                    //   Goshuin → Summary(A) → Summary(B)
                    // Back from Summary(B) would then land on
                    // Summary(A), not the grid. popUpTo(GOSHUIN) ahead
                    // of the navigate collapses any in-flight Summary
                    // so the stack is always [Goshuin, Summary(N)] —
                    // correct for double-tap races AND for sequential
                    // browsing (Summary(A) → back → Summary(B)).
                    navController.navigate(Routes.walkSummary(walkId)) {
                        launchSingleTop = true
                        popUpTo(Routes.GOSHUIN) { inclusive = false }
                    }
                },
            )
        }
    }

    val onboardingComplete by permissionsViewModel.onboardingComplete.collectAsState()
    val currentEntry by navController.currentBackStackEntryAsState()
    val context = LocalContext.current

    LaunchedEffect(onboardingComplete, currentEntry?.destination?.route) {
        if (
            onboardingComplete &&
            PermissionChecks.isMinimumGranted(context) &&
            currentEntry?.destination?.route == Routes.PERMISSIONS
        ) {
            navController.navigate(Routes.HOME) {
                popUpTo(Routes.PERMISSIONS) { inclusive = true }
            }
        }
    }
}
