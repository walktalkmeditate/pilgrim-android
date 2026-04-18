// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.content.res.AssetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies the bundled whisper model from APK assets into filesDir on first
 * call. whisper.cpp's whisper_init_from_file needs a real filesystem
 * path — APK asset entries can't be read directly.
 *
 * The "is install needed" probe compares the bundled asset's uncompressed
 * length against the on-disk size: a mid-copy process kill would leave a
 * shorter file, and we'd re-install on next launch instead of letting
 * whisper.cpp choke on a corrupt model. The copy itself writes to a temp
 * file and atomically renames so a partial file is never visible.
 */
@Singleton
class WhisperModelInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val installLock = Any()

    fun installIfNeeded(): Path = synchronized(installLock) {
        val target = context.filesDir.toPath().resolve("$DIR/$FILE")
        val expectedSize = expectedAssetSize()
        if (Files.exists(target) && Files.size(target) == expectedSize) return target
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, "ggml-tiny.en", ".bin.part")
        try {
            context.assets.open("$ASSET_DIR/$FILE").use { input ->
                Files.newOutputStream(temp).use { output -> input.copyTo(output) }
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(temp) }
            throw t
        }
        target
    }

    private fun expectedAssetSize(): Long {
        // openFd only works for uncompressed assets; .bin is exempted
        // from compression via aaptOptions.noCompress in app/build.gradle.kts,
        // so this returns a real length. Falls back to streaming-count
        // if compression rules ever change.
        val size = runCatching {
            context.assets.openFd("$ASSET_DIR/$FILE").use { it.length }
        }.getOrNull()?.takeIf { it > 0 }
            ?: context.assets.open("$ASSET_DIR/$FILE", AssetManager.ACCESS_STREAMING).use { input ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf); if (n < 0) break; total += n
                }
                total
            }
        // A zero-byte expected size would let any zero-byte target file
        // (e.g., a stale partial install with the temp deleted but the
        // target rename incomplete) be accepted as "installed" — the
        // model would then fail to load. Refuse early so the caller
        // sees a clear failure.
        require(size > 0L) { "bundled whisper model asset is empty" }
        return size
    }

    private companion object {
        const val ASSET_DIR = "models"
        const val DIR = "whisper-model"
        const val FILE = "ggml-tiny.en.bin"
    }
}
