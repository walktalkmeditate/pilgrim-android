// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import org.walktalkmeditate.pilgrim.honor.HonorImportCopy
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageMorningCard
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.StageMorningCardAction
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimCornerRadius
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.walk.PilgrimMap
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat
import org.walktalkmeditate.pilgrim.ui.walk.map.rememberWayMapPins
import org.walktalkmeditate.pilgrim.ui.walk.map.rememberWayMarkMapPins

/**
 * iOS `HonorOverviewView` (parity spec F §8–§14, shared-walk spec S4 §8–§9):
 * the map fit to the whole Way, the card over it, and Begin. Begin only
 * navigates: it closes the overview and opens the walk screen before its
 * Start (spec correction 1). The camera never follows the puck here.
 */
@Composable
fun HonorOverviewScreen(
    onClose: () -> Unit,
    onBegin: (HonorWayChoice) -> Unit,
    viewModel: HonorOverviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val voicesEnabled by viewModel.voicesEnabled.collectAsStateWithLifecycle()
    val units by viewModel.units.collectAsStateWithLifecycle()
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val positionMillis by viewModel.playbackPositionMillis.collectAsStateWithLifecycle()
    val speed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val waveforms by viewModel.waveforms.collectAsStateWithLifecycle()
    val markPins by viewModel.markPins.collectAsStateWithLifecycle()
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
        val previewPlayable = previewMomentId?.let { it in overview.playableVoices } == true
        // Keyed on the id, not the tap, so a preview restored after the
        // process was killed still loads its waveform and its 1x; and on
        // whether its voice is here, so one that lands while it is open
        // reads its bars then.
        LaunchedEffect(previewMomentId, previewPlayable) {
            previewMomentId?.let(viewModel::openPreview)
        }
        val context = LocalContext.current
        val showsPuck = remember(context) {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        }
        val mapPins = rememberWayMapPins(overview.pins)
        val mapMarks = rememberWayMarkMapPins(markPins)
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
                    honorWay = overview.line,
                    wayPins = mapPins,
                    onWayPinTap = { momentId -> previewMomentId = momentId },
                    wayMarks = mapMarks,
                    // A Way with no marks has nothing a camera report could select, as on the walk screen.
                    onCameraChanged = if (overview.way.marks.isNullOrEmpty()) null else viewModel::onCameraChanged,
                )
            },
            card = {
                HonorOverviewCard(
                    overview = overview,
                    units = units,
                    voicesEnabled = voicesEnabled,
                    onVoicesEnabledChange = viewModel::setVoicesEnabled,
                    onBegin = { onBegin(viewModel.begin()) },
                    importState = importState,
                    onRetryMedia = viewModel::retryMedia,
                    onWalkWithoutMissing = viewModel::walkWithoutMissingVoices,
                )
            },
        )
        val moment = previewMomentId?.let { id -> overview.way.moments.firstOrNull { it.id == id } }
        if (moment != null) {
            val voice = overview.playableVoices[moment.id]?.let {
                val current = when (val p = playback) {
                    is PlaybackState.Playing -> p.recordingId == it.playbackId
                    is PlaybackState.Paused -> p.recordingId == it.playbackId
                    else -> false
                }
                WayVoicePreview(
                    isPlaying = playback is PlaybackState.Playing && current,
                    positionSeconds = if (current) positionMillis / 1000.0 else 0.0,
                    totalSeconds = it.totalSeconds,
                    speed = speed,
                    waveform = waveforms[it.playbackId],
                )
            }
            WayMomentPreviewSheet(
                way = overview.way,
                moment = moment,
                units = units,
                voice = voice.takeIf { moment.kind is WayMomentKind.Voice },
                photoUri = overview.photoUris[moment.id],
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
 *
 * The import line sits under the counts, rust for trouble and fog while
 * something is on its way, and Begin is held only while a fetch or a
 * gather could still land (S4 §8.3); nothing announces the line, as on
 * iOS (pilgrim-ios #108, matched). Missing voices add "try again" and
 * "walk without the missing voices" under it; a full disk adds nothing.
 *
 * A stage (pilgrimage-stage spec P4 §6.1–§6.2) reads its stage line where
 * the date would be, says its offline note under the status line, has no
 * voice to walk with, and its Begin, read "Walk this stage", opens the
 * morning card, whose "walk" is what calls [onBegin]. The stats row is
 * Stage 21-1's, so a stage shows the dataset's synthesized clock, "a quiet
 * way", and its line's own length beside the stage line's figure
 * (pilgrim-ios #122 item 6, matched). The card's open flag survives a
 * rotation and a process death (P4 A-3).
 */
@Composable
internal fun HonorOverviewCard(
    overview: HonorOverview,
    units: UnitSystem,
    voicesEnabled: Boolean,
    onVoicesEnabledChange: (Boolean) -> Unit,
    onBegin: () -> Unit,
    modifier: Modifier = Modifier,
    importState: HonorImportState = HonorImportState.Idle,
    onRetryMedia: () -> Unit = {},
    onWalkWithoutMissing: () -> Unit = {},
) {
    val way = overview.way
    val stage = way.stage
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var showMorningCard by rememberSaveable(way.id) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(pilgrimColors.parchment)
            .padding(PilgrimSpacing.normal),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(text = way.title, style = pilgrimType.heading, color = pilgrimColors.ink)
        Text(
            text = WayStageLine.line(resources, way, units)
                ?: HonorOverviewModel.departureLine(way, ZoneId.systemDefault(), locale),
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
        HonorImportCopy.line(resources, importState)?.let {
            Text(
                text = it,
                style = pilgrimType.caption,
                color = if (importState.isTrouble) pilgrimColors.rust else pilgrimColors.fog,
            )
        }
        if (importState is HonorImportState.MediaMissing) {
            MissingVoicesChoice(onRetry = onRetryMedia, onWalkWithout = onWalkWithoutMissing)
        }
        HonorOverviewModel.weatherLine(resources, way.weather, overview.todayCondition, locale)?.let {
            Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        HonorOverviewModel.statusLine(resources, overview.distanceToStartMeters, units)?.let {
            Text(text = it, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        overview.offlineNote?.let {
            Text(text = stringResource(it), style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        // A stage carries no recordings, so "walk with their voice" would
        // be a switch over nothing, and would say "their" besides.
        if (stage == null) {
            VoicesToggle(
                checked = voicesEnabled,
                // A quiet way keeps showing its stored value, switched off from use.
                enabled = way.voiceCount > 0,
                onCheckedChange = onVoicesEnabledChange,
            )
        }
        val beginLabel = stringResource(
            if (stage != null) R.string.honor_overview_begin_stage_a11y else R.string.honor_overview_begin_a11y,
        )
        Button(
            onClick = { if (stage != null) showMorningCard = true else onBegin() },
            enabled = !importState.holdsBegin,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = beginLabel },
            shape = RoundedCornerShape(PilgrimCornerRadius.normal),
            contentPadding = PaddingValues(vertical = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = pilgrimColors.stone,
                contentColor = pilgrimColors.parchment,
                disabledContainerColor = pilgrimColors.fog,
                disabledContentColor = pilgrimColors.parchment,
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
    if (showMorningCard && stage != null) {
        StageMorningCard(
            stage = stage,
            weather = overview.todayWeather,
            units = units,
            mapsLine = null,
            action = StageMorningCardAction.WALK,
            onAction = {
                showMorningCard = false
                onBegin()
            },
            onDismiss = { showMorningCard = false },
        )
    }
}

/**
 * iOS's two buttons under "some voices didn't arrive", stacked rather
 * than paired so the long one never clips (`HonorOverviewView.swift:320-334@7c200bf`):
 * caption type in stone, 4 apart, each a full touch target.
 */
@Composable
private fun MissingVoicesChoice(onRetry: () -> Unit, onWalkWithout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        listOf(
            R.string.honor_overview_try_again to onRetry,
            R.string.honor_overview_walk_without to onWalkWithout,
        ).forEach { (label, onClick) ->
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(text = stringResource(label), style = pilgrimType.caption, color = pilgrimColors.stone)
            }
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

/** iOS `isTrouble`: a failure or missing media reads in rust. */
private val HonorImportState.isTrouble: Boolean
    get() = this is HonorImportState.Failed || this is HonorImportState.MediaMissing

/**
 * iOS `isGathering`: a fetch too, so a second link can't swap the Way out
 * from under a Begin tap. Missing media never holds it (S1 §6.5).
 */
private val HonorImportState.holdsBegin: Boolean
    get() = this is HonorImportState.Fetching || this is HonorImportState.Gathering
