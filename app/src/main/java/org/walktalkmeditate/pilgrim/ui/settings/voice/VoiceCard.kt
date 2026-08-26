// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelVariant
import org.walktalkmeditate.pilgrim.ui.settings.CardHeader
import org.walktalkmeditate.pilgrim.ui.settings.SettingNavRow
import org.walktalkmeditate.pilgrim.ui.settings.SettingToggle
import org.walktalkmeditate.pilgrim.ui.settings.SettingsDivider
import org.walktalkmeditate.pilgrim.ui.settings.settingsCard
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

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
 * iOS's "Dynamic Voice" toggle is INTENTIONALLY DEFERRED — Android has
 * no dynamic-voice feature in this milestone.
 *
 * U11 ports the in-card transcription-model row (iOS
 * `VoiceCard.swift@9a418e4` renders it under the Auto-transcribe
 * toggle): Downloading matches the iOS linear-progress + percent
 * shape, the wider Android delivery states render one caption line,
 * and the row hides only at Ready(Base). Tapping opens
 * [org.walktalkmeditate.pilgrim.ui.walk.ModelDownloadSheet] (parity
 * spec `docs/parity/2026-07-26-port-download-ux-u11.md` section 1).
 *
 * State is driven entirely by the parent — Stage 10-D Task 10 wires
 * [SettingsViewModel] StateFlows into [VoiceCardState]; the model
 * state arrives separately from `ModelDownloadViewModel` (direct
 * hot-Singleton passthrough).
 */
@Composable
fun VoiceCard(
    state: VoiceCardState,
    modelState: WhisperModelState,
    onSetVoiceGuideEnabled: (Boolean) -> Unit,
    onSetAutoTranscribe: (Boolean) -> Unit,
    onSetThreadsEnabled: (Boolean) -> Unit,
    onOpenVoiceGuides: () -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenModelDownload: () -> Unit,
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

        // iOS parity `VoiceCard.swift@0172e2b` UI-17/UI-18/BEH-84: FOURTH
        // and LAST toggle, immediately after Auto-transcribe, no Divider
        // between them, no nested disclosure row, no animation (unlike
        // the Guide Packs row above).
        SettingToggle(
            label = stringResource(R.string.settings_threads_label),
            description = stringResource(R.string.settings_threads_description),
            checked = state.threadsEnabled,
            onCheckedChange = onSetThreadsEnabled,
            testTag = THREADS_TOGGLE_TAG,
        )

        ModelDownloadRow(
            modelState = modelState,
            onClick = onOpenModelDownload,
        )

        SettingsDivider()

        SettingNavRow(
            label = stringResource(R.string.settings_recordings_row),
            detail = formatRecordingsDetail(state.recordingsCount, state.recordingsSizeBytes),
            onClick = onOpenRecordings,
        )
    }
}

internal const val THREADS_TOGGLE_TAG = "VoiceCard.threadsToggle"

/**
 * iOS Settings-row shape (`VoiceCard.swift@9a418e4`): linear progress
 * tinted stone + "Downloading model N%" caption in fog. Android's
 * non-downloading delivery states render the caption line alone;
 * Ready(Base) renders nothing (delivery complete). The whole row taps
 * through to the model download sheet.
 */
@Composable
private fun ModelDownloadRow(
    modelState: WhisperModelState,
    onClick: () -> Unit,
) {
    if (modelState is WhisperModelState.Ready &&
        modelState.variant == WhisperModelVariant.Base
    ) {
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                onClick = onClick,
                onClickLabel = stringResource(R.string.model_sheet_open_cd),
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (modelState is WhisperModelState.Downloading) {
            LinearProgressIndicator(
                progress = { modelState.fraction },
                color = pilgrimColors.stone,
                trackColor = pilgrimColors.fog.copy(alpha = 0.25f),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.settings_model_downloading_percent,
                    (modelState.fraction * 100).toInt(),
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
                maxLines = 1,
            )
        } else {
            Text(
                text = stringResource(
                    when (modelState) {
                        WhisperModelState.WaitingUnmetered ->
                            R.string.transcription_waiting_model_wifi
                        WhisperModelState.Verifying ->
                            R.string.transcription_waiting_model_verifying
                        WhisperModelState.FailedChecksum ->
                            R.string.model_state_failed_checksum
                        WhisperModelState.FailedStorage ->
                            R.string.model_state_failed_storage
                        else -> R.string.transcription_waiting_model
                    },
                ),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }
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
    /** U10: [org.walktalkmeditate.pilgrim.core.threads.ThreadsPreferencesRepository.threadsAfterWalks]
     * passthrough. Defaults `false` so existing call sites that predate
     * Thought Threads keep compiling unchanged. */
    val threadsEnabled: Boolean = false,
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
