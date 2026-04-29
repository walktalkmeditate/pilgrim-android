// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingToggle
import org.walktalkmeditate.pilgrim.ui.settings.SettingsDivider
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard

/**
 * Settings → Voice card. iOS-faithful port of
 * `pilgrim-ios/Pilgrim/Scenes/Settings/SettingsCards/VoiceCard.swift`.
 *
 * Surfaces the voice-guide master toggle (with conditional Guide Packs
 * nav row), the auto-transcribe toggle, and a Recordings nav row whose
 * detail caption summarizes the on-disk recordings (`X recordings • Y.Y MB`).
 *
 * The Guide Packs row sits BETWEEN the voice-guide toggle and the
 * unconditional divider that introduces the auto-transcribe group —
 * matching iOS, which has no trailing divider on the Guide Packs row
 * itself (the next divider is the unconditional one between the
 * voice-guide group and the auto-transcribe toggle). Both dividers are
 * rendered unconditionally regardless of toggle state.
 *
 * iOS's "Dynamic Voice" toggle and the in-card transcription-model
 * download progress bar are INTENTIONALLY DEFERRED — Android has no
 * dynamic-voice feature in this milestone, and transcription model
 * provisioning is handled elsewhere.
 *
 * State is driven entirely by the parent — Stage 10-D Task 10 wires
 * [SettingsViewModel] StateFlows into [VoiceCardState].
 */
@Composable
fun VoiceCard(
    state: VoiceCardState,
    onSetVoiceGuideEnabled: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onOpenVoiceGuides: () -> Unit,
    onOpenRecordings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .settingsCard(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CardHeader(
            title = stringResource(R.string.settings_voice_card_header_title),
            subtitle = stringResource(R.string.settings_voice_card_header_subtitle),
        )

        SettingToggle(
            label = stringResource(R.string.settings_voice_guide_label),
            description = stringResource(R.string.settings_voice_guide_description),
            checked = state.voiceGuideEnabled,
            onCheckedChange = onSetVoiceGuideEnabled,
        )

        // Mirrors iOS's `.animation(.easeInOut(duration: 0.2), value: voiceGuideEnabled)`.
        AnimatedVisibility(
            visible = state.voiceGuideEnabled,
            enter = fadeIn(animationSpec = tween(durationMillis = 200, easing = EaseInOut)) +
                expandVertically(animationSpec = tween(durationMillis = 200, easing = EaseInOut)),
            exit = fadeOut(animationSpec = tween(durationMillis = 200, easing = EaseInOut)) +
                shrinkVertically(animationSpec = tween(durationMillis = 200, easing = EaseInOut)),
        ) {
            SettingNavRow(
                label = stringResource(R.string.settings_voice_guide_packs_row),
                onClick = onOpenVoiceGuides,
            )
        }

        SettingsDivider()

        SettingToggle(
            label = stringResource(R.string.settings_auto_transcribe_label),
            description = stringResource(R.string.settings_auto_transcribe_description),
            checked = state.autoTranscribe,
            onCheckedChange = onSetAutoTranscribe,
        )

        SettingsDivider()

        SettingNavRow(
            label = stringResource(R.string.settings_recordings_row),
            detail = formatRecordingsDetail(state.recordingsCount, state.recordingsSizeBytes),
            onClick = onOpenRecordings,
        )
    }
}

/**
 * Stateless snapshot of every value VoiceCard renders. `@Stable` lets
 * Compose skip recomposition when the same state instance is supplied
 * twice — the parent [SettingsViewModel]'s StateFlow guarantees
 * referential equality on no-op emissions.
 */
@Stable
data class VoiceCardState(
    val voiceGuideEnabled: Boolean,
    val autoTranscribe: Boolean,
    val recordingsCount: Int,
    val recordingsSizeBytes: Long,
)

/**
 * Build the `X recordings • Y.Y MB` detail caption for the Recordings
 * nav row. iOS-faithful: U+2022 with surrounding spaces, "recording"
 * (singular) at count == 1, ASCII digits via [Locale.US] regardless of
 * device locale (Stage 6-A lesson — default-locale `%d` produces
 * non-ASCII digits on Arabic/Persian/Hindi).
 */
@Composable
private fun formatRecordingsDetail(count: Int, bytes: Long): String {
    val mb = String.format(Locale.US, "%.1f", bytes / 1_000_000.0)
    return when {
        count == 0 -> stringResource(R.string.settings_recordings_detail_zero)
        count == 1 -> stringResource(R.string.settings_recordings_detail_one, mb)
        else -> stringResource(R.string.settings_recordings_detail_many, count, mb)
    }
}
