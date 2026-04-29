// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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

    // Channel + receiveAsFlow for one-shot events: buffers across the
    // rotation gap and delivers each event to the next subscriber, then
    // it's gone. Must be `receiveAsFlow` (multi-collector-safe), NOT
    // `consumeAsFlow` (single-shot terminal that closes the channel
    // when the first collector cancels — second mount post-rotation
    // would crash with IllegalStateException). SharedFlow with
    // extraBufferCapacity dropped events across the subscriber gap;
    // replay-1 would re-deliver stale events on re-entry.
    private val _exportEvents = Channel<RecordingsExportResult>(Channel.BUFFERED)
    val exportEvents: Flow<RecordingsExportResult> = _exportEvents.receiveAsFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    fun exportRecordings() {
        if (!_isExporting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val zip = RecordingsExporter.export(
                            sourceDir = env.sourceDir(),
                            targetDir = env.targetDir(),
                            now = Instant.now(),
                        )
                        if (zip == null) RecordingsExportResult.Empty
                        else RecordingsExportResult.Success(zip)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        RecordingsExportResult.Failed(e.message ?: "Export failed")
                    }
                }
                _exportEvents.send(result)
            } finally {
                _isExporting.value = false
            }
        }
    }

    private companion object {
        const val SUBSCRIPTION_KEEPALIVE_MS = 5_000L
    }
}
