// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.sounds.BreathRhythm
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * iOS parity `MeditationView.swift:394-415 @ db4196e`. The long-press
 * picker on the breathing circle exposes both the voice-guide
 * selector and the breath-rhythm list. The voice-guide section only
 * appears when the user has enabled voice guide AND at least one
 * downloaded pack has a meditation track — matching iOS's
 * `showsVoiceGuideSection` gate.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeditationOptionsSheet(
    currentRhythmId: Int,
    onSelectRhythm: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    optionsViewModel: MeditationOptionsViewModel = hiltViewModel(),
) {
    val packStates by optionsViewModel.packStates.collectAsStateWithLifecycle()
    val voiceGuideEnabled by optionsViewModel.voiceGuideEnabled.collectAsStateWithLifecycle()
    val meditationPacks = packStates.filter { it.pack.hasMeditationGuide }
    val showsVoiceGuide = voiceGuideEnabled && meditationPacks.isNotEmpty()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(
                    if (showsVoiceGuide) R.string.meditation_options_title
                    else R.string.settings_breath_rhythm_picker_title,
                ),
                style = pilgrimType.heading,
                color = pilgrimColors.ink,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (showsVoiceGuide) {
                    item("voice-guide-header") {
                        SectionHeader(text = stringResource(R.string.meditation_options_voice_guide_section))
                    }
                    item("voice-guide-off") {
                        VoiceGuideOffRow(
                            isSelected = meditationPacks.none { it.isSelected },
                            onClick = {
                                optionsViewModel.setVoiceGuide(null)
                            },
                        )
                    }
                    items(items = meditationPacks, key = { it.pack.id }) { state ->
                        VoiceGuidePackRow(
                            state = state,
                            onSelect = { optionsViewModel.setVoiceGuide(state.pack.id) },
                            onDownload = { optionsViewModel.downloadPack(state.pack.id) },
                        )
                    }
                    item("voice-breath-divider") {
                        HorizontalDivider(
                            modifier = Modifier
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            color = pilgrimColors.fog.copy(alpha = 0.2f),
                        )
                    }
                    item("breath-rhythm-header") {
                        SectionHeader(text = stringResource(R.string.meditation_options_breath_section))
                    }
                }
                items(items = BreathRhythm.all, key = { it.id }) { rhythm ->
                    RhythmRow(
                        rhythm = rhythm,
                        selected = rhythm.id == currentRhythmId,
                        onClick = {
                            onSelectRhythm(rhythm.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = pilgrimType.caption,
        color = pilgrimColors.fog.copy(alpha = 0.4f),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun VoiceGuideOffRow(
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    SelectableRow(
        title = stringResource(R.string.meditation_options_voice_guide_off_name),
        subtitle = stringResource(R.string.meditation_options_voice_guide_off_subtitle),
        isSelected = isSelected,
        onClick = onClick,
    )
}

@Composable
private fun VoiceGuidePackRow(
    state: VoiceGuidePackState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
) {
    val pack = state.pack
    val downloaded = state is VoiceGuidePackState.Downloaded
    val downloading = state is VoiceGuidePackState.Downloading
    val isSelected = state.isSelected && downloaded

    val onClick: () -> Unit = when {
        downloaded -> onSelect
        downloading -> ({}) // tap-to-cancel intentionally not wired (parity with iOS)
        else -> onDownload
    }
    val titleColor = if (downloaded) pilgrimColors.ink.copy(alpha = 0.9f) else pilgrimColors.ink.copy(alpha = 0.4f)
    val background = if (isSelected) pilgrimColors.moss.copy(alpha = 0.08f) else pilgrimColors.parchment

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClickLabel = pack.name, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = pack.name, style = pilgrimType.body, color = titleColor)
            when {
                downloaded -> Text(
                    text = pack.tagline,
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog.copy(alpha = 0.35f),
                )
                state is VoiceGuidePackState.Downloading -> {
                    val progress = state.fraction
                    LinearProgressIndicator(
                        progress = { progress },
                        color = pilgrimColors.moss,
                        trackColor = pilgrimColors.fog.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    )
                }
                else -> Text(
                    text = stringResource(R.string.meditation_options_voice_guide_not_downloaded),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog.copy(alpha = 0.35f),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when {
            isSelected -> Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(18.dp),
            )
            !downloaded && !downloading -> Icon(
                imageVector = Icons.Default.ArrowDownward,
                contentDescription = null,
                tint = pilgrimColors.fog.copy(alpha = 0.3f),
                modifier = Modifier.size(18.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun SelectableRow(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val background = if (isSelected) pilgrimColors.moss.copy(alpha = 0.08f) else pilgrimColors.parchment
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .clickable(onClickLabel = title, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = pilgrimType.body, color = pilgrimColors.ink.copy(alpha = 0.9f))
            Text(text = subtitle, style = pilgrimType.caption, color = pilgrimColors.fog.copy(alpha = 0.35f))
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = pilgrimColors.moss,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun RhythmRow(
    rhythm: BreathRhythm,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = rhythm.name, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = rhythm.name, style = pilgrimType.body, color = pilgrimColors.ink)
                if (!rhythm.isNone) {
                    Spacer(Modifier.width(8.dp))
                    Text(text = rhythm.label, style = pilgrimType.caption, color = pilgrimColors.fog)
                }
            }
            Text(text = rhythm.description, style = pilgrimType.caption, color = pilgrimColors.fog)
        }
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = pilgrimColors.stone,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Suppress("unused") private val sectionPadding = PaddingValues(0.dp)
