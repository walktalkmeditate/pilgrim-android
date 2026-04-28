// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.sounds

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingToggle
import org.walktalkmeditate.pilgrim.ui.settings.SettingsAction
import org.walktalkmeditate.pilgrim.ui.settings.SettingsDivider
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Stage 10-B port of iOS `SoundSettingsView`. Six sections, each
 * wrapped in the shared [settingsCard] modifier:
 *  1. Master toggle + (when on) bell-haptic toggle
 *  2. Walk: per-event bell pickers
 *  3. Meditation: per-event bell pickers + soundscape selector + breath rhythm
 *  4. Volume: bell + soundscape sliders
 *  5. Storage: cache size + clear-all action
 *
 * Sections 2-5 are gated on [SoundSettingsViewModel.soundsEnabled]
 * and animated via `AnimatedVisibility` (`expandVertically + fadeIn`)
 * to match iOS's `easeInOut(duration: 0.2)` toggle reveal.
 *
 * `onBack` is plumbed through the screen signature so a future
 * device-back hook (e.g. confirmation dialog when downloads are in
 * flight) can wire it without rewriting the call site. Today system
 * back pops via NavController — no visible toolbar is needed.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SoundSettingsScreen(
    onAction: (SettingsAction) -> Unit,
    onBack: () -> Unit,
    viewModel: SoundSettingsViewModel = hiltViewModel(),
) {
    val soundsEnabled by viewModel.soundsEnabled.collectAsStateWithLifecycle()
    val bellHapticEnabled by viewModel.bellHapticEnabled.collectAsStateWithLifecycle()
    val bellVolume by viewModel.bellVolume.collectAsStateWithLifecycle()
    val soundscapeVolume by viewModel.soundscapeVolume.collectAsStateWithLifecycle()
    val walkStartBellId by viewModel.walkStartBellId.collectAsStateWithLifecycle()
    val walkEndBellId by viewModel.walkEndBellId.collectAsStateWithLifecycle()
    val meditationStartBellId by viewModel.meditationStartBellId.collectAsStateWithLifecycle()
    val meditationEndBellId by viewModel.meditationEndBellId.collectAsStateWithLifecycle()
    val breathRhythmId by viewModel.breathRhythm.collectAsStateWithLifecycle()
    val selectedSoundscapeId by viewModel.selectedSoundscapeId.collectAsStateWithLifecycle()
    val availableBells by viewModel.availableBells.collectAsStateWithLifecycle()
    val availableSoundscapes by viewModel.availableSoundscapes.collectAsStateWithLifecycle()
    val totalDiskUsageBytes by viewModel.totalDiskUsageBytes.collectAsStateWithLifecycle()

    // Active picker target. `rememberSaveable` so a rotation while
    // a sheet is open doesn't dismiss the user's in-progress pick.
    var activePicker by rememberSaveable { mutableStateOf<BellPickerTarget?>(null) }
    var breathRhythmPickerOpen by rememberSaveable { mutableStateOf(false) }

    val listState = rememberLazyListState()

    Scaffold(
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
                    text = stringResource(R.string.settings_sounds_title),
                    style = pilgrimType.heading,
                    color = pilgrimColors.ink,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 16.dp),
                    textAlign = TextAlign.Center,
                )
            }

            item {
                MainToggleSection(
                    soundsEnabled = soundsEnabled,
                    bellHapticEnabled = bellHapticEnabled,
                    onSetSoundsEnabled = viewModel::setSoundsEnabled,
                    onSetBellHapticEnabled = viewModel::setBellHapticEnabled,
                )
            }

            item {
                AnimatedVisibility(
                    visible = soundsEnabled,
                    enter = fadeIn(animationSpec = tween(200)) +
                        expandVertically(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) +
                        shrinkVertically(animationSpec = tween(200)),
                ) {
                    WalkSection(
                        walkStartBellId = walkStartBellId,
                        walkEndBellId = walkEndBellId,
                        availableBells = availableBells,
                        onOpenWalkStart = { activePicker = BellPickerTarget.WalkStart },
                        onOpenWalkEnd = { activePicker = BellPickerTarget.WalkEnd },
                    )
                }
            }

            item {
                AnimatedVisibility(
                    visible = soundsEnabled,
                    enter = fadeIn(animationSpec = tween(200)) +
                        expandVertically(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) +
                        shrinkVertically(animationSpec = tween(200)),
                ) {
                    MeditationSection(
                        meditationStartBellId = meditationStartBellId,
                        meditationEndBellId = meditationEndBellId,
                        availableBells = availableBells,
                        selectedSoundscapeId = selectedSoundscapeId,
                        availableSoundscapes = availableSoundscapes,
                        breathRhythmId = breathRhythmId,
                        onOpenMeditationStart = { activePicker = BellPickerTarget.MeditationStart },
                        onOpenMeditationEnd = { activePicker = BellPickerTarget.MeditationEnd },
                        onOpenSoundscape = { onAction(SettingsAction.OpenSoundscapes) },
                        onOpenBreathRhythm = { breathRhythmPickerOpen = true },
                    )
                }
            }

            item {
                AnimatedVisibility(
                    visible = soundsEnabled,
                    enter = fadeIn(animationSpec = tween(200)) +
                        expandVertically(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) +
                        shrinkVertically(animationSpec = tween(200)),
                ) {
                    VolumeSection(
                        bellVolume = bellVolume,
                        soundscapeVolume = soundscapeVolume,
                        onSetBellVolume = viewModel::setBellVolume,
                        onSetSoundscapeVolume = viewModel::setSoundscapeVolume,
                    )
                }
            }

            item {
                AnimatedVisibility(
                    visible = soundsEnabled,
                    enter = fadeIn(animationSpec = tween(200)) +
                        expandVertically(animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) +
                        shrinkVertically(animationSpec = tween(200)),
                ) {
                    StorageSection(
                        // Match iOS: total count covers ALL audio
                        // assets in the manifest (bells + soundscapes),
                        // not just soundscapes. iOS reads
                        // `manifestService.manifest?.assets.count`.
                        soundscapeCount = availableBells.size + availableSoundscapes.size,
                        totalDiskUsageBytes = totalDiskUsageBytes,
                        onClearAll = viewModel::clearAllDownloads,
                    )
                }
            }
        }
    }

    activePicker?.let { target ->
        BellPickerSheet(
            currentSelection = when (target) {
                BellPickerTarget.WalkStart -> walkStartBellId
                BellPickerTarget.WalkEnd -> walkEndBellId
                BellPickerTarget.MeditationStart -> meditationStartBellId
                BellPickerTarget.MeditationEnd -> meditationEndBellId
            },
            availableBells = availableBells,
            onSelect = { newId ->
                when (target) {
                    BellPickerTarget.WalkStart -> viewModel.setWalkStartBellId(newId)
                    BellPickerTarget.WalkEnd -> viewModel.setWalkEndBellId(newId)
                    BellPickerTarget.MeditationStart -> viewModel.setMeditationStartBellId(newId)
                    BellPickerTarget.MeditationEnd -> viewModel.setMeditationEndBellId(newId)
                }
            },
            onDismiss = { activePicker = null },
        )
    }

    if (breathRhythmPickerOpen) {
        BreathRhythmPickerSheet(
            currentRhythmId = breathRhythmId,
            onSelect = { viewModel.setBreathRhythm(it) },
            onDismiss = { breathRhythmPickerOpen = false },
        )
    }
}

