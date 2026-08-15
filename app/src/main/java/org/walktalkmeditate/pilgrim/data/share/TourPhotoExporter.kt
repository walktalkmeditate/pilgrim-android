// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto

/**
 * One successfully exported hi-res tour photo. [file] holds the
 * re-encoded JPEG on disk, carrying none of the source's EXIF metadata
 * (see [TourPhotoExporter]'s class doc for the verified nuance on what
 * "EXIF-free" means in practice) — Phase 19 U5 writes artifacts to disk
 * rather than keeping bytes in memory the way iOS's `TourPhoto.jpegData`
 * does, mirroring U4's [SharePrepStore] artifact-file pattern for the
 * same large-payload-off-heap reason. [n] is the 1-based
 * position within the SUCCESSFULLY exported set — the same index used to
 * name [file] via [SharePrepStore.photoFile] — kept explicitly here
 * rather than re-derived from list position later (Stage 4-B lesson: a
 * helper re-deriving a value the caller already has invites silent
 * divergence). [sourceUri] is the originating [WalkPhoto.photoUri],
 * stable identity for matching a failed upload back to its source photo
 * later even if a retry's candidate list has a different order or
 * length (iOS parity `TourPhoto.sourceLocalIdentifier`,
 * `TourPhotoExporter.swift:5-14@3f9f9e8`).
 */
data class TourPhoto(
    val n: Int,
    val file: File,
    val meta: SharePayload.Photo,
    val sourceUri: String,
)

/**
 * Short-export result: [requested] is the (already-capped-at-[TourPhotoExporter.MAX_PHOTOS])
 * candidate count the export attempted; [exported] (== [photos].size) is
 * how many actually landed. iOS parity: `WalkShareViewModel+ShareOrchestration.share()`
 * derives the same shape from `exportList.count` vs `tourPhotos.count`
 * (`WalkShareViewModel+ShareOrchestration.swift:30-49@3f9f9e8`) to drive
 * the pre-POST `.photosDropped` consent pause — a later unit (U8) is the
 * consumer of [isShort] here.
 */
data class TourPhotoExportResult(
    val photos: List<TourPhoto>,
    val requested: Int,
) {
    val exported: Int get() = photos.size
    val isShort: Boolean get() = exported < requested
}

