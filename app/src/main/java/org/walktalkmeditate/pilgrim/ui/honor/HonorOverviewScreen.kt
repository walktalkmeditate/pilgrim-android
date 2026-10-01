// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.ZoneId
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.PilgrimMap
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
import org.walktalkmeditate.pilgrim.ui.walk.map.rememberWayMapPins

/**
 * iOS `HonorOverviewView` (parity spec F §8–§14): the map fit to the whole
 * Way, the card over it, and Begin. Begin only navigates: it closes the
 * overview and opens the walk screen before its Start (spec correction 1).
 * The camera never follows the puck here.
 */
@Composable
fun HonorOverviewScreen(
    onClose: () -> Unit,
    onBegin: (sourceWalkId: Long) -> Unit,
    viewModel: HonorOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val voicesEnabled by viewModel.voicesEnabled.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val positionMillis by viewModel.playbackPositionMillis.collectAsStateWithLifecycle()
    val speed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val waveforms by viewModel.waveforms.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state is HonorOverviewUiState.Unavailable) onClose()
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(pilgrimColors.parchment),
    ) {
        HonorOverviewTopBar(onClose = onClose)
        val overview = (state as? HonorOverviewUiState.Ready)?.overview ?: return@Column
        var previewMomentId by rememberSaveable(overview.way.id) { mutableStateOf<String?>(null) }
        // Keyed on the id, not the tap, so a preview restored after the
        // process was killed still loads its waveform and its 1x.
        LaunchedEffect(previewMomentId) {
            previewMomentId?.let(viewModel::openPreview)
        }
        val context = LocalContext.current
        val showsPuck = remember(context) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        val mapPins = rememberWayMapPins(overview.pins)
        HonorOverviewFrame(
            map = { cardHeight ->
                PilgrimMap(
                    points = emptyList(),
                    modifier = Modifier.fillMaxSize(),
                    followLatest = false,
                    // The card's measured height is the map's bottom inset;
                    // the map keeps its full height beneath it (F §9.2).
                    bottomInsetDp = cardHeight,
                    cameraBounds = overview.bounds,
                    showsUserLocation = showsPuck,
                    ornamentBottomInsetDp = cardHeight,
                    honorWay = overview.line,
                    wayPins = mapPins,
                    onWayPinTap = { momentId -> previewMomentId = momentId },
                )
            },
            card = {
                HonorOverviewCard(
                    overview = overview,
                    units = units,
                    voicesEnabled = voicesEnabled,
                    onVoicesEnabledChange = viewModel::setVoicesEnabled,
                    onBegin = { onBegin(overview.sourceWalkId) },
                )
            },
        )
        val moment = previewMomentId?.let { id -> overview.way.moments.firstOrNull { it.id == id } }
        if (moment != null) {
            val recording = overview.playableVoices[moment.id]
            val voice = recording?.let {
                val current = when (val p = playback) {
                    is PlaybackState.Playing -> p.recordingId == it.id
                    is PlaybackState.Paused -> p.recordingId == it.id
                    else -> false
                }
                WayVoicePreview(
                    isPlaying = playback is PlaybackState.Playing && current,
                    positionSeconds = if (current) positionMillis / 1000.0 else 0.0,
                    totalSeconds = it.durationMillis / 1000.0,
                    speed = speed,
                    waveform = waveforms[it.id],
                )
            }
            WayMomentPreviewSheet(
                way = overview.way,
                moment = moment,
                units = units,
                voice = voice.takeIf { moment.kind is WayMomentKind.Voice },
                onTogglePlay = { viewModel.togglePreviewVoice(moment.id) },
                onCycleSpeed = viewModel::cyclePreviewSpeed,
                onSeek = { viewModel.seekPreviewVoice(moment.id, it) },
                onDismiss = {
                    viewModel.closePreview()
                    previewMomentId = null
                },
            )
        }
    }
}

/** iOS's inline bar with no title: "Close" leading, in stone. */
@Composable
private fun HonorOverviewTopBar(onClose: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = PilgrimSpacing.small),
    ) {
        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Text(stringResource(R.string.honor_close), style = pilgrimType.button, color = pilgrimColors.stone)
        }
    }
}

/**
 * The map full height with the card lying over its bottom edge. The
 * card's measured height reaches [map] as the inset, so the fit can run
 * beneath the card and still frame the whole Way in what stays uncovered
 * (iOS `CardHeightKey`, `HonorOverviewView.swift:112-114,411-418@7c200bf`).
 */
@Composable
internal fun HonorOverviewFrame(
    map: @Composable (cardHeight: Dp) -> Unit,
    card: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    var cardHeightPx by remember { mutableIntStateOf(0) }
    val cardHeight = with(LocalDensity.current) { cardHeightPx.toDp() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) { map(cardHeight) }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { cardHeightPx = it.height },
        ) {
            card()
        }
    }
}

/**
 * The card (F §8, §10): a flat parchment rectangle spanning the width, not
 * scrollable. Every line is its own TalkBack element, the stats row's two
 * "·" included, as iOS reads them (pilgrim-ios #108, matched).
 */
@Composable
internal fun HonorOverviewCard(
    overview: HonorOverview,
    units: UnitSystem,
    voicesEnabled: Boolean,
    onVoicesEnabledChange: (Boolean) -> Unit,
    onBegin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val way = overview.way
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(text = way.title, style = pilgrimType.heading, color = pilgrimColors.ink)
        Text(
            text = HonorOverviewModel.departureLine(way, ZoneId.systemDefault(), locale),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small)) {
            StatsText(WalkFormat.distance(way.totalDistanceMeters, units))
            StatsText(STATS_SEPARATOR)
            StatsText(HonorOverviewModel.durationText(way.theirActiveSeconds))
            StatsText(STATS_SEPARATOR)
            StatsText(HonorOverviewModel.countsLine(resources, way))
        }
        HonorOverviewModel.weatherLine(resources, way.weather, overview.todayCondition, locale)?.let {
            Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        HonorOverviewModel.statusLine(resources, overview.distanceToStartMeters, units)?.let {
            Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        VoicesToggle(
            checked = voicesEnabled,
            // A quiet way keeps showing its stored value, switched off from use.
            enabled = way.voiceCount > 0,
            onCheckedChange = onVoicesEnabledChange,
        )
        val beginLabel = stringResource(R.string.honor_overview_begin_a11y)
        Button(
            onClick = onBegin,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = beginLabel },
            shape = RoundedCornerShape(PilgrimCornerRadius.normal),
            contentPadding = PaddingValues(vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = pilgrimColors.stone,
                contentColor = pilgrimColors.parchment,
            ),
        ) {
            Text(
                text = stringResource(R.string.honor_overview_begin),
                style = pilgrimType.button,
                color = pilgrimColors.parchment,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

@Composable
private fun StatsText(text: String) {
    Text(text = text, style = pilgrimType.body, color = pilgrimColors.ink)
}

/** iOS `Toggle("walk with their voice")`, stone-tinted: one switch element read with its label. */
@Composable
private fun VoicesToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.honor_overview_voices_toggle),
            style = pilgrimType.body,
            color = pilgrimColors.ink,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = pilgrimColors.parchment,
                checkedTrackColor = pilgrimColors.stone,
                checkedBorderColor = pilgrimColors.stone,
            ),
        )
    }
}

private const val STATS_SEPARATOR = "·"
