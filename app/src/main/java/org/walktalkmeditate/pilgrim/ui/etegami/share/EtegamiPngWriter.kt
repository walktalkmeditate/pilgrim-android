// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7-D: writes an etegami [Bitmap] to the app's cacheDir under
 * `etegami/<filename>` with atomic-rename semantics. Runs on
 * [Dispatchers.IO] — the `compress` call is mixed CPU + disk I/O,
 * and under StrictMode (debug/QA builds) writing to cacheDir from
 * a Default-pool thread trips `DiskWriteViolation`. IO dispatcher
 * is sized for blocking writes, handles `fd.sync()` latency
 * gracefully, and keeps CPU cores available for rendering.
 *
 * Atomic semantics: writes to `<filename>.tmp` then `renameTo` the
 * final name. A crash mid-write leaves an orphan `.tmp` for
 * [EtegamiCacheSweeper] to remove; callers never see a half-written
 * final file.
 *
 * Caller owns the input [Bitmap]'s lifecycle — writer never recycles,
 * even on failure. Callers typically recycle in a try/finally after
 * the writer returns (Stage 7-B Bitmap-lifecycle pattern).
 */
internal object EtegamiPngWriter {

    /** Root `<cacheDir>/etegami/` directory; created on first call. */
    fun cacheRoot(context: Context): File =
        File(context.cacheDir, "etegami").apply { mkdirs() }

    /**
     * Writes [bitmap] to `cacheRoot/<filename>` and returns the final
     * [File]. Throws on any I/O failure; callers wrap in
     * `runCatching` + explicit CE re-throw (Stage 5-C / 7-C lesson).
     */
    suspend fun writeToCache(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): File = withContext(Dispatchers.IO) {
        require(filename.endsWith(".png")) { "filename must end with .png (got $filename)" }
        val dir = cacheRoot(context)
        val tmp = File(dir, "$filename.tmp")
        val finalFile = File(dir, filename)
        FileOutputStream(tmp).use { out ->
            // PNG is lossless so the quality parameter is ignored; 100
            // is customary for clarity.
            val ok = bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (!ok) {
                tmp.delete()
                error("Bitmap.compress returned false for $filename")
            }
            out.flush()
            out.fd.sync()
        }
        if (!tmp.renameTo(finalFile)) {
            tmp.delete()
            error("Atomic rename failed: $tmp → $finalFile")
        }
        finalFile
    }
}
