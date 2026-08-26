// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.threads

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * One-file-per-recording store for [TranscriptContext], backed by
 * `filesDir/transcript_contexts/<uuid>.json.gz`.
 *
 * File naming: plain `.gz`, NOT the `.gzip` rename [WordNetLexicon]'s
 * bundled assets use. That rename exists ONLY because AGP's asset-merge
 * step auto-decompresses a `.gz`-suffixed file under `src/main/assets/`
 * and strips the suffix before packaging — a build-time step that never
 * touches `filesDir` runtime writes. Files this store creates at runtime
 * are never asset-merged, so the ordinary `.gz` extension is correct
 * here; do not cargo-cult the asset-side workaround.
 *
 * Concurrency (Open question 4 in the parity spec): iOS serializes every
 * MUTATION through a private serial queue but leaves reads unserialized,
 * relying on `.atomic` writes to prevent torn reads. This port mirrors
 * that asymmetry rather than wrapping the whole store in one Mutex (which
 * would make reads block on writes — a real concurrency change iOS never
 * has): mutations serialize through [mutex]; writes land via a temp-file
 * + atomic rename so a concurrent reader never observes a partial file;
 * reads ([readRaw], [read], [loadAll], [loadAllIncludingStaleVersions],
 * [allUuids], [hasContext], [hasCurrentContext]) touch the filesystem
 * directly with no lock.
 *
 * Unlike iOS (which requires callers to already be off-Main), every
 * method here hops to [Dispatchers.IO] itself, matching this project's
 * own established convention (Stage 2-E lesson) rather than trusting
 * every future caller to remember the hop.
 *
 * The backing directory is resolved lazily and created on first real
 * write, never in the constructor (DAT-58 — Hilt may construct this
 * `@Singleton` eagerly on any thread; a unit test that never touches the
 * store must never pay a filesystem cost).
 *
 * Tombstones are in-memory only (BEH-15), matching iOS: they protect
 * against a same-process, in-flight analysis resurrecting a context file
 * after a delete queued the tombstone first. Analysis stays on
 * in-process coroutines in this port (never WorkManager), so the
 * process-bounded guarantee matches iOS's `Task.detached` analysis race
 * exactly (Open question 12) — persisting tombstones would only be
 * required if analysis ever moved to a process-surviving substrate.
 */
