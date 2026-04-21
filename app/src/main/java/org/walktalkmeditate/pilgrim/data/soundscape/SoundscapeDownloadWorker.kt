// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService

/**
 * `@HiltWorker` that downloads one soundscape asset.
 *
 * Simpler than [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadWorker]:
 * soundscapes are single-file-per-asset (no prompts loop). Same
 * atomic write-to-tmp + rename + size-verification pattern; same
 * single in-worker retry; same `CancellationException` rethrow
 * + tmp cleanup discipline.
 *
 * URL: `<baseUrl>/soundscape/<assetId>.aac` — matches iOS's
 * convention of reconstructing the path from id (not using
 * `r2Key` verbatim).
 */
@HiltWorker
class SoundscapeDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val httpClient: OkHttpClient,
    @SoundscapeBaseUrl private val baseUrl: String,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ASSET_ID) ?: return Result.failure()
        // Wait for manifest init so a WorkManager-rescheduled worker
        // after process death doesn't race the async cache load
        // (Stage 5-D lesson).
        manifestService.initialLoad.await()
        val asset = manifestService.asset(id)
            ?.takeIf { it.type == AudioAssetType.SOUNDSCAPE }
            ?: return Result.failure()

        if (fileStore.isAvailable(asset)) return Result.success()

        if (isStopped) return Result.failure()
        val ok = downloadAsset(asset) || (!isStopped && downloadAsset(asset))

        return if (ok && fileStore.isAvailable(asset)) Result.success() else Result.retry()
    }

    private suspend fun downloadAsset(asset: AudioAsset): Boolean =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + "/soundscape/${asset.id}.aac"
            val expectedSize = asset.fileSizeBytes
            val target = fileStore.fileFor(asset)
            val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "asset ${asset.id} non-2xx: ${response.code}")
                        return@use false
                    }
                    val body = response.body ?: return@use false
                    tmp.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                    if (tmp.length() != expectedSize) {
                        Log.w(
                            TAG,
                            "asset ${asset.id} size mismatch: ${tmp.length()} vs $expectedSize",
                        )
                        tmp.delete()
                        return@use false
                    }
                    if (!tmp.renameTo(target)) {
                        Files.move(
                            tmp.toPath(),
                            target.toPath(),
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    true
                }
            } catch (ce: CancellationException) {
                tmp.delete()
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "asset ${asset.id} failed", t)
                tmp.delete()
                false
            }
        }

    companion object {
        const val KEY_ASSET_ID = "asset_id"
        private const val TAG = "SoundscapeWorker"
        private const val TMP_SUFFIX = ".tmp"

        fun uniqueWorkName(assetId: String): String = "soundscape_download_$assetId"
    }
}
