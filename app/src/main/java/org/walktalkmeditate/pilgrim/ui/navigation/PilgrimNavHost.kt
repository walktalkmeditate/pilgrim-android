// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import android.content.Context
import android.util.Log
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.walktalkmeditate.pilgrim.permissions.AppSettings
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinScreen
import org.walktalkmeditate.pilgrim.ui.home.HomeScreen
import org.walktalkmeditate.pilgrim.ui.meditation.MeditationScreen
import org.walktalkmeditate.pilgrim.ui.onboarding.PermissionsScreen
import org.walktalkmeditate.pilgrim.ui.recordings.RecordingsListScreen
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.SettingsScreen
import org.walktalkmeditate.pilgrim.ui.settings.soundscape.SoundscapePickerScreen
import org.walktalkmeditate.pilgrim.ui.settings.sounds.SoundSettingsScreen
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
    const val FEEDBACK = "feedback"
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
    const val SOUND_SETTINGS = "sound_settings"
    const val RECORDINGS_LIST = "recordings"

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
 * Compose Nav's tab-switch idiom adapted for our PERMISSIONS-then-PATH
 * graph. PATH is the *effective* root after the post-onboarding
 * inclusive-pop of PERMISSIONS removes PERMISSIONS from the stack.
 * Using `findStartDestination()` (= PERMISSIONS) here would no-op the
 * popUpTo (PERMISSIONS isn't on the stack), making each tab tap PUSH
 * a new entry → unbounded stack growth. Hard-coding PATH ensures the
 * pop actually fires + saveState/restoreState do their work.
 */
internal fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(Routes.PATH) { saveState = true }
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
            // Combine fade with vertical expand/shrink so the bar's
            // measured height animates in lockstep with its alpha.
            // Without expand/shrink, AnimatedVisibility flips height
            // 0 → N instantly while the alpha fades over 150ms,
            // causing screen content to snap up/down before the bar
            // visibly appears/disappears.
            AnimatedVisibility(
                visible = showBottomBar,
                enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
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
            // Stage 9.5-A: Settings is now a tab destination. The
            // top bar / back arrow was dropped in Stage 10-A so the
            // scroll content can host a centered title (matches iOS).
            //
            // Stage 10-A: navigation funnels through SettingsAction.
            // Stage 10-B onward will route additional destinations
            // (Bells & Soundscapes, Recordings, Export/Import,
            // Feedback, About, Podcast, Play Store, Share Pilgrim);
            // see [handleSettingsAction] for the routing hub.
            val settingsContext = LocalContext.current
            SettingsScreen(
                onAction = { action ->
                    handleSettingsAction(action, navController, settingsContext)
                },
            )
        }
        composable(Routes.SOUNDSCAPE_PICKER) {
            SoundscapePickerScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.RECORDINGS_LIST) {
            // Stage 10-D: Recordings list reachable from VoiceCard's
            // Recordings nav row (SettingsAction.OpenRecordings).
            // Tapping a section header navigates to that walk's
            // WalkSummary; launchSingleTop guards a double-tap from
            // pushing two summary entries.
            RecordingsListScreen(
                onBack = { navController.popBackStack() },
                onWalkClick = { walkId ->
                    navController.navigate(Routes.walkSummary(walkId)) {
                        launchSingleTop = true
                    }
                },
            )
        }
        composable(Routes.SOUND_SETTINGS) {
            // Stage 10-B: Bells & Soundscapes sub-screen. Routed from
            // AtmosphereCard's conditional nav row via
            // SettingsAction.OpenBellsAndSoundscapes. The screen reuses
            // SettingsAction.OpenSoundscapes for its embedded
            // soundscape selector, hopping into the existing
            // SoundscapePickerScreen rather than duplicating that UI.
            val soundsContext = LocalContext.current
            SoundSettingsScreen(
                onAction = { action ->
                    handleSettingsAction(action, navController, soundsContext)
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FEEDBACK) {
            org.walktalkmeditate.pilgrim.ui.settings.connect.FeedbackScreen(
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
                    // Stage 9.5-A: a walk launched from Path leaves HOME
                    // off the back stack. popUpTo(HOME) would no-op +
                    // leave ACTIVE_WALK in the stack. Pop to PATH (the
                    // effective root) so [PATH, walkSummary] is the
                    // resulting stack — Done returns to PATH which is
                    // adjacent to the Journal tab.
                    navController.navigate(Routes.walkSummary(walkId)) {
                        popUpTo(Routes.PATH) { inclusive = false }
                        launchSingleTop = true
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
                onDiscarded = {
                    // Stage 9.5-C polish fix: discardWalk transitions
                    // Active → Idle. Without an explicit pop, the user
                    // is stranded on a frozen ActiveWalk map over a
                    // cascade-deleted walk row. The Path-launched stack
                    // is [PATH, ACTIVE_WALK]; popping ACTIVE_WALK lands
                    // on PATH (WalkStartScreen), which matches the
                    // contemplative pre-walk hub the user expects after
                    // leaving a walk.
                    navController.popBackStack(Routes.PATH, inclusive = false)
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
                    //
                    // Defensive fallback: today MEDITATION can only be
                    // reached FROM ACTIVE_WALK so popBackStack(ACTIVE_WALK)
                    // always succeeds. A future code path that opens
                    // MEDITATION via deep-link or a different surface
                    // would silently no-op the back-out without this
                    // single-pop fallback, leaving the user stranded.
                    if (!navController.popBackStack(Routes.ACTIVE_WALK, inclusive = false)) {
                        navController.popBackStack()
                    }
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
                    // Done always lands the user on the Journal (HOME)
                    // tab, regardless of how walkSummary was reached:
                    //  - Path-launched walk: stack is [PATH, walkSummary].
                    //    popBackStack(HOME) returns false → fall through
                    //    to the Path-launch branch below.
                    //  - HOME-launched: stack [PATH, HOME, walkSummary].
                    //    popBackStack(HOME) pops walkSummary, lands on HOME.
                    //  - Goshuin-launched: stack [PATH, HOME, GOSHUIN,
                    //    walkSummary]. popBackStack(HOME) pops both
                    //    walkSummary AND GOSHUIN — user lands on HOME, not
                    //    Goshuin. This is intentional: "Done" is the user
                    //    saying "I'm done; show me the Journal." Re-opening
                    //    Goshuin is a one-FAB-tap away.
                    //
                    // Stage 9.5-B device-QA fix: for Path-launched walks,
                    // we MUST pop walkSummary off PATH's stack before
                    // navigateToTab(HOME), otherwise navigateToTab's
                    // `popUpTo(PATH){saveState=true}` captures walkSummary
                    // as part of PATH's tab state. The next Path-tab tap
                    // then restores [PATH, walkSummary] instead of the
                    // bare [PATH] (WalkStartScreen) the user expects —
                    // they get stuck in a Done → Path → walkSummary loop.
                    if (!navController.popBackStack(Routes.HOME, inclusive = false)) {
                        navController.popBackStack(Routes.PATH, inclusive = false)
                        navController.navigateToTab(Routes.HOME)
                    }
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
                // Stage 9.5-A: popUpTo PATH (effective root). Back from
                // a deep-linked ACTIVE_WALK is intercepted by
                // ActiveWalkScreen's existing BackHandler (moveTaskToBack
                // while in-progress), so we don't bounce back to PATH.
                // launchSingleTop is the dedup mechanism for any
                // accidental concurrent navigate(ACTIVE_WALK) calls.
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
                // popUpTo PATH (effective root). Back from a deep-linked
                // summary lands on Path; Done navigates to HOME via
                // navigateToTab. HOME may not be on the back stack
                // (cold-launch with widget tap → only PATH is there).
                navController.navigate(Routes.walkSummary(link.walkId)) {
                    popUpTo(Routes.PATH) { saveState = false }
                    launchSingleTop = true
                }
            }
            org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.Home -> {
                if (currentRoute != Routes.HOME) {
                    navController.navigateToTab(Routes.HOME)
                }
            }
            org.walktalkmeditate.pilgrim.widget.DeepLinkTarget.ActiveWalk -> {
                // Handled above; unreachable (kept for exhaustiveness).
            }
        }
        onDeepLinkConsumed()
    }
}

/**
 * Routing hub for [SettingsAction]. The [Context] parameter is plumbed
 * through now (unused by Stage 10-A) so subsequent stages adding
 * intent-based destinations (Custom Tabs, Play Store deep link, share
 * sheet) don't need to re-touch the SettingsScreen call site.
 *
 * The exhaustive `when` block captures every variant declared on
 * [SettingsAction]; reserved variants no-op until the corresponding
 * card lands.
 */
@Suppress("UNUSED_PARAMETER")
private fun handleSettingsAction(
    action: SettingsAction,
    navController: NavController,
    context: Context,
) {
    when (action) {
        SettingsAction.OpenVoiceGuides ->
            navController.navigate(Routes.VOICE_GUIDE_PICKER) { launchSingleTop = true }
        SettingsAction.OpenSoundscapes ->
            navController.navigate(Routes.SOUNDSCAPE_PICKER) { launchSingleTop = true }
        SettingsAction.OpenBellsAndSoundscapes ->
            navController.navigate(Routes.SOUND_SETTINGS) { launchSingleTop = true }
        SettingsAction.OpenRecordings ->
            navController.navigate(Routes.RECORDINGS_LIST) { launchSingleTop = true }
        SettingsAction.OpenAppPermissionSettings ->
            context.startActivity(AppSettings.openDetailsIntent(context))
        SettingsAction.OpenFeedback ->
            navController.navigate(Routes.FEEDBACK) { launchSingleTop = true }
        SettingsAction.OpenPodcast ->
            org.walktalkmeditate.pilgrim.ui.util.CustomTabs.launch(
                context,
                android.net.Uri.parse("https://podcast.pilgrimapp.org"),
            )
        SettingsAction.OpenPlayStoreReview ->
            org.walktalkmeditate.pilgrim.ui.util.PlayStore.openListing(context)
        SettingsAction.SharePilgrim ->
            org.walktalkmeditate.pilgrim.ui.util.ShareIntents.sharePilgrim(context)
        // The remaining destinations are introduced in subsequent stages.
        // Keeping them in the sealed interface NOW lets each card author
        // drop in its own action without re-touching the SettingsScreen
        // signature; the routing handler grows exhaustively as cards
        // land. The Log.w turns silent swallowing into a discoverable
        // signal during manual QA — if a future card author wires a row
        // but forgets to update this hub, the tap will log instead of
        // vanishing into the void.
        SettingsAction.OpenExportImport,        // STAGE 10-G
        SettingsAction.OpenJourneyViewer,       // STAGE 10-G
        SettingsAction.OpenAbout ->             // STAGE 10-H
            Log.w(TAG_NAV, "Unhandled SettingsAction: $action — wire in the corresponding stage")
    }
}

private const val TAG_NAV = "PilgrimNav"
