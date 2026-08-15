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
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 * Concurrency (U4 review fix): [prepareOne] is single-flight per
 * `(walkUuid, recordingUuid)` key. A concurrent [prepare] and/or
 * [ensureArtifact] call for the same key joins the SAME in-flight
 * [Deferred] rather than starting a duplicate encode or racing the
 * `exists()` check — the encode is only ever driven by whichever
 * caller's [ConcurrentHashMap.computeIfAbsent] wins the atomic insert.
 * This is safe only because [ShareAudioTranscoder] writes to a `.part`
 * sibling and renames into place atomically, so [File.exists] on the
 * final artifact path is true if and only if a complete encode
 * produced it — joiners that arrive after completion take the fast
 * `exists()` path instead of touching the map at all.
 *
 * [inFlight] encodes run on [scope], a store-owned
 * `SupervisorJob + Dispatchers.IO` scope (same shape as
 * [org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer]'s and
 * [org.walktalkmeditate.pilgrim.data.proximity.ProximityDetectionService]'s
 * self-owned scopes) rather than a scope structurally tied to whichever
 * caller's coroutine happened to start the encode — a `prepare()`
 * caller's own cancellation (e.g. navigating away) must not tear down
 * an encode a DIFFERENT concurrent caller is still joining.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** In-flight encodes, keyed by `(walkUuid, recordingUuid)`. See class doc for the single-flight-join contract. */
    private val inFlight = ConcurrentHashMap<Pair<String, String>, Deferred<PrepState>>()

    /** `cacheDir/share-prep/<walkUuid>/<recordingUuid>.m4a` — the ONE function for writes, reads, and deletes. */
    fun artifactFile(walkUuid: String, recordingUuid: String): File =
        File(walkPrepDir(walkUuid), "$recordingUuid.m4a")

    /** Sequential over [recordings]; each item reuses an existing Ready artifact or encodes a fresh one. */
    suspend fun prepare(walkUuid: String, recordings: List<VoiceRecording>) {
        for (recording in recordings) {
            prepareOne(walkUuid, recording)
        }
    }

    /**
     * Reuse-or-re-encode: returns the artifact file, encoding it first
     * if it's missing or was evicted. Joins an in-flight encode if one
     * is already running for this key and only returns non-null once
     * that encode reaches a terminal [PrepState.Ready] — never a file
     * whose encode is still in progress.
     */
    suspend fun ensureArtifact(walkUuid: String, recording: VoiceRecording): File? {
        val result = prepareOne(walkUuid, recording)
        return if (result is PrepState.Ready) artifactFile(walkUuid, recording.uuid) else null
    }

    /**
     * Stops preparing [recordingUuid] (if in flight) and deletes its
     * artifact (if any) — used both for "exclusion mid-encode cancels
     * that file only" and for excluding a recording that already has a
     * Ready artifact.
     */
    suspend fun cancelRecording(walkUuid: String, recordingUuid: String) = withContext(Dispatchers.IO) {
        inFlight.remove(walkUuid to recordingUuid)?.cancelAndJoin()
        clearState(walkUuid, recordingUuid)
        val root = sharePrepRootPath()
        safeDeleteArtifact(artifactFile(walkUuid, recordingUuid).toPath(), root)
        Unit
    }

    /** Cancels every in-flight encode for [walkUuid], clears its state entries, and removes its whole prep dir. */
    suspend fun cancelAndCleanupWalk(walkUuid: String) = withContext(Dispatchers.IO) {
        val recordingUuids = _state.value[walkUuid]?.keys?.toList().orEmpty()
        for (recordingUuid in recordingUuids) {
            inFlight.remove(walkUuid to recordingUuid)?.cancelAndJoin()
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
     * an extension + regular-file check before any delete. Also sweeps
     * stray `.part` temp files (an encode that never finished renaming
     * — e.g. process death mid-encode) via the same guard.
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

    /**
     * Single-flight per `(walkUuid, recording.uuid)`: the fast path
     * reuses an existing complete artifact; otherwise every caller for
     * the same key — whether the one that starts the encode or a
     * concurrent joiner — atomically shares ONE [Deferred] via
     * [ConcurrentHashMap.computeIfAbsent] and suspends on it until the
     * encode reaches a terminal [PrepState]. `exists()` is safe to
     * trust as the fast-path gate only because [ShareAudioTranscoder]
     * guarantees the final artifact path never exists mid-encode.
     *
     * A [CancellationException] surfacing from [Deferred.await] does
     * NOT necessarily mean THIS caller was cancelled — [cancelRecording]
     * cancels the shared deferred directly, which every joiner
     * (including an unrelated [prepare] loop over other recordings)
     * observes. [coroutineContext.ensureActive] re-throws only when
     * this specific caller's own coroutine was cancelled; otherwise the
     * cancellation is this recording's terminal outcome and callers
     * such as [prepare]'s loop continue to the next recording — mirrors
     * the pre-fix behavior where cancelling a tracked child [Job] never
     * propagated to `prepare()`'s parent scope.
     */
    private suspend fun prepareOne(walkUuid: String, recording: VoiceRecording): PrepState {
        val key = walkUuid to recording.uuid
        val artifact = artifactFile(walkUuid, recording.uuid)
        if (withContext(Dispatchers.IO) { artifact.exists() }) {
            val ready = PrepState.Ready(artifact.length())
            setState(walkUuid, recording.uuid, ready)
            return ready
        }

        val deferred = inFlight.computeIfAbsent(key) {
            setState(walkUuid, recording.uuid, PrepState.Preparing)
            scope.async { runEncode(walkUuid, recording, artifact) }
        }
        return try {
            deferred.await()
        } catch (ce: CancellationException) {
            coroutineContext.ensureActive()
            PrepState.Failed
        } finally {
            inFlight.remove(key, deferred)
        }
    }

    private suspend fun runEncode(walkUuid: String, recording: VoiceRecording, artifact: File): PrepState {
        return try {
            val wavFile = fileSystem.absolutePath(recording.fileRelativePath)
            val result = transcoder.transcode(wavFile, artifact)
            result.fold(
                onSuccess = { bytes -> PrepState.Ready(bytes).also { setState(walkUuid, recording.uuid, it) } },
                onFailure = { PrepState.Failed.also { setState(walkUuid, recording.uuid, it) } },
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

    /** Deletes every `.m4a`/`.m4a.part` file inside [dir] via [safeDeleteArtifact], then the (hopefully empty) directory. */
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
            if (ext !in ARTIFACT_EXTENSIONS) {
                Log.w(TAG, "refusing to delete non-artifact file: $candidate")
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

        /**
         * `m4a` for a finished artifact, `part` for
         * [ShareAudioTranscoder.partFileFor]'s in-progress temp sibling
         * (`<uuid>.m4a.part` — the trailing extension is `part`). Both
         * are safe for the orphan sweep / walk-dir cleanup to remove.
         */
        val ARTIFACT_EXTENSIONS = setOf("m4a", "part")
    }
}
