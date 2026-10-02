// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.dialog
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import org.walktalkmeditate.pilgrim.domain.WalkMode
import org.walktalkmeditate.pilgrim.honor.HonorLinkScreen
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.permissions.AppSettings
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinScreen
import org.walktalkmeditate.pilgrim.ui.honor.HonorImportHostViewModel
import org.walktalkmeditate.pilgrim.ui.honor.HonorOverviewViewModel
import org.walktalkmeditate.pilgrim.ui.home.HomeScreen
import org.walktalkmeditate.pilgrim.ui.meditation.MeditationScreen
import org.walktalkmeditate.pilgrim.ui.onboarding.PermissionsScreen
import org.walktalkmeditate.pilgrim.ui.path.RecoveryBanner
import org.walktalkmeditate.pilgrim.ui.recordings.RecordingsListScreen
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.SettingsScreen
import org.walktalkmeditate.pilgrim.ui.settings.soundscape.SoundscapePickerScreen
import org.walktalkmeditate.pilgrim.ui.settings.sounds.SoundSettingsScreen
import org.walktalkmeditate.pilgrim.ui.settings.voiceguide.VoiceGuidePackDetailScreen
import org.walktalkmeditate.pilgrim.ui.settings.voiceguide.VoiceGuidePackDetailViewModel
import org.walktalkmeditate.pilgrim.ui.settings.voiceguide.VoiceGuidePickerScreen
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.walk.ActiveWalkScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryScreen
import org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel

/**
 * App-wide HazeState for backdrop blur on the floating pill bar AND
 * the sticky screen headers. Each screen's scrolling content marks
 * itself as a hazeSource(LocalAppHazeState.current); the pill +
 * headers consume it via hazeEffect.
 */
val LocalAppHazeState = compositionLocalOf { HazeState() }

object Routes {
    const val WELCOME = "welcome"
    const val PERMISSIONS = "permissions"
    const val BREATH = "breath"
    const val PATH = "path"
    const val HOME = "home"

    /**
     * ACTIVE_WALK is the route PATTERN (query-arg style) so every
     * existing `currentRoute == Routes.ACTIVE_WALK` comparison and
     * `popBackStack(Routes.ACTIVE_WALK, ...)` keeps matching the
     * destination — `NavDestination.route` reports the pattern, not the
     * filled route. Navigate with [activeWalk] to carry a mode; a bare
     * pattern-less navigate falls back to the Wander default argument.
     */
    const val ACTIVE_WALK_ARG_MODE = "mode"

    /** The walk an Honor walk follows, from an own walk's overview Begin; absent for every other walk. */
    const val ACTIVE_WALK_ARG_HONOR_SOURCE = "honorSource"

    /** The listed Way an Honor walk follows, from a shared Way's overview Begin; absent for every other walk. */
    const val ACTIVE_WALK_ARG_HONOR_WAY = "honorWay"
    const val ACTIVE_WALK =
        "active_walk?$ACTIVE_WALK_ARG_MODE={$ACTIVE_WALK_ARG_MODE}" +
            "&$ACTIVE_WALK_ARG_HONOR_SOURCE={$ACTIVE_WALK_ARG_HONOR_SOURCE}" +
            "&$ACTIVE_WALK_ARG_HONOR_WAY={$ACTIVE_WALK_ARG_HONOR_WAY}"
    fun activeWalk(mode: WalkMode, honorWay: HonorWayChoice? = null): String =
        "active_walk?$ACTIVE_WALK_ARG_MODE=${mode.name}" + when (honorWay) {
            is HonorWayChoice.OwnWalk -> "&$ACTIVE_WALK_ARG_HONOR_SOURCE=${honorWay.sourceWalkId}"
            is HonorWayChoice.Stored -> "&$ACTIVE_WALK_ARG_HONOR_WAY=${Uri.encode(honorWay.wayId)}"
            null -> ""
        }
    const val FEEDBACK = "feedback"
    const val GOSHUIN = "goshuin"
    const val MEDITATION = "meditation"
    private const val WALK_SUMMARY_PREFIX = "walk_summary"

