// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.export.RecordingsExporter

/**
 * Backs the Data settings screen. Stage 10-D: surfaces a live recording
 * count + a one-shot share-sheet event channel for "Export recordings".
 *
 * Heavy I/O hops to [Dispatchers.IO] inside [exportRecordings]; the
 * default `viewModelScope.launch` runs on Main (Stage 5-D regression
 * memory), so the wrapping `withContext(IO)` is load-bearing.
 */
@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    recordingsSource: RecordingsCountSource,
    private val env: DataExportEnv,
) : ViewModel() {

    val recordingCount: StateFlow<Int> = recordingsSource.observeAllVoiceRecordings()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_KEEPALIVE_MS),
            initialValue = 0,
        )

    private val _exportEvents = MutableSharedFlow<RecordingsExportResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val exportEvents: SharedFlow<RecordingsExportResult> = _exportEvents.asSharedFlow()

    fun exportRecordings() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val zip = RecordingsExporter.export(
                        sourceDir = env.sourceDir(),
                        targetDir = env.targetDir(),
                        now = Instant.now(),
                    )
                    if (zip == null) RecordingsExportResult.Empty
                    else RecordingsExportResult.Success(zip)
                }.getOrElse { error ->
                    RecordingsExportResult.Failed(error.message ?: "Export failed")
                }
            }
            _exportEvents.emit(result)
        }
    }

    private companion object {
        const val SUBSCRIPTION_KEEPALIVE_MS = 5_000L
    }
}