/**
 * Hi-res photo export for the interactive share's tour page. iOS parity
 * `Pilgrim/Models/Share/TourPhotoExporter.swift@3f9f9e8`, Decision 5 of
 * `docs/plans/2026-08-14-001-feat-walk-with-me-interactive-share-plan.md`.
 *
 * Reads the SAME MediaStore `content://` URI source the classic embedder
 * ([org.walktalkmeditate.pilgrim.data.pilgrim.builder.AndroidPilgrimPhotoEmbedder])
 * reads. Bounds are probed first ([BitmapFactory.Options.inJustDecodeBounds])
 * and the decode is sampled toward [TARGET_LONG_EDGE_PX] via
 * [computeInSampleSize] before a full pixel buffer is ever allocated —
 * mirrors [org.walktalkmeditate.pilgrim.data.pilgrim.builder.AndroidPilgrimPhotoEmbedder]'s
 * own bounds-probe-then-sample pattern rather than this class's original
 * full-resolution decode (U5 review: a rotated 50MP source could peak
 * near 380MB across the decode+EXIF-transform pair). EXIF orientation is
 * then applied to pixels, the long edge is scaled down EXACTLY (never
 * up) to [TARGET_LONG_EDGE_PX] — sampling never undershoots the target,
 * so this final scale always has real pixels to work with — then
 * [jpegDataUnder] walks the quality ladder until the encoded bytes fit
 * [MAX_BYTES]. [Bitmap.compress] carries forward none of the INPUT
 * file's EXIF metadata (verified: a source-orientation tag never
 * survives into the output). The output JPEG is not literally
 * EXIF-segment-free on every platform/encoder — verified empirically
 * that [androidx.exifinterface.media.ExifInterface] reads a plain
 * [Bitmap.compress] output's `TAG_ORIENTATION` as `"0"`
 * ([ExifInterface.ORIENTATION_UNDEFINED]) rather than a null/absent
 * attribute — but UNDEFINED and NORMAL both mean "apply no rotation" to
 * any EXIF-aware consumer, which is the property that actually matters:
 * the baked-into-pixels orientation from the source is never carried
 * into the output header.
 *
 * ### Per-candidate deadline
 *
 * iOS bounds each `PHImageManager` request with two independent
 * `DispatchWorkItem` deadlines — a [PER_PHOTO_TIMEOUT_MS] "cancel nudge"
 * and a [BACKSTOP_GRACE_MS] hard backstop beyond it — because a PhotoKit
 * request is a genuinely async, iCloud-fetching operation that can wedge
 * indefinitely (`TourPhotoExporter.swift:18-21,55-133@3f9f9e8`).
 * Android's read path ([android.content.ContentResolver.openInputStream]
 * + [BitmapFactory]) is a synchronous, blocking call with no request
 * object to independently cancel, so this class ports the SAME two
 * named constants (for citability) but enforces their SUM as a single
 * budget PER CANDIDATE via [kotlinx.coroutines.withTimeoutOrNull], reset
 * fresh for every candidate in [exportBounded]'s loop — one slow
 * candidate never eats into another's budget, and a candidate that times
 * out or fails is simply skipped (counted in
 * [TourPhotoExportResult.requested], never [TourPhotoExportResult.exported])
 * while the batch continues to the next candidate (U5 review fix: the
 * original cumulative-elapsed-time check aborted the WHOLE remaining
 * batch after any single slow photo).
 *
 * `withTimeoutOrNull` only preempts at a cooperative checkpoint — a
 * fully synchronous block that never suspends and never calls
 * [kotlinx.coroutines.ensureActive] is never preempted, deadline or not
 * (verified empirically against this project's kotlinx.coroutines
 * version before writing this mechanism). [loadOne] therefore calls
 * `coroutineContext.ensureActive()` between each blocking stage (read,
 * bounds probe, sampled decode) so a fired deadline is noticed as soon
 * as the CURRENT blocking call returns, not only at the next candidate.
 * A single blocking call that never returns at all (a pathological
 * SAF/cloud-backed provider) still cannot be preempted mid-call —
 * Android has no primitive to interrupt a blocking
 * `ContentResolver`/`BitmapFactory` call short of a dedicated
 * interruptible thread, out of scope here — but every call that DOES
 * eventually return, however late, is now bounded to the ONE candidate
 * it belongs to rather than stalling the rest of the batch.
 */
