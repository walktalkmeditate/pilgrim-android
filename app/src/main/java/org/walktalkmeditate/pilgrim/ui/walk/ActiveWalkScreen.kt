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
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.WalkStats
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

private val SHEET_HEIGHT_EXPANDED_DP = 340.dp
private val SHEET_HEIGHT_MINIMIZED_DP = 88.dp

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
    val intention by viewModel.intention.collectAsStateWithLifecycle()
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
    var preWalkIntention by rememberSaveable { mutableStateOf<String?>(null) }
    var showPreWalkIntention by rememberSaveable { mutableStateOf(false) }
    var showWaypointMarking by rememberSaveable { mutableStateOf(false) }
    // Single state-class side-effect block: track in-progress latch for
    // the discard-nav guard, route to neighbor screens on terminal
    // emissions, and dismiss in-walk sheets when the walk leaves an
    // in-progress state. showPreWalkIntention is intentionally NOT
    // dismissed here — Idle is the pre-walk surface itself, so the
    // pill/dialog should remain accessible between walks.
    LaunchedEffect(navWalkState::class) {
        val state = navWalkState
        val isInProgress = state is WalkState.Active ||
            state is WalkState.Paused ||
            state is WalkState.Meditating
        if (isInProgress) {
            hasSeenInProgress.value = true
        } else {
            showOptions = false
            showWaypointMarking = false
        }
        when (state) {
            is WalkState.Finished -> onFinished(state.walk.walkId)
            is WalkState.Meditating -> onEnterMeditation()
            WalkState.Idle -> if (hasSeenInProgress.value) onDiscarded()
            else -> Unit
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
            WalkOptionsSheet(
                waypointCount = waypointCount,
                canDropWaypoint = navWalkState is WalkState.Active ||
                    navWalkState is WalkState.Paused,
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
                },
                onDismiss = { showWaypointMarking = false },
            )
        }
        if (showPreWalkIntention) {
            IntentionSettingDialog(
                initial = preWalkIntention,
                onSave = { text ->
                    preWalkIntention = text.takeIf { it.isNotBlank() }
                    showPreWalkIntention = false
                },
                onDismiss = { showPreWalkIntention = false },
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
            intention = intention,
            preWalkIntention = preWalkIntention,
            onSetPreWalkIntention = { showPreWalkIntention = true },
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
