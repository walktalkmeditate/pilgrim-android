// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionChecks
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Bottom-sheet overlay for the Active Walk screen. Two detents
 * ([SheetState.Minimized] / [SheetState.Expanded]); content for both is
 * always composed and switched via alpha to keep the active mic
 * recording from being torn down on a state flip.
 *
 * The sheet's measured height is constant (~340dp regardless of state)
 * so the map's `bottomInsetDp` stays stable. Visual consequence:
 * minimized state has unused vertical space above the content. Acceptable
 * trade-off vs. SubcomposeLayout-based per-state height measurement.
 */
@Composable
fun WalkStatsSheet(
    state: SheetState,
    onStateChange: (SheetState) -> Unit,
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canDrag = walkState is WalkState.Active
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { DRAG_THRESHOLD_DP.toPx() } }
    val flickPx = remember(density) { with(density) { DRAG_FLICK_VELOCITY_DP.toPx() } }
    val clampPx = remember(density) { with(density) { DRAG_CLAMP_DP.toPx() } }

    // Animatable so partial drags below the threshold spring back to 0
    // smoothly instead of snapping. snapTo (synchronous set) is used
    // during the drag tick; animateTo runs on release.
    val dragOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    // Cancel-before-launch the per-delta snapTo so at most one snap is
    // ever queued behind Animatable's internal mutex. Without this, a
    // fast flick gesture queues dozens of stale snapTo coroutines that
    // would interrupt the onDragStopped spring-back animation.
    var snapJob by remember { mutableStateOf<Job?>(null) }

    val currentState by rememberUpdatedState(state)
    val currentCanDrag by rememberUpdatedState(canDrag)
    val currentOnStateChange by rememberUpdatedState(onStateChange)

    val draggableState = rememberDraggableState { delta ->
        if (!currentCanDrag) return@rememberDraggableState
        val proposed = (dragOffset.value + delta).coerceIn(-clampPx, clampPx)
        val target = when {
            currentState == SheetState.Minimized && delta < 0 -> proposed
            currentState == SheetState.Expanded && delta > 0 -> proposed
            else -> dragOffset.value
        }
        snapJob?.cancel()
        snapJob = coroutineScope.launch { dragOffset.snapTo(target) }
    }

    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { translationY = dragOffset.value }
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    // Cancel any in-flight snapTo from the drag tick so
                    // it can't race with the cleanup below.
                    snapJob?.cancel()
                    if (!currentCanDrag) {
                        dragOffset.animateTo(0f, SNAP_BACK_SPEC)
                        return@draggable
                    }
                    val shouldExpand = currentState == SheetState.Minimized &&
                        (dragOffset.value < -thresholdPx || velocity < -flickPx)
                    val shouldCollapse = currentState == SheetState.Expanded &&
                        (dragOffset.value > thresholdPx || velocity > flickPx)
                    when {
                        shouldExpand -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            // Instant snap so the state-change recomposition
                            // (which grows the sheet from ~88dp → ~340dp)
                            // is the only visible transition. Animating
                            // the offset BEFORE state change makes the
                            // user wait ~300ms before the sheet actually
                            // commits — perceived as lag.
                            dragOffset.snapTo(0f)
                            currentOnStateChange(SheetState.Expanded)
                        }
                        shouldCollapse -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            dragOffset.snapTo(0f)
                            currentOnStateChange(SheetState.Minimized)
                        }
                        // Below threshold: spring back smoothly (rubber-band).
                        else -> dragOffset.animateTo(0f, SNAP_BACK_SPEC)
                    }
                },
            ),
    ) {
        // Manual upward shadow: Compose Surface(elevation) casts shadow
        // downward; iOS uses y: -4 for an UPWARD shadow onto the map.
        // Soft gradient strip drawn just above the sheet's top edge.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.TopCenter)
                .offset(y = (-8).dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.10f),
                        ),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(sheetShape)
                .background(pilgrimColors.parchment)
                // Stage 9.5-A trap: PilgrimNavHost's Scaffold already passes
                // navigation-bar inset through `Modifier.padding(innerPadding)`.
                // Adding `navigationBarsPadding()` here would double-count
                // and push the action buttons ~34dp above the visible sheet
                // bottom on gesture-nav devices.
                .padding(bottom = PilgrimSpacing.normal),
        ) {
            DragHandle(canDrag = canDrag)
            // Always-mount both content variants so a state flip doesn't
            // tear down the active mic recording's LaunchedEffects + audio
            // observers. Switch via alpha rather than AnimatedContent.
            SheetContentSwitcher(
                state = state,
                minimizedContent = {
                    MinimizedContent(
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        onTap = { onStateChange(SheetState.Expanded) },
                    )
                },
                expandedContent = {
                    ExpandedContent(
                        walkState = walkState,
                        totalElapsedMillis = totalElapsedMillis,
                        distanceMeters = distanceMeters,
                        walkMillis = walkMillis,
                        talkMillis = talkMillis,
                        meditateMillis = meditateMillis,
                        recorderState = recorderState,
                        audioLevel = audioLevel,
                        recordingsCount = recordingsCount,
                        onPause = onPause,
                        onResume = onResume,
                        onStartMeditation = onStartMeditation,
                        onEndMeditation = onEndMeditation,
                        onToggleRecording = onToggleRecording,
                        onPermissionDenied = onPermissionDenied,
                        onDismissError = onDismissError,
                        onFinish = onFinish,
                    )
                },
            )
        }
    }
}

