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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
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
            hasSeenInProgress.value = true
        }
        if (state !is WalkState.Active && state !is WalkState.Paused) {
            showOptions = false
            showWaypointMarking = false
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
        // discarded the walk. `if` instead of an early-return so the
        // latch stays set in either case (the iOS reference is "fire
        // at most once per walk").
        if (navWalkState is WalkState.Active && intention == null) {
            showAutoIntention = true
        }
    }
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
                    showPreWalkIntention = true
                },
                waypointCount = waypointCount,
                canDropWaypoint = activeWalk?.lastLocation != null,
                onDropWaypoint = {
                    showOptions = false
                    showWaypointMarking = true
                },
                onDismiss = { showOptions = false },
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
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
