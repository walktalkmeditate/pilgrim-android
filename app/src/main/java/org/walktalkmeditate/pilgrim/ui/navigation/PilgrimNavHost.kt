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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.home.HomeScreen
import org.walktalkmeditate.pilgrim.ui.onboarding.PermissionsScreen

object Routes {
    const val PERMISSIONS = "permissions"
    const val HOME = "home"
}

@Composable
fun PilgrimNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    // Always start at PERMISSIONS. The StateFlow's initial value is `false`
    // until DataStore's first emission arrives (a brief moment after cold
    // start), and NavHost reads its startDestination parameter only once at
    // creation — reading onboardingComplete here would make every already-
    // onboarded user flash the permissions screen. Instead, we start at
    // PERMISSIONS for everyone and auto-navigate to HOME below when we
    // observe that onboarding is actually complete and permissions are still
    // granted.
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
            HomeScreen(permissionsViewModel = permissionsViewModel)
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
