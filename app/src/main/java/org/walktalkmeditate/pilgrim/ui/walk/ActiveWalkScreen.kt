// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.kanji
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForToday
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.WalkStats
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

private val SHEET_HEIGHT_EXPANDED_DP = 340.dp
private val SHEET_HEIGHT_MINIMIZED_DP = 88.dp

/**
 * iOS-parity (`ActiveWalkView.swift:374`): the auto-intention sheet
 * pops 0.5s after the walk transitions to Active so the start-button
 * tap haptic + sheet animation don't collide with a modal dialog
 * appearing on the same frame. Seen as a single named constant so the
 * test can use the same value and the iOS reference is documented.
 */
internal const val AUTO_INTENTION_DELAY_MS = 500L

/**
 * iOS parity (`ActiveWalkView.swift:206-235@db4196e`): 0.3s gap
 * between dismissing the options sheet and presenting the next sheet
 * (intention / waypoint) so the dismissal + present animations don't
 * fight. Android's single-overlay layer doesn't strictly require this,
 * but matching the user-perceived rhythm preserves the iOS feel.
 */
internal const val SHEET_HANDOFF_DELAY_MS = 300L

/**
 * Pure predicate extracted from the Stage 10-C auto-intention prompt
 * LaunchedEffect so it can be unit-tested without standing up Compose
 * + Hilt + Mapbox. Mirrors iOS `ActiveWalkView.swift:374`:
 *
 *   - Already checked this walk → false (latch fires once per walk).
 *   - Walk is not Active → false (only fires on Active entry).
 *   - Pref is off → false.
 *   - Intention already set (ellipsis-menu pre-walk path or prior
 *     confirm) → false.
 */
internal fun shouldAutoPromptIntention(
    walkState: WalkState,
    beginWithIntention: Boolean,
    intention: String?,
    hasCheckedAutoIntention: Boolean,
): Boolean = !hasCheckedAutoIntention &&
    walkState is WalkState.Active &&
    beginWithIntention &&
    intention == null

