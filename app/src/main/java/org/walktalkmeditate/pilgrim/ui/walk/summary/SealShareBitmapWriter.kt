// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a goshuin seal [Bitmap] to the app's cache directory under
 * a deterministic filename so subsequent share intents reuse the same
 * file (no per-tap accumulation). Mirrors EtegamiPngWriter's contract:
 * suspend + Dispatchers.IO (StrictMode-safe), atomic-rename semantics
 * via `.tmp` + `renameTo`, and explicit failure modes via `error()`
 * rather than silent half-writes.
 */
internal object SealShareBitmapWriter {

    const val CACHE_SUBDIR = "seals/share"

    /**
     * Write [bitmap] to `<cacheDir>/seals/share/seal-<suffix>.png` and
     * return the resulting File for FileProvider.getUriForFile.
     *
     * Atomic semantics: writes to `<filename>.tmp` then `renameTo` the
     * final name. A crash mid-write leaves an orphan `.tmp` that the
     * next successful write to the same suffix overwrites; FileProvider
     * never observes a half-written PNG.
     *
     * Throws on compress / rename failure — callers wrap in try/catch
     * with explicit CancellationException re-throw (Stage 5-C lesson).
     */
    suspend fun writeToCache(
        bitmap: Bitmap,
        suffix: String,
        context: Context,
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val tmp = File(dir, "seal-$suffix.png.tmp")
        val finalFile = File(dir, "seal-$suffix.png")
        FileOutputStream(tmp).use { out ->
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (!ok) {
                tmp.delete()
                error("Bitmap.compress returned false for seal-$suffix.png")
            }
            out.flush()
            out.fd.sync()
        }
        if (finalFile.exists()) finalFile.delete()
        if (!tmp.renameTo(finalFile)) {
            tmp.delete()
            error("Atomic rename failed: $tmp → $finalFile")
        }
        finalFile
    }
}
