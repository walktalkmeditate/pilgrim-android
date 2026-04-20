// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import android.util.Log
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.domain.isInProgress
import org.walktalkmeditate.pilgrim.permissions.PermissionsViewModel
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyPath
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.CalligraphyStrokeSpec
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.SeasonalInkFlavor
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.toBaseColor
import org.walktalkmeditate.pilgrim.ui.onboarding.BatteryExemptionCard
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import org.walktalkmeditate.pilgrim.ui.walk.WalkViewModel

private const val TAG = "HomeScreen"

// Approximate card-row stride (card ~116dp + PilgrimSpacing.normal 16dp
// gap). Drives CalligraphyPath's internal dot-Y placement so the thread
// feels connected to the card list without per-card measurement.
// Stage 3-F on-device QA may tune this value.
private val JOURNAL_ROW_STRIDE = 132.dp
private val JOURNAL_TOP_INSET = 24.dp

/**
 * Stage 3-A: Home surface with walk list. Stage 1-E introduced the
 * scaffolding (Start button, BatteryExemptionCard, resume-check
 * LaunchedEffect); 3-A replaced the placeholder with a
 * [HomeViewModel]-driven list of finished walks; Stage 3-E lays a
 * calligraphy ink thread behind the cards, tinted by each walk's
 * date + the device hemisphere.
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
    onEnterGoshuin: () -> Unit,
    onEnterSettings: () -> Unit,
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
    val hemisphere by homeViewModel.hemisphere.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.home_title),
                style = pilgrimType.displayMedium,
                color = pilgrimColors.ink,
            )
            IconButton(onClick = onEnterSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings_title),
                    tint = pilgrimColors.ink,
                )
            }
        }
        Spacer(Modifier.height(PilgrimSpacing.big))

        HomeListContent(
            uiState = uiState,
            hemisphere = hemisphere,
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

        Spacer(Modifier.height(PilgrimSpacing.normal))

        OutlinedButton(
            onClick = onEnterGoshuin,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_action_view_goshuin))
        }

        Spacer(Modifier.height(PilgrimSpacing.big))
        BatteryExemptionCard(viewModel = permissionsViewModel)
    }
}

@Composable
private fun HomeListContent(
    uiState: HomeUiState,
    hemisphere: Hemisphere,
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
            JournalThread(
                rows = uiState.rows,
                hemisphere = hemisphere,
                onRowClick = onRowClick,
            )
        }
    }
}

/**
 * The calligraphy-threaded walk list. Two layers:
 *   1. [CalligraphyPath] canvas, sized to match the card stack.
 *   2. Column of [HomeWalkRowCard]s on top.
 *
 * Cards are opaque `parchmentSecondary`; the thread peeks through the
 * 16dp inter-card gaps. Cards aren't dot-aligned to the thread — the
 * thread uses [JOURNAL_ROW_STRIDE] as an approximate card+gap spacing,
 * which reads as "connected" without per-card measurement.
 */
@Composable
private fun JournalThread(
    rows: List<HomeWalkRow>,
    hemisphere: Hemisphere,
    onRowClick: (Long) -> Unit,
) {
    // Resolve the four base seasonal palette colors from the current
    // theme once per theme change. The per-stroke seasonal HSB shift
    // then runs inside `remember` so a scroll or unrelated recomposition
    // doesn't rebuild N Color allocations. Matches Stage 3-B's
    // staticCompositionLocalOf hygiene lesson.
    val inkBase = SeasonalInkFlavor.Ink.toBaseColor()
    val mossBase = SeasonalInkFlavor.Moss.toBaseColor()
    val rustBase = SeasonalInkFlavor.Rust.toBaseColor()
    val dawnBase = SeasonalInkFlavor.Dawn.toBaseColor()
    val baseColors = remember(inkBase, mossBase, rustBase, dawnBase) {
        mapOf(
            SeasonalInkFlavor.Ink to inkBase,
            SeasonalInkFlavor.Moss to mossBase,
            SeasonalInkFlavor.Rust to rustBase,
            SeasonalInkFlavor.Dawn to dawnBase,
        )
    }

    val strokes: List<CalligraphyStrokeSpec> = remember(rows, hemisphere, baseColors) {
        rows.map { row ->
            val walkDate = Instant.ofEpochMilli(row.startTimestamp)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val flavor = SeasonalInkFlavor.forMonth(row.startTimestamp)
            val base = baseColors.getValue(flavor)
            val tint = SeasonalColorEngine.applySeasonalShift(
                base = base,
                intensity = SeasonalColorEngine.Intensity.Moderate,
                date = walkDate,
                hemisphere = hemisphere,
            )
            val pace = if (row.distanceMeters > 0.0 && row.durationSeconds > 0.0) {
                row.durationSeconds / (row.distanceMeters / 1000.0)
            } else {
                0.0
            }
            CalligraphyStrokeSpec(
                uuid = row.uuid,
                startMillis = row.startTimestamp,
                distanceMeters = row.distanceMeters,
                averagePaceSecPerKm = pace,
                ink = tint,
            )
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        CalligraphyPath(
            strokes = strokes,
            modifier = Modifier.fillMaxWidth(),
            verticalSpacing = JOURNAL_ROW_STRIDE,
            topInset = JOURNAL_TOP_INSET,
        )
        Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.normal)) {
            rows.forEach { row ->
                HomeWalkRowCard(
                    row = row,
                    onClick = { onRowClick(row.walkId) },
                )
            }
        }
    }
}