@Singleton
class TourPhotoExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharePrepStore: SharePrepStore,
) {

    /**
     * Exports up to [MAX_PHOTOS] of [candidates] for [walkUuid], writing
     * each to [SharePrepStore.photoFile]. [onProgress] fires
     * `(completed, total)` after every photo — same off-main-thread
     * contract as iOS (`TourPhotoExporter.swift:35-41@3f9f9e8`): this
     * suspend function makes no main-thread delivery guarantee, callers
     * hop themselves. Cancellable between photos: a
     * [CancellationException] from the caller's own scope propagates
     * out of this function rather than being swallowed into a partial
     * result (Kotlin structured-concurrency convention — unlike iOS's
     * `Task.isCancelled { break }`, which returns whatever was gathered
     * so far instead of throwing). Delegates to [exportBounded] with the
     * pinned [PER_PHOTO_TIMEOUT_MS] + [BACKSTOP_GRACE_MS] per-candidate
     * budget.
     */
    suspend fun export(
        walkUuid: String,
        candidates: List<WalkPhoto>,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): TourPhotoExportResult =
        exportBounded(walkUuid, candidates, PER_PHOTO_TIMEOUT_MS + BACKSTOP_GRACE_MS, onProgress)

    /**
     * [export]'s implementation, with the per-candidate deadline exposed
     * as [perCandidateBudgetMs] rather than hardcoded — the same "expose
     * the real parameter, default it at the public entry point" shape as
     * [jpegDataUnder]'s `capBytes`, so tests can exercise the real
     * [kotlinx.coroutines.withTimeoutOrNull] mechanism against a short,
     * real budget instead of waiting out the full 22s production
     * deadline. A timed-out or failed candidate is skipped — never
     * retried, never counted in [TourPhotoExportResult.exported] — and
     * the loop always proceeds to the next candidate; only a REAL
     * caller-scope cancellation exits early. The two
     * `coroutineContext.ensureActive()` calls disambiguate "this
     * candidate's own timeout fired" (swallowed as a null/skip) from
     * "the caller cancelled us" (re-thrown), mirroring
     * [SharePrepStore.prepareOne]'s
     * `catch (CancellationException) { ensureActive(); ... }` precedent.
     */
    internal suspend fun exportBounded(
        walkUuid: String,
        candidates: List<WalkPhoto>,
        perCandidateBudgetMs: Long,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> },
    ): TourPhotoExportResult = withContext(Dispatchers.IO) {
        val capped = candidates.take(MAX_PHOTOS)
        val requested = capped.size
        val exported = mutableListOf<TourPhoto>()
        for ((index, candidate) in capped.withIndex()) {
            coroutineContext.ensureActive()
            val photo = withTimeoutOrNull(perCandidateBudgetMs) {
                loadOne(walkUuid, candidate, n = exported.size + 1)
            }
            coroutineContext.ensureActive()
            if (photo != null) exported += photo
            onProgress(index + 1, requested)
        }
        TourPhotoExportResult(photos = exported, requested = requested)
    }

    /**
     * Bounds probe -> sampled decode (long edge kept >= [TARGET_LONG_EDGE_PX],
     * see [computeInSampleSize]) -> EXIF orientation applied to pixels ->
     * exact downscale to [TARGET_LONG_EDGE_PX] -> quality-ladder JPEG
     * encode -> write to [SharePrepStore.photoFile]. Returns null on ANY
     * failure (unresolvable URI, invalid bounds, decode failure, no
     * ladder rung fit the cap) or once this candidate's own deadline has
     * fired (see the class doc) — the caller counts that as a shortfall
     * and moves on to the next candidate, matching iOS's
     * `guard let image, let data = jpegDataUnder(...) else { resume(nil) }`.
     */
    private suspend fun loadOne(walkUuid: String, candidate: WalkPhoto, n: Int): TourPhoto? = try {
        val uri = Uri.parse(candidate.photoUri)
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            Log.w(TAG, "openInputStream returned null for ${candidate.photoUri}")
            null
        } else {
            coroutineContext.ensureActive()
            val orientation = readOrientation(bytes)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "invalid bounds for ${candidate.photoUri}")
                null
            } else {
                coroutineContext.ensureActive()
                val sampleSize = computeInSampleSize(bounds.outWidth, bounds.outHeight, TARGET_LONG_EDGE_PX)
                val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                if (decoded == null) {
                    Log.w(TAG, "decode failed for ${candidate.photoUri}")
                    null
                } else {
                    coroutineContext.ensureActive()
                    encodeAndWrite(walkUuid, candidate, n, decoded, orientation)
                }
            }
        }
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "photo export failed for ${candidate.photoUri}", t)
        null
    }

    private fun encodeAndWrite(
        walkUuid: String,
        candidate: WalkPhoto,
        n: Int,
        decoded: Bitmap,
        orientation: Int,
    ): TourPhoto? = try {
        val oriented = applyExifOrientation(decoded, orientation)
        try {
            val scaled = scaleToLongEdge(oriented, TARGET_LONG_EDGE_PX)
            try {
                val jpeg = jpegDataUnder(scaled, MAX_BYTES)
                if (jpeg == null) {
                    Log.w(TAG, "no quality-ladder rung fit the $MAX_BYTES-byte cap for ${candidate.photoUri}")
                    null
                } else {
                    val file = sharePrepStore.photoFile(walkUuid, n)
                    file.parentFile?.mkdirs()
                    file.writeBytes(jpeg)
                    TourPhoto(
                        n = n,
                        file = file,
                        meta = SharePayload.Photo(
                            lat = candidate.capturedLat ?: 0.0,
                            lon = candidate.capturedLng ?: 0.0,
                            ts = (candidate.takenAt ?: 0L) / MILLIS_PER_SECOND,
                            data = null,
                        ),
                        sourceUri = candidate.photoUri,
                    )
                }
            } finally {
                if (scaled !== oriented) scaled.recycle()
            }
        } finally {
            if (oriented !== decoded) oriented.recycle()
        }
    } finally {
        decoded.recycle()
    }

    private fun readOrientation(bytes: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (ce: CancellationException) {
        throw ce
    } catch (t: Throwable) {
        Log.w(TAG, "EXIF orientation read failed", t)
        ExifInterface.ORIENTATION_NORMAL
    }

    companion object {
        /** `TourPhotoExporter.swift:18@3f9f9e8` — per-photo output byte cap. */
        const val MAX_BYTES = 2 * 1024 * 1024

        /** `TourPhotoExporter.swift:19@3f9f9e8` — long-edge target in pixels, downscale-only. */
        const val TARGET_LONG_EDGE_PX = 1600

        /**
         * `TourPhotoExporter.swift:20@3f9f9e8` — PhotoKit cancel-nudge
         * deadline; see the class doc for the ported-but-adapted
         * enforcement mechanism.
         */
        const val PER_PHOTO_TIMEOUT_MS = 20_000L

        /** `TourPhotoExporter.swift:21@3f9f9e8` — additional backstop grace beyond [PER_PHOTO_TIMEOUT_MS]. */
        const val BACKSTOP_GRACE_MS = 2_000L

        /**
         * `TourPhotoExporter.swift:27@3f9f9e8` — `[0.8, 0.65, 0.5, 0.35, 0.2]`.
         * Android's [Bitmap.compress] quality is an Int 0-100, not a
         * Float 0.0-1.0 — x100 translation per the parity spec's DAT-52
         * drift note.
         */
        val QUALITY_LADDER = intArrayOf(80, 65, 50, 35, 20)

        /**
         * `WalkShareViewModel+ShareOrchestration.swift:412@3f9f9e8`
         * (`.prefix(20)`) — a bare literal on iOS (the parity spec's
         * DAT-66 drift note flags it as easy to miss when enumerating
         * "all the caps" since it isn't centrally defined alongside the
         * other TourBuilder/TourPhotoExporter constants); named here.
         */
        const val MAX_PHOTOS = 20

        private const val MILLIS_PER_SECOND = 1_000L
        private const val TAG = "TourPhotoExporter"

        /**
         * iOS `jpegDataUnder(cap:image:)` (`TourPhotoExporter.swift:26-33@3f9f9e8`):
         * walks [QUALITY_LADDER] top to bottom, returning the first
         * encoding whose byte size fits [capBytes], or null if none do.
         */
        internal fun jpegDataUnder(bitmap: Bitmap, capBytes: Int): ByteArray? {
            for (quality in QUALITY_LADDER) {
                val baos = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)) continue
                val bytes = baos.toByteArray()
                if (bytes.size <= capBytes) return bytes
            }
            return null
        }

        /**
         * Aspect-preserving downscale to [targetLongEdgePx]; returns
         * [bitmap] unchanged if its long edge is already <= target
         * (never upscales).
         */
        internal fun scaleToLongEdge(bitmap: Bitmap, targetLongEdgePx: Int): Bitmap {
            val longEdge = maxOf(bitmap.width, bitmap.height)
            if (longEdge <= targetLongEdgePx) return bitmap
            val scale = targetLongEdgePx.toFloat() / longEdge
            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        }

        /**
         * Largest power-of-two `inSampleSize` whose decoded long edge
         * stays >= [targetLongEdgePx] — sampling never undershoots the
         * target, so the later exact [scaleToLongEdge] pass always has
         * real pixels to downscale rather than upscaling a too-small
         * sampled decode. Mirrors
         * [org.walktalkmeditate.pilgrim.data.pilgrim.builder.AndroidPilgrimPhotoEmbedder]'s
         * bounds-probe-then-sample pattern (U5 review: full-resolution
         * decode before this fix could peak near 380MB for a rotated
         * 50MP source).
         */
        internal fun computeInSampleSize(width: Int, height: Int, targetLongEdgePx: Int): Int {
            val longEdge = maxOf(width, height)
            var sample = 1
            while (longEdge / (sample * 2) >= targetLongEdgePx) {
                sample *= 2
            }
            return sample
        }

        /**
         * Bakes EXIF [orientation] into pixels via a rotate/flip matrix
         * so the re-encoded JPEG needs no EXIF block to display upright;
         * returns [bitmap] unchanged for NORMAL/UNDEFINED orientation.
         */
        internal fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.postScale(-1f, 1f)
                }
                else -> return bitmap
            }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }
}
