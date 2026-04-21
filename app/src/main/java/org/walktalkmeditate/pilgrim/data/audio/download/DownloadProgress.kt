// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.audio.download

/**
 * Progress + terminal state for a single-asset or multi-file
 * WorkManager download. Shared across voice-guide (Stage 5-D,
 * multi-prompt) and soundscape (Stage 5-F, single-asset)
 * download stacks.
 *
 * `completed` / `total` are counts (not bytes). Schedulers report
 * them via `setProgress(workDataOf(...))` from the worker;
 * consumers display them as "X of Y" in the picker UI.
 *
 * A null `DownloadProgress` from `observe(id)` means no work
 * currently tracked for the id — the catalog overlays this onto
 * a filesystem-only base state (NotDownloaded/Downloaded).
 */
data class DownloadProgress(
    val state: State,
    val completed: Int,
    val total: Int,
) {
    enum class State { Enqueued, Running, Succeeded, Failed, Cancelled }
}