@Singleton
open class TranscriptContextStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val mutex = Mutex()
    private val tombstones = mutableSetOf<String>()

    private val _changeCount = MutableStateFlow(0L)
    val changeCount: StateFlow<Long> = _changeCount.asStateFlow()

    private val directory: File by lazy { File(context.filesDir, DIR_NAME) }

    /**
     * Bare load: decode whatever is on disk for [uuid], no hash or
     * version gating. Backs [hasContext] / [hasCurrentContext], and is
     * itself public for callers that need to inspect a possibly-stale
     * context directly.
     */
    suspend fun readRaw(uuid: String): TranscriptContext? = withContext(Dispatchers.IO) {
        decodeFile(fileFor(uuid))
    }

    /**
     * Cache-hit read: [uuid]'s stored context, or null unless BOTH its
     * [TranscriptContext.transcriptHash] equals [transcriptHash] AND its
     * [TranscriptContext.analysisVersion] is the current
     * [TranscriptContext.ANALYSIS_VERSION] (DAT-3 — iOS `context(for:matching:)`).
     * A hash-only check would silently serve stale-schema derived data as
     * current.
     */
    suspend fun read(uuid: String, transcriptHash: String): TranscriptContext? =
        withContext(Dispatchers.IO) {
            decodeFile(fileFor(uuid))?.takeIf {
                it.transcriptHash == transcriptHash && it.analysisVersion == TranscriptContext.ANALYSIS_VERSION
            }
        }

    /** Bare file existence — does NOT decode or check version (BEH-19). */
    suspend fun hasContext(uuid: String): Boolean = withContext(Dispatchers.IO) {
        fileFor(uuid).isFile
    }

    /** Existence AND current [TranscriptContext.analysisVersion] (BEH-19). */
    suspend fun hasCurrentContext(uuid: String): Boolean = withContext(Dispatchers.IO) {
        decodeFile(fileFor(uuid))?.analysisVersion == TranscriptContext.ANALYSIS_VERSION
    }

    /**
     * Every stored context at the CURRENT [TranscriptContext.ANALYSIS_VERSION],
     * sorted by [TranscriptContext.uuid] ascending for determinism
     * (DAT-26). The one bulk read consumers should memoize above (U7).
     */
    suspend fun loadAll(): List<TranscriptContext> =
        loadAllIncludingStaleVersions().filter { it.analysisVersion == TranscriptContext.ANALYSIS_VERSION }

    /**
     * Every stored context regardless of [TranscriptContext.analysisVersion] —
     * exists exclusively for the stale-orphan sweep's visibility (DAT-26).
     */
    suspend fun loadAllIncludingStaleVersions(): List<TranscriptContext> = withContext(Dispatchers.IO) {
        contextFiles().mapNotNull { decodeFile(it) }.sortedBy { it.uuid }
    }

    /**
     * Every uuid this store has a file for, derived from FILENAMES (not
     * decoded contents — mirrors [deleteAll]'s own derivation, so a
     * corrupt/undecodable file is still accounted for). A directory that
     * has never been created (fresh install, nothing ever saved) is a
     * legitimate empty world and returns `emptyList()`. Returns `null`
     * only when the directory EXISTS but can't be listed (permissions, a
     * plain file occupying the path, an I/O error) — a genuine read
     * failure, never conflated with "nothing saved yet."
     */
    suspend fun allUuids(): List<String>? = withContext(Dispatchers.IO) {
        val dir = directory
        if (!dir.exists()) return@withContext emptyList()
        val files = dir.listFiles() ?: return@withContext null
        files.filter { it.name.endsWith(FILE_SUFFIX) }.map { it.name.removeSuffix(FILE_SUFFIX) }
    }

    /**
     * The sole write path. Returns `true` when the context is accounted
     * for — either genuinely written to disk, or correctly blocked by an
     * existing tombstone (BEH-17) — `false` only for a genuine
     * encode/write failure. A tombstone-blocked call does NOT bump
     * [changeCount] (no state actually changed); a real write does.
     */
    suspend fun save(context: TranscriptContext): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (context.uuid in tombstones) return@withLock true
            val wrote = writeAtomically(context)
            if (wrote) bumpChangeCount()
            wrote
        }
    }

    suspend fun delete(uuid: String) = delete(listOf(uuid))

    /**
     * Real recording deletion: tombstones AND removes the file(s), ONE
     * [changeCount] bump per CALL regardless of batch size (BEH-18) — a
     * future dossier-builder memo's arithmetic assumes this.
     */
    suspend fun delete(uuids: List<String>) {
        if (uuids.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                for (uuid in uuids) {
                    tombstones += uuid
                    fileFor(uuid).delete()
                }
                bumpChangeCount()
            }
        }
    }

    /**
     * Tombstones WITHOUT removing files — paired with a later [deleteAll]
     * sweep (BEH-20). Used ahead of a bulk wipe so a same-process,
     * in-flight analysis queued before the wipe cannot resurrect a
     * context file after it.
     */
    suspend fun insertTombstones(uuids: List<String>) {
        if (uuids.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                tombstones += uuids
                bumpChangeCount()
            }
        }
    }

    /** `open` so tests can spy on calls (mirrors [org.walktalkmeditate.pilgrim.data.WalkRepository]'s
     * own `open` methods used the same way). */
    open suspend fun clearTombstones(uuids: List<String>) {
        if (uuids.isEmpty()) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                tombstones -= uuids.toSet()
                bumpChangeCount()
            }
        }
    }

    /**
     * Removes a stored context WITHOUT tombstoning — for edits made while
     * the feature is off, where the old analysis must not linger stale. A
     * tombstone here would permanently block a future backfill save for
     * this recording: a blocked save reports "accounted for" while
     * writing nothing, so the recording would never re-analyze once the
     * feature is re-enabled (BEH-20).
     */
    suspend fun removeContext(uuid: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            fileFor(uuid).delete()
            bumpChangeCount()
        }
    }

    /**
     * Tombstones every uuid recovered from FILENAMES currently on disk (a
     * corrupt/undecodable file still gets tombstoned — DAT-25), then
     * removes and recreates the directory. ONE [changeCount] bump.
     */
    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val dir = directory
            val names = (dir.listFiles { f -> f.name.endsWith(FILE_SUFFIX) } ?: emptyArray())
                .map { it.name.removeSuffix(FILE_SUFFIX) }
            tombstones += names
            dir.deleteRecursively()
            dir.mkdirs()
            bumpChangeCount()
        }
    }

    private fun bumpChangeCount() {
        _changeCount.value += 1
    }

    private fun fileFor(uuid: String): File = File(directory, "$uuid$FILE_SUFFIX")

    private fun contextFiles(): List<File> =
        (directory.listFiles { f -> f.name.endsWith(FILE_SUFFIX) } ?: emptyArray()).toList()

    private fun decodeFile(file: File): TranscriptContext? {
        if (!file.isFile) return null
        return try {
            val text = GZIPInputStream(file.inputStream()).use { it.bufferedReader(Charsets.UTF_8).readText() }
            json.decodeFromString(TranscriptContext.serializer(), text)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "unreadable/corrupt context file ${file.name}; treating as absent", t)
            null
        }
    }

    /** Caller already holds [mutex]. */
    private fun writeAtomically(context: TranscriptContext): Boolean {
        val dir = directory
        dir.mkdirs()
        val target = fileFor(context.uuid)
        val temp = File(dir, "${context.uuid}$TEMP_SUFFIX")
        return try {
            val text = json.encodeToString(TranscriptContext.serializer(), context)
            GZIPOutputStream(temp.outputStream()).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            moveAtomically(temp.toPath(), target.toPath())
            true
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "failed to write context for ${context.uuid}", t)
            temp.delete()
            false
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (unsupported: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val TAG = "TranscriptContextStore"
        const val DIR_NAME = "transcript_contexts"
        const val FILE_SUFFIX = ".json.gz"
        const val TEMP_SUFFIX = ".json.gz.tmp"
    }
}