    /** Whether the summary's host can open the Honor overview, so it shows "walk this again". */
    const val WALK_SUMMARY_ARG_WALK_AGAIN = "walkAgain"
    const val WALK_SUMMARY_PATTERN =
        "$WALK_SUMMARY_PREFIX/{${WalkSummaryViewModel.ARG_WALK_ID}}" +
            "?$WALK_SUMMARY_ARG_WALK_AGAIN={$WALK_SUMMARY_ARG_WALK_AGAIN}"
    fun walkSummary(walkId: Long, walkAgainDoor: Boolean = false): String =
        "$WALK_SUMMARY_PREFIX/$walkId" + if (walkAgainDoor) "?$WALK_SUMMARY_ARG_WALK_AGAIN=true" else ""

    /**
     * The Ways sheet, the "Walk again" picker, and the overview: reachable
     * only with Honor on. The overview names an own walk to rebuild or a
     * listed Way to read back, so either survives a process death.
     */
    const val HONOR_WAYS = "honor_ways"
    const val HONOR_OWN_WALKS = "honor_own_walks"
    private const val HONOR_OVERVIEW_PREFIX = "honor_overview"
    const val HONOR_OVERVIEW_PATTERN =
        "$HONOR_OVERVIEW_PREFIX?${HonorOverviewViewModel.ARG_SOURCE_WALK_ID}={${HonorOverviewViewModel.ARG_SOURCE_WALK_ID}}" +
            "&${HonorOverviewViewModel.ARG_WAY_ID}={${HonorOverviewViewModel.ARG_WAY_ID}}"
    fun honorOverview(way: HonorWayChoice): String = "$HONOR_OVERVIEW_PREFIX?" + when (way) {
        is HonorWayChoice.OwnWalk -> "${HonorOverviewViewModel.ARG_SOURCE_WALK_ID}=${way.sourceWalkId}"
        is HonorWayChoice.Stored -> "${HonorOverviewViewModel.ARG_WAY_ID}=${Uri.encode(way.wayId)}"
    }

    const val SETTINGS = "settings"
    const val VOICE_GUIDE_PICKER = "voice_guides"
    private const val VOICE_GUIDE_DETAIL_PREFIX = "voice_guide"
    const val VOICE_GUIDE_DETAIL_PATTERN =
        "$VOICE_GUIDE_DETAIL_PREFIX/{${VoiceGuidePackDetailViewModel.ARG_PACK_ID}}"
    fun voiceGuideDetail(packId: String): String = "$VOICE_GUIDE_DETAIL_PREFIX/$packId"