@Composable
fun ActiveWalkScreen(
    onFinished: (walkId: Long) -> Unit,
    onEnterMeditation: () -> Unit,
    onDiscarded: () -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // Navigation observer reads the passthrough flow, NOT uiState's
    // WhileSubscribed(5s) cache. Stage 5G stale-cache trap; see
    // WalkViewModel.walkState kdoc.
    val navWalkState by viewModel.walkState.collectAsStateWithLifecycle()
    val routePoints by viewModel.routePoints.collectAsStateWithLifecycle()
    val recorderState by viewModel.voiceRecorderState.collectAsStateWithLifecycle()
    val audioLevel by viewModel.audioLevel.collectAsStateWithLifecycle()
    val recordingsCount by viewModel.recordingsCount.collectAsStateWithLifecycle()
    val talkMillis by viewModel.talkMillis.collectAsStateWithLifecycle()
    val initialCameraCenter by viewModel.initialCameraCenter.collectAsStateWithLifecycle()
    val waypointCount by viewModel.waypointCount.collectAsStateWithLifecycle()
    val waypoints by viewModel.waypoints.collectAsStateWithLifecycle()
    val intention by viewModel.intention.collectAsStateWithLifecycle()
    val distanceUnits by viewModel.distanceUnits.collectAsStateWithLifecycle()
    val beginWithIntention by viewModel.beginWithIntention.collectAsStateWithLifecycle()
    // Stage 5-G: read walkState from the hot passthrough, not the
    // WhileSubscribed-cached uiState. After a meditation > 5s, ui freezes
    // at the pre-meditation Meditating snapshot for one frame on
    // re-entry; computing from ui.walkState would over-count the meditate
    // chip by a full meditation duration for that frame. nowMillis being
    // one tick stale is harmless — for Active state, totalMeditatedMillis
    // does not consult `now` at all.
    val meditateMillis = WalkStats.totalMeditatedMillis(navWalkState, ui.nowMillis)

    val context = LocalContext.current
    BackHandler(enabled = ui.walkState.isInProgress) {
        (context as? Activity)?.moveTaskToBack(true)
    }

    // Stage 9.5-C polish fix: gate Idle → onDiscarded behind a
    // hasSeenInProgress latch. LaunchedEffect(navWalkState::class) fires
    // on FIRST composition (Stage 5-A memory) and the controller's
    // initial state is Idle, so without the latch a fresh nav into
    // ActiveWalk would spuriously fire onDiscarded() before the
    // controller has even transitioned to Active. Pattern matches
    // Stage 9.5-B's WalkTrackingService.hasBeenActive latch.
    val hasSeenInProgress = rememberSaveable { mutableStateOf(false) }
    // iOS parity (D10 audit): bump on every Idle/Finished → Active
    // transition so the WalkStatsSheet can play its one-shot peek
    // wink animation teaching the swipe-to-expand affordance.
    val peekHintTrigger = rememberSaveable { mutableStateOf(0) }
    var sheetState by rememberSaveable { mutableStateOf(SheetState.Expanded) }
    // Drive sheet auto-state from the PASSTHROUGH walkState so we don't
    // act on a stale uiState during the brief window after returning
    // from MeditationScreen (Stage 5G stale-cache trap, generalized).
    SheetStateController(
        walkState = navWalkState,
        onUpdateState = { sheetState = it },
    )

    val sheetInsetDp = if (sheetState == SheetState.Expanded) {
        SHEET_HEIGHT_EXPANDED_DP
    } else {
        SHEET_HEIGHT_MINIMIZED_DP
    }
    var showLeaveConfirm by rememberSaveable { mutableStateOf(false) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    // preWalkIntention persists across rotation, tab-switching (PilgrimNavHost
    // pops Path with saveState=true), AND process death (rememberSaveable
    // bundle round-trip). It is ONLY cleared by:
    //   (a) successful Start — `onStartWalk` resets to null after the
    //       intention is committed to the Walk row, OR
    //   (b) back-button pop of the ACTIVE_WALK route — the NavBackStackEntry
    //       is destroyed and the rememberSaveable bundle dies with it.
    // The persistence-across-tab-switch behavior is intentional: a user who
    // composed a draft while checking an old walk in Goshuin returns to
    // their draft. Persistence-across-process-death covers the crash-recovery
    // case. If the surface is reached weeks later with stale draft text, the
    // user can still re-tap Set or just hit Start to commit it as-is.
    var preWalkIntention by rememberSaveable { mutableStateOf<String?>(null) }
    var showPreWalkIntention by rememberSaveable { mutableStateOf(false) }
    var showWaypointMarking by rememberSaveable { mutableStateOf(false) }
    // iOS parity `ActiveWalkView.swift:222, 285-313@db4196e` — whisper +
    // stone placement sheet hosts. Both surface from the options sheet
    // via the existing 300ms handoff pattern. `showWhisperSheet` opens
    // WhisperPlacementSheet (large detent, expiry + category); the
    // commit lambda calls `viewModel.placeWhisper(...)` which drives
    // the server round-trip and emits a [PlacementEvent] for the
    // success haptic + failure banner.
    var showWhisperSheet by rememberSaveable { mutableStateOf(false) }
    var showStoneSheet by rememberSaveable { mutableStateOf(false) }
    val whispersPlacedThisWalk by viewModel.whispersPlacedThisWalk.collectAsStateWithLifecycle()
    val isWhisperUnlocked by viewModel.isWhisperUnlocked.collectAsStateWithLifecycle()
    val canPlaceWhisper by viewModel.canPlaceWhisper.collectAsStateWithLifecycle()
    val stonePlacedThisWalk by viewModel.stonePlacedThisWalk.collectAsStateWithLifecycle()
    val isStoneUnlocked by viewModel.isStoneUnlocked.collectAsStateWithLifecycle()
    val canPlaceStone by viewModel.canPlaceStone.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val haptic = LocalHapticFeedback.current
    val placementWhisperPlacedMsg = stringResource(R.string.placement_whisper_placed)
    val placementStonePlacedMsg = stringResource(R.string.placement_stone_placed)
    val placementFailedFmt = stringResource(R.string.placement_failed)
    LaunchedEffect(viewModel) {
        viewModel.placementEvents.collect { event ->
            when (event) {
                is org.walktalkmeditate.pilgrim.ui.walk.PlacementEvent.WhisperPlaced -> {
                    // iOS parity `ActiveWalkView.swift:811-816@db4196e` —
                    // medium impact haptic fires AFTER server confirm.
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    snackbarHostState.showSnackbar(placementWhisperPlacedMsg)
                }
                is org.walktalkmeditate.pilgrim.ui.walk.PlacementEvent.StonePlaced -> {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    snackbarHostState.showSnackbar(placementStonePlacedMsg)
                }
                is org.walktalkmeditate.pilgrim.ui.walk.PlacementEvent.Failed -> {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    snackbarHostState.showSnackbar(
                        placementFailedFmt.format(event.message),
                    )
                }
            }
        }
    }
    // iOS parity `ActiveWalkView.swift:68-70, 138-141, 334-341@db4196e`:
    // computed once per composition because the marker only changes on a
    // day boundary (cardinal turnings are rare; the user crossing midnight
    // mid-walk would not flip the visible kanji until they re-enter the
    // screen). `kanji()` returns null for cross-quarter markers so the
    // watermark renders only on the four cardinal turnings (春分/夏至/秋分/冬至).
    val activeTurning = remember { turningMarkerForToday() }
    var showTurningCard by rememberSaveable { mutableStateOf(false) }
    // Composition-scoped scope for the 300ms sheet handoff delay
    // (D9). Tied to the screen's composition lifetime — cancels on
    // back-pop / discard so a pending handoff doesn't surface a sheet
    // after the user has left the screen. `handoffJob` tracks the
    // single in-flight delay so a re-tap during the 300ms window
    // cancels the prior handoff (no double-sheet-pop on re-tap).
    val handoffScope = rememberCoroutineScope()
    val handoffJob = remember {
        androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null)
    }
    // Stage 10-C: auto-intention prompt (mirrors iOS
    // `ActiveWalkView.swift:374`). Fires once per walk session 0.5s
    // after the walk transitions to Active when the
    // `beginWithIntention` pref is on AND no intention has been
    // committed yet (either via the pre-walk ellipsis path or by a
    // prior auto-prompt confirm). Separate state from
    // `showPreWalkIntention` so the two flows remain orthogonal: the
    // ellipsis-menu path edits a draft on the Idle state, the
    // auto-prompt commits straight to the Walk row via
    // viewModel.setIntention.
    var showAutoIntention by rememberSaveable { mutableStateOf(false) }
    // The latch is intentionally NOT rememberSaveable. It's tied to
    // the walk-id of the currently-Active walk via `remember(walkId)`
    // below, so it resets on a fresh walk start and rotation re-fires
    // the auto-prompt at most once more (mildly annoying but not a
    // correctness issue — the user can dismiss it again). Survives
    // recomposition WITHIN the same walk.
    // resetKey counters force the sheet/dialog's `rememberSaveable`-keyed
    // text states to re-initialize on each open, so Cancel-then-reopen
    // discards the typed-but-not-committed draft (matches dismiss-button
    // semantics). rememberSaveable saves to the screen-wide saveable
    // registry — without the bump on dismiss, the conditional render
    // would resurrect the cancelled draft on reopen.
    var preWalkIntentionResetKey by rememberSaveable { mutableStateOf(0) }
    var waypointMarkingResetKey by rememberSaveable { mutableStateOf(0) }
    // Single state-class side-effect block: track in-progress latch for
    // the discard-nav guard, route to neighbor screens on terminal
    // emissions, and dismiss in-walk sheets when the walk leaves an
    // in-progress state.
    //
    // Dismissal policy:
    //  - showOptions / showWaypointMarking: dismiss whenever the walk is
    //    NOT in an active-walk state (Active|Paused). Meditating dismisses
    //    them too — the nav goes to MeditationScreen and a re-emerging
    //    sheet on return would surprise the user.
    //  - showPreWalkIntention: dismiss whenever the walk is NOT Idle. The
    //    dialog is the pre-walk surface; if the state transitions to
    //    Active externally (FGS automation, restoreActiveWalk), the
    //    typed draft would have nowhere to go — Save would silently
    //    write to a now-irrelevant `preWalkIntention` field. Bumping
    //    the resetKey discards any in-progress draft so a fresh open
    //    next time we reach Idle starts clean.
    //
    // Future-self note: keying on `navWalkState::class` means same-class
    // back-to-back transitions (e.g., a hypothetical Active(walkA) →
    // Active(walkB) without an intervening Idle/Finished) would NOT
    // re-fire this effect. The reducer doesn't produce that pattern
    // today (every walk-start requires Idle/Finished), but if a future
    // path does, change the key to `navWalkState` (full instance) so
    // walkId changes also trigger.
    LaunchedEffect(navWalkState::class) {
        val state = navWalkState
        val isInProgress = state is WalkState.Active ||
            state is WalkState.Paused ||
            state is WalkState.Meditating
        if (isInProgress) {
            // Bump peek-hint trigger ONLY on the first transition INTO
            // in-progress this composition cycle — `hasSeenInProgress`
            // being false means we just came from Idle/Finished (a real
            // walk start, not a Pause↔Active flip or restoration).
            if (!hasSeenInProgress.value && state is WalkState.Active) {
                peekHintTrigger.value++
            }
            hasSeenInProgress.value = true
        }
        if (state !is WalkState.Active && state !is WalkState.Paused) {
            showOptions = false
            showWaypointMarking = false
            // Same rationale: the watermark + ritual card are tied to
            // the active-walk surface. A Meditating / Finished / Idle
            // transition pulls the user elsewhere; a re-emerging sheet
            // on return (Stage 4-C launchSingleTop + back-nav pattern)
            // would surprise.
            showTurningCard = false
        }
        if (state !is WalkState.Idle && showPreWalkIntention) {
            showPreWalkIntention = false
            preWalkIntentionResetKey++
        }
        // Stage 10-C: dismiss the auto-intention dialog if the walk
        // transitions away from Active (e.g., the user paused or
        // discarded the walk while the dialog was up). The dialog's
        // commit path writes to the Walk row via setIntention, so a
        // stale dialog after a discard would silently target a
        // non-existent walk.
        if (state !is WalkState.Active && showAutoIntention) {
            showAutoIntention = false
        }
        when (state) {
            is WalkState.Finished -> onFinished(state.walk.walkId)
            is WalkState.Meditating -> onEnterMeditation()
            WalkState.Idle -> if (hasSeenInProgress.value) onDiscarded()
            else -> Unit
        }
    }

    // Stage 10-C auto-intention prompt. Mirrors iOS
    // `ActiveWalkView.swift:374`: 0.5s after the walk transitions to
    // Active, IF `beginWithIntention` is on AND no intention has been
    // set, surface the IntentionSettingDialog. The latch
    // (`hasCheckedAutoIntention`) is keyed on the active walk id via
    // `remember(activeWalkId)` so it resets per walk — finishing one
    // walk and starting another in the same session re-arms the
    // prompt. `rememberSaveable` is intentionally NOT used (rotation
    // re-firing the prompt is a minor annoyance, not a correctness
    // issue, and the `intention != null` check naturally suppresses
    // the re-fire after a confirmed value).
    //
    // **Recovery guard**: if the first observed walk-state is already
    // in-progress (Active / Paused / Meditating), the user is
    // returning to an already-running walk via process death + cold
    // launch (Stage 9.5-D recovery) OR notification-tap-while-walking.
    // Don't pop a fresh-walk auto-prompt on the recovery surface —
    // it's confusing UX. Auto-prompt only fires when the user
    // observably transitions FROM idle TO active in this composition.
    val activeWalkId = (navWalkState as? WalkState.Active)?.walk?.walkId
    val hasCheckedAutoIntention = remember(activeWalkId) { mutableStateOf(false) }
    val isRecoveryComposition = remember {
        // `navWalkState` at first composition: Idle = fresh start;
        // anything else = we're entering an in-progress walk (recovery).
        navWalkState !is WalkState.Idle
    }
    LaunchedEffect(navWalkState, beginWithIntention, intention) {
        if (isRecoveryComposition) return@LaunchedEffect
        if (!shouldAutoPromptIntention(
                walkState = navWalkState,
                beginWithIntention = beginWithIntention,
                intention = intention,
                hasCheckedAutoIntention = hasCheckedAutoIntention.value,
            )
        ) {
            return@LaunchedEffect
        }
        // Set the latch BEFORE the delay so a recompose firing the
        // effect again (e.g., the per-second tick driving uiState
        // doesn't fire here, but `intention` flipping null -> "x" via
        // a separate path would re-key the effect) finds the latch
        // already set.
        hasCheckedAutoIntention.value = true
        delay(AUTO_INTENTION_DELAY_MS)
        // Re-check after the delay — the user might have set the
        // intention via the ellipsis menu in the gap, or paused /
        // discarded the walk. Also guard against sheet collision: if
        // any other modal surface is already open (ellipsis options,
        // leave confirm, waypoint marking), don't pop the auto-intent
        // dialog on top of it. The user can still set the intention
        // from the options menu they're currently in. `if` instead of
        // an early-return so the latch stays set in either case (iOS
        // reference is "fire at most once per walk").
        // Defense-in-depth: include `showPreWalkIntention` even though
        // the state-change effect dismisses it on Idle→Active before
        // this delay starts. If a future refactor decouples that
        // dismissal, the auto-prompt would otherwise stack on top.
        // Also include `showAutoIntention` itself — closes the re-entrancy
        // window where a `remember(activeWalkId)` reset (brief walkId flip
        // mid-delay) could let a second auto-prompt stack atop a still-
        // visible first one. The latch is set before the delay, so the
        // realistic failure path is small, but the guard cost is one
        // boolean OR.
        val anyOtherSheetOpen = showOptions || showLeaveConfirm ||
            showWaypointMarking || showPreWalkIntention ||
            showAutoIntention
        if (navWalkState is WalkState.Active && intention == null && !anyOtherSheetOpen) {
            showAutoIntention = true
        }
    }
    val activeWeather by viewModel.activeWeather.collectAsStateWithLifecycle()
    val activeCelestialGreeting by viewModel.activeCelestialGreeting.collectAsStateWithLifecycle()
    // iOS parity (D6/D7 audit): fade a greeting in only while
    // recording. Hand the overlay a non-null condition exactly once
    // per state-enter-Active transition; the overlay owns the timer
    // and per-walk one-shot token.
    val greetingCondition = if (navWalkState is WalkState.Active) {
        activeWeather?.condition
    } else {
        null
    }
    val greetingWalkId = (navWalkState as? WalkState.Active)?.walk?.walkId

    Box(modifier = Modifier.fillMaxSize()) {
        PilgrimMap(
            points = routePoints,
            followLatest = true,
            initialCenter = initialCameraCenter,
            // Match map bottom-inset to the visible sheet height so the
            // user puck stays just above the sheet in BOTH detents.
            bottomInsetDp = sheetInsetDp,
            waypoints = waypoints,
            modifier = Modifier.fillMaxSize(),
        )
        // Weather greeting overlay — fades in over 0.8s + holds 3.5s +
        // fades out over 1.0s. Aligned to top so it sits above the map
        // overlay buttons; ZIndex inferred from declaration order.
        // iOS positions the greeting between the status bar and the
        // ellipsis/X overlay row (~72pt below the safe-area top); the
        // `PilgrimSpacing.big * 3` constant (24 × 3 = 72.dp) hits the
        // same anchor.
        WeatherGreetingOverlay(
            triggerCondition = greetingCondition,
            walkId = greetingWalkId,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = PilgrimSpacing.big * 3),
        )
        // Celestial greeting overlay — same anchor as weather, but
        // schedules its own 5s pre-delay before fading in so the
        // weather greeting (3.5s + 1s fadeout = 4.5s) finishes first.
        CelestialGreetingOverlay(
            text = if (navWalkState is WalkState.Active) activeCelestialGreeting else null,
            walkId = greetingWalkId,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = PilgrimSpacing.big * 3),
        )
        // iOS-parity overlay row at the top of the map: ellipsis (options)
        // top-left, X (leave walk) top-right.
        // ActiveWalkView.swift:530-567.
        MapOverlayButtons(
            onOptionsClick = { showOptions = true },
            onLeaveClick = { showLeaveConfirm = true },
            // Stage 9.5-A trap (already fixed for the bottom sheet): the
            // PilgrimNavHost Scaffold already passes status-bar inset
            // through `Modifier.padding(innerPadding)` on the NavHost,
            // so calling `statusBarsPadding()` here would double-count
            // and push the buttons ~48dp lower than iOS. Just align to
            // top of the already-inset content area.
            modifier = Modifier.align(Alignment.TopCenter),
        )
        if (showLeaveConfirm) {
            LeaveWalkDialog(
                onConfirm = {
                    showLeaveConfirm = false
                    viewModel.discardWalk()
                },
                onDismiss = { showLeaveConfirm = false },
            )
        }
        if (showOptions) {
            // Gate Drop Waypoint on BOTH (a) walk-is-trackable state AND
            // (b) we have a GPS fix. Without (b), `recordWaypoint` would
            // silently no-op inside the controller's dispatch lock —
            // user taps chip, hears the haptic confirmation, sheet
            // dismisses, but no waypoint exists. The pre-gate makes the
            // failure visible: row is greyed out until a fix arrives.
            // Meditating is intentionally omitted: the LaunchedEffect
            // above force-dismisses showOptions on Meditating transition
            // (the user routes to MeditationScreen), so this branch is
            // unreachable when state is Meditating.
            val activeWalk = (navWalkState as? WalkState.Active)?.walk
                ?: (navWalkState as? WalkState.Paused)?.walk
            WalkOptionsSheet(
                // Per-state row visibility:
                //  - Idle: only Set Intention. Waypoints can't be dropped
                //    before a walk row exists.
                //  - Active|Paused (with GPS fix): only Drop Waypoint.
                //    Intention is committed at startWalk; not editable
                //    once a walk is in progress.
                canSetIntention = navWalkState is WalkState.Idle,
                intention = preWalkIntention,
                onSetIntention = {
                    showOptions = false
                    // iOS parity `ActiveWalkView.swift:206-235@db4196e`:
                    // 0.3s delay between dismissing the options sheet and
                    // presenting the next sheet so the dismissal +
                    // present animations don't fight. Android's overlay
                    // system doesn't strictly need this (single overlay
                    // layer), but the user-perceived rhythm matches.
                    handoffJob.value?.cancel()
                    handoffJob.value = handoffScope.launch {
                        kotlinx.coroutines.delay(SHEET_HANDOFF_DELAY_MS)
                        showPreWalkIntention = true
                    }
                },
                waypointCount = waypointCount,
                canDropWaypoint = activeWalk?.lastLocation != null,
                onDropWaypoint = {
                    showOptions = false
                    handoffJob.value?.cancel()
                    handoffJob.value = handoffScope.launch {
                        kotlinx.coroutines.delay(SHEET_HANDOFF_DELAY_MS)
                        showWaypointMarking = true
                    }
                },
                onDismiss = { showOptions = false },
                isWhisperUnlocked = isWhisperUnlocked,
                canPlaceWhisper = canPlaceWhisper,
                whispersRemaining = (7 - whispersPlacedThisWalk).coerceAtLeast(0),
                onLeaveWhisper = {
                    showOptions = false
                    handoffJob.value?.cancel()
                    handoffJob.value = handoffScope.launch {
                        kotlinx.coroutines.delay(SHEET_HANDOFF_DELAY_MS)
                        showWhisperSheet = true
                    }
                },
                isStoneUnlocked = isStoneUnlocked,
                canPlaceStone = canPlaceStone,
                stonePlaced = stonePlacedThisWalk,
                onPlaceStone = {
                    showOptions = false
                    handoffJob.value?.cancel()
                    handoffJob.value = handoffScope.launch {
                        kotlinx.coroutines.delay(SHEET_HANDOFF_DELAY_MS)
                        showStoneSheet = true
                    }
                },
            )
        }
        if (showWhisperSheet) {
            WhisperPlacementSheet(
                onPlace = { category, _ ->
                    showWhisperSheet = false
                    // Fire-and-forget — the VM launches into
                    // viewModelScope so the HTTP round-trip survives
                    // rotation (UI's rememberCoroutineScope would
                    // cancel the in-flight request, losing the cap
                    // increment after server-success).
                    viewModel.placeWhisper(category)
                },
                onDismiss = { showWhisperSheet = false },
            )
        }
        if (showStoneSheet) {
            StonePlacementSheet(
                onPlace = {
                    showStoneSheet = false
                    viewModel.placeStone()
                },
                onDismiss = { showStoneSheet = false },
            )
        }
        if (showWaypointMarking) {
            WaypointMarkingSheet(
                onMark = { label, icon ->
                    viewModel.dropWaypoint(label = label, icon = icon)
                    showWaypointMarking = false
                    waypointMarkingResetKey++
                },
                onDismiss = {
                    showWaypointMarking = false
                    waypointMarkingResetKey++
                },
                resetKey = waypointMarkingResetKey,
            )
        }
        if (showPreWalkIntention) {
            IntentionSettingDialog(
                initial = preWalkIntention,
                onSave = { text ->
                    preWalkIntention = text.takeIf { it.isNotBlank() }
                    showPreWalkIntention = false
                    preWalkIntentionResetKey++
                },
                onDismiss = {
                    showPreWalkIntention = false
                    preWalkIntentionResetKey++
                },
                resetKey = preWalkIntentionResetKey,
            )
        }
        // Stage 10-C: auto-intention dialog. Distinct conditional from
        // showPreWalkIntention — they cover two different states (Idle
        // pre-walk vs Active post-start), and bundling them would
        // require a single resetKey-style draft buffer that doesn't
        // exist for the auto path (commit goes straight to the Walk
        // row).
        if (showAutoIntention) {
            IntentionSettingDialog(
                initial = null,
                onSave = { text ->
                    if (text.isNotBlank()) viewModel.setIntention(text)
                    showAutoIntention = false
                },
                onDismiss = { showAutoIntention = false },
            )
        }
        // iOS parity `ActiveWalkView.swift:80-96, 138-141@db4196e`:
        // faint kanji watermark on cardinal-turning days. Gated to
        // in-progress walk + minimized sheet so it doesn't compete with
        // the bottom sheet's drag affordance when expanded. Tap opens a
        // contemplative ritual card via ModalBottomSheet.
        val turningKanji = activeTurning?.kanji()
        val turningInProgress = navWalkState is WalkState.Active || navWalkState is WalkState.Paused
        if (turningKanji != null && turningInProgress && sheetState == SheetState.Minimized) {
            TurningWatermark(
                kanji = turningKanji,
                contentDescription = activeTurning.displayName,
                a11yHint = stringResource(R.string.turning_watermark_a11y_hint),
                onClick = { showTurningCard = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = SHEET_HEIGHT_MINIMIZED_DP + 16.dp),
            )
        }
        if (showTurningCard && activeTurning != null) {
            TurningRitualSheet(
                turning = activeTurning,
                onDismiss = { showTurningCard = false },
            )
        }
        WalkStatsSheet(
            state = sheetState,
            onStateChange = { sheetState = it },
            // Stage 5-G stale-cache trap: `ui.walkState` is sourced from a
            // WhileSubscribed(5s) flow. After a meditation > 5s, ui freezes
            // at the pre-meditation Meditating snapshot for one frame on
            // ActiveWalkScreen re-entry, rendering the wrong action buttons
            // (e.g., End Meditation when the controller is already Active).
            // navWalkState is the hot Singleton passthrough — always fresh.
            walkState = navWalkState,
            totalElapsedMillis = ui.totalElapsedMillis,
            distanceMeters = ui.distanceMeters,
            walkMillis = ui.activeWalkingMillis,
            talkMillis = talkMillis,
            meditateMillis = meditateMillis,
            recorderState = recorderState,
            audioLevel = audioLevel,
            recordingsCount = recordingsCount,
            units = distanceUnits,
            // Caption display rule: pre-walk shows the typed-but-not-yet-
            // committed draft (preWalkIntention); in-walk shows the value
            // committed to the Walk row (intention StateFlow). The two are
            // never simultaneously set — startWalk clears preWalkIntention
            // and writes intention; until Start, the Walk row doesn't
            // exist and intention is null. iOS unifies these via a single
            // viewModel.intention; Android keeps them split because the
            // pre-walk path doesn't write to Room until commit.
            intention = preWalkIntention ?: intention,
            onPause = viewModel::pauseWalk,
            onResume = viewModel::resumeWalk,
            onStartWalk = {
                viewModel.startWalk(intention = preWalkIntention)
                preWalkIntention = null
            },
            onStartMeditation = viewModel::startMeditation,
            onEndMeditation = viewModel::endMeditation,
            onToggleRecording = viewModel::toggleRecording,
            onPermissionDenied = viewModel::emitPermissionDenied,
            onDismissError = viewModel::dismissRecorderError,
            onFinish = viewModel::finishWalk,
            peekHintTrigger = peekHintTrigger.value,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        // iOS parity `ProximityNotificationView.swift@db4196e` — placement
        // result banner. iOS uses a custom floating banner; Android MVP
        // uses Material 3 Snackbar (auto-dismissal, accessible) at the
        // top center so the bottom sheet remains usable. The full
        // floating-banner port is deferred along with the proximity
        // detection epic.
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter),
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = pilgrimColors.parchmentSecondary,
                contentColor = pilgrimColors.ink,
            )
        }
    }
}

