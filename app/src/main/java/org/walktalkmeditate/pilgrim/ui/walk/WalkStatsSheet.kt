// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState
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
    val sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)

    Box(modifier = modifier.fillMaxWidth()) {
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
                .navigationBarsPadding()
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
                    Box(modifier = Modifier.fillMaxWidth())
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
    val showExpanded = state == SheetState.Expanded
    Box {
        Box(
            modifier = Modifier
                .alpha(if (showExpanded) 0f else 1f)
                .testTag(MINIMIZED_LAYER_TAG),
        ) {
            minimizedContent()
        }
        Box(
            modifier = Modifier
                .alpha(if (showExpanded) 1f else 0f)
                .testTag(EXPANDED_LAYER_TAG),
        ) {
            expandedContent()
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

internal const val MINIMIZED_LAYER_TAG = "walk-sheet-minimized-layer"
internal const val EXPANDED_LAYER_TAG = "walk-sheet-expanded-layer"
