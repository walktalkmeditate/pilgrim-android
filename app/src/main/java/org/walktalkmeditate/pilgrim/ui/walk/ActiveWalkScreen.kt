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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

@Composable
fun ActiveWalkScreen(
    onFinished: (walkId: Long) -> Unit,
    viewModel: WalkViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    // State-driven navigation: when the controller reaches Finished we
    // leave this screen for the summary. Avoids UI-layer state inference.
    LaunchedEffect(ui.walkState) {
        val state = ui.walkState
        if (state is WalkState.Finished) onFinished(state.walk.walkId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        MapPlaceholder()
        Spacer(Modifier.height(PilgrimSpacing.big))
        Timer(ui.totalElapsedMillis)
        Spacer(Modifier.height(PilgrimSpacing.normal))
        StatRow(
            distanceLabel = stringResource(R.string.walk_stat_distance),
            distanceValue = WalkFormat.distance(ui.distanceMeters),
            paceLabel = stringResource(R.string.walk_stat_pace),
            paceValue = WalkFormat.pace(ui.paceSecondsPerKm),
        )
        Spacer(Modifier.height(PilgrimSpacing.breathingRoom))
        Controls(
            walkState = ui.walkState,
            onPause = viewModel::pauseWalk,
            onResume = viewModel::resumeWalk,
            onStartMeditation = viewModel::startMeditation,
            onEndMeditation = viewModel::endMeditation,
            onFinish = viewModel::finishWalk,
        )
    }
}

@Composable
private fun MapPlaceholder() {
    // Stage 1-F replaces this card with the Mapbox Compose wrapper.
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
        colors = CardDefaults.cardColors(
            containerColor = pilgrimColors.parchmentSecondary,
            contentColor = pilgrimColors.fog,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.walk_map_placeholder),
                style = pilgrimType.caption,
            )
        }
    }
}

@Composable
private fun Timer(elapsedMillis: Long) {
    Text(
        text = WalkFormat.duration(elapsedMillis),
        style = pilgrimType.timer,
        color = pilgrimColors.ink,
    )
}

@Composable
private fun StatRow(
    distanceLabel: String,
    distanceValue: String,
    paceLabel: String,
    paceValue: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Stat(label = distanceLabel, value = distanceValue)
        Stat(label = paceLabel, value = paceValue)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(text = value, style = pilgrimType.statValue, color = pilgrimColors.ink)
        Text(text = label, style = pilgrimType.statLabel, color = pilgrimColors.fog)
    }
}

@Composable
private fun Controls(
    walkState: WalkState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStartMeditation: () -> Unit,
    onEndMeditation: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (walkState) {
                is WalkState.Active -> {
                    TextButton(onClick = onStartMeditation) {
                        Text(stringResource(R.string.walk_action_meditate))
                    }
                    Button(onClick = onPause) {
                        Text(stringResource(R.string.walk_action_pause))
                    }
                }
                is WalkState.Paused -> {
                    Spacer(modifier = Modifier.size(0.dp))
                    Button(onClick = onResume) {
                        Text(stringResource(R.string.walk_action_resume))
                    }
                }
                is WalkState.Meditating -> {
                    Spacer(modifier = Modifier.size(0.dp))
                    Button(onClick = onEndMeditation) {
                        Text(stringResource(R.string.walk_action_end_meditation))
                    }
                }
                WalkState.Idle, is WalkState.Finished -> Unit
            }
        }
        Spacer(Modifier.height(PilgrimSpacing.normal))
        Button(
            onClick = onFinish,
            enabled = walkState !is WalkState.Finished && walkState != WalkState.Idle,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = pilgrimColors.rust,
                contentColor = pilgrimColors.parchment,
            ),
        ) {
            Text(stringResource(R.string.walk_action_finish))
        }
    }
}
