// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.pilgrim.builder

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.walktalkmeditate.pilgrim.BuildConfig
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.export.BackupTimeCode
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimManifest
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimSchema
import org.walktalkmeditate.pilgrim.data.pilgrim.PilgrimWalk
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.di.PilgrimJson

/**
 * Assembles a `.pilgrim` ZIP from all finished walks. Mirrors iOS
 * [PilgrimPackageBuilder] pipeline:
 *
 * 1. Load all finished walks + per-walk related entities via
 *    [WalkRepository] reads (Dispatchers.IO).
 * 2. Convert each via [PilgrimPackageConverter.convert].
 * 3. If `includePhotos`, run [AndroidPilgrimPhotoEmbedder]; rewrite
 *    `embeddedPhotoFilename` on each walk's photos and drop entries
 *    that were skipped.
 * 4. Write `manifest.json`, `schema.json`, `walks/<uuid>.json` (and
 *    `photos/<sanitized>.jpg` from the embedder) into a temp dir
 *    under `cacheDir`.
 * 5. ZIP the temp dir to `cacheDir/pilgrim_export/pilgrim-<timecode>.pilgrim`.
 * 6. Delete temp dir; return [PilgrimPackageBuildResult].
 */
@Singleton
class PilgrimPackageBuilder @Inject constructor(
    private val walkRepository: WalkRepository,
    private val walkPhotoDao: WalkPhotoDao,
    private val practicePreferences: PracticePreferencesRepository,
    private val unitsPreferences: UnitsPreferencesRepository,
    private val photoEmbedder: AndroidPilgrimPhotoEmbedder,
    @PilgrimJson private val json: Json,
    @ApplicationContext private val context: Context,
) {

    /**
     * Build a `.pilgrim` archive containing every finished walk.
     *
     * @throws PilgrimPackageError.NoWalksFound when no finished walks exist.
     * @throws PilgrimPackageError.FileSystemError on IO errors.
     * @throws PilgrimPackageError.ZipFailed on ZIP packaging errors.
     * @throws CancellationException if the caller's coroutine cancels.
     */
    suspend fun build(includePhotos: Boolean): PilgrimPackageBuildResult =
        withContext(Dispatchers.IO) {
            val finishedWalks = walkRepository.allWalks().filter { it.endTimestamp != null }
            if (finishedWalks.isEmpty()) throw PilgrimPackageError.NoWalksFound

            var skippedFromConverter = 0

            val pilgrimWalks: MutableList<PilgrimWalk> = finishedWalks.map { walk ->
                val bundle = WalkExportBundle(
                    walk = walk,
                    routeSamples = walkRepository.locationSamplesFor(walk.id),
                    altitudeSamples = walkRepository.altitudeSamplesFor(walk.id),
                    walkEvents = walkRepository.eventsFor(walk.id),
                    activityIntervals = walkRepository.activityIntervalsFor(walk.id),
                    waypoints = walkRepository.waypointsFor(walk.id),
                    voiceRecordings = walkRepository.voiceRecordingsFor(walk.id),
                    walkPhotos = walkPhotoDao.getForWalk(walk.id),
                )
                val converted = PilgrimPackageConverter.convert(bundle, includePhotos = includePhotos)
                skippedFromConverter += converted.skippedPhotoCount
                converted.walk
            }.toMutableList()

            val tempDir = File(context.cacheDir, "pilgrim-export-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val embedSkipped = if (includePhotos) {
                    val embedResult = photoEmbedder.embedPhotos(pilgrimWalks, tempDir)
                    for (i in pilgrimWalks.indices) {
                        pilgrimWalks[i] = applyEmbeddedFilenames(pilgrimWalks[i], embedResult.filenameMap)
                    }
                    embedResult.skippedCount
                } else {
                    0
                }

                val manifest = PilgrimPackageConverter.buildManifest(
                    appVersion = BuildConfig.VERSION_NAME.removeSuffix("-debug"),
                    walkCount = pilgrimWalks.size,
                    distanceUnits = unitsPreferences.distanceUnits.value,
                    celestialAwareness = practicePreferences.celestialAwarenessEnabled.value,
                    zodiacSystem = practicePreferences.zodiacSystem.value.storageValue(),
                    beginWithIntention = practicePreferences.beginWithIntention.value,
                    exportInstant = Instant.now(),
                )

                writePayload(tempDir, pilgrimWalks, manifest)
                val outputFile = zipPayload(tempDir)

                PilgrimPackageBuildResult(
                    file = outputFile,
                    skippedPhotoCount = skippedFromConverter + embedSkipped,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: PilgrimPackageError) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "Build failed", e)
                throw PilgrimPackageError.FileSystemError(e)
            } finally {
                runCatching { tempDir.deleteRecursively() }
            }
        }

    private fun writePayload(
        tempDir: File,
        walks: List<PilgrimWalk>,
        manifest: PilgrimManifest,
    ) {
        val walksDir = File(tempDir, "walks").apply { mkdirs() }
        for (walk in walks) {
            val payload = json.encodeToString(PilgrimWalk.serializer(), walk)
            File(walksDir, "${walk.id}.json").writeText(payload)
        }
        val manifestJson = json.encodeToString(PilgrimManifest.serializer(), manifest)
        File(tempDir, "manifest.json").writeText(manifestJson)
        File(tempDir, "schema.json").writeText(PilgrimSchema.JSON)
    }

    private fun zipPayload(tempDir: File): File {
        val outputDir = File(context.cacheDir, "pilgrim_export").apply { mkdirs() }
        val timeCode = BackupTimeCode.format(Instant.now())
        val output = File(outputDir, "pilgrim-$timeCode.pilgrim")

        try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
                for (file in tempDir.walkTopDown().filter { it.isFile }) {
                    val entryName = file.relativeTo(tempDir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(entryName))
                    FileInputStream(file).use { input ->
                        input.copyTo(zip, bufferSize = COPY_BUFFER_BYTES)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: Throwable) {
            runCatching { output.delete() }
            throw PilgrimPackageError.ZipFailed(e)
        }
        return output
    }

    private fun applyEmbeddedFilenames(
        walk: PilgrimWalk,
        filenameMap: Map<String, String>,
    ): PilgrimWalk {
        val photos = walk.photos ?: return walk
        val updated = photos.mapNotNull { photo ->
            val filename = filenameMap[photo.localIdentifier] ?: return@mapNotNull null
            photo.copy(embeddedPhotoFilename = filename)
        }
        return walk.copy(photos = updated)
    }

    private companion object {
        const val TAG = "PilgrimPackageBuilder"
        const val COPY_BUFFER_BYTES = 8 * 1024
    }
}
