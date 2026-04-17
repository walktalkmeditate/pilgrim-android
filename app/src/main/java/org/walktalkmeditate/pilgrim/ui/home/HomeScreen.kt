// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.util.Log
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.onboarding.BatteryExemptionCard
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

private const val TAG = "HomeScreen"

/**
 * Stage 1-E home surface. On first composition, runs a one-shot resume
 * check: if the controller is already tracking (notification-return, or
 * unfinished walk row restored from Room), route straight to ActiveWalk.
 * Otherwise show the Start button.
 *
 * Using a one-shot LaunchedEffect(Unit) rather than a state-observer
 * LaunchedEffect avoids a back-stack race where HomeScreen stays in
 * STARTED lifecycle while the user is on ActiveWalkScreen, and state
 * transitions from Active → Paused → Meditating would re-fire a
 * navigate call on the observer, stacking duplicate ACTIVE_WALK
 * entries.
 */
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
) {
    val didResumeCheck = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didResumeCheck.value) return@LaunchedEffect
        didResumeCheck.value = true
        // Already walking (notification-return, recomposition after
        // backgrounding)? Go straight to the active screen.
        val current = walkViewModel.uiState.value.walkState
        Log.i(TAG, "resume-check entry state=${current::class.simpleName}")
        if (current.isInProgress) {
            Log.i(TAG, "resume-check: already in progress, navigating to ActiveWalk")
            onEnterActiveWalk()
            return@LaunchedEffect
        }
        // Walk row in Room from a previous process — rehydrate and go.
        val restored = walkViewModel.restoreActiveWalk()
        if (restored != null) {
            Log.i(TAG, "resume-check: restored walk id=${restored.id}, navigating to ActiveWalk")
            onEnterActiveWalk()
        } else {
            Log.i(TAG, "resume-check: nothing to restore, staying on Home")
        }
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
                onEnterActiveWalk()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_action_start_walk))
        }

        Spacer(Modifier.height(PilgrimSpacing.big))
        BatteryExemptionCard(viewModel = permissionsViewModel)
    }
}