    const val SOUNDSCAPE_PICKER = "soundscapes"
    const val SOUND_SETTINGS = "sound_settings"
    const val RECORDINGS_LIST = "recordings"
    const val DATA_SETTINGS = "data_settings"
    const val WAYS_LIST = "ways_list"
    const val JOURNEY_VIEWER = "journey_viewer"
    const val JOURNEY_EDITOR = "journey_editor"
    const val ABOUT = "about"
    const val APPEARANCE = "appearance"

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
    /**
     * iOS parity v1.6.0 Welcome ritual. When `false`, the start
     * destination is [Routes.WELCOME] so first-launch users see the
     * breathing-logo + footprints + privacy-promise sequence before
     * the Permissions prompt. Subsequent launches read `true` and skip
     * straight to PERMISSIONS (which itself skips to PATH if the
     * required perms are already granted).
     */
    welcomeCompleted: Boolean = true,
    /** The 2.0.0 release flag: off, no Honor route exists and no door leads to one (AE12). */
    honorEnabled: Boolean = false,
    /** A walk the swipe from Recents finalized: the recovery banner shows over the Path tab until [onRecoveryBannerDone]. */
    walkRecovered: Boolean = false,
    onRecoveryBannerDone: () -> Unit = {},
) {
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val showBottomBar = currentRoute in TAB_ROUTES
    // Every move the Honor routing tracks changes the top entry, which recomposes this.
    val backStackRoutes = navController.honorBackStack(currentRoute)
    // Activity-scoped, so the graph builder below captures one stable instance.
    val honorHost: HonorImportHostViewModel? = if (honorEnabled) hiltViewModel() else null

    val appHazeState = remember { HazeState() }
    CompositionLocalProvider(LocalAppHazeState provides appHazeState) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors.parchment,
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
        // NavHost extends edge-to-edge — the floating pill overlays on
        // top via Box.align(BottomCenter) so screen content (parchment
        // canvas, journal calligraphy, scroll lists) renders behind the
        // pill region, picking up the haze backdrop blur. Screens with
        // primary actions near the bottom add their own bottom padding
        // (Path's Wander button, etc.) to clear the pill footprint.
        NavHost(
            navController = navController,
            startDestination = if (welcomeCompleted) Routes.PERMISSIONS else Routes.WELCOME,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(appHazeState),
        ) {
        composable(Routes.WELCOME) {
            org.walktalkmeditate.pilgrim.ui.onboarding.WelcomeScreen(
                onBegin = {
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                onComplete = {
                    navController.navigate(Routes.BREATH) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
                viewModel = permissionsViewModel,
            )
        }
        // iOS parity `SetupCoordinatorView.swift` — a single breath beat
        // between granting permissions and entering the app.
        composable(Routes.BREATH) {
            org.walktalkmeditate.pilgrim.ui.onboarding.BreathTransitionScreen(
                onComplete = {
                    navController.navigate(Routes.PATH) {
                        popUpTo(Routes.BREATH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.PATH) {
            org.walktalkmeditate.pilgrim.ui.path.WalkStartScreen(
                onEnterActiveWalk = { mode ->
                    navController.navigate(Routes.activeWalk(mode)) {
                        launchSingleTop = true
                    }
                },
                onChooseWay = {
                    if (honorEnabled) {
                        navController.navigate(Routes.HONOR_WAYS) { launchSingleTop = true }
                    }
                },
                honorEnabled = honorEnabled,
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
                    navController.navigate(Routes.walkSummary(walkId, walkAgainDoor = honorEnabled)) {
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
        composable(Routes.DATA_SETTINGS) {
            val dataSettingsContext = LocalContext.current
            org.walktalkmeditate.pilgrim.ui.settings.data.DataSettingsScreen(
                onBack = { navController.popBackStack() },
                onAction = { action ->
                    handleSettingsAction(action, navController, dataSettingsContext)
                },
            )
        }
        composable(Routes.WAYS_LIST) {
            // Popped by name: the screen also leaves on its own when a walk starts.
            org.walktalkmeditate.pilgrim.ui.settings.data.WaysListScreen(
                onBack = { navController.popBackStack(Routes.WAYS_LIST, inclusive = true) },
            )
        }
        composable(Routes.FEEDBACK) {
            org.walktalkmeditate.pilgrim.ui.settings.connect.FeedbackScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.JOURNEY_VIEWER) {
            org.walktalkmeditate.pilgrim.ui.settings.data.JourneyViewerScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.JOURNEY_EDITOR) {
            org.walktalkmeditate.pilgrim.ui.settings.data.JourneyEditorScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            org.walktalkmeditate.pilgrim.ui.settings.about.AboutScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.APPEARANCE) {
            org.walktalkmeditate.pilgrim.ui.settings.AppearanceScreen(
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
        composable(
            Routes.ACTIVE_WALK,
            arguments = activeWalkArguments,
        ) { backStackEntry ->
            val honorWay = honorWayOf(backStackEntry.arguments)?.takeIf { honorEnabled }
            val walkMode = activeWalkMode(
                WalkMode.fromWire(backStackEntry.arguments?.getString(Routes.ACTIVE_WALK_ARG_MODE)),
                honorWay,
            )
            ActiveWalkScreen(
                mode = walkMode,
                honorWay = honorWay,
                onFinished = { walkId ->
                    // Stage 9.5-A: a walk launched from Path leaves HOME
                    // off the back stack. popUpTo(HOME) would no-op +
                    // leave ACTIVE_WALK in the stack. Pop to PATH (the
                    // effective root) so [PATH, walkSummary] is the
                    // resulting stack — Done returns to PATH which is
                    // adjacent to the Journal tab.
                    navController.navigate(Routes.walkSummary(walkId, walkAgainDoor = honorEnabled)) {
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
                onOpenSoundscapePicker = {
                    // iOS parity `MeditationView.swift:293-333` — the
                    // soundscape affordance (tap when Silence /
                    // long-press always) opens the soundscape picker.
                    navController.navigate(Routes.SOUNDSCAPE_PICKER) {
                        launchSingleTop = true
                    }
                },
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
                navArgument(Routes.WALK_SUMMARY_ARG_WALK_AGAIN) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { entry ->
            val walkId = entry.arguments?.getLong(WalkSummaryViewModel.ARG_WALK_ID) ?: 0L
            val hostOffersWalkAgain = honorEnabled &&
                entry.arguments?.getBoolean(Routes.WALK_SUMMARY_ARG_WALK_AGAIN) == true
            // Stage 5: present Walk Summary as a Dialog so the host screen
            // (Home / Path / Recordings / Goshuin) stays behind it instead
            // of being replaced — matches iOS .sheet semantics. The Dialog's
            // onDismissRequest handles the system back gesture; the existing
            // onDone lambda body is hoisted out so the Done button and the
            // Dialog's dismiss share the same handler.
            val onDone: () -> Unit = remember(navController) {
                {
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
                }
            }
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            )
            val sheetScope = rememberCoroutineScope()
            // Done-tap previously popped the back stack while the sheet
            // was still fully expanded — Material3 then tore the sheet
            // down abruptly, a visible "tap … nothing … jump" delay.
            // Animate the sheet down first (immediate visual feedback),
            // then pop on completion. onDismissRequest (swipe/scrim)
            // already animated, so it pops directly.
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            val dismissAndDone: () -> Unit = remember(sheetState) {
                {
                    sheetScope.launch { sheetState.hide() }
                        .invokeOnCompletion {
                            if (!sheetState.isVisible) onDone()
                        }
                }
            }
            @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = onDone,
                sheetState = sheetState,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(
                    topStart = 20.dp,
                    topEnd = 20.dp,
                ),
                dragHandle = null,
                containerColor = org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors.parchmentSecondary,
                contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0) },
            ) {
                WalkSummaryScreen(
                    onDone = dismissAndDone,
                    onShareJourney = {
                        navController.navigate(Routes.walkShare(walkId)) {
                            launchSingleTop = true
                        }
                    },
                    // iOS `walkAgain`: the summary closes, then the overview
                    // opens over whatever hosted it, never over the summary;
                    // a walk with no Way only closes it (F §6.2, F-1 matched).
                    // Either way it overwrites a link's Way parked behind the
                    // summary, nil included, so that Way never opens.
                    onWalkAgain = if (hostOffersWalkAgain) {
                        { result ->
                            if (result.built) {
                                sheetScope.launch { sheetState.hide() }.invokeOnCompletion {
                                    navController.openHonorOverviewFromSummary(result.sourceWalkId)
                                }
                            } else {
                                honorHost?.dropParkedWay()
                                dismissAndDone()
                            }
                        }
                    } else {
                        null
                    },
                )
            }
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
                    navController.navigate(Routes.walkSummary(walkId, walkAgainDoor = honorEnabled)) {
                        launchSingleTop = true
                        popUpTo(Routes.GOSHUIN) { inclusive = false }
                    }
                },
            )
        }
        if (honorEnabled) {
            honorRoutes(navController)
        }
        }

        // iOS parity v1.6.0: constellation overlay painted on top of
        // every screen — stars + nebulae + cosmic gradient render when
        // AppearanceMode == Constellation. Nebulae are SUPPRESSED on
        // Active Walk + Walk Summary + Meditation (and Walk Share) so
        // the purple/blue clouds don't clash with the dense Mapbox map
        // and warm parchmentSecondary cards. Stars + cosmic gradient
        // still render there.
        val noNebulaeRoutes = remember {
            setOf(Routes.ACTIVE_WALK, Routes.MEDITATION)
        }
        val nebulaeOn = when {
            currentRoute == null -> true
            currentRoute in noNebulaeRoutes -> false
            currentRoute.startsWith("walk_summary") -> false
            currentRoute.startsWith("walk_share") -> false
            currentRoute.startsWith("honor_overview") -> false
            else -> true
        }
        org.walktalkmeditate.pilgrim.ui.design
            .ConstellationDecoration(includesNebulae = nebulaeOn)

        // iOS's one top overlay on the tab view (`MainTabView.swift:146-159@7c200bf`,
        // S2 §7.2): the recovery banner, then the link toast under it, 4 apart.
        // One host for every screen, so a toast that rises over one tab rides
        // the switch to another rather than starting again.
        Column(
            modifier = Modifier.align(Alignment.TopCenter),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
        ) {
            RecoveryBanner(
                visible = walkRecovered && currentRoute == Routes.PATH,
                onDismiss = onRecoveryBannerDone,
            )
            if (honorHost != null) LinkToastHost(honorHost, shows = linkToastShows(backStackRoutes))
        }

        // Pill overlays the screen content at BottomCenter — content
        // extends edge-to-edge behind the pill so the area around the
        // pill is whatever the screen renders (parchment canvas, journal
        // dots, calligraphy, etc.) — not a reserved opaque band.
        AnimatedVisibility(
            visible = showBottomBar,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(150)) + shrinkVertically(animationSpec = tween(150)),
        ) {
            PilgrimBottomBar(
                currentRoute = currentRoute,
                onSelectTab = { route -> navController.navigateToTab(route) },
            )
        }
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

    if (honorHost != null) {
        HonorLinksLanding(navController = navController, backStack = backStackRoutes, host = honorHost)
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
                // Deep links always navigate with the Wander default —
                // the mode arg only drives the pre-walk seek setup, and
                // deep links target an already-running walk whose mode
                // lives on the accumulator, not the nav arg.
                navController.navigate(
                    Routes.activeWalk(WalkMode.Wander),
                ) {
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
                navController.navigate(Routes.walkSummary(link.walkId, walkAgainDoor = honorEnabled)) {
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

/** The `honorSource` default: no own walk to follow. */
internal const val NO_HONOR_SOURCE = -1L

/** The walk screen's arguments; tests build their graph from the same list. */
internal val activeWalkArguments = listOf(
    navArgument(Routes.ACTIVE_WALK_ARG_MODE) {
        type = NavType.StringType
        defaultValue = WalkMode.Wander.name
    },
    navArgument(Routes.ACTIVE_WALK_ARG_HONOR_SOURCE) {
        type = NavType.LongType
        defaultValue = NO_HONOR_SOURCE
    },
    navArgument(Routes.ACTIVE_WALK_ARG_HONOR_WAY) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

/** The overview's arguments: exactly one of the two is set. */
internal val honorOverviewArguments = listOf(
    navArgument(HonorOverviewViewModel.ARG_SOURCE_WALK_ID) {
        type = NavType.LongType
        defaultValue = HonorOverviewViewModel.NO_SOURCE_WALK
    },
    navArgument(HonorOverviewViewModel.ARG_WAY_ID) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    },
)

/** The Way the walk screen's arguments name, if any: a listed Way, or an own walk to rebuild. */
internal fun honorWayOf(arguments: Bundle?): HonorWayChoice? {
    arguments ?: return null
    arguments.getString(Routes.ACTIVE_WALK_ARG_HONOR_WAY)?.let { return HonorWayChoice.Stored(it) }
    return arguments.getLong(Routes.ACTIVE_WALK_ARG_HONOR_SOURCE, NO_HONOR_SOURCE)
        .takeIf { it != NO_HONOR_SOURCE }
        ?.let(HonorWayChoice::OwnWalk)
}

/**
 * An Honor walk needs the Way it follows; one without (a redirect into a
 * running walk, a flag-off build) is a plain walk screen, whose running
 * walk's mode lives on the accumulator anyway.
 */
internal fun activeWalkMode(mode: WalkMode, honorWay: HonorWayChoice?): WalkMode =
    if (mode == WalkMode.Honor && honorWay == null) WalkMode.Wander else mode

private val SETUP_ROUTES = setOf(Routes.WELCOME, Routes.PERMISSIONS, Routes.BREATH)

/** The routes the Honor routing asks the back stack about. */
private val HONOR_TRACKED_ROUTES = listOf(
    Routes.ACTIVE_WALK,
    Routes.HONOR_WAYS,
    Routes.WALK_SUMMARY_PATTERN,
    Routes.HONOR_OVERVIEW_PATTERN,
)

/**
 * Which tracked routes the back stack holds, with [currentRoute] last: the
 * list [honorLinkScreen] and [fetchedWayLanding] read. The full back stack
 * is a restricted API; [NavController.getBackStackEntry] answers per route.
 */
private fun NavController.honorBackStack(currentRoute: String?): List<String> =
    HONOR_TRACKED_ROUTES.filter(::hasBackStackEntry) + listOfNotNull(currentRoute)

/**
 * The back stack as the link routing reads it, [backStack] holding route
 * patterns with the current one last. Every route in front of the first
 * `PATH` is setup (S2 resolution 3), and so is a back stack the graph
 * hasn't filled yet.
 */
internal fun honorLinkScreen(backStack: List<String>): HonorLinkScreen {
    val current = backStack.lastOrNull()
    return HonorLinkScreen(
        inSetup = current == null || current in SETUP_ROUTES,
        atPath = current == Routes.PATH,
        walkScreenUp = Routes.ACTIVE_WALK in backStack,
        waysSheetUp = Routes.HONOR_WAYS in backStack,
        summaryUp = Routes.WALK_SUMMARY_PATTERN in backStack,
        overviewUp = Routes.HONOR_OVERVIEW_PATTERN in backStack,
    )
}

/** What a Way an import just listed does on the screen showing now. */
internal enum class FetchedWayLanding {
    /** Its overview opens over the Path tab. */
    PRESENT,

    /** It waits for this screen to go: the Ways sheet takes it itself, and setup holds it. */
    WAIT,

    /** It waits behind a summary, from any host, until the walker closes it (owner decision 5). */
    PARK,

    /** Nothing interrupts a walk, or a "walk this again" that took the summary's place (iOS drops it silently; the Way stays listed). */
    DROP,
}

/**
 * iOS `openWay`'s success and `openOverview` (S1 §6.3, S2 §4.3), over
 * [backStack] as [honorLinkScreen] reads it. A Way [parkedBehindSummary]
 * gives way to the overview "walk this again" opens in the summary's
 * place, as iOS's `walkAgain` overwrites the park.
 */
internal fun fetchedWayLanding(backStack: List<String>, parkedBehindSummary: Boolean = false): FetchedWayLanding {
    val screen = honorLinkScreen(backStack)
    return when {
        screen.walkScreenUp -> FetchedWayLanding.DROP
        screen.inSetup || backStack.lastOrNull() == Routes.HONOR_WAYS -> FetchedWayLanding.WAIT
        screen.summaryUp -> FetchedWayLanding.PARK
        parkedBehindSummary && screen.overviewUp -> FetchedWayLanding.DROP
        else -> FetchedWayLanding.PRESENT
    }
}

/**
 * Whether the app-wide toast shows over [backStack]'s screen. During a
 * walk only the walk screen shows it: not meditation (owner decision 3,
 * matching iOS's toast hidden under the meditation cover), nor anything
 * over it. An overview hides it, as iOS's overview sheet covers its toast;
 * the summary's sheet, a window of its own, covers it by itself.
 */
internal fun linkToastShows(backStack: List<String>): Boolean {
    val current = backStack.lastOrNull() ?: return false
    return when {
        Routes.ACTIVE_WALK in backStack -> current == Routes.ACTIVE_WALK
        current == Routes.HONOR_OVERVIEW_PATTERN -> false
        else -> true
    }
}

@Composable
private fun LinkToastHost(host: HonorImportHostViewModel, shows: Boolean) {
    val toast by host.linkToast.collectAsState()
    org.walktalkmeditate.pilgrim.ui.honor.HonorLinkToastView(toast = toast.takeIf { shows })
}

/**
 * The app's end of the links and a shared walk's import: the back stack
 * goes to the link routing, which holds a link through setup and cancels
 * an import as the walk screen opens; the Path switch it asks for is made
 * here; and a Way the import listed with no Ways sheet up to take it opens
 * its overview over the Path tab, or waits behind a summary. Its own
 * overview already showing stays, and gathers again.
 */
@Composable
private fun HonorLinksLanding(
    navController: NavHostController,
    backStack: List<String>,
    host: HonorImportHostViewModel,
) {
    val screen = honorLinkScreen(backStack)
    LaunchedEffect(screen) { host.screenChanged(screen) }
    DisposableEffect(host) { onDispose { host.screenGone() } }

    val pathSwitch by host.pathSwitch.collectAsState()
    LaunchedEffect(pathSwitch) {
        if (!pathSwitch) return@LaunchedEffect
        host.pathSwitchTaken()
        if (navController.currentDestination?.route != Routes.PATH) navController.navigateToTab(Routes.PATH)
    }

    val fetched by host.fetched.collectAsState()
    var parkedBehindSummary by remember { mutableStateOf(false) }
    LaunchedEffect(fetched, backStack) {
        val wayId = fetched
        if (wayId == null) {
            parkedBehindSummary = false
            return@LaunchedEffect
        }
        when (fetchedWayLanding(backStack, parkedBehindSummary)) {
            FetchedWayLanding.WAIT -> Unit
            FetchedWayLanding.PARK -> parkedBehindSummary = true
            FetchedWayLanding.DROP -> host.consumeFetched(wayId)
            FetchedWayLanding.PRESENT -> {
                host.consumeFetched(wayId)
                if (navController.showsStoredOverview(wayId)) {
                    host.gatherShownAgain(wayId)
                } else {
                    navController.openFetchedWayOverview(wayId)
                }
            }
        }
    }
}

/**
 * The overview showing is [wayId]'s. A link for that share keeps it, as
 * iOS's sheet keeps an item of the same id and only takes its new value
 * (`Way.swift:222@7c200bf`, S2 §5 rows 8–9): its scroll and any open
 * moment preview stay.
 */
internal fun NavController.showsStoredOverview(wayId: String): Boolean {
    val entry = currentBackStackEntry ?: return false
    return entry.destination.route == Routes.HONOR_OVERVIEW_PATTERN &&
        entry.arguments?.getString(HonorOverviewViewModel.ARG_WAY_ID) == wayId
}

private fun NavController.hasBackStackEntry(route: String): Boolean = try {
    getBackStackEntry(route)
    true
} catch (_: IllegalArgumentException) {
    false
}

/**
 * The Ways sheet → "Walk again" picker → overview → walk screen chain
 * (parity spec F §2–§12). Each step leaves the one before it: the sheets
 * are gone before the overview opens, and Back from the overview never
 * lands on a sheet. Begin closes the overview and opens the walk screen
 * before its Start.
 */
private fun androidx.navigation.NavGraphBuilder.honorRoutes(navController: NavHostController) {
    honorSheet(Routes.HONOR_WAYS) {
        org.walktalkmeditate.pilgrim.ui.honor.HonorWaysSheetRoute(
            onClosed = { navController.popBackStack(Routes.HONOR_WAYS, inclusive = true) },
            onOpenOwnWalks = {
                navController.navigate(Routes.HONOR_OWN_WALKS) { launchSingleTop = true }
            },
            onOpenOverview = navController::openStoredWayOverview,
        )
    }
    honorSheet(Routes.HONOR_OWN_WALKS) {
        org.walktalkmeditate.pilgrim.ui.honor.OwnWalkPickerRoute(
            onClosed = { navController.popBackStack(Routes.HONOR_OWN_WALKS, inclusive = true) },
            onOpenOverview = { sourceWalkId ->
                navController.navigate(Routes.honorOverview(HonorWayChoice.OwnWalk(sourceWalkId))) {
                    popUpTo(Routes.HONOR_WAYS) { inclusive = true }
                    launchSingleTop = true
                }
            },
        )
    }
    composable(
        route = Routes.HONOR_OVERVIEW_PATTERN,
        arguments = honorOverviewArguments,
    ) {
        org.walktalkmeditate.pilgrim.ui.honor.HonorOverviewScreen(
            onClose = navController::closeHonorOverview,
            onBegin = navController::beginHonorWalk,
        )
    }
}

/**
 * The Ways sheet and its picker: dialog routes, so the screen beneath them
 * (the Path tab) stays composed behind the sheet, as iOS's sheet sits over
 * its tab view. Each draws its own sheet in its own window over the route's.
 */
internal fun androidx.navigation.NavGraphBuilder.honorSheet(
    route: String,
    content: @Composable (androidx.navigation.NavBackStackEntry) -> Unit,
) {
    dialog(route, content = content)
}

/** "walk this again" built a Way: the overview takes the summary's place over its host (F §6.2). */
internal fun NavController.openHonorOverviewFromSummary(sourceWalkId: Long) {
    navigate(Routes.honorOverview(HonorWayChoice.OwnWalk(sourceWalkId))) {
        popUpTo(Routes.WALK_SUMMARY_PATTERN) { inclusive = true }
        launchSingleTop = true
    }
}

/** Close returns to whatever hosted the overview; system Back does the same. */
internal fun NavController.closeHonorOverview() {
    popBackStack(Routes.HONOR_OVERVIEW_PATTERN, inclusive = true)
}

/**
 * A listed Way's overview, from its "Shared with you" row or a finished
 * import: it takes the place of the Ways sheet (and the picker over it),
 * or of an overview already up, as iOS swaps the overview's Way (S1 §8.16);
 * anywhere else it opens over the screen showing.
 */
internal fun NavController.openStoredWayOverview(wayId: String) {
    val replaces = listOf(Routes.HONOR_WAYS, Routes.HONOR_OVERVIEW_PATTERN).firstOrNull(::hasBackStackEntry)
    navigate(Routes.honorOverview(HonorWayChoice.Stored(wayId))) {
        replaces?.let { popUpTo(it) { inclusive = true } }
        launchSingleTop = true
    }
}

/**
 * A Way a link or a paste fetched opens over the Path tab, so Close lands
 * there, as it does on iOS, whose link switched the tab beneath (S2 §4.1).
 * An overview already up, or the Ways sheet with its picker, gives up its
 * place unsaved; any other screen is left as a tab tap leaves it.
 */
internal fun NavHostController.openFetchedWayOverview(wayId: String) {
    popBackStack(Routes.HONOR_OVERVIEW_PATTERN, inclusive = true)
    popBackStack(Routes.HONOR_WAYS, inclusive = true)
    if (currentDestination?.route != Routes.PATH) navigateToTab(Routes.PATH)
    navigate(Routes.honorOverview(HonorWayChoice.Stored(wayId))) { launchSingleTop = true }
}

/** Begin takes the overview's place with the walk screen, before its Start (spec correction 1). */
internal fun NavController.beginHonorWalk(way: HonorWayChoice) {
    navigate(Routes.activeWalk(WalkMode.Honor, way)) {
        popUpTo(Routes.HONOR_OVERVIEW_PATTERN) { inclusive = true }
        launchSingleTop = true
    }
}

/**
 * Routing hub for [SettingsAction]. The [Context] parameter is plumbed
 * through so intent-based destinations (Custom Tabs, Play Store deep
 * link, share sheet, app-permission settings) don't need to re-touch
 * the SettingsScreen call site.
 *
 * The exhaustive `when` block captures every variant declared on
 * [SettingsAction].
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
        SettingsAction.OpenExportImport ->
            navController.navigate(Routes.DATA_SETTINGS) { launchSingleTop = true }
        SettingsAction.OpenAbout ->
            navController.navigate(Routes.ABOUT) { launchSingleTop = true }
        SettingsAction.OpenJourneyViewer ->
            navController.navigate(Routes.JOURNEY_VIEWER) { launchSingleTop = true }
        SettingsAction.OpenJourneyEditor ->
            navController.navigate(Routes.JOURNEY_EDITOR) { launchSingleTop = true }
        SettingsAction.OpenAppearance ->
            navController.navigate(Routes.APPEARANCE) { launchSingleTop = true }
        SettingsAction.OpenWays ->
            navController.navigate(Routes.WAYS_LIST) { launchSingleTop = true }
    }
}
