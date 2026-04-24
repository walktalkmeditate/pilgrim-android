// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Stage 7-D: pure builder for the etegami share chooser intent.
 *
 * `ClipData.newRawUri` is MANDATORY on all APIs — without it, modern
 * receiving apps (Google Drive, Gmail) fail to render the image
 * preview in the chooser. `EXTRA_STREAM` alone is not sufficient.
 *
 * The [buildFromFile] entry point resolves the file to a
 * FileProvider content URI; [buildFromUri] takes a pre-resolved URI
 * directly. The split keeps the intent-plumbing testable without
 * dragging FileProvider's SimplePathStrategy into the test path —
 * Robolectric + macOS's `/var → /private/var` symlink causes
 * FileProvider's canonical-vs-absolute path comparison to fail
 * matching any registered root (FileProvider canonicalizes the file
 * but not the roots). Production (real devices) has no symlink and
 * works correctly.
 */
internal object EtegamiShareIntentFactory {

    const val AUTHORITY_SUFFIX = ".fileprovider"

    /**
     * Build a chooser [Intent] ready for [Context.startActivity],
     * resolving [pngFile] to a FileProvider content URI.
     */
    fun buildFromFile(
        context: Context,
        pngFile: File,
        chooserTitle: CharSequence,
    ): Intent {
        val authority = context.packageName + AUTHORITY_SUFFIX
        val uri: Uri = FileProvider.getUriForFile(context, authority, pngFile)
        return buildFromUri(uri, pngFile.name, chooserTitle)
    }

    /**
     * Build the chooser [Intent] from an already-resolved content
     * URI. Pure function; no Android resolver dependencies. All
     * intent plumbing happens here (action, MIME, EXTRA_STREAM,
     * ClipData, FLAG_GRANT_READ_URI_PERMISSION, chooser wrap).
     */
    fun buildFromUri(
        uri: Uri,
        displayName: String,
        chooserTitle: CharSequence,
    ): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
