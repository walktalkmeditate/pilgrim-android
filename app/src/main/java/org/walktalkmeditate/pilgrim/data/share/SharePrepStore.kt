// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.share

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.ShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/** Per-recording state of the interactive-share transcode prep pipeline (Decision 3). */
@Immutable
sealed interface PrepState {
    @Immutable
    data object Preparing : PrepState

    @Immutable
    data class Ready(val sizeBytes: Long) : PrepState

    @Immutable
    data object Failed : PrepState
}

/**
 * Per-walk coordinator for the WAV -> AAC-LC transcode prep pipeline
 * (Android-original — see U4 of
 * `docs/plans/2026-08-14-001-feat-walk-with-me-interactive-share-plan.md`).
 * Encodes included recordings sequentially via [ShareAudioTranscoder],
 * caches artifacts at [artifactFile] (Decision 2: one function for
 * writes, reads, and deletes), and exposes their state so a ViewModel
 * can gate the Share button and render per-row "preparing..." copy.
 *
 * This unit ships the cancellation/cleanup *mechanisms* — [cancelRecording],
 * [cancelAndCleanupWalk], and [sweepOrphans] — a later unit wires the
 * actual triggers (Interactive toggle-off, exclusion, screen exit).
 *
 * All I/O is dispatched to [Dispatchers.IO]; callers may be on Main.
 */
