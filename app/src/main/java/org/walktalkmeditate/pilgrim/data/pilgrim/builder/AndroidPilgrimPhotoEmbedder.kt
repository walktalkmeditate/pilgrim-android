// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk

/**
 * Reads pinned reliquary photos from MediaStore `content://` URIs,
 * resizes to ≤600×600 aspect-fit JPEG @ quality 70, writes them into
 * `tempDir/photos/<sanitized-localid>.jpg`. Returns a
 * `localIdentifier → filename` map for the builder to stamp onto
 * each `PilgrimPhoto.embeddedPhotoFilename`, plus a skipped count
 * for the post-share alert.
 *
 * Mirrors iOS `PilgrimPhotoEmbedder` but consumes
 * `Context.contentResolver.openInputStream(uri)` rather than
 * synchronous PhotoKit fetches.
 */
@Singleton
class AndroidPilgrimPhotoEmbedder @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    data class EmbedResult(
        val filenameMap: Map<String, String>,
        val skippedCount: Int,
    )

    /**
     * Must be called from a background coroutine — reads MediaStore
     * bytes synchronously.
     */
    fun embedPhotos(walks: List<PilgrimWalk>, tempDir: File): EmbedResult {
        val photosDir = File(tempDir, PHOTOS_DIR_NAME)
        val filenameMap = mutableMapOf<String, String>()
        var skippedCount = 0
        var photosDirEnsured = false

        for (walk in walks) {
            val photos = walk.photos ?: continue
            for (photo in photos) {
                if (filenameMap.containsKey(photo.localIdentifier)) {
                    continue
                }
                val jpegBytes = encodePhoto(photo.localIdentifier)
                if (jpegBytes == null) {
                    skippedCount += 1
                    continue
                }
                if (!photosDirEnsured) {
                    if (!photosDir.exists() && !photosDir.mkdirs()) {
                        Log.w(TAG, "Failed to create photos dir at $photosDir")
                        skippedCount += 1
                        continue
                    }
                    photosDirEnsured = true
                }
                val filename = sanitizedFilename(photo.localIdentifier)
                val target = File(photosDir, filename)
                try {
                    target.writeBytes(jpegBytes)
                    filenameMap[photo.localIdentifier] = filename
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to write $filename", e)
                    skippedCount += 1
                }
            }
        }

        return EmbedResult(filenameMap = filenameMap, skippedCount = skippedCount)
    }

    /**
     * Encode a pinned photo's bytes as a `data:image/jpeg;base64,...`
     * URL for in-app WebView rendering (Stage 10-I JourneyViewer
     * thumbnail enrichment). iOS reference:
     * `JourneyViewerView.swift` `enrichWithInlinePhotos(_)`.
     *
     * Returns null on any failure (URI gone, decode failure, encode
     * exceeds the 150KB ceiling — same skip semantics as the
     * disk-write path). Must be called from a background coroutine.
     */
    fun encodeAsDataUrl(localIdentifier: String): String? {
        val bytes = encodePhoto(localIdentifier) ?: return null
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    /**
     * Read URI → decode bounds → re-decode with inSampleSize → resize
     * → JPEG-encode @ quality 70. Returns null on any failure (caller
     * counts as skipped).
     */
    private fun encodePhoto(localIdentifier: String): ByteArray? {
        val uri = try {
            Uri.parse(localIdentifier)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse photo URI: $localIdentifier", e)
            return null
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            } ?: run {
                Log.w(TAG, "openInputStream returned null for $localIdentifier")
                return null
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to probe dimensions for $localIdentifier", e)
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Invalid bounds for $localIdentifier: ${bounds.outWidth}x${bounds.outHeight}")
            return null
        }

        val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, DECODE_TARGET_PX)
        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded: Bitmap = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to decode $localIdentifier", e)
            return null
        } ?: return null

        try {
            val resized = resizeAspectFit(decoded, MAX_DIMENSION_PX)
            val recycled = resized !== decoded
            try {
                val baos = ByteArrayOutputStream()
                val ok = resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                if (!ok) {
                    Log.w(TAG, "Bitmap.compress returned false for $localIdentifier")
                    return null
                }
                val bytes = baos.toByteArray()
                if (bytes.size > MAX_ENCODED_BYTES) {
                    Log.w(
                        TAG,
                        "Photo $localIdentifier exceeded max size " +
                            "(${bytes.size}/$MAX_ENCODED_BYTES bytes), dropping",
                    )
                    return null
                }
                return bytes
            } finally {
                if (recycled) resized.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    /**
     * Power-of-two `inSampleSize` so the resulting decoded long edge
     * does not exceed `target`. iOS asks PhotoKit for a 1200×1200
     * candidate; we approximate with `target = 1200` and let
     * BitmapFactory's inSampleSize do the bulk of the size reduction
     * before we apply final aspect-fit math in pixel space.
     */
    private fun computeSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= target && height <= target) return 1
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target || h / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * Aspect-fit resize. Returns the original bitmap if it already
     * fits — caller checks `result !== input` to decide whether to
     * recycle.
     */
    private fun resizeAspectFit(input: Bitmap, maxDimension: Int): Bitmap {
        val w = input.width
        val h = input.height
        if (w <= maxDimension && h <= maxDimension) return input
        val scale = minOf(maxDimension.toFloat() / w, maxDimension.toFloat() / h)
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(input, newW, newH, true)
    }

    companion object {
        const val PHOTOS_DIR_NAME = "photos"

        private const val MAX_DIMENSION_PX = 600

        private const val DECODE_TARGET_PX = 1200

        private const val JPEG_QUALITY = 70

        private const val MAX_ENCODED_BYTES = 150_000

        private const val TAG = "PilgrimPhotoEmbedder"

        /**
         * `content://` URIs contain `/` separators. Replace with `_`
         * to flatten so they're safe filenames. Appends `.jpg`. Same
         * pattern as iOS.
         */
        fun sanitizedFilename(localIdentifier: String): String =
            localIdentifier.replace('/', '_') + ".jpg"
    }
}
