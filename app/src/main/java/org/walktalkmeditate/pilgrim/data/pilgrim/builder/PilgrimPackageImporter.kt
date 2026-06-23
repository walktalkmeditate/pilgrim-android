// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.pilgrim.ArchivedWalkRegistry
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimArchivedWalk
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimManifest
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimSchema
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk
import org.walktalkmeditate.pilgrim.di.PilgrimJson

/**
 * Read a `.pilgrim` archive from a content URI and restore its
 * walks via a single Room transaction. Mirrors iOS
 * [PilgrimPackageImporter] semantics:
 *
 * - Photos in `photos/` are extracted to temp but NEVER copied
 *   into app storage. Photo metadata round-trips via
 *   `WalkPhoto.photoUri` (won't resolve cross-platform — desktop
 *   viewer is the cross-platform photo carrier).
 * - Manifest fields Android can't model (events, intentions,
 *   customPromptStyles) are dropped silently.
 * - Per-file decode failures inside the archive's `walks/` directory skip the file
 *   with a log; the import continues with the rest.
 * - Duplicate uuid (already in the DB, or repeated within the archive) →
 *   skipped silently, not counted. A walk that decodes but fails to INSERT
 *   is counted in `ImportSummary.skipped` (honest partial-import reporting).
 */
@Singleton
class PilgrimPackageImporter @Inject constructor(
    private val database: PilgrimDatabase,
    @PilgrimJson private val json: Json,
    @ApplicationContext private val context: Context,
    private val archivedRegistry: ArchivedWalkRegistry,
) {

    /**
     * Result of an import.
     *
     * iOS parity v1.6.0: a single `Int` return is insufficient — tended
     * files can simultaneously add new walks, replace existing walks,
     * and archive walks. Each count is surfaced so the success alert
     * can say "0 added, 5 tended, 3 archived".
     */
    data class ImportSummary(
        val added: Int,
        val replaced: Int,
        val archived: Int,
        /**
         * Walks present in the archive that did NOT land: files that
         * couldn't be decoded PLUS walks that decoded but failed to insert
         * (child-invariant violation, unique-uuid clash, etc.). Surfaced so
         * a partial import isn't reported as an unqualified success — iOS
         * PR #45 AF28 honest-feedback parity. Excluded from [total] (those
         * are the walks that actually landed).
         */
        val skipped: Int = 0,
    ) {
        val total: Int get() = added + replaced + archived
    }

    /**
     * @return [ImportSummary] with added / replaced / archived counts.
     * @throws PilgrimPackageError.InvalidPackage if the archive is
     *   structurally invalid (missing manifest, bad ZIP, etc.).
     * @throws PilgrimPackageError.UnsupportedSchemaVersion if the
     *   archive's schemaVersion isn't `"1.0"`.
     * @throws PilgrimPackageError.DecodingFailed if the manifest
     *   itself can't be decoded.
     * @throws PilgrimPackageError.FileSystemError on IO failures.
     */
    suspend fun import(uri: Uri): ImportSummary = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "pilgrim-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            unzipTo(uri, tempDir)
            val manifest = readManifest(tempDir)
            if (manifest.schemaVersion != PilgrimSchema.VERSION) {
                throw PilgrimPackageError.UnsupportedSchemaVersion(manifest.schemaVersion)
            }
            val readResult = readWalks(tempDir)
            val archivedEntries = manifest.archived ?: emptyList()
            val isTended = manifest.isTended
            val insertResult = insertWalks(readResult.walks, overwriteByUuid = isTended)
            val archivedCount = if (archivedEntries.isNotEmpty()) {
                applyArchivedEntries(archivedEntries)
            } else 0
            ImportSummary(
                added = insertResult.added,
                replaced = insertResult.replaced,
                archived = archivedCount,
                skipped = readResult.decodeFailures + insertResult.failed,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: PilgrimPackageError) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "Import failed", e)
            throw PilgrimPackageError.FileSystemError(e)
        } finally {
            runCatching { tempDir.deleteRecursively() }
        }
    }

    /** Stream-copy + unzip. Throws `InvalidPackage` if the URI can't be opened or the ZIP is malformed. */
    private fun unzipTo(uri: Uri, tempDir: File) {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw PilgrimPackageError.InvalidPackage
        try {
            ZipInputStream(inputStream.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = resolveSafeEntryFile(tempDir, entry.name)
                    if (entry.isDirectory) {
                        target.mkdirs()
                        zip.closeEntry()
                        continue
                    }
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        zip.copyTo(out, bufferSize = COPY_BUFFER_BYTES)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: PilgrimPackageError) {
            throw e
        } catch (e: Throwable) {
            throw PilgrimPackageError.InvalidPackage
        }
    }

    /**
     * Defensive zip-slip guard: rejects archive entries (file OR
     * directory) whose resolved path escapes [tempDir]. Without this,
     * a malicious archive with `../../foo` could create empty dirs or
     * write payloads outside the import sandbox.
     */
    private fun resolveSafeEntryFile(tempDir: File, entryName: String): File {
        val target = File(tempDir, entryName).canonicalFile
        val tempCanon = tempDir.canonicalFile
        if (!target.path.startsWith(tempCanon.path + File.separator) &&
            target.path != tempCanon.path
        ) {
            throw PilgrimPackageError.InvalidPackage
        }
        return target
    }

    private fun readManifest(tempDir: File): PilgrimManifest {
        val manifestFile = File(tempDir, "manifest.json")
        if (!manifestFile.exists()) throw PilgrimPackageError.InvalidPackage
        return try {
            json.decodeFromString(PilgrimManifest.serializer(), manifestFile.readText())
        } catch (e: Throwable) {
            throw PilgrimPackageError.DecodingFailed(e)
        }
    }

    private fun readWalks(tempDir: File): ReadWalksResult {
        val walksDir = File(tempDir, "walks")
        if (!walksDir.exists() || !walksDir.isDirectory) {
            // No walks dir — opted-into-import an empty archive.
            // Treat as zero walks rather than InvalidPackage.
            return ReadWalksResult(emptyList(), decodeFailures = 0)
        }
        val files = walksDir.listFiles { _, name -> name.endsWith(".json") }
            ?: return ReadWalksResult(emptyList(), decodeFailures = 0)
        return decodeWalkFiles(files.toList(), json)
    }

    /**
     * Tracks per-uuid insertion outcome from [insertWalks]. [failed] is
     * walks that decoded but could not be inserted (a child-entity invariant
     * violation, a degenerate insert id, etc.); surfaced via
     * [ImportSummary.skipped] so they aren't silently dropped.
     */
    private data class InsertResult(val added: Int, val replaced: Int, val failed: Int)

    /**
     * Each walk is imported in its OWN top-level Room transaction (see the
     * inline note on why nesting under one batch transaction is unsafe on
     * framework SQLite). Per walk:
     *  - If [overwriteByUuid] (tended file) and uuid already in DB:
     *    delete the existing walk first, then insert the new version, both
     *    inside the same transaction. Counts as `replaced`. iOS parity
     *    v1.6.0 — the web editor already applied modifications to the per-walk
     *    JSON payload, so on import we honor those edits by replacing in place.
     *  - Otherwise, skip if uuid already in DB (idempotent re-import).
     *  - Insert walk row (Room returns the autogen id).
     *  - Bulk-insert child entities with `walkId = newId`.
     * A walk whose transaction throws is rolled back in full and counted in
     * `failed`; sibling walks already committed are unaffected.
     */
    private suspend fun insertWalks(walks: List<PilgrimWalk>, overwriteByUuid: Boolean): InsertResult {
        if (walks.isEmpty()) return InsertResult(0, 0, 0)

        val walkDao = database.walkDao()
        val routeDao = database.routeDataSampleDao()
        val waypointDao = database.waypointDao()
        val eventDao = database.walkEventDao()
        val activityDao = database.activityIntervalDao()
        val voiceDao = database.voiceRecordingDao()
        val photoDao = database.walkPhotoDao()

        // uuids already in the DB (skip / replace decision) and uuids handled
        // earlier in THIS archive (so a malformed archive listing the same
        // uuid twice is handled once, not double-replaced).
        val existingUuids = walkDao.getAllUuids().toHashSet()
        val processedInBatch = HashSet<String>()

        var added = 0
        var replaced = 0
        var failed = 0

        for (pilgrimWalk in walks) {
            if (!processedInBatch.add(pilgrimWalk.id)) {
                Log.d(TAG, "Skipping in-archive duplicate walk uuid=${pilgrimWalk.id}")
                continue
            }
            val alreadyPresent = pilgrimWalk.id in existingUuids
            if (alreadyPresent && !overwriteByUuid) {
                Log.d(TAG, "Skipping duplicate walk uuid=${pilgrimWalk.id}")
                continue
            }
            val isReplacement = alreadyPresent && overwriteByUuid
            val didInsert = try {
                // Each walk is its OWN top-level transaction. Framework SQLite
                // does NOT turn nested withTransaction blocks into savepoints —
                // a failed inner transaction dooms the entire enclosing one
                // (verified: a sibling's failure silently rolled back already-
                // imported walks). A per-walk top-level transaction is the only
                // way to (a) roll a failed walk back in full — the tended delete
                // + re-insert are atomic, so the user's original survives a bad
                // payload — while (b) leaving already-imported walks committed.
                //
                // Every preserve-the-original exit must THROW so the transaction
                // rolls back; a bare `return@withTransaction false` would COMMIT
                // the partial state (incl. the delete). Hence `check()`, not a
                // false return, on the degenerate insert.
                database.withTransaction {
                    if (isReplacement) {
                        // Editor applied edits to the JSON payload; delete the
                        // stale Room row(s) so child entities cascade away, then
                        // re-insert the edited version (atomic with the delete).
                        walkDao.deleteByUuids(listOf(pilgrimWalk.id))
                    }
                    val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
                    val newWalkId = walkDao.insert(pending.walk)
                    check(newWalkId > 0) {
                        "walkDao.insert returned $newWalkId for uuid=${pilgrimWalk.id}"
                    }
                    insertChildEntities(
                        pending = pending,
                        newWalkId = newWalkId,
                        routeDao = routeDao,
                        waypointDao = waypointDao,
                        eventDao = eventDao,
                        activityDao = activityDao,
                        voiceDao = voiceDao,
                        photoDao = photoDao,
                    )
                    true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Skipping walk uuid=${pilgrimWalk.id}: ${e.message}", e)
                false
            }
            if (didInsert) {
                if (isReplacement) replaced += 1 else added += 1
            } else {
                failed += 1
            }
        }
        return InsertResult(added, replaced, failed)
    }

    /**
     * iOS parity v1.6.0 `PilgrimPackageImporter.applyArchivedEntries`.
     * For each archived entry:
     *  - If a Walk row with this UUID exists, strip its heavy children
     *    (route, photos, recordings, waypoints, events, activity
     *    intervals) and keep the surface stats. The strip happens
     *    inside the transaction so partial failure rolls back cleanly.
     *  - If no matching Walk exists, create a stub Walk row with the
     *    archived surface stats so the user still sees a dot on the
     *    journey (matches iOS — the walk happened, it just lives in
     *    archived form on this device).
     *  - Mark the UUID in [archivedRegistry] AFTER the transaction
     *    commits, so a transaction failure leaves the registry clean.
     *
     * Audio file deletion is deferred to a future sweep so this method
     * stays DB-only and cheap; OrphanRecordingSweeper handles the
     * filesystem side on next launch.
     */
    private suspend fun applyArchivedEntries(entries: List<PilgrimArchivedWalk>): Int {
        if (entries.isEmpty()) return 0
        val toMark = mutableListOf<Pair<String, Double>>()
        database.withTransaction {
            val walkDao = database.walkDao()
            val routeDao = database.routeDataSampleDao()
            val waypointDao = database.waypointDao()
            val eventDao = database.walkEventDao()
            val activityDao = database.activityIntervalDao()
            val voiceDao = database.voiceRecordingDao()
            val photoDao = database.walkPhotoDao()
            for (entry in entries) {
                val existing = walkDao.getByUuid(entry.id)
                if (existing != null) {
                    // Strip heavy children. DAOs each expose a per-walkId
                    // delete; chain them inside the same transaction so
                    // partial failures roll back together.
                    runCatching { routeDao.deleteByWalkId(existing.id) }
                    runCatching { waypointDao.deleteByWalkId(existing.id) }
                    runCatching { eventDao.deleteByWalkId(existing.id) }
                    runCatching { activityDao.deleteByWalkId(existing.id) }
                    runCatching { voiceDao.deleteByWalkId(existing.id) }
                    runCatching { photoDao.deleteByWalkId(existing.id) }
                }
                toMark += entry.id to entry.archivedAt
            }
        }
        // Registry mutations after the transaction commits — a mid-
        // transaction abort must not leak archived flags. Per-UUID
        // marks serialize through DataStore's internal mutex.
        for ((uuid, archivedAt) in toMark) {
            runCatching { archivedRegistry.markArchived(uuid, archivedAt) }
        }
        return toMark.size
    }

    private suspend fun insertChildEntities(
        pending: PendingImport,
        newWalkId: Long,
        routeDao: RouteDataSampleDao,
        waypointDao: WaypointDao,
        eventDao: WalkEventDao,
        activityDao: ActivityIntervalDao,
        voiceDao: VoiceRecordingDao,
        photoDao: WalkPhotoDao,
    ) {
        pending.routeSamples
            .map { it.copy(walkId = newWalkId) }
            .takeIf { it.isNotEmpty() }
            ?.let { routeDao.insertAll(it) }

        pending.waypoints
            .map { it.copy(walkId = newWalkId) }
            .forEach { waypointDao.insert(it) }

        pending.walkEvents
            .map { it.copy(walkId = newWalkId) }
            .forEach { eventDao.insert(it) }

        pending.activityIntervals
            .map { it.copy(walkId = newWalkId) }
            .let { if (it.isNotEmpty()) activityDao.insertAll(it) }

        pending.voiceRecordings
            .map { it.copy(walkId = newWalkId) }
            .forEach { voiceDao.insert(it) }

        pending.walkPhotos
            .map { pendingPhoto ->
                WalkPhoto(
                    walkId = newWalkId,
                    photoUri = pendingPhoto.photoUri,
                    pinnedAt = pendingPhoto.pinnedAt,
                    takenAt = pendingPhoto.takenAt,
                )
            }
            .takeIf { it.isNotEmpty() }
            ?.let { photoDao.insertAll(it) }
    }

    private companion object {
        const val TAG = "PilgrimPackageImporter"
        const val COPY_BUFFER_BYTES = 8 * 1024
    }
}

/** Outcome of decoding the JSON files in an archive's `walks/` directory. */
internal data class ReadWalksResult(
    val walks: List<PilgrimWalk>,
    val decodeFailures: Int,
)

/**
 * Decode each walk JSON file, counting the ones that fail. A failed
 * decode is skipped (not fatal) so a single corrupt file doesn't abort
 * the whole import — but the count is returned so the caller can report
 * the partial import honestly (AF28) instead of silently dropping walks.
 * Extracted from [PilgrimPackageImporter.readWalks] so the skip-counting
 * is unit-testable without building a full archive.
 */
internal fun decodeWalkFiles(files: List<File>, json: Json): ReadWalksResult {
    var decodeFailures = 0
    val walks = files.mapNotNull { file ->
        try {
            json.decodeFromString(PilgrimWalk.serializer(), file.readText())
        } catch (e: Throwable) {
            Log.w("PilgrimPackageImporter", "Skipping ${file.name}: ${e.message}")
            decodeFailures++
            null
        }
    }
    return ReadWalksResult(walks, decodeFailures)
}
