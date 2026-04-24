// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Stage 7-D: saves the etegami PNG to the user's Photos / Gallery.
 *
 * API 29+ (Q+): MediaStore scoped-storage insert, two-phase
 * IS_PENDING pattern — insert with IS_PENDING=1 so the file is
 * reserved but invisible, stream bytes via ContentResolver's
 * OutputStream, then update IS_PENDING=0 to publish. No permission
 * needed for files the app itself inserts.
 *
 * API 28 (P): legacy `Environment.getExternalStoragePublicDirectory`
 * write under `Pictures/Pilgrim/`. Requires
 * `WRITE_EXTERNAL_STORAGE` runtime grant; the Composable checks +
 * requests before calling into this saver and passes the outcome as
 * a precondition (this object returns [SaveResult.NeedsPermission]
 * if called ungranted, so the UI can bounce back to the request
 * flow rather than silently failing).
 *
 * All paths run on [Dispatchers.IO] — these are content-resolver /
 * filesystem calls. The underlying `Bitmap.compress` call is CPU-
 * bound but in practice dominated by write-latency on external
 * storage, so a single IO hop covers both.
 */
internal object EtegamiGallerySaver {

    private const val TAG = "EtegamiGallerySaver"

    /** Album name under `Pictures/` in the gallery. */
    private const val ALBUM = "Pilgrim"

    sealed interface SaveResult {
        data class Success(val uri: Uri) : SaveResult
        /** Only returned on API 28 when WRITE_EXTERNAL_STORAGE isn't granted. */
        object NeedsPermission : SaveResult
        data class Failed(val cause: Throwable) : SaveResult
    }

    suspend fun saveToGallery(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): SaveResult = withContext(Dispatchers.IO) {
        require(filename.endsWith(".png")) { "filename must end with .png" }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(bitmap, filename, context)
            } else {
                saveViaLegacyExternal(bitmap, filename, context)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "saveToGallery failed", t)
            SaveResult.Failed(t)
        }
    }

    private fun saveViaMediaStore(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): SaveResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/$ALBUM",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return SaveResult.Failed(IllegalStateException("insert returned null"))
        try {
            // Use openFileDescriptor instead of openOutputStream so we
            // can explicitly flush + fsync before flipping IS_PENDING.
            // Otherwise the MediaProvider transaction that publishes
            // the record can race the kernel pagecache writeback and
            // the user sees a partially-written / zero-padded image
            // in Photos — same failure mode the legacy path avoids
            // via `os.flush()` + `os.fd.sync()`.
            resolver.openFileDescriptor(uri, "w")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("compress returned false")
                    }
                    out.flush()
                    out.fd.sync()
                }
            } ?: error("openFileDescriptor returned null")
            val clear = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            // resolver.update returns the number of rows affected.
            // If 0, the record wasn't published — the image is on
            // disk but stays IS_PENDING=1 forever and is invisible
            // to Photos. User would see a "Saved" snackbar but no
            // file in their gallery. Rollback + fail rather than
            // produce a phantom record.
            val updated = resolver.update(uri, clear, null, null)
            if (updated == 0) {
                error("IS_PENDING clear updated 0 rows — record unpublished")
            }
            return SaveResult.Success(uri)
        } catch (t: Throwable) {
            // Rollback the pending insert so it doesn't linger as a
            // ghost record in the user's Photos.
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyExternal(
        bitmap: Bitmap,
        filename: String,
        context: Context,
    ): SaveResult {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return SaveResult.NeedsPermission
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            ALBUM,
        ).apply { mkdirs() }
        val out = File(dir, filename)
        FileOutputStream(out).use { os ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)) {
                out.delete()
                error("compress returned false")
            }
            os.flush()
            os.fd.sync()
        }
        // Notify MediaScanner so the file appears in Photos. Using
        // `MediaScannerConnection.scanFile` is the canonical API on
        // pre-Q devices — more reliable than a `resolver.insert`
        // with `_DATA`, which historically failed to trigger the
        // scanner on a handful of OEM devices. Fire-and-forget with
        // a null callback; worst case the user waits a few seconds
        // for the gallery's own periodic scan to pick it up.
        MediaScannerConnection.scanFile(
            context,
            arrayOf(out.absolutePath),
            arrayOf("image/png"),
            null,
        )
        return SaveResult.Success(Uri.fromFile(out))
    }
}
