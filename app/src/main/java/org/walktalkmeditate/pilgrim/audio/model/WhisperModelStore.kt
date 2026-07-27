// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Domain snapshot of the model download's WorkManager state, kept free
 * of WorkInfo so the store composes against a fake in tests.
 *
 * U9's scheduler implements the mapping: WorkInfo ENQUEUED/BLOCKED →
 * [Enqueued]; RUNNING → [Downloading] (byte progress from
 * `setProgress`) or [Verifying] (worker-flagged phase); SUCCEEDED →
 * [Succeeded]; FAILED → [Failed] with the reason read from the
 * worker's outputData; CANCELLED → [Cancelled]. Transient network
 * failures never surface here — the worker returns retry and stays
 * ENQUEUED through WorkManager's internal backoff.
 */
sealed interface ModelDownloadWork {
    data object Enqueued : ModelDownloadWork

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : ModelDownloadWork

    data object Verifying : ModelDownloadWork

    data object Succeeded : ModelDownloadWork

    data object Cancelled : ModelDownloadWork

    data class Failed(val reason: Reason) : ModelDownloadWork {
        enum class Reason { Checksum, Storage }
    }
}

/**
 * Seam over the model-download WorkManager observation. The default
 * binding is [NoOpModelDownloadWorkSource] until U9's scheduler lands
 * and replaces it in `TranscriptionModule`.
 */
interface ModelDownloadWorkSource {
    /** Emits null while no download work is tracked. */
    fun observe(): Flow<ModelDownloadWork?>
}

class NoOpModelDownloadWorkSource @Inject constructor() : ModelDownloadWorkSource {
    override fun observe(): Flow<ModelDownloadWork?> = flowOf(null)
}

/**
 * Seam over ConnectivityManager so the WaitingUnmetered/Enqueued
 * disambiguation is testable — WorkInfo ENQUEUED alone can't
 * distinguish "waiting for Wi-Fi" from "about to start".
 */
fun interface UnmeteredNetworkProbe {
    fun isUnmeteredAvailable(): Boolean
}

class ConnectivityUnmeteredNetworkProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : UnmeteredNetworkProbe {
    override fun isUnmeteredAvailable(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}

/**
 * Single probe-based source of truth for "which whisper model is
 * usable, and what is the download doing" — the Android analogue of
 * iOS `TranscriptionService`'s variant-keyed `resolvedModelPath` +
 * observable `state` (parity spec
 * `docs/parity/2026-07-26-port-model-state-u8.md`).
 *
 * Presence is always a filesystem probe (file + exact size + sha
 * marker), never a persisted flag: device-to-device transfers and
 * partial restores deliver inconsistent halves, and the probe reads
 * whatever half actually arrived. Every [state] emission re-probes, so
 * terminal work transitions see fresh disk truth (Stage 5-D staleness
 * lesson) and "clear app storage" degrades cleanly to [WhisperModelState.Absent].
 */
@Singleton
class WhisperModelStore @Inject constructor(
    @ApplicationContext context: Context,
    workSource: ModelDownloadWorkSource,
    private val unmeteredProbe: UnmeteredNetworkProbe,
    @WhisperModelScope scope: CoroutineScope,
) {
    private val filesDir: Path = context.filesDir.toPath()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val state: StateFlow<WhisperModelState> =
        combine(
            workSource.observe().onStart { emit(null) },
            invalidations.onStart { emit(Unit) },
        ) { work, _ -> work }
            .map { work -> composeState(work) }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), WhisperModelState.Absent)

    /**
     * Re-probe after a filesystem change with no accompanying work
     * emission (U10's post-switch tiny delete, checksum-failure partial
     * cleanup). Dropped emissions are harmless: every fresh
     * subscription re-probes via `onStart`.
     */
    fun invalidate() {
        invalidations.tryEmit(Unit)
    }

    /**
     * The model the engine should load right now: verified base
     * preferred; the legacy tiny accepted transitionally so upgraders
     * keep transcribing through the download window (spec D3); null
     * when neither survives its probe.
     */
    suspend fun readyModelPath(): Path? = withContext(Dispatchers.IO) {
        when {
            baseVerified() -> WhisperModelConfig.baseModelPath(filesDir)
            legacyTinyPresent() -> WhisperModelConfig.legacyTinyPath(filesDir)
            else -> null
        }
    }

    private suspend fun composeState(work: ModelDownloadWork?): WhisperModelState {
        val probe = withContext(Dispatchers.IO) {
            FilesystemProbe(baseVerified = baseVerified(), tinyPresent = legacyTinyPresent())
        }
        if (probe.baseVerified) return WhisperModelState.Ready(WhisperModelVariant.Base)
        return when (work) {
            ModelDownloadWork.Enqueued ->
                if (unmeteredProbe.isUnmeteredAvailable()) {
                    WhisperModelState.Enqueued
                } else {
                    WhisperModelState.WaitingUnmetered
                }
            is ModelDownloadWork.Downloading ->
                WhisperModelState.Downloading(work.bytesDownloaded, work.totalBytes)
            ModelDownloadWork.Verifying -> WhisperModelState.Verifying
            is ModelDownloadWork.Failed -> when (work.reason) {
                ModelDownloadWork.Failed.Reason.Checksum -> WhisperModelState.FailedChecksum
                ModelDownloadWork.Failed.Reason.Storage -> WhisperModelState.FailedStorage
            }
            ModelDownloadWork.Succeeded, ModelDownloadWork.Cancelled, null ->
                if (probe.tinyPresent) {
                    WhisperModelState.Ready(WhisperModelVariant.LegacyTiny)
                } else {
                    WhisperModelState.Absent
                }
        }
    }

    private fun baseVerified(): Boolean = try {
        val model = WhisperModelConfig.baseModelPath(filesDir)
        val marker = WhisperModelConfig.baseShaMarkerPath(filesDir)
        Files.exists(model) &&
            Files.size(model) == WhisperModelConfig.EXPECTED_BYTES &&
            Files.exists(marker) &&
            String(Files.readAllBytes(marker), Charsets.UTF_8).trim() ==
            WhisperModelConfig.EXPECTED_SHA256
    } catch (_: IOException) {
        false
    }

    private fun legacyTinyPresent(): Boolean = try {
        val tiny = WhisperModelConfig.legacyTinyPath(filesDir)
        Files.exists(tiny) && Files.size(tiny) == WhisperModelConfig.LEGACY_TINY_EXPECTED_BYTES
    } catch (_: IOException) {
        false
    }

    private data class FilesystemProbe(
        val baseVerified: Boolean,
        val tinyPresent: Boolean,
    )
}
