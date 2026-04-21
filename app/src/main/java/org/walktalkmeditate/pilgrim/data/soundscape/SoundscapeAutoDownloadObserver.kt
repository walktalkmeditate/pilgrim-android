// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService

/**
 * App-scoped observer that subscribes to [AudioManifestService.assets],
 * filters to soundscape-typed assets, and enqueues any that aren't on
 * disk. Matches iOS's behavior: `AudioManifestService.syncIfNeeded`
 * calls `downloadManager.downloadMissing(assets)` whenever a new
 * manifest is fetched, so all soundscapes (+ bells on iOS) get
 * background-downloaded without user action.
 *
 * [start] must be called once from `PilgrimApp.onCreate`. Subscription
 * lives for the process. Also kicks off a manifest sync on start so
 * a fresh install begins downloading as soon as the network responds.
 *
 * Uses the KEEP policy via [SoundscapeDownloadScheduler.enqueue] —
 * already-enqueued or in-flight assets are no-ops.
 */
@Singleton
class SoundscapeAutoDownloadObserver @Inject constructor(
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val scheduler: SoundscapeDownloadScheduler,
    @SoundscapeCatalogScope private val scope: CoroutineScope,
) {
    fun start() {
        scope.launch { observe() }
    }

    private suspend fun observe() {
        // Kick a manifest sync on start so fresh installs without a
        // cached manifest begin downloading as soon as the network
        // responds. No-op if a sync is already in flight.
        manifestService.syncIfNeeded()

        manifestService.assets.collect { assets ->
            val missing = withContext(Dispatchers.IO) {
                assets
                    .filter { it.type == AudioAssetType.SOUNDSCAPE }
                    .filter { !fileStore.isAvailable(it) }
            }
            for (asset in missing) {
                try {
                    scheduler.enqueue(asset.id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    // Don't let one bad enqueue kill the observer; log
                    // and continue. Stage 5-D lesson.
                    Log.w(TAG, "enqueue failed for ${asset.id}", t)
                }
            }
        }
    }

    private companion object {
        const val TAG = "SoundscapeAutoDl"
    }
}
