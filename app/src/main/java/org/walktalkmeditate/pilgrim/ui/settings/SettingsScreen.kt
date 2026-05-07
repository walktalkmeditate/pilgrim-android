// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.about.PilgrimLogo
import org.walktalkmeditate.pilgrim.ui.settings.connect.ConnectCard
import org.walktalkmeditate.pilgrim.ui.settings.data.DataCard
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
    val practiceSummary by viewModel.practiceSummary.collectAsStateWithLifecycle()
    val milestone by viewModel.milestone.collectAsStateWithLifecycle()
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
        val density = LocalDensity.current
        var titleHeightPx by rememberSaveable { mutableStateOf(0) }
        val titleHeightDp = with(density) { titleHeightPx.toDp() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = titleHeightDp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
            item {
                PracticeSummaryHeader(
                    walkCount = practiceSummary.walkCount,
                    totalDistanceMeters = practiceSummary.totalDistanceMeters,
                    totalMeditationSeconds = practiceSummary.totalMeditationSeconds,
                    firstWalkInstant = practiceSummary.firstWalkInstant,
                    distanceUnits = distanceUnits,
                    collectiveStats = stats,
                    milestone = milestone,
                    onMilestoneShown = viewModel::onMilestoneShown,
                    onMilestoneDismiss = viewModel::dismissMilestone,
                )
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
            // iOS card order: Practice → Atmosphere → Voice → Permissions
            // → Data → Connect → About. Stage 10-D originally landed
            // VoiceCard before AtmosphereCard; reordered here for iOS
            // pixel-parity per user request.
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
                VoiceCard(
                    state = voiceCardState,
                    onSetVoiceGuideEnabled = viewModel::setVoiceGuideEnabled,
                    onSetAutoTranscribe = viewModel::setAutoTranscribe,
                    onOpenVoiceGuides = { onAction(SettingsAction.OpenVoiceGuides) },
                    onOpenRecordings = { onAction(SettingsAction.OpenRecordings) },
                )
            }
            item {
                PermissionsCard(onAction = onAction)
            }
            item {
                DataCard(onAction = onAction)
            }
            item {
                ConnectCard(onAction = onAction)
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .settingsCard()
                        .clickable(onClickLabel = stringResource(R.string.settings_about_pilgrim)) {
                            onAction(SettingsAction.OpenAbout)
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PilgrimLogo(size = 24.dp, breathing = true)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.settings_about_pilgrim),
                        style = pilgrimType.body,
                        color = pilgrimColors.ink,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                        style = pilgrimType.caption,
                        color = pilgrimColors.fog,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = pilgrimColors.fog,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            }

            // iOS-parity sticky title — overlays the LazyColumn via
            // zIndex with a semi-transparent parchment fill so list
            // content peeks behind during scroll. Measured height feeds
            // contentPadding(top) above so the first card sits below
            // the header at rest.
            Text(
                text = stringResource(R.string.settings_title),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(1f)
                    .background(pilgrimColors.parchment.copy(alpha = 0.85f))
                    .padding(top = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp)
                    .onSizeChanged { titleHeightPx = it.height },
                textAlign = TextAlign.Center,
            )
        }
    }
}

