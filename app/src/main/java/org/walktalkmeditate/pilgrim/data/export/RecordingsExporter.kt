// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.export

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds a `.zip` of every regular file under [sourceDir]. Entry
 * names preserve the relative path from `sourceDir`, so a recordings
 * tree like `walks/<id>/<uuid>.wav` survives the round trip.
 *
 * Returns `null` when the source dir is empty (or absent); the caller
 * should surface a "no recordings yet" UI state rather than producing
 * an empty ZIP.
 *
 * Stage 10-G: `targetDir` should be `cacheDir/recordings_export/`
 * (declared in `res/xml/file_paths.xml`); files there are
 * FileProvider-shareable and auto-cleaned by the OS on storage
 * pressure.
 */
object RecordingsExporter {

    fun export(sourceDir: File, targetDir: File, now: Instant = Instant.now()): File? {
        if (!sourceDir.exists() || !sourceDir.isDirectory) return null
        val files = sourceDir.walkTopDown()
            .filter { it.isFile }
            .toList()
        if (files.isEmpty()) return null

        targetDir.mkdirs()
        val timeCode = BackupTimeCode.format(now)
        val out = File(targetDir, "pilgrim-recordings-$timeCode.zip")

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zip ->
                for (file in files) {
                    val entryName = file.relativeTo(sourceDir).invariantSeparatorsPath
                    val entry = ZipEntry(entryName)
                    zip.putNextEntry(entry)
                    FileInputStream(file).use { input ->
                        input.copyTo(zip, bufferSize = COPY_BUFFER_BYTES)
                    }
                    zip.closeEntry()
                }
            }
        } catch (t: Throwable) {
            // Source file may have been swept (OrphanRecordingSweeper) or
            // gone unreadable mid-loop. ZipOutputStream.use already closed
            // and flushed a half-written archive — delete it so the user
            // doesn't share or accidentally cache a corrupt zip.
            out.delete()
            throw t
        }
        return out
    }

    private const val COPY_BUFFER_BYTES = 8 * 1024
}