@Composable
private fun SheetContentSwitcher(
    state: SheetState,
    minimizedContent: @Composable () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    // Conditional composition so the sheet's measured height matches the
    // visible variant's natural height. The earlier always-mount + alpha
    // design measured to MAX(minimized, expanded) ≈ 340dp regardless of
    // state, leaving a large blank parchment block under the minimized
    // row. Device QA rejected that trade-off.
    //
    // Tearing down ExpandedContent on collapse means the mic button's
    // LaunchedEffect(err) timer and permission launcher are re-created
    // when the sheet next expands. The actual recording state lives in
    // VoiceRecorder + WalkViewModel and survives composition swaps —
    // only ephemeral UI side-effects re-init, which is harmless.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(if (state == SheetState.Expanded) EXPANDED_LAYER_TAG else MINIMIZED_LAYER_TAG),
    ) {
        if (state == SheetState.Expanded) {
            expandedContent()
        } else {
            minimizedContent()
        }
    }
}

@Composable
private fun DragHandle(canDrag: Boolean) {
    val opacity = if (canDrag) 0.35f else 0.12f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 5.dp)
                .background(
                    color = pilgrimColors.fog.copy(alpha = opacity),
                    shape = RoundedCornerShape(percent = 50),
                ),
        )
    }
}

@Composable
private fun MinimizedContent(
    totalElapsedMillis: Long,
    distanceMeters: Double,
    onTap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap,
            )
            .padding(horizontal = PilgrimSpacing.big, vertical = PilgrimSpacing.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatColumn(
            value = WalkFormat.duration(totalElapsedMillis),
            label = stringResource(R.string.walk_stat_time),
        )
        StatColumn(
            value = WalkFormat.distance(distanceMeters),
            label = stringResource(R.string.walk_stat_distance),
        )
        StatColumn(
            value = "—",
            label = stringResource(R.string.walk_stat_steps),
        )
    }
}

@Composable
private fun StatColumn(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
        Text(
            text = label,
            style = pilgrimType.statLabel,
            color = pilgrimColors.fog,
        )
    }
}

