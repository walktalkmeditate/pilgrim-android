// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
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

/**
 * Reactive join of [VoiceGuideManifestService.packs] +
 * [VoiceGuideFileStore] (via [VoiceGuideFileStore.invalidations] +
 * synchronous re-check on every combine emission) +
 * [VoiceGuideSelectionRepository.selectedPackId] +
 * [VoiceGuideDownloadScheduler.observe] (per pack), surfaced as
 * [packStates] for picker UIs.
 *
 * Action surface ([download], [select], [delete], etc.) forwards to
 * the underlying repositories so ViewModels have one injection
 * point instead of four.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class VoiceGuideCatalogRepository @Inject constructor(
    private val manifestService: VoiceGuideManifestService,
    private val fileStore: VoiceGuideFileStore,
    private val selection: VoiceGuideSelectionRepository,
    private val scheduler: VoiceGuideDownloadScheduler,
    @VoiceGuideCatalogScope private val scope: CoroutineScope,
) {
    val packStates: StateFlow<List<VoiceGuidePackState>> =
        combine(
            manifestService.packs,
            selection.selectedPackId,
            // Re-emit whenever files change. onStart emits once so the
            // combine fires on initial subscription.
            fileStore.invalidations.onStart { emit(Unit) },
        ) { packs, selectedId, _ ->
            packs.map { pack ->
                val isSelected = pack.id == selectedId
                if (fileStore.isPackDownloaded(pack)) {
                    VoiceGuidePackState.Downloaded(pack, isSelected)
                } else {
                    VoiceGuidePackState.NotDownloaded(pack, isSelected)
                }
            }
        }
            .flatMapLatest { bases ->
                if (bases.isEmpty()) flowOf(bases) else combineLatestProgress(bases)
            }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    private fun combineLatestProgress(
        bases: List<VoiceGuidePackState>,
    ): Flow<List<VoiceGuidePackState>> =
        combine(
            bases.map { base ->
                // `onStart { emit(null) }` so the combine fires even
                // when no WorkInfo exists for this pack yet.
                scheduler.observe(base.pack.id)
                    .onStart { emit(null) }
                    .map { progress -> base to progress }
            },
        ) { pairs ->
            pairs.map { (base, progress) -> applyProgress(base, progress) }
        }

    private fun applyProgress(
        base: VoiceGuidePackState,
        progress: DownloadProgress?,
    ): VoiceGuidePackState {
        if (progress == null) return base
        return when (progress.state) {
            DownloadProgress.State.Enqueued, DownloadProgress.State.Running ->
                VoiceGuidePackState.Downloading(
                    pack = base.pack,
                    isSelected = base.isSelected,
                    completed = progress.completed,
                    total = progress.total,
                )
            DownloadProgress.State.Failed -> VoiceGuidePackState.Failed(
                pack = base.pack,
                isSelected = base.isSelected,
                reason = FAIL_REASON_DOWNLOAD,
            )
            // Terminal success → filesystem should now report Downloaded;
            // Cancelled → filesystem dictates (partial files may remain
            // but isPackDownloaded will be false). Fall through to
            // whatever the filesystem-based base state says.
            DownloadProgress.State.Succeeded, DownloadProgress.State.Cancelled -> base
        }
    }

    fun download(packId: String) = scheduler.enqueue(packId)
    fun retry(packId: String) = scheduler.retry(packId)
    fun cancel(packId: String) = scheduler.cancel(packId)

    suspend fun select(packId: String) = selection.select(packId)
    suspend fun deselect() = selection.deselect()

    /**
     * Delete the pack's files; if the deleted pack was the selected
     * pack, also clear the selection. Matches iOS's
     * "can't have a deleted pack as the active guide" semantics.
     */
    suspend fun delete(pack: VoiceGuidePack) {
        fileStore.deletePack(pack)
        if (selection.selectedPackId.value == pack.id) selection.deselect()
    }

    private companion object {
        const val FAIL_REASON_DOWNLOAD = "download_failed"
    }
}
