// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import org.walktalkmeditate.pilgrim.ui.settings.SettingsScreen
import org.walktalkmeditate.pilgrim.ui.settings.soundscape.SoundscapePickerScreen
import org.walktalkmeditate.pilgrim.ui.settings.voiceguide.VoiceGuidePackDetailScreen
import org.walktalkmeditate.pilgrim.ui.settings.voiceguide.VoiceGuidePackDetailViewModel
import org.walktalkmeditate.pilgrim.ui.settings.voiceguide.VoiceGuidePickerScreen
import org.walktalkmeditate.pilgrim.ui.walk.ActiveWalkScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel

object Routes {
    const val PERMISSIONS = "permissions"
    const val PATH = "path"
    const val HOME = "home"
    const val ACTIVE_WALK = "active_walk"
    const val GOSHUIN = "goshuin"
    const val MEDITATION = "meditation"
    private const val WALK_SUMMARY_PREFIX = "walk_summary"
    const val WALK_SUMMARY_PATTERN = "$WALK_SUMMARY_PREFIX/{${WalkSummaryViewModel.ARG_WALK_ID}}"
    fun walkSummary(walkId: Long): String = "$WALK_SUMMARY_PREFIX/$walkId"

    const val SETTINGS = "settings"
    const val VOICE_GUIDE_PICKER = "voice_guides"
    private const val VOICE_GUIDE_DETAIL_PREFIX = "voice_guide"
    const val VOICE_GUIDE_DETAIL_PATTERN =
        "$VOICE_GUIDE_DETAIL_PREFIX/{${VoiceGuidePackDetailViewModel.ARG_PACK_ID}}"
    fun voiceGuideDetail(packId: String): String = "$VOICE_GUIDE_DETAIL_PREFIX/$packId"

    const val SOUNDSCAPE_PICKER = "soundscapes"

    private const val WALK_SHARE_PREFIX = "walk_share"
    const val WALK_SHARE_PATTERN = "$WALK_SHARE_PREFIX/{${org.walktalkmeditate.pilgrim.ui.walk.share.WalkShareViewModel.ARG_WALK_ID}}"
    fun walkShare(walkId: Long): String = "$WALK_SHARE_PREFIX/$walkId"
}

/**
 * Set of routes that show the bottom NavigationBar. All other routes
 * (ACTIVE_WALK, MEDITATION, walkSummary, walkShare, GOSHUIN, voice-guide
 * picker/detail, soundscape picker) hide the bar — accept this divergence
 * from iOS, which keeps the tab bar visible during .sheet modals.
 */
internal val TAB_ROUTES = setOf(Routes.PATH, Routes.HOME, Routes.SETTINGS)

/**
 * Compose Nav's canonical tab-switch idiom: pop to the graph's start
 * destination (PERMISSIONS, but saveState=true preserves each tab's
 * back stack), avoid stacking duplicate top-of-tab entries, and
 * restore the destination's saved state if we're returning to a tab
 * that was previously visited.
 */
