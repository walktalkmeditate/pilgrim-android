// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Canonical path resolver for voice recording files. Stage 10-D introduced this
 * helper to centralize what was previously scattered across `ExoPlayerVoicePlaybackController`,
 * `WhisperEngine`, and `TranscriptionRunner`. Per Stage 5-D memory: delete operations
 * MUST drive their path computation through the SAME function the write path used.
 *
 * Callers pass the entity's `fileRelativePath` (e.g., `"recordings/<walkUuid>/<recUuid>.wav"`).
 */
@Singleton
class VoiceRecordingFileSystem @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun absolutePath(relativePath: String): File =
        File(context.filesDir, relativePath)

    fun fileExists(relativePath: String): Boolean =
        absolutePath(relativePath).exists()

    fun fileSizeBytes(relativePath: String): Long {
        val f = absolutePath(relativePath)
        return if (f.exists()) f.length() else 0L
    }

    suspend fun deleteFile(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        absolutePath(relativePath).delete()
    }
}
