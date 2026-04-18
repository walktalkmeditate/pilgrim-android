// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.io.path.name
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Reconciles WAV files under `recordings/<walkUuid>/` on disk with
 * `voice_recordings` rows in Room. Triggered on
 * [org.walktalkmeditate.pilgrim.ui.walk.WalkSummaryViewModel] init for
 * the displayed walk and from a daily WorkManager job for the global
 * case.
 *
 * Four cases are handled (best-effort, log-and-continue on failure):
 *  - (a) WAV file on disk with no matching Room row → delete file.
 *  - (b) Row whose `fileRelativePath` points to a missing WAV → delete row.
 *  - (c) Row with `transcription == null` AND WAV `dataSize == 0` →
 *        delete both row and file (mid-capture process kill).
 *  - (d) Row with `transcription == null` AND WAV `dataSize > 0` →
 *        re-enqueue Stage 2-D's transcription for the walk (handles
 *        the Stage 2-D `withTimeoutOrNull` late-INSERT scenario).
 *
 * Case (a) is the only destructive disk operation and is guarded by:
 * canonical-path check (must resolve under filesDir/recordings),
 * `.wav` extension, regular-file check.
 */
@Singleton
class OrphanRecordingSweeper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val transcriptionScheduler: TranscriptionScheduler,
) {
    private val recordingsRoot: Path
        get() = context.filesDir.toPath().resolve(RECORDINGS_DIR).toAbsolutePath().normalize()

    suspend fun sweep(walkId: Long): SweepResult {
        val walk = repository.getWalk(walkId) ?: return SweepResult()
        val rows = repository.voiceRecordingsFor(walkId)
        val walkDir = recordingsRoot.resolve(walk.uuid)

        var orphanFilesDeleted = 0
        var orphanRowsDeleted = 0
        var zombieRowsDeleted = 0
        var rescheduled = false

        // Case (a): files on disk with no matching row.
        val rowFilenames = rows.map { Paths.get(it.fileRelativePath).name }.toSet()
        if (Files.isDirectory(walkDir)) {
            try {
                val diskFiles = Files.list(walkDir).use { stream ->
                    stream.collect(Collectors.toList())
                }
                for (file in diskFiles) {
                    if (file.name in rowFilenames) continue
                    if (safeDeleteOrphanFile(file)) orphanFilesDeleted++
                }
            } catch (t: Throwable) {
                Log.w(TAG, "case (a) listing failed for walk $walkId", t)
            }
        }

        // Cases (b)/(c)/(d) per row.
        for (row in rows) {
            val absolute = context.filesDir.toPath().resolve(row.fileRelativePath)
            val exists = Files.exists(absolute)
            if (!exists) {
                if (safeDeleteRow(row)) orphanRowsDeleted++
                continue
            }
            if (row.transcription != null) continue
            val dataSize = wavDataSizeOrNull(absolute)
            if (dataSize == 0L) {
                if (safeDeleteZombie(row, absolute)) zombieRowsDeleted++
                continue
            }
            if (dataSize != null && dataSize > 0L) {
                rescheduled = true
            }
        }

        if (rescheduled) {
            try {
                transcriptionScheduler.scheduleForWalk(walkId)
            } catch (t: Throwable) {
                Log.w(TAG, "case (d) reschedule failed for walk $walkId", t)
            }
        }

        return SweepResult(
            orphanFilesDeleted = orphanFilesDeleted,
            orphanRowsDeleted = orphanRowsDeleted,
            zombieRowsDeleted = zombieRowsDeleted,
            rescheduledWalks = if (rescheduled) 1 else 0,
        )
    }

    suspend fun sweepAll(): SweepResult {
        val walks = try {
            repository.allWalks()
        } catch (t: Throwable) {
            Log.w(TAG, "sweepAll: allWalks() failed", t)
            return SweepResult()
        }
        var total = SweepResult()
        for (walk in walks) {
            total = total + sweep(walk.id)
        }
        return total
    }

    private fun safeDeleteOrphanFile(file: Path): Boolean {
        return try {
            val candidate = file.toAbsolutePath().normalize()
            if (!candidate.startsWith(recordingsRoot)) {
                Log.w(TAG, "refusing to delete file outside recordings root: $candidate")
                return false
            }
            if (candidate.name.lowercase().substringAfterLast('.') != "wav") {
                Log.w(TAG, "refusing to delete non-.wav file: $candidate")
                return false
            }
            if (!Files.isRegularFile(candidate)) {
                Log.w(TAG, "refusing to delete non-regular file: $candidate")
                return false
            }
            Files.delete(candidate)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "case (a) delete failed: $file", t)
            false
        }
    }

    private suspend fun safeDeleteRow(row: VoiceRecording): Boolean {
        return try {
            repository.deleteVoiceRecording(row)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "case (b) delete row ${row.id} failed", t)
            false
        }
    }

    private suspend fun safeDeleteZombie(row: VoiceRecording, file: Path): Boolean {
        return try {
            repository.deleteVoiceRecording(row)
            // Defensive: even if row delete succeeds, the file might
            // outlive it. Use the same canonical-path guard as case (a).
            // If the file delete fails the row is still gone — next
            // sweep will pick the file up as a case-(a) orphan — but
            // we count this zombie sweep only when BOTH row and file
            // are reconciled, so the metric reflects reality.
            val fileDeleted = safeDeleteOrphanFile(file)
            fileDeleted
        } catch (t: Throwable) {
            Log.w(TAG, "case (c) zombie delete row ${row.id} failed", t)
            false
        }
    }

    private fun wavDataSizeOrNull(file: Path): Long? = try {
        Files.newByteChannel(file).use { ch ->
            if (ch.size() < 44) return null
            ch.position(WAV_DATA_SIZE_OFFSET)
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            if (ch.read(buf) < 4) return null
            buf.rewind()
            buf.int.toLong() and 0xFFFFFFFFL
        }
    } catch (_: Throwable) { null }

    private companion object {
        const val TAG = "OrphanSweeper"
        const val RECORDINGS_DIR = "recordings"
        // Canonical 44-byte WAV header layout: the data subchunk size
        // is a uint32 little-endian at offset 40 (data chunk header is
        // 8 bytes: 4 for "data" + 4 for size).
        const val WAV_DATA_SIZE_OFFSET = 40L
    }
}

data class SweepResult(
    val orphanFilesDeleted: Int = 0,
    val orphanRowsDeleted: Int = 0,
    val zombieRowsDeleted: Int = 0,
    val rescheduledWalks: Int = 0,
) {
    operator fun plus(other: SweepResult) = SweepResult(
        orphanFilesDeleted = orphanFilesDeleted + other.orphanFilesDeleted,
        orphanRowsDeleted = orphanRowsDeleted + other.orphanRowsDeleted,
        zombieRowsDeleted = zombieRowsDeleted + other.zombieRowsDeleted,
        rescheduledWalks = rescheduledWalks + other.rescheduledWalks,
    )
}
