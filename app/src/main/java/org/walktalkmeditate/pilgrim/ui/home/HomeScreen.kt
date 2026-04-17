// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.onboarding.BatteryExemptionCard
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

/**
 * Stage 1-E home surface. Calls [WalkViewModel.restoreActiveWalk] on
 * first composition — if an unfinished walk row lives in Room from a
 * previous process (kill mid-walk), navigate straight to ActiveWalk
 * so the user doesn't have to tap "Start" again to find their walk.
 * Otherwise show the Start button.
 */
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onStartWalk: () -> Unit,
    onResumeWalk: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
) {
    // On first composition only, restore a walk left unfinished in Room
    // by a process kill. The state-class observer below handles the
    // actual navigation; this just flips the controller state.
    var didCheckResume by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didCheckResume) return@LaunchedEffect
        didCheckResume = true
        walkViewModel.restoreActiveWalk()
    }

    // Whenever the controller reports a walk in progress (restored from
    // Room, or navigated-back-to-Home by accident via system back), route
    // the user to ActiveWalk. This is the safety net that prevents a
    // tracking walk from being orphaned without a UI entry point.
    val uiState by walkViewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(uiState.walkState::class) {
        if (uiState.walkState.isInProgress) onResumeWalk()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Text(
            text = stringResource(R.string.home_placeholder_title),
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(PilgrimSpacing.small))
        Text(
            text = stringResource(R.string.home_placeholder_subtitle),
            style = pilgrimType.body,
            color = pilgrimColors.fog,
        )
        Spacer(Modifier.height(PilgrimSpacing.big))

        Button(
            onClick = {
                walkViewModel.startWalk()
                onStartWalk()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_action_start_walk))
        }

        Spacer(Modifier.height(PilgrimSpacing.big))
        BatteryExemptionCard(viewModel = permissionsViewModel)
    }
}
