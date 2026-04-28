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
     * Absolute file for this soundscape asset. `root` is lazy-
     * initialized with `mkdirs()` so the parent directory is
     * guaranteed to exist on first access; no per-call `mkdirs`.
     * Keeping this pure read-only lets orchestrator-side eligibility
     * checks run on any dispatcher without StrictMode complaints.
     */
    fun fileFor(asset: AudioAsset): File = File(root, "${asset.id}.aac")

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

    /**
     * Sum of every cached soundscape file's length, in bytes. Iterates
     * the on-disk directory rather than the manifest so partial /
     * orphaned downloads (asset removed from manifest but file still
     * present on disk) are still surfaced — matches iOS's
     * `AudioFileStore.totalDiskUsage()`. Suspends because
     * `File.length()` is a blocking syscall on every entry.
     */
    suspend fun totalSize(): Long = withContext(Dispatchers.IO) {
        val files = root.listFiles() ?: return@withContext 0L
        files.sumOf { if (it.isFile) it.length() else 0L }
    }

    /**
     * Delete every file in the soundscape directory. Counterpart to
     * iOS's `AudioFileStore.clearAll()` — used by SoundSettingsScreen's
     * "Clear all downloads" action. Emits one invalidation after the
     * sweep so observers re-derive availability state once.
     */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            val files = root.listFiles() ?: return@withContext
            files.forEach { if (it.isFile) it.delete() }
        }
        _invalidations.tryEmit(Unit)
    }

    private companion object {
        const val SOUNDSCAPE_DIR = "audio/soundscape"
    }
}
