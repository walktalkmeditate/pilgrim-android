// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.export.RecordingsExporter
import org.walktalkmeditate.pilgrim.data.pilgrim.builder.PilgrimPackageError

/**
 * Backs the Data settings screen. Stage 10-I extends Stage 10-D's
 * recordings export to also drive `.pilgrim` archive build + import:
 *
 *  - Live walk count + earliest/latest range + pinned-photo count
 *    feed [ExportConfirmationSheet]'s summary.
 *  - [requestPilgrimExport] surfaces the sheet (or short-circuits
 *    with `Failed` on zero walks).
 *  - [confirmPilgrimExport] hops the archive build through the
 *    gateway and emits the resulting File via [pilgrimShareEvents].
 *  - [importPilgrim] runs a SAF-picked URI through the gateway and
 *    surfaces the imported walk count via [pilgrimImportState].
 *
 * Heavy I/O hops to [Dispatchers.IO] inside [exportRecordings]; the
 * default `viewModelScope.launch` runs on Main (Stage 5-D regression
 * memory), so the wrapping `withContext(IO)` is load-bearing. The
 * builder + importer wrap their own internal IO so the export +
 * import action functions don't need a manual hop.
 */
@HiltViewModel
class DataSettingsViewModel @Inject constructor(
    recordingsSource: RecordingsCountSource,
    walksSource: WalksSource,
    walkPhotoDao: WalkPhotoDao,
    private val pilgrimGateway: PilgrimPackageGateway,
    private val env: DataExportEnv,
) : ViewModel() {

    val recordingCount: StateFlow<Int> = recordingsSource.observeAllVoiceRecordings()
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIPTION_KEEPALIVE_MS),
            initialValue = 0,
        )

    // Read by `requestPilgrimExport()` via `.value` (non-suspend),
    // which doesn't count as a subscriber for `WhileSubscribed`. Use
    // `Eagerly` + upstream `.catch { emit(...) }` so the first tap on
    // Export within the warmup window doesn't see the `initialValue`
    // and short-circuit to "No walks found" when walks exist.
    val walkCount: StateFlow<Int> = walksSource.observeAllWalks()
        .map { walks -> walks.count { it.endTimestamp != null } }
        .catch { emit(0) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = 0,
        )

    val dateRange: StateFlow<Pair<Instant, Instant>?> = walksSource.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isEmpty()) return@map null
            val earliest = Instant.ofEpochMilli(finished.minOf { it.startTimestamp })
            val latest = Instant.ofEpochMilli(finished.maxOf { it.startTimestamp })
            earliest to latest
        }
        .catch { emit(null) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    val pinnedPhotoCount: StateFlow<Int> = walkPhotoDao.observeAllCount()
        .catch { emit(0) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
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

    private val _pilgrimExportState = MutableStateFlow<PilgrimExportState>(PilgrimExportState.Idle)
    val pilgrimExportState: StateFlow<PilgrimExportState> = _pilgrimExportState.asStateFlow()

    private val _pilgrimImportState = MutableStateFlow<PilgrimImportState>(PilgrimImportState.Idle)
    val pilgrimImportState: StateFlow<PilgrimImportState> = _pilgrimImportState.asStateFlow()

    // Mirrors [exportEvents]: BUFFERED Channel + receiveAsFlow so
    // rotation doesn't drop the share-sheet trigger. Caller collects
    // the file once and launches the share intent.
    private val _pilgrimShareEvents = Channel<File>(Channel.BUFFERED)
    val pilgrimShareEvents: Flow<File> = _pilgrimShareEvents.receiveAsFlow()

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

    /**
     * Surface the [ExportConfirmationSheet] preconditions snapshot.
     * Short-circuits to [PilgrimExportState.Failed] when zero finished
     * walks exist — UI surfaces this as a "no walks" Snackbar so the
     * user gets immediate feedback instead of an empty sheet.
     */
    fun requestPilgrimExport() {
        val count = walkCount.value
        if (count == 0) {
            _pilgrimExportState.value =
                PilgrimExportState.Failed("No walks found to export.")
            return
        }
        val range = dateRange.value
        val dateRangeText = if (range != null) {
            ExportDateRangeFormatter.format(range.first, range.second)
        } else {
            ""
        }
        val photos = pinnedPhotoCount.value
        _pilgrimExportState.value = PilgrimExportState.Confirming(
            walkCount = count,
            dateRangeText = dateRangeText,
            pinnedPhotoCount = photos,
            estimatedPhotoBytes = photos.toLong() * ESTIMATED_BYTES_PER_PHOTO,
        )
    }

    fun cancelPilgrimExport() {
        _pilgrimExportState.value = PilgrimExportState.Idle
    }

    /**
     * Drive the archive build through the gateway. Caller must have
     * already shown the confirmation sheet — calling this in any
     * other state is a no-op so the UI can't accidentally trigger a
     * second build. CancellationException propagates so the coroutine
     * scope teardown isn't masked as a builder failure.
     */
    fun confirmPilgrimExport(includePhotos: Boolean) {
        if (_pilgrimExportState.value !is PilgrimExportState.Confirming) return
        _pilgrimExportState.value = PilgrimExportState.Building
        viewModelScope.launch {
            val result = try {
                pilgrimGateway.build(includePhotos = includePhotos)
            } catch (e: CancellationException) {
                _pilgrimExportState.value = PilgrimExportState.Idle
                throw e
            } catch (e: Throwable) {
                _pilgrimExportState.value = PilgrimExportState.Failed(
                    "Couldn't create the archive — please try again.",
                )
                return@launch
            }
            _pilgrimShareEvents.send(result.file)
            _pilgrimExportState.value = PilgrimExportState.Done(result.skippedPhotoCount)
        }
    }

    fun importPilgrim(uri: Uri) {
        if (_pilgrimImportState.value is PilgrimImportState.Importing) return
        _pilgrimImportState.value = PilgrimImportState.Importing
        viewModelScope.launch {
            val count = try {
                pilgrimGateway.import(uri)
            } catch (e: CancellationException) {
                _pilgrimImportState.value = PilgrimImportState.Idle
                throw e
            } catch (e: PilgrimPackageError.UnsupportedSchemaVersion) {
                _pilgrimImportState.value = PilgrimImportState.Failed(
                    "Unsupported version: ${e.version}",
                )
                return@launch
            } catch (e: Throwable) {
                _pilgrimImportState.value = PilgrimImportState.Failed(
                    "Couldn't read the archive — please try again.",
                )
                return@launch
            }
            _pilgrimImportState.value = PilgrimImportState.Imported(count)
        }
    }

    private companion object {
        const val SUBSCRIPTION_KEEPALIVE_MS = 5_000L
        const val ESTIMATED_BYTES_PER_PHOTO = 80_000L
    }
}

/**
 * .pilgrim archive build state. Drives the export confirmation sheet
 * + downstream snackbars / share-sheet trigger.
 */
sealed interface PilgrimExportState {
    object Idle : PilgrimExportState
    data class Confirming(
        val walkCount: Int,
        val dateRangeText: String,
        val pinnedPhotoCount: Int,
        val estimatedPhotoBytes: Long,
    ) : PilgrimExportState
    object Building : PilgrimExportState
    data class Done(val skippedPhotoCount: Int) : PilgrimExportState
    data class Failed(val message: String) : PilgrimExportState
}

/**
 * .pilgrim archive import state. Drives the import-result Snackbar.
 */
sealed interface PilgrimImportState {
    object Idle : PilgrimImportState
    object Importing : PilgrimImportState
    data class Imported(val walkCount: Int) : PilgrimImportState
    data class Failed(val message: String) : PilgrimImportState
}