@Composable
private fun ExpandedContent(
    walkState: WalkState,
    totalElapsedMillis: Long,
    distanceMeters: Double,
    walkMillis: Long,
    talkMillis: Long,
    meditateMillis: Long,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = PilgrimSpacing.big),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = WalkFormat.duration(totalElapsedMillis),
                style = pilgrimType.timer,
                color = pilgrimColors.ink,
            )
            Spacer(Modifier.height(PilgrimSpacing.xs))
            Text(
                text = stringResource(R.string.walk_caption_every_step),
                style = pilgrimType.caption,
                color = pilgrimColors.fog.copy(alpha = 0.6f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatColumn(
                value = WalkFormat.distance(distanceMeters),
                label = stringResource(R.string.walk_stat_distance),
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = "—",
                label = stringResource(R.string.walk_stat_steps),
                modifier = Modifier.weight(1f),
            )
            StatColumn(
                value = "—",
                label = stringResource(R.string.walk_stat_ascent),
                modifier = Modifier.weight(1f),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            TimeChip(
                label = stringResource(R.string.walk_chip_walk),
                icon = Icons.AutoMirrored.Filled.DirectionsWalk,
                value = WalkFormat.shortDuration(walkMillis),
                active = walkState is WalkState.Active,
                modifier = Modifier.weight(1f),
            )
            TimeChip(
                label = stringResource(R.string.walk_chip_talk),
                icon = Icons.Filled.Mic,
                value = WalkFormat.shortDuration(talkMillis),
                active = recorderState is VoiceRecorderUiState.Recording,
                modifier = Modifier.weight(1f),
            )
            TimeChip(
                label = stringResource(R.string.walk_chip_meditate),
                icon = Icons.Outlined.SelfImprovement,
                value = WalkFormat.shortDuration(meditateMillis),
                active = walkState is WalkState.Meditating,
                modifier = Modifier.weight(1f),
            )
        }
        ActionButtonRow(
            walkState = walkState,
            recorderState = recorderState,
            audioLevel = audioLevel,
            recordingsCount = recordingsCount,
            onStartMeditation = onStartMeditation,
            onEndMeditation = onEndMeditation,
            onToggleRecording = onToggleRecording,
            onPermissionDenied = onPermissionDenied,
            onDismissError = onDismissError,
            onFinish = onFinish,
        )
    }
}

@Composable
private fun TimeChip(
    label: String,
    icon: ImageVector,
    value: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val border = if (active) pilgrimColors.dawn else pilgrimColors.fog.copy(alpha = 0.4f)
    val tint = if (active) pilgrimColors.ink else pilgrimColors.fog
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(pilgrimColors.parchmentSecondary)
            .border(1.dp, border, RoundedCornerShape(percent = 50))
            .padding(vertical = PilgrimSpacing.xs, horizontal = PilgrimSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                style = pilgrimType.statValue,
                color = pilgrimColors.ink,
            )
            Text(
                text = label,
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
    }
}

