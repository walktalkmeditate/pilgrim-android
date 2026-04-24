// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a `content://` URI into a Bitmap, downsampled with a
 * power-of-two `inSampleSize` targeting approximately [MAX_EDGE_PX]
 * on the long edge. Because `BitmapFactory.Options.inSampleSize` only
 * accepts powers of two, the actual long edge can be up to roughly
 * 2× [MAX_EDGE_PX] for inputs whose long edge doesn't sit at a
 * power-of-two boundary — matches Android's standard downsampling
 * idiom (see the "Loading Large Bitmaps Efficiently" training guide).
 * Memory ceiling stays bounded: a 2000×1500 ARGB_8888 is ~12 MB, fine
 * for one-at-a-time recycled iteration inside [PhotoAnalysisRunner].
 *
 * Interface-at-the-boundary so tests can fake it without touching
 * ContentResolver / BitmapFactory. Production uses [ContentResolverBitmapLoader].
 */
interface BitmapLoader {
    /**
     * Returns a decoded Bitmap, or `null` when the URI can't be
     * opened (grant revoked, source photo deleted, SD card
     * unmounted, decode threw OOM / invalid format).
     */
    suspend fun load(uri: Uri): Bitmap?

    companion object {
        const val MAX_EDGE_PX: Int = 1024
    }
}

/**
 * Production implementation. Two-pass decode:
 *  1. `inJustDecodeBounds = true` to read the source dimensions.
 *  2. Decode again with `inSampleSize` computed from those dimensions.
 *
 * The runner interprets `null` as "mark analyzed with no label" so
 * the tombstone UI takes over for display.
 */
@Singleton
class ContentResolverBitmapLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) : BitmapLoader {

    override suspend fun load(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        val bounds = readBounds(uri) ?: return@withContext null
        val sampleSize = computeInSampleSize(bounds.first, bounds.second)
        decodeSampled(uri, sampleSize)
    }

    private fun readBounds(uri: Uri): Pair<Int, Int>? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, /* outPadding = */ null, opts)
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null
        else opts.outWidth to opts.outHeight
    }.getOrNull()

    private fun decodeSampled(uri: Uri, sampleSize: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, /* outPadding = */ null, opts)
        }
    }.getOrNull()

    private fun computeInSampleSize(width: Int, height: Int): Int {
        val longest = max(width, height)
        if (longest <= BitmapLoader.MAX_EDGE_PX) return 1
        // Round down to next power of two — BitmapFactory rounds up anyway,
        // but computing explicitly keeps the behavior obvious.
        var sample = 1
        while (longest / (sample * 2) >= BitmapLoader.MAX_EDGE_PX) {
            sample *= 2
        }
        return max(sample, 1)
    }
}
