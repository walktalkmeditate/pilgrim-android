// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collectLatest
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.LocalSoundsEnabled
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.home.dot.WalkDot
import org.walktalkmeditate.pilgrim.ui.home.dot.WalkDotMath
import org.walktalkmeditate.pilgrim.ui.home.scroll.JournalHapticDispatcher
import org.walktalkmeditate.pilgrim.ui.home.scroll.ScrollHapticState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

private val JOURNAL_ROW_HEIGHT = 90.dp
private val JOURNAL_TOP_INSET_DP = 40.dp

/**
 * Stage 14-A LazyColumn migration: each row is a [WalkDot]; chrome
 * (header, expand sheet, FAB, BatteryExemptionCard) returns in Bucket
 * 14-B / 14-D via the dedicated [JournalScreen]. The viewport-center
 * Y is fed into [ScrollHapticState] → [JournalHapticDispatcher] so a
 * scroll fires light/heavy haptics as dots cross center.
 *
 * `permissionsViewModel` and `onEnterWalkSummary` are unused for 14-A
 * (BatteryExemptionCard moves to JournalScreen in Bucket 14-D; row tap
 * opens the expand sheet via `setExpandedSnapshotId` rather than
 * navigating to the summary directly). Kept as parameters so the
 * NavHost call site doesn't need to change mid-stage.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterGoshuin: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val journalState by homeViewModel.journalState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val soundsEnabled = LocalSoundsEnabled.current
    // Closure-captured rememberUpdatedState so the dispatcher's
    // `soundsEnabledProvider` always sees the current Compose value
    // without re-instantiating the dispatcher on every flip.
    val soundsEnabledState = rememberUpdatedState(soundsEnabled)
    val dispatcher = remember(context) {
        JournalHapticDispatcher(
            context = context,
            soundsEnabledProvider = { soundsEnabledState.value },
        )
    }
    val density = LocalDensity.current
    val verticalSpacingPx = with(density) { JOURNAL_ROW_HEIGHT.toPx() }
    val topInsetPx = with(density) { JOURNAL_TOP_INSET_DP.toPx() }
    // iOS thresholds are in points; Android equivalent is dp. Convert
    // here so haptic windows are physically consistent across densities
    // (raw `20f` would shrink to 5dp on xxxhdpi screens and miss dots).
    val dotThresholdPx = with(density) { 20.dp.toPx() }
    val milestoneThresholdPx = with(density) { 25.dp.toPx() }
    val largeDotCutoffPx = with(density) { 15.dp.toPx() }

    val loaded = journalState as? JournalUiState.Loaded
    val snapshots = loaded?.snapshots ?: emptyList()
    val sizesPx = remember(snapshots) {
        snapshots.map { with(density) { WalkDotMath.dotSize(it.durationSec).dp.toPx() } }
    }
    // Dot Y in canvas-space matches the formula used by
    // `CalligraphyPath.dotPositions` so when Stage 14-D layers the
    // calligraphy canvas behind the LazyColumn, haptic-fire positions,
    // dot draw positions, and lunar/milestone-marker positions all
    // share one origin: `topInset + spacing * i + spacing / 2`. The
    // LazyColumn applies `contentPadding(top = topInset)` below so
    // item-0's visual top sits at y = topInset.
    val dotYsPx = remember(snapshots, topInsetPx, verticalSpacingPx) {
        snapshots.indices.map {
            topInsetPx + verticalSpacingPx * it + verticalSpacingPx / 2f
        }
    }
    val hapticState = remember(snapshots, dotThresholdPx, milestoneThresholdPx, largeDotCutoffPx) {
        ScrollHapticState(
            dotPositionsPx = dotYsPx,
            dotSizesPx = sizesPx,
            milestonePositionsPx = emptyList(),
            largeDotCutoffPx = largeDotCutoffPx,
            dotThresholdPx = dotThresholdPx,
            milestoneThresholdPx = milestoneThresholdPx,
        )
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, hapticState, topInsetPx) {
        snapshotFlow {
            listState.firstVisibleItemIndex * verticalSpacingPx +
                listState.firstVisibleItemScrollOffset
        }.collectLatest { topPx ->
            // Convert into canvas-space (same coordinate frame as
            // `dotYsPx`): item-0-top sits at `topInsetPx` because of
            // `contentPadding(top = topInsetPx)`. Visible viewport
            // height is the gap between the two paddings.
            val visibleHeightPx = (
                listState.layoutInfo.viewportEndOffset -
                    listState.layoutInfo.viewportStartOffset
                ).toFloat()
            val centerPx = topInsetPx + topPx + visibleHeightPx / 2f
            val event = hapticState.handleViewportCenterPx(centerPx)
            dispatcher.dispatch(event)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val s = journalState) {
            JournalUiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = pilgrimColors.stone,
                    )
                }
            }
            JournalUiState.Empty -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(PilgrimSpacing.big),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.home_empty_message),
                        style = pilgrimType.body,
                        color = pilgrimColors.fog,
                    )
                }
            }
            is JournalUiState.Loaded -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = JOURNAL_TOP_INSET_DP),
                ) {
                    itemsIndexed(s.snapshots, key = { _, snap -> snap.id }) { index, snap ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(JOURNAL_ROW_HEIGHT),
                            contentAlignment = Alignment.Center,
                        ) {
                            WalkDot(
                                snapshot = snap,
                                sizeDp = WalkDotMath.dotSize(snap.durationSec),
                                color = MaterialTheme.colorScheme.onSurface,
                                opacity = WalkDotMath.dotOpacity(index, s.snapshots.size),
                                isNewest = index == 0,
                                contentDescription = "walk dot $index",
                                onTap = { homeViewModel.setExpandedSnapshotId(snap.id) },
                            )
                        }
                    }
                }
            }
        }

        // Goshuin compass FAB. Lives on Journal screen only (per spec).
        FloatingActionButton(
            onClick = onEnterGoshuin,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PilgrimSpacing.big),
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.stone,
        ) {
            Icon(
                imageVector = Icons.Outlined.Explore,
                contentDescription = stringResource(R.string.home_action_view_goshuin),
            )
        }
    }
}