@Composable
private fun MapOverlayButtons(
    onOptionsClick: () -> Unit,
    onLeaveClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = PilgrimSpacing.normal,
                end = PilgrimSpacing.normal,
                top = PilgrimSpacing.normal,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OverlayCircleButton(
            icon = Icons.Filled.MoreHoriz,
            contentDescription = "Walk options",
            onClick = onOptionsClick,
        )
        OverlayCircleButton(
            icon = Icons.Filled.Close,
            contentDescription = "Leave walk",
            onClick = onLeaveClick,
        )
    }
}

@Composable
private fun OverlayCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            // Compose has no `.ultraThinMaterial`. parchment-secondary at
            // ~70% alpha reads as a soft translucent disc against either
            // light- or dark-mode map tiles.
            .background(pilgrimColors.parchmentSecondary.copy(alpha = 0.7f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = pilgrimColors.ink,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun LeaveWalkDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave Walk?") },
        text = { Text("This walk will not be saved.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Leave") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Stay") }
        },
        containerColor = pilgrimColors.parchment,
        titleContentColor = pilgrimColors.ink,
        textContentColor = pilgrimColors.ink,
    )
}

/**
 * iOS parity `ActiveWalkView.swift:80-96@db4196e`. Faint kanji glyph
 * anchored above the minimized stats sheet; tap opens the
 * [TurningRitualSheet]. The visible glyph stays small (18sp fixed) so
 * it remains ambient even at large font scale — it's decoration, not
 * a primary information element. Tap target uses generous padding
 * (PilgrimSpacing.normal) so even fingers and TalkBack users can hit
 * it reliably.
 */