@Composable
private fun MainToggleSection(
    soundsEnabled: Boolean,
    bellHapticEnabled: Boolean,
    onSetSoundsEnabled: (Boolean) -> Unit,
    onSetBellHapticEnabled: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .testTag(SOUNDS_MAIN_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingToggle(
            label = stringResource(R.string.settings_sounds_master_label),
            description = stringResource(R.string.settings_sounds_description),
            checked = soundsEnabled,
            onCheckedChange = onSetSoundsEnabled,
        )
        AnimatedVisibility(
            visible = soundsEnabled,
            enter = fadeIn(animationSpec = tween(200)) +
                expandVertically(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200)) +
                shrinkVertically(animationSpec = tween(200)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsDivider()
                SettingToggle(
                    label = stringResource(R.string.settings_bell_haptic_label),
                    description = "",
                    checked = bellHapticEnabled,
                    onCheckedChange = onSetBellHapticEnabled,
                )
            }
        }
    }
}

@Composable
private fun WalkSection(
    walkStartBellId: String?,
    walkEndBellId: String?,
    availableBells: List<AudioAsset>,
    onOpenWalkStart: () -> Unit,
    onOpenWalkEnd: () -> Unit,
) {
    val noneLabel = stringResource(R.string.settings_bell_picker_none)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .testTag(SOUNDS_WALK_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_section_walk),
            subtitle = "",
        )
        SettingNavRow(
            label = stringResource(R.string.settings_bell_start_label),
            detail = bellDisplayName(walkStartBellId, availableBells, noneLabel),
            onClick = onOpenWalkStart,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsDivider()
        SettingNavRow(
            label = stringResource(R.string.settings_bell_end_label),
            detail = bellDisplayName(walkEndBellId, availableBells, noneLabel),
            onClick = onOpenWalkEnd,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun MeditationSection(
    meditationStartBellId: String?,
    meditationEndBellId: String?,
    availableBells: List<AudioAsset>,
    selectedSoundscapeId: String?,
    availableSoundscapes: List<AudioAsset>,
    breathRhythmId: Int,
    onOpenMeditationStart: () -> Unit,
    onOpenMeditationEnd: () -> Unit,
    onOpenSoundscape: () -> Unit,
    onOpenBreathRhythm: () -> Unit,
) {
    val noneLabel = stringResource(R.string.settings_bell_picker_none)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .testTag(SOUNDS_MEDITATION_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_section_meditation),
            subtitle = "",
        )
        SettingNavRow(
            label = stringResource(R.string.settings_bell_start_label),
            detail = bellDisplayName(meditationStartBellId, availableBells, noneLabel),
            onClick = onOpenMeditationStart,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsDivider()
        SettingNavRow(
            label = stringResource(R.string.settings_bell_end_label),
            detail = bellDisplayName(meditationEndBellId, availableBells, noneLabel),
            onClick = onOpenMeditationEnd,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsDivider()
        SettingNavRow(
            label = stringResource(R.string.settings_soundscape_label),
            detail = soundscapeDisplayName(selectedSoundscapeId, availableSoundscapes, noneLabel),
            onClick = onOpenSoundscape,
            modifier = Modifier.fillMaxWidth(),
        )
        SettingsDivider()
        SettingNavRow(
            label = stringResource(R.string.settings_breath_rhythm_label),
            detail = BreathRhythm.byId(breathRhythmId).name,
            onClick = onOpenBreathRhythm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun VolumeSection(
    bellVolume: Float,
    soundscapeVolume: Float,
    onSetBellVolume: (Float) -> Unit,
    onSetSoundscapeVolume: (Float) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .testTag(SOUNDS_VOLUME_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_section_volume),
            subtitle = "",
        )
        VolumeRow(
            label = stringResource(R.string.settings_volume_bells),
            volume = bellVolume,
            onChange = onSetBellVolume,
            testTag = SOUNDS_BELL_VOLUME_SLIDER_TAG,
        )
        VolumeRow(
            label = stringResource(R.string.settings_volume_soundscape),
            volume = soundscapeVolume,
            onChange = onSetSoundscapeVolume,
            testTag = SOUNDS_SOUNDSCAPE_VOLUME_SLIDER_TAG,
        )
    }
}

@Composable
private fun VolumeRow(
    label: String,
    volume: Float,
    onChange: (Float) -> Unit,
    testTag: String,
) {
    // Hold a local drag-state so onValueChange updates only Compose
    // state. The DataStore write fires once on `onValueChangeFinished`
    // — without this, every drag tick (dozens per gesture) would
    // dispatch a `viewModelScope.launch { dataStore.edit { } }`,
    // hammering the I/O bus and visually lagging the slider on
    // low-end devices under battery saver. Re-sync local with the
    // VM value when it changes externally (live re-emit on toggle
    // OFF/ON, rotation, etc.).
    var dragValue by remember(volume) { mutableStateOf(volume) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row {
            Text(
                text = label,
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.settings_volume_percent_format,
                    (dragValue * 100f).toInt(),
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        // M3 Slider has no direct `tint(.stone)` equivalent; use
        // SliderDefaults.colors with stone for the thumb + active
        // track and parchmentTertiary for the inactive track to
        // approximate iOS's flat stone tint on parchment.
        Slider(
            value = dragValue,
            onValueChange = { dragValue = it },
            onValueChangeFinished = { onChange(dragValue) },
            valueRange = 0f..1f,
            steps = 19,
            colors = SliderDefaults.colors(
                thumbColor = pilgrimColors.stone,
                activeTrackColor = pilgrimColors.stone,
                inactiveTrackColor = pilgrimColors.parchmentTertiary,
            ),
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
    }
}

@Composable
private fun StorageSection(
    soundscapeCount: Int,
    totalDiskUsageBytes: Long,
    onClearAll: () -> Unit,
) {
    val sizeMb = totalDiskUsageBytes / 1_000_000.0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .settingsCard()
            .testTag(SOUNDS_STORAGE_SECTION_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_section_storage),
            subtitle = "",
        )
        Row {
            Text(
                text = stringResource(R.string.settings_storage_count_format, soundscapeCount),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.settings_storage_size_format, sizeMb),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
        Spacer(Modifier.height(4.dp))
        OutlinedButton(
            onClick = onClearAll,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = pilgrimColors.rust,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SOUNDS_CLEAR_ALL_BUTTON_TAG),
        ) {
            Text(stringResource(R.string.settings_storage_clear_all))
        }
    }
}

private fun bellDisplayName(
    id: String?,
    available: List<AudioAsset>,
    noneLabel: String,
): String {
    if (id == null) return noneLabel
    // Fall back to noneLabel when the persisted id doesn't resolve in
    // the current manifest. Two cases:
    //  1. Manifest hasn't loaded yet (cold start, network race) —
    //     `available` is empty for the first ~50-200ms of the screen.
    //     The caption flickers from "None" to the asset name once the
    //     manifest emits — acceptable; the alternative (raw asset id)
    //     is worse.
    //  2. Asset was removed in a future manifest version (downgrade or
    //     server-side asset retirement). User is told "None" so they
    //     can re-select.
    // Match `soundscapeDisplayName` semantics for consistency.
    return available.firstOrNull { it.id == id }?.displayName ?: noneLabel
}

private fun soundscapeDisplayName(
    id: String?,
    available: List<AudioAsset>,
    noneLabel: String,
): String {
    if (id == null) return noneLabel
    return available.firstOrNull { it.id == id }?.displayName ?: noneLabel
}

internal enum class BellPickerTarget {
    WalkStart, WalkEnd, MeditationStart, MeditationEnd
}

internal const val SOUNDS_MAIN_SECTION_TAG = "SoundSettings.main"
internal const val SOUNDS_WALK_SECTION_TAG = "SoundSettings.walk"
internal const val SOUNDS_MEDITATION_SECTION_TAG = "SoundSettings.meditation"
internal const val SOUNDS_VOLUME_SECTION_TAG = "SoundSettings.volume"
internal const val SOUNDS_STORAGE_SECTION_TAG = "SoundSettings.storage"
internal const val SOUNDS_BELL_VOLUME_SLIDER_TAG = "SoundSettings.bellVolume"
internal const val SOUNDS_SOUNDSCAPE_VOLUME_SLIDER_TAG = "SoundSettings.soundscapeVolume"
internal const val SOUNDS_CLEAR_ALL_BUTTON_TAG = "SoundSettings.clearAll"
