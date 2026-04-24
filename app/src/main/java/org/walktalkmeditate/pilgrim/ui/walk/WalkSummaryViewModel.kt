// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.core.celestial.LightReading
import org.walktalkmeditate.pilgrim.data.PhotoPinRef
import org.walktalkmeditate.pilgrim.data.UnpinPhotoResult
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.photo.PhotoAnalysisScheduler
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestone
import org.walktalkmeditate.pilgrim.ui.goshuin.GoshuinMilestones
import org.walktalkmeditate.pilgrim.ui.goshuin.WalkMilestoneInput
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository
import org.walktalkmeditate.pilgrim.ui.walk.reliquary.MAX_PINS_PER_WALK

/**
 * Three-state load for the summary screen: the VM's [summary] flow
 * starts at [Loading], resolves to [Loaded] when the walk row + samples
 * + events land, or [NotFound] if the walk row is missing (deleted or
 * never existed for this id). Replaces the previous nullable pattern
 * where "loading" and "gone" were indistinguishable.
 */
sealed class WalkSummaryUiState {
    data object Loading : WalkSummaryUiState()
    data class Loaded(val summary: WalkSummary) : WalkSummaryUiState()
    data object NotFound : WalkSummaryUiState()
}

/**
 * `@Immutable` because `WalkSummary` contains [Walk] — a Room entity
 * from an external module that the Compose compiler can't infer as
 * stable, even though all of `Walk`'s fields are primitives + `String?`.
 * Without this annotation Compose marks the whole class Unstable and
 * `WalkSummaryScreen`'s child composables that take a [WalkSummary]
 * skip-check-fail on every hemisphere-flow re-emission, causing
 * needless recomposition. Same lesson as Stage 4-C `GoshuinSeal`.
 */
@Immutable
data class WalkSummary(
    val walk: Walk,
    val totalElapsedMillis: Long,
    val activeWalkingMillis: Long,
    val totalPausedMillis: Long,
    val totalMeditatedMillis: Long,
    val distanceMeters: Double,
    val paceSecondsPerKm: Double?,
    val waypointCount: Int,
    val routePoints: List<LocationPoint>,
    /**
     * Pre-built goshuin seal spec for the Stage 4-B reveal animation.
     * [SealSpec.ink] is [Color.Transparent] here — the composable
     * resolves the real seasonal-shifted color via
     * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine]
     * in `@Composable` context (theme reads can't happen in a VM).
     */
    val sealSpec: SealSpec,
    /**
     * Stage 4-D: highest-precedence milestone for the current walk
     * across the user's entire history (or `null` when no milestone
     * applies). Detected via [GoshuinMilestones.detect] in
     * [WalkSummaryViewModel.buildState] using the same shared pure
     * function the goshuin grid uses, so the visual halo on the grid
     * cell and the celebratory haptic on the reveal overlay are always
     * in sync. The Stage 4-B reveal overlay reads this through its
     * `isMilestone` flag and fires a 2-pulse haptic + 0.5s extra hold.
     */
    val milestone: GoshuinMilestone? = null,
    /**
     * Stage 6-B: Light Reading aggregate for this walk — moon phase,
     * sun times (if GPS was available), planetary hour, and a
     * deterministic koan. Null iff [LightReading.from] threw
     * (unreachable in production since Room's autoGenerate gives
     * walkId >= 1; guarded via `runCatching` for defensive logging).
     * When null, the card simply doesn't render.
     */
    val lightReading: LightReading? = null,
)

