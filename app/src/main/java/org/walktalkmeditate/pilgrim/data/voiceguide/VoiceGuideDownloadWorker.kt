// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
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

/**
 * `@HiltWorker` that downloads all missing prompts for one pack.
 *
 * Contract:
 *  - Input: `KEY_PACK_ID` — the pack's manifest id.
 *  - Output: `KEY_COMPLETED` + `KEY_TOTAL` via `setProgress`, plus
 *    `Result.success()` on fully-downloaded, `Result.retry()` when
 *    partial (WorkManager will back off + retry), `Result.failure()`
 *    on permanent input errors (missing pack, no pack id).
 *
 * Per-prompt behavior:
 *  - Atomic write: stream body into `<r2Key>.tmp`, verify size, rename
 *    over the target (fall back to `Files.move(REPLACE_EXISTING)`).
 *  - Single in-worker retry (matches iOS's single-retry policy).
 *  - Size mismatch deletes the tmp; the prompt remains missing and
 *    the next worker invocation retries.
 *
 * Cancellation is checked between prompts (`isStopped`). Returns
 * `failure()` (not `success`) on cancel so downstream observers see
 * the pack as incomplete.
 */
@HiltWorker
class VoiceGuideDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val manifestService: VoiceGuideManifestService,
    private val fileStore: VoiceGuideFileStore,
    private val httpClient: OkHttpClient,
    @VoiceGuidePromptBaseUrl private val promptBaseUrl: String,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val packId = inputData.getString(KEY_PACK_ID) ?: return Result.failure()
        val pack = manifestService.pack(id = packId) ?: return Result.failure()

        val all = fileStore.allPrompts(pack)
        val toFetch = fileStore.missingPrompts(pack)
        val total = all.size
        var completed = total - toFetch.size
        reportProgress(completed, total)

        for (prompt in toFetch) {
            if (isStopped) return Result.failure()
            val ok = downloadPrompt(prompt) || (!isStopped && downloadPrompt(prompt))
            if (ok) {
                completed += 1
                reportProgress(completed, total)
            }
            // If !ok after one retry: leave the prompt missing. Next
            // worker invocation (WorkManager retry or user-initiated)
            // picks it up via missingPrompts().
        }
        return if (fileStore.isPackDownloaded(pack)) Result.success() else Result.retry()
    }

    private suspend fun downloadPrompt(prompt: VoiceGuidePrompt): Boolean =
        withContext(Dispatchers.IO) {
            val url = promptBaseUrl.trimEnd('/') + "/" + prompt.r2Key.trimStart('/')
            val target = fileStore.fileForPrompt(prompt.r2Key)
            val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
            try {
                val request = Request.Builder().url(url).build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "prompt ${prompt.r2Key} non-2xx: ${response.code}")
                        return@use false
                    }
                    val body = response.body ?: run {
                        Log.w(TAG, "prompt ${prompt.r2Key} empty body")
                        return@use false
                    }
                    tmp.sink().buffer().use { sink -> sink.writeAll(body.source()) }
                    if (tmp.length() != prompt.fileSizeBytes) {
                        Log.w(
                            TAG,
                            "prompt ${prompt.r2Key} size mismatch: ${tmp.length()} vs ${prompt.fileSizeBytes}",
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
                // Preserve structured concurrency. Clean up the tmp on
                // the way out — matches the pattern from
                // VoiceGuideManifestService.saveLocalManifest.
                tmp.delete()
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "prompt ${prompt.r2Key} failed", t)
                tmp.delete()
                false
            }
        }

    private suspend fun reportProgress(completed: Int, total: Int) {
        setProgress(workDataOf(KEY_COMPLETED to completed, KEY_TOTAL to total))
    }

    companion object {
        const val KEY_PACK_ID = "pack_id"
        const val KEY_COMPLETED = "completed"
        const val KEY_TOTAL = "total"
        private const val TAG = "VoiceGuideWorker"
        private const val TMP_SUFFIX = ".tmp"

        fun uniqueWorkName(packId: String): String = "voice_guide_download_$packId"
    }
}
