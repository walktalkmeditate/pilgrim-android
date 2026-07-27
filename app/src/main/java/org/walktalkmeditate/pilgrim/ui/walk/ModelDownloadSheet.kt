// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.audio.model.BackgroundDataRestrictionProbe
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelConfig
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelDownloadScheduler
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelState
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelStore
import org.walktalkmeditate.pilgrim.audio.model.WhisperModelVariant
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/** User-facing MB (10^6 bytes, matching recordings-size captions), ASCII digits. */
internal fun modelMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.0f", bytes / 1_000_000.0)

/**
 * Backing VM for the model download sheet, the Settings voice row, and
 * the pending-row substate (U11 spec section 4). [modelState] is a
 * direct hot passthrough of [WhisperModelStore.state] (Stage 5-G
 * display-only pattern); the scheduler owns the REPLACE semantics
 * behind [setCellularOverride] and [retryDownload] (U9 C1/C2).
 */
@HiltViewModel
class ModelDownloadViewModel @Inject constructor(
    modelStore: WhisperModelStore,
    voicePreferences: VoicePreferencesRepository,
    private val downloadScheduler: WhisperModelDownloadScheduler,
    private val backgroundDataProbe: BackgroundDataRestrictionProbe,
) : ViewModel() {

    val modelState: StateFlow<WhisperModelState> = modelStore.state

    /**
     * Substate for null-transcription rows on the recordings surfaces —
     * the [pendingTranscriptionSubstate] matrix over (auto-transcribe
     * pref x model state x model usability).
     */
    val pendingSubstate: StateFlow<PendingTranscriptionSubstate> = combine(
        voicePreferences.autoTranscribe,
        modelStore.state,
        modelStore.modelUsable,
    ) { autoTranscribe, state, usable ->
        pendingTranscriptionSubstate(autoTranscribe, state, usable)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = pendingTranscriptionSubstate(
            voicePreferences.autoTranscribe.value,
            modelStore.state.value,
            modelStore.modelUsable.value,
        ),
    )

    val cellularOverride: StateFlow<Boolean> = downloadScheduler
        .observeCellularOverride()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** Live probe read per access; the sheet captures one value per composition. */
    val dataSaverRestricted: Boolean
        get() = backgroundDataProbe.isBackgroundDataRestricted()

    fun setCellularOverride(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { downloadScheduler.setCellularOverride(enabled) }
                .onFailure { Log.w(TAG, "failed to persist cellular override", it) }
        }
    }

    fun retryDownload() {
        viewModelScope.launch {
            runCatching { downloadScheduler.retry() }
                .onFailure { Log.w(TAG, "model download retry failed to enqueue", it) }
        }
    }

    private companion object {
        const val TAG = "ModelDownloadVM"
    }
}

