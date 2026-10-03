// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data

import android.util.Log
import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
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
import org.walktalkmeditate.pilgrim.data.honor.HonorFinishKind
import org.walktalkmeditate.pilgrim.data.honor.HonorSessionEntity
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.seek.SeekSessionEntity
import org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot
import org.walktalkmeditate.pilgrim.domain.WalkEventType
import org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence
import org.walktalkmeditate.pilgrim.walk.honor.HonorFinalizeOutcome
import org.walktalkmeditate.pilgrim.walk.honor.HonorFinalizer

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
    /**
     * Nullable + defaulted for the same reason as [transcriptContextStore]:
     * `null` means "no staged Way to discard", never reached in production.
     */
    private val wayStore: WayStore? = null,
    /** Nullable + defaulted likewise: `null` means "no Honor finalize step". */
    private val honorFinalizer: HonorFinalizer? = null,
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
     * Ids of all walks that honored a Way (one `HONOR_MODE` event at
     * recording start), in a single query, as [seekWalkIds] does for
     * seeks (iOS `fetchWalkIDs(withEvent: .honorMode)`,
     * `HomeViewModel.swift:153-167@7c200bf`). Whatever the release flag
     * says: callers gate on it.
     */
    open suspend fun honorWalkIds(): Set<Long> =
        walkEventDao.walkIdsWithEvent(WalkEventType.HONOR_MODE.name).toSet()

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

    suspend fun walkByUuid(uuid: String): Walk? = walkDao.getByUuid(uuid)

    /** Whether a walk under [walkUuid] finished with Honor: the marker outlives the walk's own row. */
    suspend fun hasHonorMarker(walkUuid: String): Boolean = database.honorDao().getMarker(walkUuid) != null

    /** [uuid] is the Honor Begin use case's minted uuid; null mints one here. */
    suspend fun startWalk(startTimestamp: Long, intention: String? = null, uuid: String? = null): Walk {
        val draft = if (uuid == null) {
            Walk(startTimestamp = startTimestamp, intention = intention)
        } else {
            Walk(uuid = uuid, startTimestamp = startTimestamp, intention = intention)
        }
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
     *
     * The same transaction records how a walk with Honor ended, so the
     * Honor step ([runHonorFinalize]) knows whether to write the arrival
     * numbers. Only the tracker's FinalizeWalk effect is a clean finish;
     * every other caller finalizes a walk its process lost, hence the
     * default. The first kind recorded wins.
     */
    suspend fun finishWalkAtomic(
        walkId: Long,
        endTimestamp: Long,
        finishKind: HonorFinishKind = HonorFinishKind.RECOVERED,
    ): Boolean =
        database.withTransaction {
            val walk = walkDao.getById(walkId) ?: return@withTransaction false
            walkDao.update(walk.copy(endTimestamp = endTimestamp))
            database.honorDao().recordFinishKind(walkId, finishKind)
            true
        }

    /**
     * The Honor step after [finishWalkAtomic]: link, promotion, marker, and
     * the live rows last. Never throws but for cancellation; a step that
     * fails leaves the live rows for [HonorFinalizer.runAtLaunch] to retry.
     * Also the guard the archive strip and the tended replace run first,
     * since both drop the live rows a pending step still needs.
     */
    suspend fun runHonorFinalize(walkId: Long): HonorFinalizeOutcome {
        val finalizer = honorFinalizer ?: return HonorFinalizeOutcome.DONE
        return try {
            finalizer.finalize(walkId)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "Honor finalize for walk $walkId deferred: ${e::class.simpleName}")
            HonorFinalizeOutcome.PENDING
        }
    }

    /**
     * Whether an Honor start for [walkUuid] can follow [wayId]: the id passes
     * the store's allow-list, and the Way loads, staged under the walk's uuid
     * or listed. The `:tracker` service accepts nothing else.
     */
    suspend fun canStartHonorWalk(walkUuid: String, wayId: String): Boolean {
        val store = wayStore ?: return false
        if (!WayStore.isValidId(wayId) || !WayStore.isValidWalkUuid(walkUuid)) return false
        return withContext(Dispatchers.IO) {
            store.staged(walkUuid)?.id == wayId || store.load(wayId) != null
        }
    }

    /** The HONOR_MODE marker and the session row land together or not at all. */
    suspend fun recordHonorStart(marker: WalkEvent, session: HonorSessionEntity) {
        database.withTransaction {
            walkEventDao.insert(marker)
            database.honorDao().insertSession(session)
        }
    }

    /**
     * Arrival's compare-and-set: on a walk still unfinished, flips the
     * session's phase once and keeps the arrival numbers for the link and
     * the [walkedMeters] for the stage arrival card, and only then writes
     * HONOR_ARRIVAL and the reserved waypoint (iOS
     * `recordHonorArrival`, `ActiveWalkViewModel+Honor.swift:247-252@7c200bf`:
     * event, then waypoint). A [waypoint] is null when no fix exists yet;
     * the event still lands, as on iOS.
     *
     * @return true only when this call flipped the phase.
     */
    suspend fun recordHonorArrival(
        walkId: Long,
        theirSeconds: Double,
        yourSeconds: Double,
        walkedMeters: Double,
        eventAt: Long,
        waypoint: Waypoint?,
    ): Boolean = database.withTransaction {
        val walk = walkDao.getById(walkId)
        if (walk == null || walk.endTimestamp != null) return@withTransaction false
        if (database.honorDao().recordArrival(walkId, theirSeconds, yourSeconds, walkedMeters) != 1) {
            return@withTransaction false
        }
        walkEventDao.insert(WalkEvent(walkId = walkId, timestamp = eventAt, eventType = WalkEventType.HONOR_ARRIVAL))
        if (waypoint != null) waypointDao.insert(waypoint)
        true
    }

    /** The SEEK_MODE marker and the seek session row land together or not at all (plan U25). */
    suspend fun recordSeekStart(marker: WalkEvent, session: SeekSessionEntity) {
        database.withTransaction {
            walkEventDao.insert(marker)
            database.seekDao().insertSession(session)
        }
    }

    /**
     * A seek session for a walk that started before its chain locked: the
     * walk keeps its SEEK_MODE marker and gains the row now. Only for a walk
     * still unfinished and without a row, so a replay changes nothing.
     *
     * @return true when this call wrote the row.
     */
    suspend fun attachSeekSession(session: SeekSessionEntity): Boolean = database.withTransaction {
        val walk = walkDao.getById(session.walkId)
        if (walk == null || walk.endTimestamp != null) return@withTransaction false
        if (database.seekDao().getSession(session.walkId) != null) return@withTransaction false
        database.seekDao().insertSession(session)
        true
    }

    /**
     * Arrival's compare-and-set for a seek walk in `:tracker` (plan U25): on
     * a walk still unfinished, flips the clearing at [activeIndex] to
     * arrived once, and only then writes SEEK_ARRIVAL and, given a [place],
     * the reserved waypoint labelled by [label] with its ordinal (iOS
     * `recordSeekArrival`, `ActiveWalkViewModel+Seek.swift:179-190@c1745e8`:
     * event, then waypoint). The ordinal counts arrivals already persisted,
     * as the UI process's orchestrator counts them.
     *
     * @return true only when this call flipped the phase.
     */
    suspend fun recordSeekArrival(
        walkId: Long,
        activeIndex: Int,
        eventAt: Long,
        place: Pair<Double, Double>?,
        label: (ordinal: Int) -> String,
    ): Boolean = database.withTransaction {
        val walk = walkDao.getById(walkId)
        if (walk == null || walk.endTimestamp != null) return@withTransaction false
        if (database.seekDao().recordArrival(walkId, activeIndex, eventAt) != 1) return@withTransaction false
        walkEventDao.insert(WalkEvent(walkId = walkId, timestamp = eventAt, eventType = WalkEventType.SEEK_ARRIVAL))
        if (place != null) {
            val ordinal = SeekPersistence.arrivalOrdinal(waypointDao.getForWalk(walkId).map { it.icon })
            waypointDao.insert(
                Waypoint(
                    walkId = walkId,
                    timestamp = eventAt,
                    latitude = place.first,
                    longitude = place.second,
                    label = label(ordinal),
                    icon = SeekPersistence.ARRIVAL_WAYPOINT_ICON,
                ),
            )
        }
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

    suspend fun deleteWalk(walk: Walk) = deleteWalkById(walk.id)

    /**
     * The one walk-delete path: [deleteWalk], this, and the discard's
     * PurgeWalk all end here, and [stripArchivedWalk] applies the same
     * Honor rule to a walk whose row stays.
     *
     * Deletes the walk row by id. All child rows in route_data_samples,
     * altitude_samples, walk_events, activity_intervals, waypoints,
     * voice_recordings, walk_photos, seek_sessions, and the live Honor tables are removed
     * via SQLite `ON DELETE CASCADE`; any staged own-walk Way goes after
     * the commit. The walk's Ways-store link file and its Honor marker are
     * kept: both are keyed by the walk's uuid, and iOS drops a link only
     * when its Way is deleted (parity spec A §23), so a walked shared Way
     * stays walked for the expiry sweep. No-op when the id matches no row.
     *
     * Recording uuids are captured INSIDE the transaction, before the
     * cascade tears the rows down — otherwise there is nothing left to
     * read once the delete commits. Threads context cleanup runs AFTER
     * the transaction commits, mirroring iOS's capture-then-tombstone
     * ordering (DAT-33/DAT-17): a context write must never be attempted
     * for a recording whose walk turned out not to exist.
     */
    suspend fun deleteWalkById(walkId: Long) {
        val removed = database.withTransaction {
            val walk = walkDao.getById(walkId) ?: return@withTransaction null
            val recordingUuids = voiceRecordingDao.getForWalk(walkId).map { it.uuid }
            walkDao.deleteById(walkId)
            walk.uuid to recordingUuids
        } ?: return
        val (walkUuid, recordingUuids) = removed
        if (recordingUuids.isNotEmpty()) {
            transcriptContextStore?.delete(recordingUuids)
        }
        discardHonorStaging(walkUuid)
    }

    /**
     * The `.pilgrim` archive strip of one walk: its heavy children, its seek
     * session, and its live Honor rows go, while the row with its surface stats, its link
     * file, and its Honor marker stay. The children are iOS's
     * (`PilgrimPackageImporter.swift:457-464@7c200bf`). Joins the caller's
     * transaction; once that commits, the caller runs [discardHonorStaging]
     * for the walk. [keepLiveHonorRows] keeps the live rows (and the
     * caller keeps the staging) of a walk whose Honor step is still
     * pending, so the next launch's retry still has what it links.
     *
     * A failed delete propagates. Catching it would not keep the others:
     * each DAO call is a nested transaction, which on framework SQLite
     * dooms the whole batch without throwing, so the caller would go on to
     * discard staging and mark walks archived that had rolled back.
     */
    suspend fun stripArchivedWalk(walkId: Long, keepLiveHonorRows: Boolean = false) {
        routeDao.deleteByWalkId(walkId)
        waypointDao.deleteByWalkId(walkId)
        walkEventDao.deleteByWalkId(walkId)
        activityIntervalDao.deleteByWalkId(walkId)
        voiceRecordingDao.deleteByWalkId(walkId)
        walkPhotoDao.deleteByWalkId(walkId)
        database.seekDao().deleteSession(walkId)
        if (!keepLiveHonorRows) database.honorDao().deleteLiveRows(walkId)
    }

    /**
     * Removes the own-walk Way staged for [walkUuid], if any. Only after the
     * walk's removal has committed: files don't roll back.
     */
    suspend fun discardHonorStaging(walkUuid: String) {
        val store = wayStore ?: return
        withContext(Dispatchers.IO) { store.discardStaged(walkUuid) }
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

    /**
     * The walk's `activity_intervals` rows — never its sittings. Sittings
     * live in `walk_events` and are read through
     * [org.walktalkmeditate.pilgrim.data.walk.deriveActivityIntervals]
     * (#223); these rows only carry an imported walk's non-meditation
     * activities back out to `.pilgrim` export.
     */
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

    private companion object {
        const val TAG = "WalkRepository"
    }
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
