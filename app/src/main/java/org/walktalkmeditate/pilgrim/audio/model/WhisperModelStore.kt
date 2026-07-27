// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * Seam over the model-download WorkManager observation. Implemented by
 * `WorkManagerWhisperModelDownloadScheduler` (U9), bound in
 * `TranscriptionModule`.
 */
interface ModelDownloadWorkSource {
    /** Emits null while no download work is tracked. */
    fun observe(): Flow<ModelDownloadWork?>
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
 * Seam over `ConnectivityManager.getRestrictBackgroundStatus` so the
 * U11 sheet's Data Saver note is testable without a shadowed
 * ConnectivityManager — same shape as [UnmeteredNetworkProbe] above.
 */
fun interface BackgroundDataRestrictionProbe {
    fun isBackgroundDataRestricted(): Boolean
}

class ConnectivityBackgroundDataRestrictionProbe @Inject constructor(
    @ApplicationContext private val context: Context,
) : BackgroundDataRestrictionProbe {
    override fun isBackgroundDataRestricted(): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return false
        return manager.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
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
open class WhisperModelStore @Inject constructor(
    @ApplicationContext context: Context,
    workSource: ModelDownloadWorkSource,
    private val unmeteredProbe: UnmeteredNetworkProbe,
    @WhisperModelScope scope: CoroutineScope,
) {
    private val filesDir: Path = context.filesDir.toPath()
    private val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val tinyCleanupMutex = Mutex()

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
    open fun invalidate() {
        invalidations.tryEmit(Unit)
    }

    /**
     * Success hook the U9 download worker invokes after the verified
     * base model's sha marker lands (marker-last ordering, spec L4).
     * The Android analogue of iOS `purgeStaleModels(around:)` — the
     * sibling variant is reclaimed only after its replacement is proven
     * (U10 spec L1). Sequencing invariant: the verified base exists on
     * disk BEFORE the tiny is deleted, re-checked here via the same
     * probe the resolver uses, so a misordered caller can never open a
     * no-model window. The delete routes through the same
     * [WhisperModelConfig.legacyTinyPath] the resolver reads
     * (write/delete coupling). Must never take
     * `ModelDownloadFiles.writerMutex` — the worker invokes this hook
     * while holding it.
     */
    open suspend fun onBaseVerified() {
        tinyCleanupMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!baseVerified()) return@withContext
                try {
                    Files.deleteIfExists(WhisperModelConfig.legacyTinyPath(filesDir))
                } catch (io: IOException) {
                    // A surviving tiny only wastes storage: the resolver
                    // already prefers the verified base.
                    Log.w(TAG, "legacy tiny delete failed", io)
                }
            }
        }
        invalidate()
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

    private fun baseVerified(): Boolean = WhisperModelConfig.verifiedModelPresent(
        filesDir = filesDir,
        expectedBytes = WhisperModelConfig.EXPECTED_BYTES,
        expectedSha256 = WhisperModelConfig.EXPECTED_SHA256,
    )

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

    private companion object {
        const val TAG = "WhisperModelStore"
    }
}

/**
 * Per-ViewModel "model is Ready — Base or the transitional LegacyTiny
 * (U10 gating rule)" gate. Derived Eagerly so `.value` guards in the
 * owning VM read a live value (Stage 5-F: `.value` reads don't
 * subscribe a WhileSubscribed flow; this stateIn keeps the store's
 * upstream hot for the VM lifetime). Deliberately an extension over a
 * caller-owned [scope], NOT an always-hot store property — the per-VM
 * lifecycle keeps the store's WhileSubscribed flow cold when no VM is
 * alive.
 */
fun WhisperModelStore.modelReadyIn(scope: CoroutineScope): StateFlow<Boolean> =
    state
        .map { it is WhisperModelState.Ready }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = state.value is WhisperModelState.Ready,
        )
