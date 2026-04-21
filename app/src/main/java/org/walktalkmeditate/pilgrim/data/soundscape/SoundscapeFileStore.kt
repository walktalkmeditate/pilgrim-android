// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset

/**
 * Filesystem source-of-truth for soundscape asset downloads.
 * Matches iOS's `Audio/soundscape/<id>.aac` flat layout. Size
 * verification (not just existence) catches truncated downloads
 * without needing a hash.
 *
 * [invalidations] broadcasts when [delete] completes so observers
 * (e.g. `SoundscapeCatalogRepository`) can re-derive state. Same
 * shape as `VoiceGuideFileStore` from Stage 5-D.
 */
@Singleton
class SoundscapeFileStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File by lazy {
        File(context.filesDir, SOUNDSCAPE_DIR).also { it.mkdirs() }
    }

    private val _invalidations = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    /**
     * Absolute file for this soundscape asset. Creates the parent
     * directory on call.
     */
    fun fileFor(asset: AudioAsset): File {
        val file = File(root, "${asset.id}.aac")
        file.parentFile?.mkdirs()
        return file
    }

    /** True iff the file exists AND its length matches the manifest. */
    fun isAvailable(asset: AudioAsset): Boolean {
        val f = fileFor(asset)
        return f.exists() && f.length() == asset.fileSizeBytes
    }

    /**
     * Delete the asset's file. Suspending because `File.delete()`
     * is a blocking syscall — callers are typically
     * `viewModelScope.launch { ... }` which defaults to Main
     * (Stage 5-D lesson). Hop to IO inside.
     */
    suspend fun delete(asset: AudioAsset) {
        withContext(Dispatchers.IO) {
            fileFor(asset).delete()
        }
        _invalidations.tryEmit(Unit)
    }

    private companion object {
        const val SOUNDSCAPE_DIR = "audio/soundscape"
    }
}
