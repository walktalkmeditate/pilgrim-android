// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealRevealOverlay
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

@Composable
fun WalkSummaryScreen(
    onDone: () -> Unit,
    viewModel: WalkSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val playbackUiState by viewModel.playbackUiState.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.runStartupSweep() }

    // Stage 4-B: the reveal plays on this entry to WalkSummaryScreen.
    // After the overlay calls onDismiss (auto-dismiss at 2.5s or
    // tap-to-dismiss early), flip the flag so it doesn't replay on
    // unrelated recompositions (scroll, recordings flow update, etc.).
    // Accepts reveal-replay on back-nav + re-entry — iOS has the same
    // behavior and a proper single-play guard needs a Room migration.
    var showReveal by remember { mutableStateOf(true) }

    Box(modifier = Modifier.fillMaxSize()) {
        // Summary content renders first (z-order: behind the overlay).
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PilgrimSpacing.big),
        ) {
            Text(
                text = stringResource(R.string.summary_title),
                style = pilgrimType.displayMedium,
                color = pilgrimColors.ink,
            )
            Spacer(Modifier.height(PilgrimSpacing.big))

            when (val s = state) {
                is WalkSummaryUiState.Loading -> {
                    SummaryMapPlaceholder()
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    LoadingRow()
                }
                is WalkSummaryUiState.NotFound -> {
                    SummaryMapPlaceholder()
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    Text(
                        text = stringResource(R.string.summary_unavailable),
                        style = pilgrimType.body,
                        color = pilgrimColors.fog,
                    )
                }
                is WalkSummaryUiState.Loaded -> {
                    SummaryMap(points = s.summary.routePoints)
                    Spacer(Modifier.height(PilgrimSpacing.big))
                    SummaryStats(summary = s.summary)
                    if (recordings.isNotEmpty()) {
                        Spacer(Modifier.height(PilgrimSpacing.big))
                        VoiceRecordingsSection(
                            walkStartTimestamp = s.summary.walk.startTimestamp,
                            recordings = recordings,
                            playbackUiState = playbackUiState,
                            onPlay = viewModel::playRecording,
                            onPause = viewModel::pausePlayback,
                        )
                    }
                }
            }

            Spacer(Modifier.height(PilgrimSpacing.breathingRoom))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.summary_action_done))
            }
        }

        // Stage 4-B reveal overlay: renders on top of the summary
        // content. Only for Loaded state — we need the sealSpec.
        // Loading / NotFound branches skip the overlay.
        val loaded = state as? WalkSummaryUiState.Loaded
        if (showReveal && loaded != null) {
            val baseInk = pilgrimColors.rust
            val walkDate = remember(loaded.summary.walk.startTimestamp) {
                Instant.ofEpochMilli(loaded.summary.walk.startTimestamp)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate()
            }
            val tintedInk = SeasonalColorEngine.applySeasonalShift(
                base = baseInk,
                intensity = SeasonalColorEngine.Intensity.Full,
                date = walkDate,
                hemisphere = hemisphere,
            )
            val specForReveal = loaded.summary.sealSpec.copy(ink = tintedInk)
            SealRevealOverlay(
                spec = specForReveal,
                onDismiss = { showReveal = false },
            )
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // CircularProgressIndicator needs equal width + height — height()
        // alone collapses to zero width and renders invisibly.
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = pilgrimColors.stone,
        )
    }
}

@Composable
private fun SummaryMap(points: List<org.walktalkmeditate.pilgrim.domain.LocationPoint>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
        ),
    ) {
        if (points.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.walk_map_no_route),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
        } else {
            PilgrimMap(
                points = points,
                followLatest = false,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SummaryMapPlaceholder() {
    // Shown during loading / not-found states to reserve the same visual
    // area a rendered map occupies — avoids a layout jump once the
    // summary resolves.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.fog,
        ),
    ) {}
}

@Composable
private fun SummaryStats(summary: WalkSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
        SummaryRow(
            label = stringResource(R.string.walk_stat_duration),
            value = WalkFormat.duration(summary.totalElapsedMillis),
        )
        SummaryRow(
            label = stringResource(R.string.walk_stat_active_walking),
            value = WalkFormat.duration(summary.activeWalkingMillis),
        )
        SummaryRow(
            label = stringResource(R.string.walk_stat_distance),
            value = WalkFormat.distance(summary.distanceMeters),
        )
        SummaryRow(
            label = stringResource(R.string.walk_stat_pace),
            value = WalkFormat.pace(summary.paceSecondsPerKm),
        )
        if (summary.totalPausedMillis > 0) {
            SummaryRow(
                label = stringResource(R.string.walk_stat_paused_time),
                value = WalkFormat.duration(summary.totalPausedMillis),
            )
        }
        if (summary.totalMeditatedMillis > 0) {
            SummaryRow(
                label = stringResource(R.string.walk_stat_meditation_time),
                value = WalkFormat.duration(summary.totalMeditatedMillis),
            )
        }
        if (summary.waypointCount > 0) {
            SummaryRow(
                label = stringResource(R.string.walk_stat_waypoints),
                value = summary.waypointCount.toString(),
            )
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        Text(
            text = value,
            style = pilgrimType.statValue,
            color = pilgrimColors.ink,
        )
    }
}
