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

@Composable
fun PilgrimNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    permissionsViewModel: PermissionsViewModel = hiltViewModel(),
    pendingDeepLink: org.walktalkmeditate.pilgrim.widget.DeepLinkTarget? = null,
    onDeepLinkConsumed: () -> Unit = {},
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
                onEnterSettings = {
                    navController.navigate(Routes.SETTINGS) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
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

    // Stage 9-A: handle widget deep links. Allowlist:
    // - HOME (journal scroll) — primary case.
    // - WALK_SUMMARY — user is browsing one summary; allowing nav from
    //   here lets a multi-instance widget setup (e.g., widget A on
    //   home screen + widget B on lockscreen) work as expected: tap
    //   widget A → summary 7, tap widget B → summary 11, both work.
    //
    // Active sessions (ACTIVE_WALK / MEDITATION / GOSHUIN /
    // SOUNDSCAPE_PICKER / VOICE_GUIDE_PICKER / WALK_SHARE) drop the
    // deep link silently — never yank the user out of an active
    // session for a widget tap.
    //
    // popUpTo(HOME) on the navigate keeps the back stack as
    // [HOME, WalkSummary] so back press lands on the journal scroll
    // regardless of the entry point.
    LaunchedEffect(pendingDeepLink, currentEntry?.destination?.route) {
        val link = pendingDeepLink ?: return@LaunchedEffect
        val currentRoute = currentEntry?.destination?.route ?: return@LaunchedEffect
        val allowedToNavigate = currentRoute == Routes.HOME ||
            currentRoute == Routes.WALK_SUMMARY_PATTERN
        if (!allowedToNavigate) {
            if (currentRoute != Routes.PERMISSIONS) {
                // PERMISSIONS will auto-nav to HOME via the effect
                // above; don't consume yet. Other non-allowed routes
                // are user-driven sessions; drop the deep link.
                onDeepLinkConsumed()
            }
            return@LaunchedEffect
        }
        when (link) {
            is org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.WalkSummary -> {
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
        }
        onDeepLinkConsumed()
    }
}
