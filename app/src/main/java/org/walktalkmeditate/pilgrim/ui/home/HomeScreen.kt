// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.BuildConfig
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
 * Stage 3-A: Home surface with walk list. Stage 1-E introduced the
 * scaffolding (Start button, BatteryExemptionCard, resume-check
 * LaunchedEffect); 3-A replaces the placeholder title+subtitle with
 * a [HomeViewModel]-driven list of finished walks.
 *
 * The resume-check is preserved verbatim: on first composition, route
 * straight to ActiveWalk if the controller is already tracking or
 * there's an unfinished walk row in Room. Using a one-shot
 * `LaunchedEffect(Unit)` rather than a state-observer avoids a back-
 * stack race where HomeScreen stays STARTED while the user is on
 * ActiveWalkScreen, and state transitions Active → Paused →
 * Meditating would stack duplicate ACTIVE_WALK entries.
 */
@Composable
fun HomeScreen(
    permissionsViewModel: PermissionsViewModel,
    onEnterActiveWalk: () -> Unit,
    onEnterWalkSummary: (Long) -> Unit,
    onEnterCalligraphyPreview: () -> Unit,
    walkViewModel: WalkViewModel = hiltViewModel(),
    homeViewModel: HomeViewModel = hiltViewModel(),
) {
    val didResumeCheck = remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (didResumeCheck.value) return@LaunchedEffect
        didResumeCheck.value = true
        val current = walkViewModel.uiState.value.walkState
        Log.i(TAG, "resume-check entry state=${current::class.simpleName}")
        if (current.isInProgress) {
            Log.i(TAG, "resume-check: already in progress, navigating to ActiveWalk")
            onEnterActiveWalk()
            return@LaunchedEffect
        }
        val restored = walkViewModel.restoreActiveWalk()
        if (restored != null) {
            Log.i(TAG, "resume-check: restored walk id=${restored.id}, navigating to ActiveWalk")
            onEnterActiveWalk()
        } else {
            Log.i(TAG, "resume-check: nothing to restore, staying on Home")
        }
    }

    val uiState by homeViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        Spacer(Modifier.height(PilgrimSpacing.big))

        HomeListContent(
            uiState = uiState,
            onRowClick = onEnterWalkSummary,
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

        // Debug-only preview entry point for Stage 3-C's calligraphy
        // renderer. Removed in Stage 3-E when the renderer lands inside
        // this screen's scroll.
        if (BuildConfig.DEBUG) {
            Spacer(Modifier.height(PilgrimSpacing.big))
            TextButton(
                onClick = onEnterCalligraphyPreview,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Calligraphy preview (debug)")
            }
        }
    }
}

@Composable
private fun HomeListContent(
    uiState: HomeUiState,
    onRowClick: (Long) -> Unit,
) {
    when (uiState) {
        is HomeUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = pilgrimColors.stone,
                )
            }
        }
        is HomeUiState.Empty -> {
            Text(
                text = stringResource(R.string.home_empty_message),
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
        }
        is HomeUiState.Loaded -> {
            // Plain Column.forEach rather than LazyColumn — the
            // enclosing verticalScroll + LazyColumn would crash with a
            // nested-scroll exception. Low volume of walks (tens,
            // maybe low hundreds) is comfortable for non-lazy render.
            // When Stage 3-E introduces the calligraphy journal
            // thread, revisit to LazyColumn with a custom decoration.
            Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
                uiState.rows.forEach { row ->
                    HomeWalkRowCard(
                        row = row,
                        onClick = { onRowClick(row.walkId) },
                    )
                }
            }
        }
    }
}