internal fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun PilgrimNavHost(
    navController: NavHostController = rememberNavController(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
    pendingDeepLink: org.walktalkmeditate.pilgrim.widget.DeepLinkTarget? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)),
            ) {
                PilgrimBottomBar(
                    currentRoute = currentRoute,
                    onSelectTab = { route -> navController.navigateToTab(route) },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.PERMISSIONS,
            modifier = Modifier.padding(innerPadding),
        ) {
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onComplete = {
                    navController.navigate(Routes.PATH) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
                viewModel = permissionsViewModel,
            )
        }
        composable(Routes.PATH) {
            org.walktalkmeditate.pilgrim.ui.path.WalkStartScreen(
                onEnterActiveWalk = {
                    navController.navigate(Routes.ACTIVE_WALK) {
                        launchSingleTop = true
                    }
                },
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
        composable(Routes.SETTINGS) {
            // Stage 9.5-A: Settings is now a tab destination. No back
            // arrow when reached as a tab — onBack = null.
            SettingsScreen(
                onBack = null,
                onOpenVoiceGuides = {
                    navController.navigate(Routes.VOICE_GUIDE_PICKER) {
                        launchSingleTop = true
                    }
                },
                onOpenSoundscapes = {
                    navController.navigate(Routes.SOUNDSCAPE_PICKER) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.SOUNDSCAPE_PICKER) {
            SoundscapePickerScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.VOICE_GUIDE_PICKER) {
            VoiceGuidePickerScreen(
                onBack = { navController.popBackStack() },
                onOpenPack = { packId ->
                    // Same nav pattern as Goshuin → Summary: launchSingleTop
                    // plus popUpTo(picker) so cross-pack double-tap never
                    // stacks two detail screens.
                    navController.navigate(Routes.voiceGuideDetail(packId)) {
                        launchSingleTop = true
                        popUpTo(Routes.VOICE_GUIDE_PICKER) { inclusive = false }
                    }
                },
            )
        }
        composable(
            route = Routes.VOICE_GUIDE_DETAIL_PATTERN,
            arguments = listOf(
                navArgument(VoiceGuidePackDetailViewModel.ARG_PACK_ID) {
                    type = NavType.StringType
                },
            ),
        ) {
            VoiceGuidePackDetailScreen(
                onBack = { navController.popBackStack() },
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
        ) { entry ->
            val walkId = entry.arguments?.getLong(WalkSummaryViewModel.ARG_WALK_ID) ?: 0L
            WalkSummaryScreen(
                onDone = {
                    navController.popBackStack(Routes.HOME, inclusive = false)
                },
                onShareJourney = {
                    navController.navigate(Routes.walkShare(walkId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(
            route = Routes.WALK_SHARE_PATTERN,
            arguments = listOf(
                navArgument(org.walktalkmeditate.pilgrim.ui.walk.share.WalkShareViewModel.ARG_WALK_ID) {
                    type = NavType.LongType
                },
            ),
        ) {
            org.walktalkmeditate.pilgrim.ui.walk.share.WalkShareScreen(
                onDone = { navController.popBackStack() },
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
    }

    val onboardingComplete by permissionsViewModel.onboardingComplete.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(onboardingComplete, currentEntry?.destination?.route) {
        if (
            onboardingComplete &&
            PermissionChecks.isMinimumGranted(context) &&
            currentEntry?.destination?.route == Routes.PERMISSIONS
        ) {
            // Stage 9.5-A: Path is now the default destination
            // post-onboarding. Auto-nav to PATH lands the user on the
            // contemplative pre-walk hub.
            navController.navigate(Routes.PATH) {
                popUpTo(Routes.PERMISSIONS) { inclusive = true }
            }
        }
    }

    // Stage 9-A/B: handle widget + notification deep links.
    //
    // ActiveWalk fires UNCONDITIONALLY (the target IS an active-session
    // route, so there's nothing to "disrupt"). If the user is already on
    // ACTIVE_WALK or MEDITATION, we no-op the navigate and consume the
    // link — pulling someone out of meditation to land on the active
    // walk screen is the wrong UX even though both are active-session
    // routes.
    //
    // WalkSummary + Home are passive deep-links (Stage 9-A widget). They
    // sit BELOW the isActiveSession early-return so a widget tap never
    // yanks the user out of an in-progress walk or meditation.
    //
    // popUpTo(HOME) on the navigate keeps the back stack consistent:
    // [HOME, ACTIVE_WALK] or [HOME, WalkSummary] so back press lands on
    // the journal scroll regardless of the entry point.
    LaunchedEffect(pendingDeepLink, currentEntry?.destination?.route) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        val currentRoute = currentEntry?.destination?.route ?: return@LaunchedEffect
        if (currentRoute == Routes.PERMISSIONS) {
            // Auto-nav to PATH is in flight; wait for it to land
            // before consuming the deep link.
            return@LaunchedEffect
        }
        if (link is org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.ActiveWalk) {
            val alreadyInSession = currentRoute == Routes.ACTIVE_WALK ||
                currentRoute == Routes.MEDITATION
            if (!alreadyInSession) {
                // Stage 9.5-A: popUpTo PATH (default destination).
                // Back from a deep-linked ACTIVE_WALK lands on Path —
                // Path's didCheck latch ensures the auto-redirect fires
                // at most once, so no Back-loop.
                navController.navigate(Routes.ACTIVE_WALK) {
                    popUpTo(Routes.PATH) { saveState = false }
                    launchSingleTop = true
                }
            }
            onDeepLinkConsumed()
            return@LaunchedEffect
        }
        val isActiveSession = currentRoute == Routes.ACTIVE_WALK ||
            currentRoute == Routes.MEDITATION
        if (isActiveSession) {
            // Drop the deep link silently — never disrupt an in-
            // progress walk or meditation for a widget tap.
            onDeepLinkConsumed()
            return@LaunchedEffect
        }
        when (link) {
            is org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.WalkSummary -> {
                // popUpTo HOME — Back from a deep-linked summary lands
                // in the journal context (where finished walks live),
                // not on Path.
                navController.navigate(Routes.walkSummary(link.walkId)) {
                    popUpTo(Routes.HOME) { saveState = false }
                    launchSingleTop = true
                }
            }
            org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.Home -> {
                if (currentRoute != Routes.HOME) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            }
            org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.ActiveWalk -> {
                // Handled above; unreachable (kept for exhaustiveness).
            }
        }
        onDeepLinkConsumed()
    }
}
