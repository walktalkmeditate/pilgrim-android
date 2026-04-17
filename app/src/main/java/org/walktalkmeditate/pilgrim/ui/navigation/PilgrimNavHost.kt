// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
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
    navController: NavHostController = rememberNavController(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
) {
    val onboardingComplete by permissionsViewModel.onboardingComplete.collectAsState()
    val context = LocalContext.current
    val minimumGranted = remember(onboardingComplete) {
        PermissionChecks.isMinimumGranted(context)
    }

    val startDestination = if (onboardingComplete && minimumGranted) {
        Routes.HOME
    } else {
        Routes.PERMISSIONS
    }

    NavHost(navController = navController, startDestination = startDestination) {
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
            HomeScreen()
        }
    }
}
