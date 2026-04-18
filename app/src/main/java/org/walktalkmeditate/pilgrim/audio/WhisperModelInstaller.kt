// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the bundled whisper model from APK assets into filesDir on first
 * call. whisper.cpp's whisper_init_from_file needs a real filesystem
 * path — APK asset entries can't be read directly.
 */
@Singleton
class WhisperModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun installIfNeeded(): Path {
        val target = context.filesDir.toPath().resolve("$DIR/$FILE")
        if (Files.exists(target) && Files.size(target) > 0) return target
        Files.createDirectories(target.parent)
        context.assets.open("$ASSET_DIR/$FILE").use { input ->
            Files.newOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    private companion object {
        const val ASSET_DIR = "models"
        const val DIR = "whisper-model"
        const val FILE = "ggml-tiny.en.bin"
    }
}