/**
 * The one place a user manages the whisper model delivery: size, live
 * progress, waiting-for-Wi-Fi explanation, sticky cellular override,
 * retry past terminals, and the Data Saver caveat. Reachable from the
 * pending-transcription rows and the Settings voice row (U11 spec
 * section 4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDownloadSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ModelDownloadViewModel = hiltViewModel(),
) {
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val cellularOverride by viewModel.cellularOverride.collectAsStateWithLifecycle()
    // Probed once per sheet composition (the getter is a Binder IPC —
    // per-recomposition reads would fire on every 4 MB progress tick);
    // a mid-sheet Data Saver flip is acceptable staleness for a
    // transient sheet.
    val dataSaverRestricted = remember { viewModel.dataSaverRestricted }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = pilgrimColors.parchment,
        modifier = modifier,
    ) {
        ModelDownloadSheetContent(
            modelState = modelState,
            cellularOverride = cellularOverride,
            dataSaverRestricted = dataSaverRestricted,
            onToggleCellularOverride = viewModel::setCellularOverride,
            onRetry = viewModel::retryDownload,
        )
    }
}

@Composable
internal fun ModelDownloadSheetContent(
    modelState: WhisperModelState,
    cellularOverride: Boolean,
    dataSaverRestricted: Boolean,
    onToggleCellularOverride: (Boolean) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PilgrimSpacing.normal, vertical = PilgrimSpacing.small),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
    ) {
        Text(
            text = stringResource(R.string.model_sheet_title),
            style = pilgrimType.heading,
            color = pilgrimColors.ink,
        )
        Text(
            text = stringResource(
                R.string.model_sheet_size,
                modelMegabytes(WhisperModelConfig.EXPECTED_BYTES),
            ),
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )

        SheetStatus(modelState = modelState)

        if (modelState == WhisperModelState.WaitingUnmetered) {
            Text(
                text = stringResource(R.string.model_sheet_wifi_explanation),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PilgrimSpacing.small),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.model_sheet_use_cellular),
                    style = pilgrimType.body,
                    color = pilgrimColors.ink,
                )
                Text(
                    text = stringResource(R.string.model_sheet_use_cellular_caption),
                    style = pilgrimType.caption,
                    color = pilgrimColors.fog,
                )
            }
            Switch(
                checked = cellularOverride,
                onCheckedChange = onToggleCellularOverride,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = pilgrimColors.stone,
                    checkedThumbColor = pilgrimColors.parchment,
                ),
            )
        }

        if (dataSaverRestricted) {
            Text(
                text = stringResource(R.string.model_sheet_data_saver_note),
                style = pilgrimType.caption,
                color = pilgrimColors.fog,
            )
        }

        when (modelState) {
            WhisperModelState.FailedChecksum -> FailureBlock(
                body = stringResource(R.string.model_sheet_failed_checksum_body),
                onRetry = onRetry,
            )
            WhisperModelState.FailedStorage -> FailureBlock(
                body = stringResource(
                    R.string.model_sheet_failed_storage_body,
                    modelMegabytes(WhisperModelConfig.EXPECTED_BYTES),
                ),
                onRetry = onRetry,
            )
            else -> Unit
        }
    }
}

@Composable
private fun SheetStatus(modelState: WhisperModelState) {
    when (modelState) {
        is WhisperModelState.Downloading -> {
            Text(
                text = stringResource(
                    R.string.model_sheet_status_downloading,
                    modelMegabytes(modelState.bytesDownloaded),
                    modelMegabytes(modelState.totalBytes),
                ),
                style = pilgrimType.body,
                color = pilgrimColors.ink,
            )
            LinearProgressIndicator(
                progress = { modelState.fraction },
                color = pilgrimColors.stone,
                trackColor = pilgrimColors.fog.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        WhisperModelState.Verifying -> SheetStatusLine(
            stringResource(R.string.model_sheet_status_verifying),
        )
        WhisperModelState.WaitingUnmetered -> SheetStatusLine(
            stringResource(R.string.transcription_waiting_model_wifi),
        )
        // LegacyTiny reads as still-waiting: the tiny is serving the
        // engine (U8 D3) but the base delivery this sheet manages has
        // not landed yet.
        is WhisperModelState.Ready -> SheetStatusLine(
            if (modelState.variant == WhisperModelVariant.Base) {
                stringResource(R.string.model_sheet_status_ready)
            } else {
                stringResource(R.string.model_sheet_status_waiting)
            },
        )
        WhisperModelState.Absent,
        WhisperModelState.Enqueued,
        -> SheetStatusLine(stringResource(R.string.model_sheet_status_waiting))
        WhisperModelState.FailedChecksum -> SheetStatusLine(
            stringResource(R.string.model_state_failed_checksum),
        )
        WhisperModelState.FailedStorage -> SheetStatusLine(
            stringResource(R.string.model_state_failed_storage),
        )
    }
}

@Composable
private fun SheetStatusLine(text: String) {
    Text(
        text = text,
        style = pilgrimType.body,
        color = pilgrimColors.ink,
    )
}

@Composable
private fun FailureBlock(
    body: String,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.xs)) {
        Text(
            text = body,
            style = pilgrimType.caption,
            color = pilgrimColors.fog,
        )
        StoneChip(
            text = stringResource(R.string.model_action_retry_download),
            onClick = onRetry,
            verticalPadding = 6.dp,
        )
    }
}
