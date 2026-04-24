// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkEvent
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.entity.Waypoint

@Singleton
class WalkRepository @Inject constructor(
    private val database: PilgrimDatabase,
    private val walkDao: WalkDao,
    private val routeDao: RouteDataSampleDao,
    private val altitudeDao: AltitudeSampleDao,
    private val walkEventDao: WalkEventDao,
    private val activityIntervalDao: ActivityIntervalDao,
    private val waypointDao: WaypointDao,
    private val voiceRecordingDao: VoiceRecordingDao,
    private val walkPhotoDao: WalkPhotoDao,
) {
    fun observeAllWalks(): Flow<List<Walk>> = walkDao.observeAll()

    suspend fun allWalks(): List<Walk> = walkDao.getAll()

    suspend fun mostRecentFinishedWalk(): Walk? = walkDao.getMostRecentFinished()

    suspend fun getActiveWalk(): Walk? = walkDao.getActive()

    suspend fun getWalk(id: Long): Walk? = walkDao.getById(id)

    suspend fun startWalk(startTimestamp: Long, intention: String? = null): Walk {
        val draft = Walk(startTimestamp = startTimestamp, intention = intention)
        val id = walkDao.insert(draft)
        return draft.copy(id = id)
    }

    suspend fun finishWalk(walk: Walk, endTimestamp: Long) {
        walkDao.update(walk.copy(endTimestamp = endTimestamp))
    }

    /**
     * Finalize by id under a single Room transaction: reads the current
     * row and writes back the end_timestamp atomically. Returns `false`
     * if the walk row is gone by the time finalize runs (e.g., user
     * deleted the walk from another surface mid-finish). Prefer this over
     * the read+update two-call pattern from [getWalk] + [finishWalk].
     */
    suspend fun finishWalkAtomic(walkId: Long, endTimestamp: Long): Boolean =
        database.withTransaction {
            val walk = walkDao.getById(walkId) ?: return@withTransaction false
            walkDao.update(walk.copy(endTimestamp = endTimestamp))
            true
        }

    suspend fun updateWalk(walk: Walk) {
        walkDao.update(walk)
    }

    suspend fun deleteWalk(walk: Walk) {
        walkDao.delete(walk)
    }

    suspend fun recordLocation(sample: RouteDataSample): Long = routeDao.insert(sample)

    suspend fun recordLocations(samples: List<RouteDataSample>) = routeDao.insertAll(samples)

    suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> = routeDao.getForWalk(walkId)

    suspend fun lastLocationSampleFor(walkId: Long): RouteDataSample? = routeDao.getLastForWalk(walkId)

    fun observeLocationSamples(walkId: Long): Flow<List<RouteDataSample>> =
        routeDao.observeForWalk(walkId)

    suspend fun recordAltitude(sample: AltitudeSample): Long = altitudeDao.insert(sample)

    suspend fun altitudeSamplesFor(walkId: Long): List<AltitudeSample> = altitudeDao.getForWalk(walkId)

    suspend fun recordEvent(event: WalkEvent): Long = walkEventDao.insert(event)

    suspend fun eventsFor(walkId: Long): List<WalkEvent> = walkEventDao.getForWalk(walkId)

    suspend fun recordActivityInterval(interval: ActivityInterval): Long = activityIntervalDao.insert(interval)

    suspend fun activityIntervalsFor(walkId: Long): List<ActivityInterval> = activityIntervalDao.getForWalk(walkId)

    suspend fun addWaypoint(waypoint: Waypoint): Long = waypointDao.insert(waypoint)

    suspend fun waypointsFor(walkId: Long): List<Waypoint> = waypointDao.getForWalk(walkId)

    suspend fun recordVoice(recording: VoiceRecording): Long =
        voiceRecordingDao.insert(recording)

    suspend fun updateVoiceRecording(recording: VoiceRecording) =
        voiceRecordingDao.update(recording)

    suspend fun deleteVoiceRecording(recording: VoiceRecording) =
        voiceRecordingDao.delete(recording)

    suspend fun getVoiceRecording(id: Long): VoiceRecording? =
        voiceRecordingDao.getById(id)

    suspend fun voiceRecordingsFor(walkId: Long): List<VoiceRecording> =
        voiceRecordingDao.getForWalk(walkId)

    fun observeVoiceRecordings(walkId: Long): Flow<List<VoiceRecording>> =
        voiceRecordingDao.observeForWalk(walkId)

    fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> =
        voiceRecordingDao.observeAll()

    suspend fun countVoiceRecordingsFor(walkId: Long): Int =
        voiceRecordingDao.countForWalk(walkId)

    // --- Stage 7-A: photo reliquary -----------------------------------

    /**
     * Pin a single photo to [walkId]. Callers must hand over the exact
     * [pinnedAt] they want stored — usually one wall-clock reading
     * shared across a pick batch so all rows cluster together for the
     * grid's `ORDER BY pinned_at`.
     */
    suspend fun pinPhoto(
        walkId: Long,
        photoUri: String,
        takenAt: Long?,
        pinnedAt: Long,
    ): Long = walkPhotoDao.insert(
        WalkPhoto(
            walkId = walkId,
            photoUri = photoUri,
            pinnedAt = pinnedAt,
            takenAt = takenAt,
        ),
    )

    /**
     * Insert a batch of picked photos under a single Room transaction.
     * Count, clip to remaining slots, and insert all happen under the
     * same lock so concurrent [pinPhotos] calls cannot collectively
     * exceed [cap] — the double-pick race (user backs out of the first
     * picker and opens a second before the first batch's StateFlow
     * emission lands) could otherwise push the walk over the cap by
     * reading a stale size in the VM.
     *
     * All committed rows share the same [pinnedAt] so they sort
     * together and the grid sees one diff rather than N.
     *
     * Returns a [PinPhotosResult] describing what landed and what was
     * clipped. The VM takes persistable grants on [refs] BEFORE calling
     * this method (idempotent if another walk already held a grant),
     * so clipped URIs would otherwise leak — callers must release
     * grants on [PinPhotosResult.droppedOrphanUris], which are the
     * tail URIs whose grants no other walk references after the
     * transaction closes. The orphan check happens in the same
     * transaction as the insert so a concurrent writer can't race a
     * reference in or out between the clip and the release decision.
     */
    suspend fun pinPhotos(
        walkId: Long,
        refs: List<PhotoPinRef>,
        pinnedAt: Long,
        cap: Int = Int.MAX_VALUE,
    ): PinPhotosResult {
        if (refs.isEmpty()) return PinPhotosResult(emptyList(), emptyList())
        return database.withTransaction {
            val remaining = (cap - walkPhotoDao.countForWalk(walkId))
                .coerceAtLeast(0)
            val accepted = if (remaining < refs.size) refs.take(remaining) else refs
            val dropped = if (remaining < refs.size) refs.drop(remaining) else emptyList()
            val insertedIds = if (accepted.isEmpty()) {
                emptyList()
            } else {
                walkPhotoDao.insertAll(
                    accepted.map { ref ->
                        WalkPhoto(
                            walkId = walkId,
                            photoUri = ref.uri,
                            pinnedAt = pinnedAt,
                            takenAt = ref.takenAt,
                        )
                    },
                )
            }
            // A dropped URI is "orphaned" from this app's perspective
            // when no row (in any walk) references it anymore. VM dedup
            // makes it unlikely the URI also appears in `accepted`, but
            // `countByPhotoUri` is the source of truth — if another
            // walk pins the URI, or this batch had an internal dupe,
            // keep the grant.
            val orphanUris = dropped
                .map { it.uri }
                .distinct()
                .filter { walkPhotoDao.countByPhotoUri(it) == 0 }
            PinPhotosResult(
                insertedIds = insertedIds,
                droppedOrphanUris = orphanUris,
            )
        }
    }

    /**
     * Remove a pin by id under a single transaction so the removal and
     * the follow-up cross-walk reference count are consistent. Returns
     * a [UnpinPhotoResult] describing what happened — the caller needs
     * [UnpinPhotoResult.wasLastReference] to decide whether to release
     * the URI's persistable read grant. Grants are shared app-wide, so
     * releasing while another walk still pins the same URI would
     * tombstone the other walk's tile.
     */
    suspend fun unpinPhoto(photoId: Long): UnpinPhotoResult =
        database.withTransaction {
            val target = walkPhotoDao.getById(photoId)
                ?: return@withTransaction UnpinPhotoResult.NotFound
            val removed = walkPhotoDao.deleteById(photoId) > 0
            if (!removed) return@withTransaction UnpinPhotoResult.NotFound
            val remaining = walkPhotoDao.countByPhotoUri(target.photoUri)
            UnpinPhotoResult.Removed(
                photoUri = target.photoUri,
                wasLastReference = remaining == 0,
            )
        }

    suspend fun countPhotosFor(walkId: Long): Int =
        walkPhotoDao.countForWalk(walkId)

    fun observePhotosFor(walkId: Long): Flow<List<WalkPhoto>> =
        walkPhotoDao.observeForWalk(walkId)
}

/**
 * Outcome of [WalkRepository.unpinPhoto]. The VM reads
 * [Removed.wasLastReference] to decide whether to release the
 * persistable URI grant — see the repo method's doc for why.
 */
sealed class UnpinPhotoResult {
    data object NotFound : UnpinPhotoResult()
    data class Removed(
        val photoUri: String,
        val wasLastReference: Boolean,
    ) : UnpinPhotoResult()
}

/**
 * Outcome of [WalkRepository.pinPhotos]. [insertedIds] has one id per
 * row actually inserted (may be shorter than the caller's `refs` if
 * the repo's transactional cap clipped the batch). [droppedOrphanUris]
 * lists URIs whose grants the caller should release — they were
 * dropped by the cap clip AND no other walk still references them.
 */
data class PinPhotosResult(
    val insertedIds: List<Long>,
    val droppedOrphanUris: List<String>,
)