@HiltViewModel
class WalkSummaryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val playback: VoicePlaybackController,
    private val sweeper: OrphanRecordingSweeper,
    private val photoAnalysisScheduler: PhotoAnalysisScheduler,
    hemisphereRepository: HemisphereRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walkId: Long = requireNotNull(savedStateHandle.get<Long>(ARG_WALK_ID)) {
        "walkId argument missing from nav savedStateHandle"
    }

    /**
     * Proxied from [HemisphereRepository] so the summary screen can
     * resolve the seal's seasonal tint. Separate from [state] so a
     * rare hemisphere flip doesn't force a re-emission of the full
     * [WalkSummaryUiState.Loaded] payload.
     */
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    val state: StateFlow<WalkSummaryUiState> = flow {
        emit(buildState())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WalkSummaryUiState.Loading,
    )

    /**
     * Live list of voice recordings for this walk. Backed by a Room
     * Flow so transcription updates from Stage 2-D's worker land in
     * the UI without a manual refresh.
     */
    val recordings: StateFlow<List<VoiceRecording>> =
        repository.observeVoiceRecordings(walkId).stateIn(
            scope = viewModelScope,
            // WhileSubscribed (not Eagerly) so unit tests that don't
            // subscribe don't leave a never-completing collector running
            // in viewModelScope — runTest waits on it forever otherwise.
            // The UI's collectAsStateWithLifecycle is a real subscriber,
            // so production behavior is unchanged.
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    val playbackUiState: StateFlow<PlaybackUiState> = playback.state
        .map { it.toUi() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = PlaybackUiState.IDLE,
        )

    /**
     * Stage 7-A: live list of photos pinned to this walk. Mirrors
     * [recordings] — `WhileSubscribed` so unit tests that don't observe
     * don't strand a collector. Compose UI subscribes via
     * `collectAsStateWithLifecycle`; there is no nav-observer path that
     * needs `Eagerly` here (the nav-ping-pong risk from Stage 5-F
     * applies only to flows driving nav decisions).
     */
    val pinnedPhotos: StateFlow<List<WalkPhoto>> =
        repository.observePhotosFor(walkId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    /**
     * Best-effort cleanup for the displayed walk: handles orphan WAVs,
     * dangling rows, zombie rows from mid-capture kills, and late-
     * arriving auto-stop rows that need transcription rescheduling.
     * Triggered from [WalkSummaryScreen]'s LaunchedEffect on first
     * composition. Per-case errors are logged inside the sweeper.
     *
     * Public (rather than init-block) so unit tests don't unconditionally
     * fire the sweep — Room observation under runTest was hanging when
     * the sweep raced against test-scope coroutine tracking.
     */
    fun runStartupSweep() {
        // Dispatchers.IO: the sweeper does Files.list, Files.delete,
        // and Files.newByteChannel reads. On budget hardware under
        // battery saver these can block for tens of ms — running on
        // viewModelScope's default Main dispatcher would ANR.
        // CoroutineWorker's doWork already runs on Dispatchers.IO so
        // the daily-worker path is fine; only this on-init path needed
        // the explicit hop.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                sweeper.sweep(walkId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                // Sweep is best-effort cleanup; surfacing the error to
                // the UI would obscure the walk summary content. Log
                // and continue.
                android.util.Log.w(TAG, "runStartupSweep failed for walk $walkId", t)
            }
        }
        // Stage 7-B: rendezvous any pending photo analysis. Cheap —
        // the scheduler's KEEP policy dedups if a worker is already
        // running, and the runner returns immediately when nothing is
        // pending. Covers the process-death-mid-analysis path.
        photoAnalysisScheduler.scheduleForWalk(walkId)
    }

    fun playRecording(recording: VoiceRecording) = playback.play(recording)
    fun pausePlayback() = playback.pause()
    fun stopPlayback() = playback.stop()

    /**
     * Stage 7-A: commit a batch of photo-picker URIs as pins for this
     * walk. Runs on [Dispatchers.IO] because it touches ContentResolver
     * (persistable URI grant + `DATE_TAKEN` cursor) and Room. All rows
     * share the same `pinnedAt` wall-clock so they sort together and
     * arrive as a single grid diff.
     *
     * Clipping to the cap is performed by the repo under the SAME Room
     * transaction that inserts — so concurrent pinPhotos calls cannot
     * collectively exceed [MAX_PINS_PER_WALK] (e.g., the double-pick
     * race where the user backs out of one picker and opens another
     * before the first batch's StateFlow emission lands). The VM's
     * only job here is assembling the refs and stamping one pinnedAt.
     */
    fun pinPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // Pre-clip optimistically against the current StateFlow
            // snapshot so we only request a persistable grant on URIs
            // likely to land. The repo's transactional clip is still
            // authoritative — this pre-clip just keeps the per-app
            // persistable-grant quota (~512 on current Android) from
            // being chewed up by URIs the repo will drop.
            //
            // Also dedup by URI inside the batch: some OEM pickers
            // (and SAF fallback) occasionally return the same URI
            // twice when the user multi-selects from "recents". Our
            // schema's unique index is on the auto-generated uuid,
            // not (walk_id, photo_uri), so duplicates would land as
            // distinct rows and waste cap slots.
            val current = pinnedPhotos.value
            val alreadyPinned = current.mapTo(mutableSetOf()) { it.photoUri }
            val seenThisBatch = mutableSetOf<String>()
            val remaining = (MAX_PINS_PER_WALK - current.size).coerceAtLeast(0)
            val unique = uris.filter { candidate ->
                val key = candidate.toString()
                key !in alreadyPinned && seenThisBatch.add(key)
            }
            val optimistic = if (unique.size > remaining) {
                unique.take(remaining)
            } else {
                unique
            }
            if (optimistic.isEmpty()) return@launch
            val pinnedAt = System.currentTimeMillis()
            val refs = optimistic.map { uri ->
                // Hold a persistable read grant so the URI survives
                // process death. Safe to swallow SecurityException
                // here: some OEM pickers (or SAF-fallback on API
                // 28-29 without Play Services) reject the call; the
                // ephemeral grant still works for the current
                // process, and the row will insert either way. We
                // simply lose cross-boot durability on those devices
                // — verify during device QA.
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.onFailure {
                    android.util.Log.w(
                        TAG,
                        "takePersistableUriPermission failed for $uri",
                        it,
                    )
                }
                PhotoPinRef(uri = uri.toString(), takenAt = readDateTaken(uri))
            }
            val result = try {
                repository.pinPhotos(
                    walkId = walkId,
                    refs = refs,
                    pinnedAt = pinnedAt,
                    cap = MAX_PINS_PER_WALK,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "pinPhotos failed for walk $walkId", t)
                return@launch
            }
            // Release grants for URIs the repo's transactional clip
            // dropped (e.g. because a concurrent pinPhotos committed
            // between our pre-clip and the repo's count). The grant
            // was taken above; without this, the URI would leak out
            // of the app-wide persistable-grant quota with no row
            // and therefore no unpin path to release it. The repo
            // has already confirmed no other walk references these
            // URIs, so releasing is safe.
            result.droppedOrphanUris.forEach { orphan ->
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(orphan),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.onFailure {
                    android.util.Log.w(
                        TAG,
                        "releasePersistableUriPermission (orphan clip) failed for $orphan",
                        it,
                    )
                }
            }
            // Stage 7-B: kick off on-device ML Kit labeling for the
            // freshly inserted rows. Per-walk batch (not per-photo) —
            // the runner iterates the pending list, so one call
            // covers the whole batch even when N > 1 photos landed.
            // KEEP policy + the runner's pending filter make this a
            // no-op if a worker is already running for this walk.
            if (result.insertedIds.isNotEmpty()) {
                photoAnalysisScheduler.scheduleForWalk(walkId)
            }
        }
    }

    /**
     * Stage 7-A: drop a pin. Idempotent on the repo side — calling
     * with an already-deleted id is a no-op.
     *
     * Releases the URI's persistable read grant ONLY when the deleted
     * row was the last reference to that URI across all walks.
     * `takePersistableUriPermission` is idempotent — pinning the same
     * URI to walk A and walk B yields a single app-wide grant — so
     * releasing on every unpin would tombstone the other walk's tile
     * on cold start. The repo's [UnpinPhotoResult.wasLastReference]
     * flag is computed inside the same transaction that deletes the
     * row, so the reference count is consistent.
     *
     * `releasePersistableUriPermission` on a URI we never persisted
     * (e.g. because `takePersistableUriPermission` threw at pin time)
     * raises SecurityException; swallow via runCatching. The worst
     * case of a missed release is one grant leaked, not user-visible.
     */
    fun unpinPhoto(photo: WalkPhoto) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                repository.unpinPhoto(photo.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "unpinPhoto failed id=${photo.id}", t)
                return@launch
            }
            if (result is UnpinPhotoResult.Removed && result.wasLastReference) {
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(
                        Uri.parse(result.photoUri),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }.onFailure {
                    android.util.Log.w(
                        TAG,
                        "releasePersistableUriPermission failed for ${result.photoUri}",
                        it,
                    )
                }
            }
        }
    }

    /**
     * Best-effort `DATE_TAKEN` read off a picker URI. Returns null when
     * the query yields no row, the column is missing, or the URI scheme
     * doesn't support metadata queries (SAF-fallback URIs on older
     * devices). The caller stores the result as [WalkPhoto.takenAt] —
     * nullability is a first-class outcome, not an error.
     */
    private fun readDateTaken(uri: Uri): Long? = runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DATE_TAKEN),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val col = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                if (col >= 0 && !cursor.isNull(col)) cursor.getLong(col) else null
            } else {
                null
            }
        }
    }.getOrNull()

    override fun onCleared() {
        // Stop, don't release: VoicePlaybackController is @Singleton and
        // outlives this ViewModel (matches WhisperCppEngine's Stage 2-D
        // pattern). A previous design called release() here, which set
        // player = null and would race with a subsequent VM's play()
        // posted to the same main looper. Stop just halts current
        // playback; the next VM finds the player ready to use.
        playback.stop()
        super.onCleared()
    }

    private suspend fun buildState(): WalkSummaryUiState {
        val walk = repository.getWalk(walkId) ?: return WalkSummaryUiState.NotFound
        // Production never navigates to WalkSummary for an unfinished
        // walk — ActiveWalkScreen's `onFinished` callback only fires on
        // WalkState.Finished, which requires `endTimestamp` to be set.
        // But defend against a rogue call (deep link, test harness,
        // direct repository manipulation): without an endTimestamp the
        // seal spec's `requireNotNull(endTimestamp)` would throw and
        // the state flow would propagate the exception. Treat unfinished
        // walks as Not Found instead.
        if (walk.endTimestamp == null) return WalkSummaryUiState.NotFound
        val samples = repository.locationSamplesFor(walkId)
        val events = repository.eventsFor(walkId)
        val waypoints = repository.waypointsFor(walkId)

        val points = samples.map {
            LocationPoint(
                timestamp = it.timestamp,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
        val distance = walkDistanceMeters(points)
        // Close dangling PAUSED/MEDITATION_START intervals at the walk's
        // end timestamp — the reducer folds them into the in-memory
        // accumulator on Finish but does not persist synthetic close
        // events, so the replay would otherwise undercount pause and
        // meditation time (and overcount active walking).
        val totals = replayWalkEventTotals(events = events, closeAt = walk.endTimestamp)
        val totalElapsed = (walk.endTimestamp ?: walk.startTimestamp) - walk.startTimestamp
        val activeWalking = (totalElapsed - totals.totalPausedMillis - totals.totalMeditatedMillis)
            .coerceAtLeast(0)

        val distanceKm = distance / 1_000.0
        val pace = if (distanceKm >= 0.01 && activeWalking >= 1_000L) {
            (activeWalking / 1_000.0) / distanceKm
        } else {
            null
        }

        val distanceLabel = WalkFormat.distanceLabel(distance)
        val sealSpec = walk.toSealSpec(
            // Reuse the haversine sum computed above — `toSealSpec`
            // takes the distance directly so both the seal's center
            // text and the summary stats share a single source of
            // truth (future accuracy-filter changes apply uniformly).
            distanceMeters = distance,
            // Placeholder — resolved to a seasonal tint in the
            // @Composable layer where LocalPilgrimColors is available.
            ink = Color.Transparent,
            displayDistance = distanceLabel.value,
            unitLabel = distanceLabel.unit,
        )

        // Stage 4-D: detect milestone for THIS walk against the user's
        // entire finished-walk history. Reuses the cached `distance`
        // for the current walk so we don't re-haversine the samples
        // we just iterated; other walks pay one `locationSamplesFor`
        // per row. Same N+1 cost as `GoshuinViewModel`; acceptable
        // here because milestone detection is a once-per-summary-load
        // computation, not a hot path.
        val milestone = detectMilestoneFor(walk, distance)

        // Stage 6-B: compute Light Reading. Pure, deterministic from
        // walkId + startedAt + first GPS location. `runCatching` is
        // defense-in-depth — LightReading.from requires walkId > 0,
        // which Room's autoGenerate guarantees. On failure we log
        // and leave lightReading = null; the card simply doesn't
        // render. Uses device `ZoneId.systemDefault()` at render time
        // (documented iOS-parity limitation).
        val firstLocation = points.firstOrNull()
        val lightReading = runCatching {
            LightReading.from(
                walkId = walkId,
                startedAtEpochMs = walk.startTimestamp,
                location = firstLocation,
                zoneId = ZoneId.systemDefault(),
            )
        }.onFailure {
            android.util.Log.w(TAG, "LightReading.from failed for walk $walkId", it)
        }.getOrNull()

        return WalkSummaryUiState.Loaded(
            WalkSummary(
                walk = walk,
                totalElapsedMillis = totalElapsed,
                activeWalkingMillis = activeWalking,
                totalPausedMillis = totals.totalPausedMillis,
                totalMeditatedMillis = totals.totalMeditatedMillis,
                distanceMeters = distance,
                paceSecondsPerKm = pace,
                waypointCount = waypoints.size,
                routePoints = points,
                sealSpec = sealSpec,
                milestone = milestone,
                lightReading = lightReading,
            ),
        )
    }

    private suspend fun detectMilestoneFor(
        currentWalk: Walk,
        currentDistance: Double,
    ): GoshuinMilestone? {
        val finished = repository.allWalks()
            .filter { it.endTimestamp != null }
            .sortedWith(
                compareByDescending<Walk> { it.endTimestamp }
                    .thenByDescending { it.id },
            )
        if (finished.isEmpty()) return null
        val inputs = finished.map { walk ->
            val d = if (walk.id == currentWalk.id) {
                currentDistance
            } else {
                walkDistanceMeters(
                    repository.locationSamplesFor(walk.id).map {
                        LocationPoint(
                            timestamp = it.timestamp,
                            latitude = it.latitude,
                            longitude = it.longitude,
                        )
                    },
                )
            }
            WalkMilestoneInput(
                walkId = walk.id,
                uuid = walk.uuid,
                startTimestamp = walk.startTimestamp,
                distanceMeters = d,
            )
        }
        val currentIndex = finished.indexOfFirst { it.id == currentWalk.id }
        if (currentIndex < 0) return null
        return GoshuinMilestones.detect(
            walkIndex = currentIndex,
            walk = inputs[currentIndex],
            allFinished = inputs,
            hemisphere = hemisphere.value,
        )
    }

    companion object {
        const val ARG_WALK_ID = "walkId"
        private const val SUBSCRIBER_GRACE_MS = 5_000L
        private const val TAG = "WalkSummaryViewModel"
    }
}

data class PlaybackUiState(
    val playingRecordingId: Long?,
    val isPlaying: Boolean,
    val errorMessage: String?,
) {
    companion object {
        val IDLE = PlaybackUiState(playingRecordingId = null, isPlaying = false, errorMessage = null)
    }
}

internal fun PlaybackState.toUi(): PlaybackUiState = when (this) {
    is PlaybackState.Idle -> PlaybackUiState.IDLE
    is PlaybackState.Playing -> PlaybackUiState(recordingId, isPlaying = true, errorMessage = null)
    is PlaybackState.Paused -> PlaybackUiState(recordingId, isPlaying = false, errorMessage = null)
    is PlaybackState.Error -> PlaybackUiState(recordingId, isPlaying = false, errorMessage = message)
}
