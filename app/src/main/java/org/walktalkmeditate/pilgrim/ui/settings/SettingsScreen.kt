// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.connect.ConnectCard
import org.walktalkmeditate.pilgrim.ui.settings.permissions.PermissionsCard
import org.walktalkmeditate.pilgrim.ui.settings.practice.PracticeCard
import org.walktalkmeditate.pilgrim.ui.settings.voice.VoiceCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Settings scaffold. Card-based layout matching iOS exactly: no nav
 * bar, a centered "Settings" title at the top of the scroll content,
 * then cards spaced evenly down the page. Stage 10-A lands this
 * scaffolding plus AtmosphereCard + the existing CollectiveStats
 * card; subsequent stages absorb Voice Guides + Soundscapes into
 * proper cards (Voice card / Bells & Soundscapes card).
 *
 * Navigation is funneled through a single [SettingsAction] channel —
 * the host (PilgrimNavHost) routes each action to a navController
 * call or system Intent dispatch. New cards extend [SettingsAction]
 * without changing this signature.
 */
@Composable
fun SettingsScreen(
    onAction: (SettingsAction) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val optIn by viewModel.optIn.collectAsStateWithLifecycle()
    val appearanceMode by viewModel.appearanceMode.collectAsStateWithLifecycle()
    val soundsEnabled by viewModel.soundsEnabled.collectAsStateWithLifecycle()
    val distanceUnits by viewModel.distanceUnits.collectAsStateWithLifecycle()
    val beginWithIntention by viewModel.beginWithIntention.collectAsStateWithLifecycle()
    val celestialAwareness by viewModel.celestialAwarenessEnabled.collectAsStateWithLifecycle()
    val zodiacSystem by viewModel.zodiacSystem.collectAsStateWithLifecycle()
    val walkReliquary by viewModel.walkReliquaryEnabled.collectAsStateWithLifecycle()
    val voiceCardState by viewModel.voiceCardState.collectAsStateWithLifecycle()
    // Android's Photo Picker (ActivityResultContracts.PickVisualMedia)
    // doesn't require a runtime permission on API 33+ and uses the
    // photo-picker pseudo-permission below that, so this flag will rarely
    // flip to true in practice. It is screen-level transient state per
    // the iOS pattern and survives rotation via rememberSaveable.
    var showPhotosDeniedNote by rememberSaveable { mutableStateOf(false) }
    // rememberLazyListState wraps a rememberSaveable internally — without
    // it, rotating the device would yank the user back to the top of
    // Settings instead of preserving their scroll position.
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) { viewModel.fetchOnAppear() }
    Scaffold(
        // Stage 9.5-A: PilgrimNavHost's outer Scaffold already consumed
        // the system bar insets; nesting this Scaffold with the default
        // contentWindowInsets = WindowInsets.safeDrawing would re-apply
        // them and add a visible gap above the bottom nav + below the
        // status bar. Pass WindowInsets(0) so the inner Scaffold doesn't
        // double-count.
        contentWindowInsets = WindowInsets(0),
        containerColor = pilgrimColors.parchment,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            state = listState,
            contentPadding = PaddingValues(top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }
            item {
                CollectiveStatsCard(stats = stats, units = distanceUnits)
            }
            item {
                PracticeCard(
                    beginWithIntention = beginWithIntention,
                    onSetBeginWithIntention = viewModel::setBeginWithIntention,
                    celestialAwareness = celestialAwareness,
                    onSetCelestialAwareness = viewModel::setCelestialAwarenessEnabled,
                    zodiacSystem = zodiacSystem,
                    onSetZodiacSystem = viewModel::setZodiacSystem,
                    distanceUnits = distanceUnits,
                    onSetDistanceUnits = viewModel::setDistanceUnits,
                    walkWithCollective = optIn,
                    onSetWalkWithCollective = viewModel::setOptIn,
                    walkReliquary = walkReliquary,
                    onSetWalkReliquary = viewModel::setWalkReliquaryEnabled,
                    showPhotosDeniedNote = showPhotosDeniedNote,
                )
            }
            // Stage 10-D: VoiceCard sits between Practice and
            // Atmosphere, matching iOS's settings card order.
            // Replaces the transitional voice-guides nav row.
            item {
                VoiceCard(
                    state = voiceCardState,
                    onSetVoiceGuideEnabled = viewModel::setVoiceGuideEnabled,
                    onSetAutoTranscribe = viewModel::setAutoTranscribe,
                    onOpenVoiceGuides = { onAction(SettingsAction.OpenVoiceGuides) },
                    onOpenRecordings = { onAction(SettingsAction.OpenRecordings) },
                )
            }
            item {
                AtmosphereCard(
                    currentMode = appearanceMode,
                    onSelectMode = viewModel::setAppearanceMode,
                    soundsEnabled = soundsEnabled,
                    onSetSoundsEnabled = viewModel::setSoundsEnabled,
                    onAction = onAction,
                )
            }
            item {
                PermissionsCard(onAction = onAction)
            }
            item {
                ConnectCard(onAction = onAction)
            }
            // Soundscapes is landed here as a SettingNavRow
            // stand-in inside a settingsCard wrapper to share
            // AtmosphereCard's 32dp content indent. The row will
            // be absorbed into a proper Bells & Soundscapes card
            // in Stage 10-B at which point this transitional
            // wrapper goes away.
            item {
                Column(modifier = Modifier.fillMaxWidth().settingsCard()) {
                    SettingNavRow(
                        label = stringResource(R.string.settings_soundscapes_row),
                        detail = stringResource(R.string.settings_soundscapes_subtitle),
                        onClick = { onAction(SettingsAction.OpenSoundscapes) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