@Composable
private fun TurningWatermark(
    kanji: String,
    contentDescription: String,
    a11yHint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(PilgrimSpacing.normal)
            .semantics(mergeDescendants = true) {
                // Parent owns the a11y label. The kanji Text below is
                // hidden from the a11y tree via
                // `Modifier.clearAndSetSemantics { }` so TalkBack
                // reads only this contentDescription ("Spring
                // Equinox. Opens a contemplative ritual card.") —
                // without the clear, Compose merges the kanji's Text
                // semantic into the parent and TalkBack concatenates
                // it with our contentDescription.
                this.contentDescription = "$contentDescription. $a11yHint"
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kanji,
            // sp (not dp) so the watermark respects the user's system
            // font scale — at large-text accessibility settings the
            // kanji grows. iOS uses 18pt fixed via `.system(size: 18,
            // weight: .ultraLight)` which does NOT scale with Dynamic
            // Type. The Android divergence is intentional: respecting
            // the system font preference is the platform-idiomatic
            // choice, and the kanji remains visually ambient at every
            // scale.
            fontSize = 18.sp,
            fontWeight = FontWeight.Light,
            // iOS uses `.foregroundColor(.stone.opacity(0.18))`.
            // PilgrimColors exposes `stone` mapped to the same warm
            // neutral mid-tone on both light + dark mode — direct
            // parity, no role-flip surprise.
            color = pilgrimColors.stone.copy(alpha = 0.18f),
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * iOS parity `ActiveWalkView.swift:334-341@db4196e`. Medium-detent
 * `ModalBottomSheet` presenting the [TurningRitualCard]. The system
 * drag indicator + swipe-down dismiss the card. Parchment background
 * matches the iOS `Color.parchment.opacity(0.95)` cue.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TurningRitualSheet(
    turning: SeasonalMarker,
    onDismiss: () -> Unit,
) {
    // `skipPartiallyExpanded = false` leaves M3's PartiallyExpanded
    // (~50% height) detent available — that's the closest equivalent
    // to iOS's `.medium` detent. With `skipPartiallyExpanded = true`
    // M3 lands at full Expanded (~full height), which diverges from
    // iOS UX. For short content like TurningRitualCard the sheet
    // settles at PartiallyExpanded on first show.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment.copy(alpha = 0.95f),
    ) {
        TurningRitualCard(
            turning = turning,
            modifier = Modifier.padding(
                horizontal = PilgrimSpacing.normal,
                vertical = PilgrimSpacing.big,
            ),
        )
    }
}