@Singleton
class SharePrepStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcoder: ShareAudioTranscoder,
    private val fileSystem: VoiceRecordingFileSystem,
) {
    /**
     * `walkUuid -> recordingUuid -> PrepState`. Nested rather than a
     * flat map keyed by recording UUID alone so per-walk cleanup can
     * enumerate exactly the recordings it owns without a second,
     * independently-maintained index that could drift out of sync
     * (this map IS the index).
     */
    private val _state = MutableStateFlow<Map<String, Map<String, PrepState>>>(emptyMap())
    val state: StateFlow<Map<String, Map<String, PrepState>>> = _state.asStateFlow()

    private val activeJobs = ConcurrentHashMap<Pair<String, String>, Job>()

    /** `cacheDir/share-prep/<walkUuid>/<recordingUuid>.m4a` — the ONE function for writes, reads, and deletes. */
    fun artifactFile(walkUuid: String, recordingUuid: String): File =
        File(walkPrepDir(walkUuid), "$recordingUuid.m4a")

    /** Sequential over [recordings]; each item reuses an existing Ready artifact or encodes a fresh one. */
    suspend fun prepare(walkUuid: String, recordings: List<VoiceRecording>) {
        for (recording in recordings) {
            prepareOne(walkUuid, recording)
        }
    }

    /** Reuse-or-re-encode: returns the artifact file, encoding it first if it's missing or was evicted. */
    suspend fun ensureArtifact(walkUuid: String, recording: VoiceRecording): File? {
        prepareOne(walkUuid, recording)
        val artifact = artifactFile(walkUuid, recording.uuid)
        return if (withContext(Dispatchers.IO) { artifact.exists() }) artifact else null
    }

    /**
     * Stops preparing [recordingUuid] (if in flight) and deletes its
     * artifact (if any) — used both for "exclusion mid-encode cancels
     * that file only" and for excluding a recording that already has a
     * Ready artifact.
     */
    suspend fun cancelRecording(walkUuid: String, recordingUuid: String) = withContext(Dispatchers.IO) {
        activeJobs.remove(walkUuid to recordingUuid)?.cancelAndJoin()
        clearState(walkUuid, recordingUuid)
        val root = sharePrepRootPath()
        safeDeleteArtifact(artifactFile(walkUuid, recordingUuid).toPath(), root)
        Unit
    }

    /** Cancels every in-flight job for [walkUuid], clears its state entries, and removes its whole prep dir. */
    suspend fun cancelAndCleanupWalk(walkUuid: String) = withContext(Dispatchers.IO) {
        val recordingUuids = _state.value[walkUuid]?.keys?.toList().orEmpty()
        for (recordingUuid in recordingUuids) {
            activeJobs.remove(walkUuid to recordingUuid)?.cancelAndJoin()
        }
        _state.update { it - walkUuid }
        deleteWalkPrepDirIfSafe(walkPrepDir(walkUuid).toPath(), sharePrepRootPath())
        Unit
    }

    /**
     * Removes `share-prep/` subdirectories whose walk UUID is not in
     * [keepWalkUuids]. Mirrors [org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper]'s
     * guard discipline: canonical-path containment, a UUID-shaped
     * directory name, and (per file, inside [deleteWalkPrepDirIfSafe])
     * an extension + regular-file check before any delete.
     */
    suspend fun sweepOrphans(keepWalkUuids: Set<String>): Int = withContext(Dispatchers.IO) {
        val root = sharePrepRoot()
        if (!root.isDirectory) return@withContext 0
        val rootPath = sharePrepRootPath()
        val entries = try {
            Files.list(root.toPath()).use { stream -> stream.collect(Collectors.toList()) }
        } catch (t: Throwable) {
            Log.w(TAG, "sweepOrphans: listing failed", t)
            return@withContext 0
        }
        var removed = 0
        for (dir in entries) {
            val canonical = dir.toAbsolutePath().normalize()
            if (!canonical.startsWith(rootPath)) {
                Log.w(TAG, "sweepOrphans: refusing dir outside share-prep root: $canonical")
                continue
            }
            if (!Files.isDirectory(canonical)) continue
            val name = canonical.fileName.toString()
            if (!name.matches(WALK_UUID_REGEX)) {
                Log.w(TAG, "sweepOrphans: skipping non-uuid dir: $name")
                continue
            }
            if (name in keepWalkUuids) continue
            if (deleteWalkPrepDirIfSafe(canonical, rootPath)) removed++
        }
        removed
    }

    private suspend fun prepareOne(walkUuid: String, recording: VoiceRecording) {
        val key = walkUuid to recording.uuid
        val artifact = artifactFile(walkUuid, recording.uuid)
        if (withContext(Dispatchers.IO) { artifact.exists() }) {
            setState(walkUuid, recording.uuid, PrepState.Ready(artifact.length()))
            return
        }
        if (activeJobs.containsKey(key)) return // already in flight (e.g. a concurrent ensureArtifact call)

        setState(walkUuid, recording.uuid, PrepState.Preparing)
        coroutineScope {
            val job = launch(Dispatchers.IO) { runEncode(walkUuid, recording, artifact) }
            activeJobs[key] = job
            try {
                job.join()
            } finally {
                activeJobs.remove(key)
            }
        }
    }

    private suspend fun runEncode(walkUuid: String, recording: VoiceRecording, artifact: File) {
        try {
            val wavFile = fileSystem.absolutePath(recording.fileRelativePath)
            val result = transcoder.transcode(wavFile, artifact)
            result.fold(
                onSuccess = { bytes -> setState(walkUuid, recording.uuid, PrepState.Ready(bytes)) },
                onFailure = { setState(walkUuid, recording.uuid, PrepState.Failed) },
            )
        } catch (ce: CancellationException) {
            clearState(walkUuid, recording.uuid)
            throw ce
        }
    }

    private fun setState(walkUuid: String, recordingUuid: String, prepState: PrepState) {
        _state.update { current ->
            val walkEntries = current[walkUuid].orEmpty() + (recordingUuid to prepState)
            current + (walkUuid to walkEntries)
        }
    }

    private fun clearState(walkUuid: String, recordingUuid: String) {
        _state.update { current ->
            val walkEntries = current[walkUuid]?.minus(recordingUuid) ?: return@update current
            if (walkEntries.isEmpty()) current - walkUuid else current + (walkUuid to walkEntries)
        }
    }

    private fun sharePrepRoot(): File = File(context.cacheDir, SHARE_PREP_DIR)

    private fun sharePrepRootPath(): Path = sharePrepRoot().toPath().toAbsolutePath().normalize()

    private fun walkPrepDir(walkUuid: String): File = File(sharePrepRoot(), walkUuid)

    /** Deletes every `.m4a` file inside [dir] via [safeDeleteArtifact], then the (hopefully empty) directory. */
    private fun deleteWalkPrepDirIfSafe(dir: Path, root: Path): Boolean {
        return try {
            val canonical = dir.toAbsolutePath().normalize()
            if (!canonical.startsWith(root)) {
                Log.w(TAG, "refusing to delete walk prep dir outside share-prep root: $canonical")
                return false
            }
            if (!Files.isDirectory(canonical)) return true // nothing to clean up — already the desired end state
            Files.list(canonical).use { stream -> stream.collect(Collectors.toList()) }
                .forEach { file -> if (Files.isRegularFile(file)) safeDeleteArtifact(file, root) }
            // Files.delete throws if the dir is non-empty (unexpected content survived
            // the per-file guard above) — caught below and left for investigation,
            // mirroring OrphanRecordingSweeper's deleteOrphanWalkDir.
            Files.delete(canonical)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "walk prep dir delete failed: $dir", t)
            false
        }
    }

    private fun safeDeleteArtifact(file: Path, root: Path): Boolean {
        return try {
            val candidate = file.toAbsolutePath().normalize()
            if (!candidate.startsWith(root)) {
                Log.w(TAG, "refusing to delete artifact outside share-prep root: $candidate")
                return false
            }
            val ext = candidate.fileName.toString().substringAfterLast('.').lowercase()
            if (ext != "m4a") {
                Log.w(TAG, "refusing to delete non-m4a file: $candidate")
                return false
            }
            if (!Files.isRegularFile(candidate)) {
                Log.w(TAG, "refusing to delete non-regular file: $candidate")
                return false
            }
            Files.delete(candidate)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "artifact delete failed: $file", t)
            false
        }
    }

    private companion object {
        const val TAG = "SharePrepStore"
        const val SHARE_PREP_DIR = "share-prep"
        val WALK_UUID_REGEX =
            Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    }
}
