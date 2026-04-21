// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.audio.download.DownloadProgress

/**
 * Reactive join of [AudioManifestService.assets] (filtered to
 * soundscapes) + [SoundscapeFileStore] + [SoundscapeSelectionRepository]
 * + [SoundscapeDownloadScheduler.observe] into picker-ready
 * [SoundscapeState] DTOs.
 *
 * Same shape as `VoiceGuideCatalogRepository` (Stage 5-D),
 * including the `applyProgress` terminal-state re-read of the
 * filesystem (fix for the "catalog stuck on NotDownloaded after
 * worker success" bug caught in 5-D polish) and the
 * `withContext(Dispatchers.IO)` wrap around the combine's
 * filesystem walks (5-D initial review fix).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class SoundscapeCatalogRepository @Inject constructor(
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val selection: SoundscapeSelectionRepository,
    private val scheduler: SoundscapeDownloadScheduler,
    @SoundscapeCatalogScope private val scope: CoroutineScope,
) {
    val soundscapeStates: StateFlow<List<SoundscapeState>> =
        combine(
            manifestService.assets,
            selection.selectedSoundscapeId,
            fileStore.invalidations.onStart { emit(Unit) },
        ) { allAssets, selectedId, _ ->
            // Hop to IO for the filesystem walk — scope is Dispatchers.Default
            // (shared with whisper.cpp). Stage 5-D lesson.
            withContext(Dispatchers.IO) {
                allAssets
                    .filter { it.type == AudioAssetType.SOUNDSCAPE }
                    .map { asset ->
                        val isSelected = asset.id == selectedId
                        if (fileStore.isAvailable(asset)) {
                            SoundscapeState.Downloaded(asset, isSelected)
                        } else {
                            SoundscapeState.NotDownloaded(asset, isSelected)
                        }
                    }
            }
        }
            .flatMapLatest { bases ->
                if (bases.isEmpty()) flowOf(bases) else combineLatestProgress(bases)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private fun combineLatestProgress(
        bases: List<SoundscapeState>,
    ): Flow<List<SoundscapeState>> =
        combine(
            bases.map { base ->
                scheduler.observe(base.asset.id)
                    .onStart { emit(null) }
                    .map { progress -> base to progress }
            },
        ) { pairs ->
            pairs.map { (base, progress) -> applyProgress(base, progress) }
        }

    private suspend fun applyProgress(
        base: SoundscapeState,
        progress: DownloadProgress?,
    ): SoundscapeState {
        if (progress == null) return base
        return when (progress.state) {
            DownloadProgress.State.Enqueued, DownloadProgress.State.Running ->
                SoundscapeState.Downloading(base.asset, base.isSelected)
            DownloadProgress.State.Failed ->
                SoundscapeState.Failed(base.asset, base.isSelected, FAIL_REASON_DOWNLOAD)
            // Terminal transitions re-read the filesystem — the outer
            // combine's captured `base` is stale after the worker
            // wrote files (Stage 5-D lesson: worker side-effects
            // don't flow through `fileStore.invalidations`).
            DownloadProgress.State.Succeeded, DownloadProgress.State.Cancelled ->
                withContext(Dispatchers.IO) {
                    if (fileStore.isAvailable(base.asset)) {
                        SoundscapeState.Downloaded(base.asset, base.isSelected)
                    } else {
                        SoundscapeState.NotDownloaded(base.asset, base.isSelected)
                    }
                }
        }
    }

    /** Manifest refresh pass-through so the picker VM has one injection point. */
    fun refreshManifest() = manifestService.syncIfNeeded()

    fun download(assetId: String) = scheduler.enqueue(assetId)
    fun retry(assetId: String) = scheduler.retry(assetId)
    fun cancel(assetId: String) = scheduler.cancel(assetId)

    suspend fun select(assetId: String) = selection.select(assetId)
    suspend fun deselect() = selection.deselect()

    /**
     * Delete the soundscape's file; if it was selected, also
     * deselect. Cancels any in-flight download worker first —
     * defense-in-depth from Stage 5-D closing review.
     */
    suspend fun delete(asset: AudioAsset) {
        scheduler.cancel(asset.id)
        fileStore.delete(asset)
        if (selection.selectedSoundscapeId.value == asset.id) selection.deselect()
    }

    private companion object {
        const val FAIL_REASON_DOWNLOAD = "download_failed"
    }
}
