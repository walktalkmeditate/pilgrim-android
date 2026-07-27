// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import androidx.compose.runtime.Immutable

/**
 * Delivery state of the on-demand whisper model, composed by
 * [WhisperModelStore] from filesystem probe × download-work snapshot ×
 * unmetered-network probe. The Android analogue of iOS
 * `TranscriptionService.State.downloadingModel(progress:)`, widened for
 * constraint-gated WorkManager delivery (parity spec
 * `docs/parity/2026-07-26-port-model-state-u8.md`, D4).
 *
 * Transient network failures are deliberately NOT a state: the download
 * worker returns retry and WorkManager backs off internally, so this
 * flow re-presents [Enqueued]/[WaitingUnmetered] until bytes flow
 * again. Only the two user-actionable terminals — [FailedChecksum]
 * (retry) and [FailedStorage] (free up space) — are modeled, and both
 * arrive via the work source's outputs, never the filesystem.
 */
@Immutable
sealed interface WhisperModelState {

    data object Absent : WhisperModelState

    data object Enqueued : WhisperModelState

    data object WaitingUnmetered : WhisperModelState

    @Immutable
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
    ) : WhisperModelState {
        val fraction: Float
            get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes).toFloat()
    }

    data object Verifying : WhisperModelState

    @Immutable
    data class Ready(val variant: WhisperModelVariant) : WhisperModelState

    data object FailedChecksum : WhisperModelState

    data object FailedStorage : WhisperModelState
}

/**
 * Which model satisfies transcription right now. [LegacyTiny] is the
 * upgrade-window overlay: a v1.2.0 install's tiny keeps serving the
 * engine until base is SHA-verified (U10 deletes it after the atomic
 * switch). It never satisfies the shipped base variant.
 */
enum class WhisperModelVariant { Base, LegacyTiny }
