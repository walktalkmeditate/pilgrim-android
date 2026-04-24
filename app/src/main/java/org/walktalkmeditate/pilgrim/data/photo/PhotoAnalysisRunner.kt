// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.net.Uri
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import org.walktalkmeditate.pilgrim.data.WalkRepository

/**
 * Best-effort batch orchestrator for Stage 7-B photo analysis. Mirrors
 * [org.walktalkmeditate.pilgrim.audio.TranscriptionRunner] — read
 * pending rows, process each, write back via the repository, log
 * per-photo failures without aborting the batch.
 *
 * A photo is "pending" when its `analyzed_at` is null. The runner
 * always stamps `analyzed_at = clock()` on each row it visits,
 * regardless of whether the labeler produced a result — so a
 * permanently broken URI (deleted library, revoked grant) is marked
 * once and not retried on the next schedule. The UI tombstone path
 * handles display when `top_label` stays null post-analysis.
 */
@Singleton
class PhotoAnalysisRunner @Inject constructor(
    private val repository: WalkRepository,
    private val labeler: PhotoLabeler,
    private val bitmapLoader: BitmapLoader,
) {
    /**
     * [clock] is a parameter so tests can stamp deterministic times
     * without coupling to virtual-time machinery. Returns the number
     * of rows successfully written (whether labeled or tombstoned).
     */
    suspend fun analyzePending(
        walkId: Long,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Result<Int> {
        val pending = try {
            repository.pendingAnalysisPhotosFor(walkId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            return Result.failure(t)
        }

        var written = 0
        for (photo in pending) {
            val bitmap = bitmapLoader.load(Uri.parse(photo.photoUri))
            val now = clock()
            val top: LabeledResult? = if (bitmap == null) {
                null
            } else {
                try {
                    labeler.label(bitmap).firstOrNull()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    Log.w(TAG, "labeler failed id=${photo.id}; storing null label", t)
                    null
                } finally {
                    // We own the Bitmap; release it before the next
                    // iteration so a 20-photo batch doesn't accumulate
                    // ~30 MB of decoded images in memory.
                    bitmap.recycle()
                }
            }
            try {
                repository.updatePhotoAnalysis(
                    photoId = photo.id,
                    label = top?.text,
                    confidence = top?.confidence,
                    analyzedAt = now,
                )
                written++
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "updatePhotoAnalysis failed id=${photo.id}", t)
            }
        }
        return Result.success(written)
    }

    companion object {
        private const val TAG = "PhotoAnalysisRunner"
    }
}
