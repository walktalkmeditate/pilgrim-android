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
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
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
 * - Duplicate uuid (already-imported walk) → skipped silently.
 */
@Singleton
class PilgrimPackageImporter @Inject constructor(
    private val database: PilgrimDatabase,
    @PilgrimJson private val json: Json,
    @ApplicationContext private val context: Context,
) {

    /**
     * @return number of walks successfully imported.
     * @throws PilgrimPackageError.InvalidPackage if the archive is
     *   structurally invalid (missing manifest, bad ZIP, etc.).
     * @throws PilgrimPackageError.UnsupportedSchemaVersion if the
     *   archive's schemaVersion isn't `"1.0"`.
     * @throws PilgrimPackageError.DecodingFailed if the manifest
     *   itself can't be decoded.
     * @throws PilgrimPackageError.FileSystemError on IO failures.
     */
    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tempDir = File(context.cacheDir, "pilgrim-import-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            unzipTo(uri, tempDir)
            val manifest = readManifest(tempDir)
            if (manifest.schemaVersion != PilgrimSchema.VERSION) {
                throw PilgrimPackageError.UnsupportedSchemaVersion(manifest.schemaVersion)
            }
            val walks = readWalks(tempDir)
            insertWalks(walks)
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
                val tempCanon = tempDir.canonicalFile
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        File(tempDir, entry.name).mkdirs()
                        zip.closeEntry()
                        continue
                    }
                    // Defensive zip-slip guard: reject entries whose
                    // resolved path escapes tempDir.
                    val target = File(tempDir, entry.name).canonicalFile
                    if (!target.path.startsWith(tempCanon.path + File.separator) &&
                        target.path != tempCanon.path
                    ) {
                        throw PilgrimPackageError.InvalidPackage
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

    private fun readManifest(tempDir: File): PilgrimManifest {
        val manifestFile = File(tempDir, "manifest.json")
        if (!manifestFile.exists()) throw PilgrimPackageError.InvalidPackage
        return try {
            json.decodeFromString(PilgrimManifest.serializer(), manifestFile.readText())
        } catch (e: Throwable) {
            throw PilgrimPackageError.DecodingFailed(e)
        }
    }

    private fun readWalks(tempDir: File): List<PilgrimWalk> {
        val walksDir = File(tempDir, "walks")
        if (!walksDir.exists() || !walksDir.isDirectory) {
            // No walks dir — opted-into-import an empty archive.
            // Treat as zero walks rather than InvalidPackage.
            return emptyList()
        }
        val files = walksDir.listFiles { _, name -> name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                json.decodeFromString(PilgrimWalk.serializer(), file.readText())
            } catch (e: Throwable) {
                Log.w(TAG, "Skipping ${file.name}: ${e.message}")
                null
            }
        }
    }

    /**
     * Single Room transaction. Each walk:
     *  - Skip if uuid already in DB (idempotent re-import).
     *  - Insert walk row (Room returns the autogen id).
     *  - Bulk-insert child entities with `walkId = newId`.
     */
    private suspend fun insertWalks(walks: List<PilgrimWalk>): Int {
        if (walks.isEmpty()) return 0

        var inserted = 0
        database.withTransaction {
            val walkDao = database.walkDao()
            val routeDao = database.routeDataSampleDao()
            val waypointDao = database.waypointDao()
            val eventDao = database.walkEventDao()
            val activityDao = database.activityIntervalDao()
            val voiceDao = database.voiceRecordingDao()
            val photoDao = database.walkPhotoDao()

            // Pre-fetch existing uuids to skip duplicates without
            // per-walk DB hits.
            val existingUuids = walkDao.getAll().map { it.uuid }.toHashSet()

            for (pilgrimWalk in walks) {
                if (pilgrimWalk.id in existingUuids) {
                    Log.d(TAG, "Skipping duplicate walk uuid=${pilgrimWalk.id}")
                    continue
                }
                val pending = PilgrimPackageConverter.convertToImport(pilgrimWalk)
                val newWalkId = walkDao.insert(pending.walk)
                if (newWalkId <= 0) {
                    Log.w(TAG, "walkDao.insert returned $newWalkId for uuid=${pilgrimWalk.id}; skipping children")
                    continue
                }

                pending.routeSamples
                    .map { it.copy(walkId = newWalkId) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { routeDao.insertAll(it) }

                for (waypoint in pending.waypoints) {
                    waypointDao.insert(waypoint.copy(walkId = newWalkId))
                }

                for (event in pending.walkEvents) {
                    eventDao.insert(event.copy(walkId = newWalkId))
                }

                pending.activityIntervals
                    .map { it.copy(walkId = newWalkId) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { activityDao.insertAll(it) }

                for (recording in pending.voiceRecordings) {
                    voiceDao.insert(recording.copy(walkId = newWalkId))
                }

                // Construct WalkPhoto with the real walkId (Stage 7-A
                // invariant requires walkId > 0, so we couldn't carry
                // them through the PendingImport bundle directly).
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

                inserted += 1
            }
        }
        return inserted
    }

    private companion object {
        const val TAG = "PilgrimPackageImporter"
        const val COPY_BUFFER_BYTES = 8 * 1024
    }
}
