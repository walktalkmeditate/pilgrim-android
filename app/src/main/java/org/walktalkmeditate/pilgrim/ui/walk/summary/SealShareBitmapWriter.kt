// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.Context
import android.graphics.Bitmap
import java.io.File

/**
 * Writes a goshuin seal [Bitmap] to the app's cache directory under
 * a deterministic filename so subsequent share intents reuse the same
 * file (no per-tap accumulation). Mirrors EtegamiPngWriter's contract.
 */
internal object SealShareBitmapWriter {

    const val CACHE_SUBDIR = "seals/share"

    /**
     * Write [bitmap] as a PNG under `<cacheDir>/seals/share/seal-<suffix>.png`.
     * Returns the resulting File; caller passes to FileProvider.getUriForFile.
     *
     * Overwrites existing file at the same path (deterministic naming + idempotent
     * write is the design — re-tapping the goshuin share button on the same walk
     * doesn't accumulate cache files).
     */
    fun writeToCache(bitmap: Bitmap, suffix: String, context: Context): File {
        val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val file = File(dir, "seal-$suffix.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file
    }
}
