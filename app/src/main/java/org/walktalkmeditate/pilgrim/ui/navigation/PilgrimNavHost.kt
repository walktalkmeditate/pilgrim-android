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
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.design.seals.SealPreviewScreen
import org.walktalkmeditate.pilgrim.ui.home.HomeScreen
import org.walktalkmeditate.pilgrim.ui.onboarding.PermissionsScreen
import org.walktalkmeditate.pilgrim.ui.walk.ActiveWalkScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel

object Routes {
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
    const val ACTIVE_WALK = "active_walk"
    const val SEAL_PREVIEW = "seal_preview"
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
                onEnterSealPreview = {
                    // Defense-in-depth: the HomeScreen button that
                    // invokes this is BuildConfig.DEBUG-gated AND the
                    // composable() registration below is too. If a
                    // release build ever ended up calling this lambda,
                    // navigate() would throw for an unknown route —
                    // short-circuit here instead.
                    if (BuildConfig.DEBUG) {
                        navController.navigate(Routes.SEAL_PREVIEW) {
                            launchSingleTop = true
                        }
                    }
                },
            )
        }
        // Register the preview destination AND its Hilt-graph bindings
        // only for debug builds. Keeps a release build from resolving
        // the preview route via a rogue deep link and instantiating the
        // debug VM. (Same pattern Stage 3-C used for calligraphy preview.)
        if (BuildConfig.DEBUG) {
            composable(Routes.SEAL_PREVIEW) {
                SealPreviewScreen()
            }
        }
        composable(Routes.ACTIVE_WALK) {
            ActiveWalkScreen(
                onFinished = { walkId ->
                    navController.navigate(Routes.walkSummary(walkId)) {
                        popUpTo(Routes.HOME) { inclusive = false }
                    }
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
