// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.core.threads.TranscriptContextStore
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
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.domain.WalkEventType

@Singleton
open class WalkRepository @Inject constructor(
    private val database: PilgrimDatabase,
    private val walkDao: WalkDao,
    private val routeDao: RouteDataSampleDao,
    private val altitudeDao: AltitudeSampleDao,
    private val walkEventDao: WalkEventDao,
    private val activityIntervalDao: ActivityIntervalDao,
    private val waypointDao: WaypointDao,
    private val voiceRecordingDao: VoiceRecordingDao,
    private val walkPhotoDao: WalkPhotoDao,
    /**
     * Nullable + defaulted so the ~40 existing call sites across the test
     * suite that construct [WalkRepository] directly (with named
     * arguments, none specifying this one) keep compiling unchanged;
     * production's Hilt graph always supplies the real singleton. `null`
     * here means "no Threads context cleanup" — never reached in
     * production, exercised deliberately by tests that don't care about
     * Threads hygiene.
     */
    private val transcriptContextStore: TranscriptContextStore? = null,
) {
    fun observeAllWalks(): Flow<List<Walk>> = walkDao.observeAll()

    suspend fun allWalks(): List<Walk> = walkDao.getAll()

    suspend fun mostRecentFinishedWalk(): Walk? = walkDao.getMostRecentFinished()

    /**
     * The N most-recent finished walks, ordered by end_timestamp
     * descending. Used by the home-screen widget refresh worker to
     * find the most recent walk that meets a reportable threshold —
     * skipping accidental sub-minute walks that would otherwise
     * tombstone an earlier valid record.
     */
    suspend fun recentFinishedWalks(limit: Int): List<Walk> =
        walkDao.getRecentFinished(limit)

    /**
     * Walks finished BEFORE [currentStart], DESC by end time, capped
     * to [limit]. Drives the Walk Summary milestone-callout chain
     * (LongestMeditation / LongestWalk / TotalDistance) — re-opening
     * an older walk's summary correctly compares against only walks
     * that started before it. Verbatim port of iOS
     * `WalkSummaryView.swift:436` predicate.
     */
    open suspend fun recentFinishedWalksBefore(currentStart: Long, limit: Int): List<Walk> =
        walkDao.getRecentFinishedBefore(currentStart, limit)

    /**
     * Stage 14 stub: returns `(talkSec, meditateSec)`. talkSec is hard-zeroed
     * because no live ActivityIntervalCoordinator exists yet — native walks
     * don't write talk intervals. meditateSec reads `Walk.meditationSeconds`
     * (cached column populated by WalkMetricsCache).
     *
     * TODO Stage 14.X: wire live ActivityInterval recording from WalkViewModel.
     */
    open suspend fun activitySumsFor(walkId: Long, walk: Walk): Pair<Long, Long> =
        Pair(0L, walk.meditationSeconds ?: 0L)

    /** Pause-aware duration math input for [HomeViewModel.buildSnapshots]. */
    open suspend fun walkEventsFor(walkId: Long): List<WalkEvent> =
        walkEventDao.getForWalk(walkId)

    /**
     * Ids of all walks marked as seeks (one `SEEK_MODE` event at
     * recording start), in a single query. Mirrors iOS
     * `HomeViewModel.fetchSeekWalkIDs` — the journal glyph must never
     * fault per-walk event lists to answer this.
     */
    open suspend fun seekWalkIds(): Set<Long> =
        walkEventDao.walkIdsWithEvent(WalkEventType.SEEK_MODE.name).toSet()

    /**
     * Icon strings of every icon-carrying waypoint, grouped by walk id,
     * in a single query (no N+1). Feeds
     * `GoshuinMilestones.arrivalCounts` — the pure pass that turns
     * these into seek-arrival counts for the seeking seals.
     */
    open suspend fun waypointIconsByWalk(): Map<Long, List<String?>> =
        waypointDao.iconsPerWalk().groupBy({ it.walkId }, { it.icon })

    suspend fun getActiveWalk(): Walk? = walkDao.getActive()

    /**
     * Cross-process Flow of the in-progress walk row (`end_timestamp
     * IS NULL`) or null. The UI process consumes this to derive
     * [org.walktalkmeditate.pilgrim.domain.WalkState] without
     * sharing the `:tracker` process's in-memory state.
     */
    fun observeActiveWalk(): Flow<Walk?> = walkDao.observeActive()

    open suspend fun getWalk(id: Long): Walk? = walkDao.getById(id)

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

    suspend fun updateWalkIntention(walkId: Long, intention: String?) {
        walkDao.updateIntention(walkId = walkId, intention = intention)
    }

    suspend fun setFavicon(walkId: Long, favicon: String?) =
        walkDao.updateFavicon(walkId, favicon)

    /**
     * Stage 12-A: persist a [WeatherSnapshot] to the four weather
     * columns on `walks`. Pass-through to [WalkDao.updateWeather] —
     * maps the typed [WeatherCondition] enum to its on-disk
     * `rawValue` so the column matches iOS verbatim.
     */
    suspend fun updateWeather(walkId: Long, snapshot: WeatherSnapshot) {
        walkDao.updateWeather(
            id = walkId,
            condition = snapshot.condition.rawValue,
            temperature = snapshot.temperatureCelsius,
            humidity = snapshot.humidityFraction,
            windSpeed = snapshot.windSpeedMps,
        )
    }

    /**
     * iOS parity Walk.steps. Pass-through to [WalkDao.updateSteps].
     * Called from [WalkController.finishWalk] with the diff of
     * `Sensor.TYPE_STEP_COUNTER` cumulative readings between start
     * and finish; null when the sensor is unavailable, the
     * ACTIVITY_RECOGNITION permission is denied, or the device
     * rebooted mid-walk.
     */
    suspend fun updateSteps(walkId: Long, steps: Int?) {
        walkDao.updateSteps(id = walkId, steps = steps)
    }

    suspend fun deleteWalk(walk: Walk) {
        walkDao.delete(walk)
    }

    /**
     * Deletes the walk row by id. All child rows in route_data_samples,
     * altitude_samples, walk_events, activity_intervals, waypoints,
     * voice_recordings, and walk_photos are removed via SQLite
     * `ON DELETE CASCADE`. No-op when the id matches no row.
     *
     * Recording uuids are captured INSIDE the transaction, before the
     * cascade tears the rows down — otherwise there is nothing left to
     * read once the delete commits. Threads context cleanup runs AFTER
     * the transaction commits, mirroring iOS's capture-then-tombstone
     * ordering (DAT-33/DAT-17): a context write must never be attempted
     * for a recording whose walk turned out not to exist.
     */
    suspend fun deleteWalkById(walkId: Long) {
        val recordingUuids = database.withTransaction {
            val uuids = voiceRecordingDao.getForWalk(walkId).map { it.uuid }
            walkDao.deleteById(walkId)
            uuids
        }
        if (recordingUuids.isNotEmpty()) {
            transcriptContextStore?.delete(recordingUuids)
        }
    }

    suspend fun recordLocation(sample: RouteDataSample): Long = routeDao.insert(sample)

    suspend fun recordLocations(samples: List<RouteDataSample>) = routeDao.insertAll(samples)

    open suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> = routeDao.getForWalk(walkId)

    suspend fun lastLocationSampleFor(walkId: Long): RouteDataSample? = routeDao.getLastForWalk(walkId)

    /**
     * First GPS sample (by timestamp). The walk's location hemisphere is
     * derived from this latitude for the seal / share / milestone, matching
     * iOS which keys those off `routeData.first` (not the device hemisphere).
     */
    open suspend fun firstLocationSampleFor(walkId: Long): RouteDataSample? =
        routeDao.getFirstForWalk(walkId)

    /**
     * First-sample latitude per walk, in a single query (no N+1). Used by
     * milestone detection to compute each walk's location-hemisphere season.
     */
    open suspend fun firstRouteLatitudesByWalk(): Map<Long, Double> =
        routeDao.firstLatitudePerWalk().associate { it.walkId to it.latitude }

    fun observeLocationSamples(walkId: Long): Flow<List<RouteDataSample>> =
        routeDao.observeForWalk(walkId)

    suspend fun recordAltitude(sample: AltitudeSample): Long = altitudeDao.insert(sample)

    suspend fun altitudeSamplesFor(walkId: Long): List<AltitudeSample> = altitudeDao.getForWalk(walkId)

    /**
     * Cross-process Flow of altitude samples for [walkId]. UI uses
     * this to render live ascent on the active walk screen as the
     * tracker writes new samples.
     */
    fun observeAltitudeSamples(walkId: Long): Flow<List<AltitudeSample>> =
        altitudeDao.observeForWalk(walkId)

    suspend fun recordEvent(event: WalkEvent): Long = walkEventDao.insert(event)

    suspend fun eventsFor(walkId: Long): List<WalkEvent> = walkEventDao.getForWalk(walkId)

    /**
     * Cross-process Flow of walk-lifecycle events for [walkId]. The
     * UI process consumes this together with [observeActiveWalk] and
     * [observeLocationSamples] to derive the live WalkState while
     * the `:tracker` process owns the in-memory reducer.
     */
    fun observeEventsForWalk(walkId: Long): Flow<List<WalkEvent>> =
        walkEventDao.observeForWalk(walkId)

    suspend fun recordActivityInterval(interval: ActivityInterval): Long = activityIntervalDao.insert(interval)

    open suspend fun activityIntervalsFor(walkId: Long): List<ActivityInterval> = activityIntervalDao.getForWalk(walkId)

    suspend fun addWaypoint(waypoint: Waypoint): Long = waypointDao.insert(waypoint)

    open suspend fun waypointsFor(walkId: Long): List<Waypoint> = waypointDao.getForWalk(walkId)

    fun observeWaypoints(walkId: Long): Flow<List<Waypoint>> =
        waypointDao.observeForWalk(walkId)

    fun observeWaypointCount(walkId: Long): Flow<Int> =
        waypointDao.observeCountForWalk(walkId)

    suspend fun recordVoice(recording: VoiceRecording): Long =
        voiceRecordingDao.insert(recording)

    /** `open` so tests can inject controlled failures (TranscriptionRunner's
     * two-attempt persistence retry, U5/BEH-58). */
    open suspend fun updateVoiceRecording(recording: VoiceRecording) =
        voiceRecordingDao.update(recording)

    /**
     * Deletes a single voice-recording row (the orphan sweeper's
     * case-b/case-c cleanup — a DB row whose backing file is missing or a
     * "zombie" pairing). Any stored Threads context for it is tombstoned
     * and removed the same way a whole-walk delete does — a vanished
     * recording is a vanished recording regardless of which path removed
     * its row.
     */
    suspend fun deleteVoiceRecording(recording: VoiceRecording) {
        voiceRecordingDao.delete(recording)
        transcriptContextStore?.delete(recording.uuid)
    }

    suspend fun getVoiceRecording(id: Long): VoiceRecording? =
        voiceRecordingDao.getById(id)

    open suspend fun voiceRecordingsFor(walkId: Long): List<VoiceRecording> =
        voiceRecordingDao.getForWalk(walkId)

    open suspend fun walkIdsWithPendingTranscriptions(): List<Long> =
        voiceRecordingDao.walkIdsWithNullTranscription()

    /** U6: [ThreadsBackfillRunner][org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillRunner]'s
     * default snapshot source — every already-transcribed recording. */
    open suspend fun transcribedRecordingsSnapshot(): List<org.walktalkmeditate.pilgrim.data.dao.TranscribedRecordingSnapshot> =
        voiceRecordingDao.transcribedSnapshot()

    fun observeVoiceRecordings(walkId: Long): Flow<List<VoiceRecording>> =
        voiceRecordingDao.observeForWalk(walkId)

    fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> =
        voiceRecordingDao.observeAll()

    suspend fun countVoiceRecordingsFor(walkId: Long): Int =
        voiceRecordingDao.countForWalk(walkId)

    /**
     * Walk Summary tap-to-edit + retranscribe path. Updates only the
     * transcription column without round-tripping the whole entity
     * (avoids touching the [VoiceRecording.init] invariants that police
     * durationMillis = end - start).
     */
    suspend fun updateVoiceRecordingTranscription(id: Long, transcription: String?) =
        voiceRecordingDao.updateTranscription(id, transcription)

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
                            capturedLat = ref.capturedLat,
                            capturedLng = ref.capturedLng,
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

    /**
     * One-shot read of pinned photos for [walkId] in the same order
     * [observePhotosFor] emits. Used by the Stage 13-XZ
     * [org.walktalkmeditate.pilgrim.core.prompt.PromptsCoordinator] to
     * snapshot the reliquary contents inside `buildContext` without
     * paying the Flow-collector overhead of `observePhotosFor(...).first()`.
     */
    open suspend fun photosFor(walkId: Long): List<WalkPhoto> =
        walkPhotoDao.getForWalk(walkId)

    // --- Stage 7-B: photo analysis ------------------------------------

    /**
     * Write an ML Kit analysis result back to a pinned photo. Null
     * [label] + [confidence] with a positive [analyzedAt] marks a row
     * as "analyzed but labeler produced no usable result" (URI
     * unreadable, empty result above threshold, labeler error) — the
     * UI tombstone path then handles display.
     *
     * The raw `@Query UPDATE` under this method bypasses the
     * [WalkPhoto.init] invariant, so the same pair + range checks run
     * here defensively. A caller who accidentally writes a
     * half-populated pair or an out-of-range confidence gets an
     * IllegalArgumentException at the repo seam rather than silently
     * corrupting the tombstone-vs-labeled distinction downstream.
     */
    suspend fun updatePhotoAnalysis(
        photoId: Long,
        label: String?,
        confidence: Double?,
        analyzedAt: Long,
    ) {
        require((label == null) == (confidence == null)) {
            "label and confidence must be both null or both non-null " +
                "(got label=$label, confidence=$confidence)"
        }
        require(confidence == null || confidence in 0.0..1.0) {
            "confidence must be null or within [0.0, 1.0] (got $confidence)"
        }
        require(analyzedAt > 0) {
            "analyzedAt must be positive epoch ms (got $analyzedAt)"
        }
        walkPhotoDao.updateAnalysis(photoId, label, confidence, analyzedAt)
    }

    /**
     * Photos still awaiting analysis for a walk. [PhotoAnalysisRunner]
     * iterates this list; empty when every pin has been analyzed
     * (successfully or tombstoned).
     */
    suspend fun pendingAnalysisPhotosFor(walkId: Long): List<WalkPhoto> =
        walkPhotoDao.getPendingAnalysisForWalk(walkId)
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