@Composable
private fun ActionButtonRow(
    walkState: WalkState,
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    recordingsCount: Int,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onToggleRecording: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    onFinish: () -> Unit,
) {
    // 3-button row matching iOS reference: Meditate / Mic / End.
    // Manual Pause was removed — iOS relies on motion-based auto-pause
    // which Android does not yet have. Auto-pause is a deferred stage.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal),
    ) {
        when (walkState) {
            is WalkState.Active -> CircularActionButton(
                label = stringResource(R.string.walk_action_meditate_short),
                icon = Icons.Outlined.SelfImprovement,
                color = pilgrimColors.dawn,
                onClick = onStartMeditation,
                modifier = Modifier.weight(1f),
            )
            is WalkState.Meditating -> CircularActionButton(
                label = stringResource(R.string.walk_action_end_meditation_short),
                icon = Icons.Filled.Stop,
                color = pilgrimColors.dawn,
                onClick = onEndMeditation,
                modifier = Modifier.weight(1f),
            )
            else -> CircularActionButton(
                label = stringResource(R.string.walk_action_meditate_short),
                icon = Icons.Outlined.SelfImprovement,
                color = pilgrimColors.fog,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        MicActionButton(
            recorderState = recorderState,
            audioLevel = audioLevel,
            walkState = walkState,
            onToggle = onToggleRecording,
            onPermissionDenied = onPermissionDenied,
            onDismissError = onDismissError,
            modifier = Modifier.weight(1f),
        )
        CircularActionButton(
            label = stringResource(R.string.walk_action_finish_short),
            icon = Icons.Filled.Stop,
            color = pilgrimColors.fog,
            onClick = onFinish,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CircularActionButton(
    label: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val effectiveColor = if (enabled) color else pilgrimColors.fog.copy(alpha = 0.4f)
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        // indication = null suppresses the default Material ripple,
        // which would otherwise draw a bounded rectangle over the
        // Column (button circle + label) — visually a square card
        // around the circular button.
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
            onClick = onClick,
        ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = effectiveColor.copy(alpha = 0.06f),
                    shape = CircleShape,
                )
                .border(1.5.dp, effectiveColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = effectiveColor,
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.xs))
        Text(
            text = label,
            style = pilgrimType.caption,
            color = effectiveColor,
            maxLines = 1,
        )
    }
}

/**
 * Circular mic-toggle button used inside the bottom sheet's action row.
 * Owns the same permission launcher + Idle/Recording/Error protocol the
 * standalone control used pre-9.5-B; visually framed as a circular pill
 * matching the other action buttons in the row.
 */
@Composable
private fun MicActionButton(
    recorderState: VoiceRecorderUiState,
    audioLevel: Float,
    walkState: WalkState,
    onToggle: () -> Unit,
    onPermissionDenied: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val enabled = walkState.isInProgress
    val isRecording = recorderState is VoiceRecorderUiState.Recording

    // rememberLauncherForActivityResult's DisposableEffect keys on contract
    // reference identity — inline `ActivityResultContracts.RequestPermission()`
    // produces a new instance per recompose, causing unregister/re-register
    // races that drop in-flight permission results (Stage 7-A precedent).
    val permissionContract = remember { ActivityResultContracts.RequestPermission() }
    val permLauncher = rememberLauncherForActivityResult(
        contract = permissionContract,
    ) { granted ->
        if (granted) onToggle() else onPermissionDenied()
    }

    // rememberUpdatedState mandatory for LaunchedEffect-scoped delayed callbacks
    // (Stage 4-B precedent) — without it, the captured lambda goes stale on
    // fresh-lambda recompose and the dismiss fires against an obsolete reference.
    val currentOnDismissError by rememberUpdatedState(onDismissError)
    val err = recorderState as? VoiceRecorderUiState.Error
    LaunchedEffect(err) {
        if (err != null && err.kind != VoiceRecorderUiState.Kind.Cancelled) {
            delay(MIC_ERROR_BANNER_MS)
            currentOnDismissError()
        }
    }

    val baseColor = if (isRecording) pilgrimColors.rust else pilgrimColors.stone
    val effectiveColor = if (enabled) baseColor else pilgrimColors.fog.copy(alpha = 0.4f)
    // The label sitting under the mic intentionally MIRRORS the chip's
    // "Talk" label so the spatial association is obvious to the user.
    val label = if (isRecording) "REC" else stringResource(R.string.walk_chip_talk)

    val interactionSource = remember { MutableInteractionSource() }
    Column(
        // indication = null: same rationale as CircularActionButton —
        // suppress the bounded rectangle ripple over the circular pill.
        modifier = modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            role = Role.Button,
        ) {
            if (isRecording || PermissionChecks.isMicrophoneGranted(context)) {
                onToggle()
            } else {
                permLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    color = effectiveColor.copy(
                        alpha = if (isRecording) 0.18f else 0.06f,
                    ),
                    shape = CircleShape,
                )
                .border(1.5.dp, effectiveColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = null,
                tint = effectiveColor,
            )
        }
        Spacer(Modifier.height(PilgrimSpacing.xs))
        Text(
            text = label,
            style = pilgrimType.caption,
            color = effectiveColor,
            maxLines = 1,
        )
    }
}

private const val MIC_ERROR_BANNER_MS = 4_000L

private val DRAG_THRESHOLD_DP = 40.dp
// Density-relative flick threshold. Compose's VelocityTracker reports
// velocity in px/s, so we convert dp via density.toPx() at use time —
// a "300dp/s" flick scales naturally across screen densities.
private val DRAG_FLICK_VELOCITY_DP = 300.dp
private val DRAG_CLAMP_DP = 100.dp

// Spring-back animation when the user releases below the commit
// threshold. Default damping ratio + slightly lower stiffness reads as
// a gentle rubber-band rather than a stiff snap.
private val SNAP_BACK_SPEC = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

internal const val MINIMIZED_LAYER_TAG = "walk-sheet-minimized-layer"
internal const val EXPANDED_LAYER_TAG = "walk-sheet-expanded-layer"
