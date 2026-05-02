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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.core.celestial.LightReading
import org.walktalkmeditate.pilgrim.data.PhotoPinRef
import org.walktalkmeditate.pilgrim.data.UnpinPhotoResult
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.entity.ActivityInterval
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon
import org.walktalkmeditate.pilgrim.data.entity.WalkPhoto
import org.walktalkmeditate.pilgrim.data.photo.PhotoAnalysisScheduler
import org.walktalkmeditate.pilgrim.di.PersistenceScope
import org.walktalkmeditate.pilgrim.data.walk.RouteSegment
import org.walktalkmeditate.pilgrim.data.walk.WalkMapAnnotation
import org.walktalkmeditate.pilgrim.data.walk.computeAscend
import org.walktalkmeditate.pilgrim.data.walk.computeRouteSegments
import org.walktalkmeditate.pilgrim.data.walk.computeWalkMapAnnotations
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.ui.design.seals.SealSpec
import org.walktalkmeditate.pilgrim.ui.design.seals.toSealSpec
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiBitmapRenderer
import org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiCacheSweeper
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiFilename
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiGallerySaver
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiPngWriter
import org.walktalkmeditate.pilgrim.ui.etegami.share.EtegamiShareIntentFactory
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
    val talkMillis: Long,
    val activeMillis: Long,
    val ascendMeters: Double,
    val routePoints: List<LocationPoint>,
    /**
     * Stage 13-B: per-activity classified slices of [routePoints] for
     * the segment-tinted polyline on the Walk Summary map. One segment
     * per contiguous run of `Walking` / `Talking` / `Meditating`;
     * boundary points are duplicated across adjacent segments so the
     * rendered polylines connect seamlessly. Empty when fewer than 2
     * GPS samples landed (single point can't draw a polyline).
     */
    val routeSegments: List<RouteSegment> = emptyList(),
    /**
     * Stage 13-D: pin set for the Walk Summary map (start, end,
     * meditation, voice-recording markers). Built once in the VM via
     * [computeWalkMapAnnotations] so the screen + share-sheet renderers
     * share a single source of truth. Empty when the route has no GPS
     * samples (single-sample walks still get a Start pin only).
     */
    val walkAnnotations: List<WalkMapAnnotation> = emptyList(),
    /**
     * Stage 13-C: voice recordings for this walk, ordered by repository
     * default. Consumed by the activity timeline bar (talk segments) and
     * the activity list card.
     */
    val voiceRecordings: List<VoiceRecording> = emptyList(),
    /**
     * Stage 13-C: meditation intervals only — `activityIntervalsFor`
     * filtered to [ActivityType.MEDITATING] in the VM so consumers don't
     * each repeat the filter. Talk segments come from [voiceRecordings];
     * walking is implicit (the bar's background fill).
     */
    val meditationIntervals: List<ActivityInterval> = emptyList(),
    /**
     * Stage 13-C: raw GPS samples for the pace sparkline. Speed values
     * are nullable per-sample; the sparkline helper filters and buckets
     * them. Same data the route polyline derives from — stored here so
     * the timeline card doesn't need its own repo dependency.
     */
    val routeSamples: List<RouteDataSample> = emptyList(),
    /**
     * Stage 13-F: barometric altitude samples for the post-walk
     * elevation sparkline. The composable normalizes + buckets these
     * via [org.walktalkmeditate.pilgrim.ui.walk.summary.computeElevationSparklinePoints]
     * and self-gates render when fewer than 6 samples or `max - min
     * <= 1m` (iOS parity, `WalkSummaryView.swift:288`) — caller invokes
     * ElevationProfile unconditionally and a no-op return emits no node.
     */
    val altitudeSamples: List<AltitudeSample> = emptyList(),
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
    /**
     * Stage 7-C: pre-composed inputs for the etegami postcard
     * renderer — route, seal, moon phase, stats, intention/notes
     * text, activity markers. Null iff `composeEtegamiSpec` threw
     * (not expected today but guarded via `runCatching` for
     * defensive logging). Renderer lives in `WalkEtegamiCard` which
     * calls `EtegamiBitmapRenderer.render(spec, context)` inside a
     * `produceState` on first composition.
     */
    val etegamiSpec: org.walktalkmeditate.pilgrim.ui.etegami.EtegamiSpec? = null,
)

@HiltViewModel
class WalkSummaryViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WalkRepository,
    private val playback: VoicePlaybackController,
    private val sweeper: OrphanRecordingSweeper,
    private val photoAnalysisScheduler: PhotoAnalysisScheduler,
    hemisphereRepository: HemisphereRepository,
    private val cachedShareStore: CachedShareStore,
    unitsPreferences: UnitsPreferencesRepository,
    private val practicePreferences: PracticePreferencesRepository,
    @PersistenceScope private val persistenceScope: CoroutineScope,
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

    /**
     * Stage 10-C: passthrough of the units preference. The summary
     * stats card and stats sheet read this to format distance / pace
     * in the user's chosen unit system. The seal artwork itself
     * (built once into [WalkSummary.sealSpec]) currently bakes the
     * metric label at generation time — see TODO in [buildState].
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    /**
     * Stage 13-E: VM-level favicon selection. Seeded from the persisted
     * `walk.favicon` column inside [buildState] and updated optimistically
     * by [setFavicon] (revert on DAO failure). Separate from the loaded
     * summary payload so a user tap flips the UI without re-emitting the
     * full [WalkSummaryUiState.Loaded] (the favicon button reads this
     * StateFlow directly).
     *
     * Grouped above [state] for readability — by the time `state`'s
     * launched `buildState()` runs, all VM fields are initialized
     * regardless of declaration order.
     */
    private val _selectedFavicon = MutableStateFlow<WalkFavicon?>(null)
    val selectedFavicon: StateFlow<WalkFavicon?> = _selectedFavicon.asStateFlow()

    val state: StateFlow<WalkSummaryUiState> = flow {
        emit(buildState())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = WalkSummaryUiState.Loading,
    )

    /**
     * Live-gated light reading: combines the loaded summary's stored
     * [LightReading] with the practice preference. Toggling
     * Celestial awareness ON/OFF in Settings flips this immediately,
     * so the post-walk Light Reading card appears/disappears without
     * the user navigating away and back. iOS computes the snapshot
     * conditionally at walk-finish; we always compute it (cheap) and
     * gate at display time so the toggle is observable.
     */
    val lightReadingDisplay: StateFlow<LightReading?> =
        kotlinx.coroutines.flow.combine(
            state,
            practicePreferences.celestialAwarenessEnabled,
        ) { s, enabled ->
            if (s is WalkSummaryUiState.Loaded && enabled) s.summary.lightReading else null
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    /**
     * Stage 8-A: observer for the per-walk cached journey-share. Drives
     * the Fresh / Active / Expired state of [WalkShareJourneyRow] on
     * the summary. `flatMapLatest` re-opens the DataStore observer
     * only when the Loaded state first arrives (one-shot
     * transition). `Eagerly` is safe here because the flow is
     * trivially cheap and the UI always subscribes when on summary.
     */
    @kotlin.OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val cachedShareFlow: StateFlow<CachedShare?> = state
        .flatMapLatest { s ->
            if (s is WalkSummaryUiState.Loaded) {
                cachedShareStore.observe(s.summary.walk.uuid)
            } else {
                kotlinx.coroutines.flow.flowOf(null)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
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
        // pending. Covers the process-death-mid-analysis path. Wrapped
        // to mirror the WalkViewModel + OrphanRecordingSweeper pattern
        // so a future WorkRequest.build() regression (e.g., mistakenly
        // adding an incompatible constraint) doesn't crash the
        // LaunchedEffect that triggered the sweep.
        try {
            photoAnalysisScheduler.scheduleForWalk(walkId)
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "photo analysis schedule failed for walk $walkId", t)
        }
    }

    fun playRecording(recording: VoiceRecording) = playback.play(recording)
    fun pausePlayback() = playback.pause()
    fun stopPlayback() = playback.stop()

    /**
     * Stage 7-A: commit a batch of photo-picker URIs as pins for this
     * walk. Runs on [persistenceScope] (process-lifetime, IO dispatcher
     * under the hood) so the URI grant + Room insert survive the user
     * immediately tapping Done after picking — viewModelScope would
     * cancel mid-write. Touches ContentResolver (persistable URI grant
     * + `DATE_TAKEN` cursor) and Room. All rows share the same `pinnedAt` wall-clock so
     * they sort together and arrive as a single grid diff.
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
        persistenceScope.launch {
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
                try {
                    photoAnalysisScheduler.scheduleForWalk(walkId)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w(
                        TAG,
                        "photo analysis schedule failed for walk $walkId",
                        t,
                    )
                }
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
     *
     * Runs on [persistenceScope] so the Room delete + URI release
     * survive viewModelScope cancellation when the user immediately
     * back-navs after tapping unpin.
     */
    fun unpinPhoto(photo: WalkPhoto) {
        persistenceScope.launch {
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
     * Stage 13-E: persist a favicon selection. Optimistic — flips the
     * StateFlow immediately, writes through to Room on
     * [persistenceScope] (process-lifetime, IO dispatcher) so the
     * write survives the user tapping a favicon and immediately tapping
     * Done — `viewModelScope.launch` would cancel mid-write within the
     * ~1ms window between optimistic flip + DAO call landing, losing
     * the user's choice. Reverts the StateFlow on DAO failure.
     * Tapping the same value deselects (writes null).
     *
     * The revert uses [MutableStateFlow.compareAndSet] so a failure
     * landing AFTER a newer tap has already updated the flow does NOT
     * clobber the user's latest choice. Out-of-order fire-and-forget
     * launches can still cause "last-tap wins on UI, last-IO wins on
     * DB" divergence under sustained rapid tapping; acceptable for a
     * mood-tag surface where serialization-by-mutex would add latency
     * for no perceptible benefit.
     */
    fun setFavicon(favicon: WalkFavicon?) {
        val current = _selectedFavicon.value
        val newValue = if (favicon == current) null else favicon
        _selectedFavicon.value = newValue
        persistenceScope.launch {
            try {
                repository.setFavicon(walkId, newValue?.rawValue)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.e(TAG, "setFavicon failed for walk $walkId", t)
                _selectedFavicon.compareAndSet(newValue, current)
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
        // Stage 13-E: seed the favicon StateFlow from the persisted
        // column so the selector reflects prior user choice on load.
        _selectedFavicon.value = WalkFavicon.fromRawValue(walk.favicon)
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

        // TODO(stage 10-Z): Goshuin seal artwork stays metric for now —
        // the seal is treated as a permanent record of the walk; re-
        // rendering on a unit-toggle is a separate concern (cached
        // bitmaps, share-sheet asset reuse). The surrounding card text
        // (e.g. WalkSummaryScreen's `walk_stat_distance` row) DOES flip
        // with [distanceUnits].
        val distanceLabel = WalkFormat.distanceLabel(distance, UnitSystem.Metric)
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
        //
        // Stage 10-C: ALWAYS compute the lightReading; the celestial-
        // awareness gate is applied at the SCREEN level via the
        // [lightReadingDisplay] flow (combine of summary + pref). This
        // way the user can toggle the pref while the summary is open
        // and the card appears/disappears immediately, rather than
        // being frozen at summary-load time.
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

        // Stage 13-A: hoisted repo reads — `voiceRecordings` and
        // `altitudeSamples` are consumed by both the new top-level
        // summary fields below and the etegami-spec composition further
        // down. The etegami runCatching block reuses these locals by
        // name rather than re-reading the repo.
        // Stage 13-B: `activityIntervals` joins the same hoisted set —
        // both the route-segments classifier (top-level field) and the
        // etegami spec consume it.
        val voiceRecordings = repository.voiceRecordingsFor(walkId)
        val altitudeSamples = repository.altitudeSamplesFor(walkId)
        val activityIntervals = repository.activityIntervalsFor(walkId)

        // Stage 13-B: classify each GPS sample's activity (walking /
        // talking / meditating) and group into contiguous segments for
        // the segment-tinted polyline. Pure function — see
        // [computeRouteSegments] for the priority rules.
        //
        // Stage 13-D: same Default-dispatcher hop also computes the
        // map annotation pin set (start / end / meditation / voice
        // recording). Pair-return keeps both pure computations on a
        // single dispatcher hop instead of two.
        //
        // Hopped to Dispatchers.Default because `buildState()` runs on
        // viewModelScope's Main dispatcher (SharingStarted.Eagerly).
        // Worst-case 90-min walk @ 1Hz GPS + 100 voice recordings is
        // ~5400 samples × ~120 predicate evals = ~640K compares — tens
        // of ms on mid-range hardware, enough to trip ANR thresholds
        // when stacked with the other Main-thread work in this build.
        val (routeSegments, walkAnnotations) = withContext(Dispatchers.Default) {
            val seg = computeRouteSegments(
                samples = samples,
                intervals = activityIntervals,
                recordings = voiceRecordings,
            )
            // Pre-filter to MEDITATING per the function's parameter contract.
            // The function has a defensive `continue` on non-MEDITATING types
            // but relying on that as load-bearing means the contract drifts
            // and a future refactor (e.g. when a new ActivityType gains a
            // pin) would silently start drawing walking-interval pins.
            val ann = computeWalkMapAnnotations(
                routeSamples = samples,
                meditationIntervals = activityIntervals.filter {
                    it.activityType == ActivityType.MEDITATING
                },
                voiceRecordings = voiceRecordings,
            )
            seg to ann
        }

        val talkMillis = voiceRecordings.sumOf { it.durationMillis }
        // Stage 13-A: paused-excluded, meditation-included. Mirrors the
        // iOS hero stat `walk.activeDuration`. Distinct from
        // [activeWalkingMillis] above which also excludes meditation
        // (used by the Walk card stats).
        val activeMillis = (totalElapsed - totals.totalPausedMillis).coerceAtLeast(0L)
        val ascendMeters = computeAscend(altitudeSamples)

        // Stage 7-C: compose the etegami spec. Pulls altitude samples
        // + activity intervals + voice recordings from the repo to
        // assemble elevation gain + activity markers. Sealed behind
        // runCatching so any unexpected data-shape issue (e.g. a
        // future schema change) degrades to "no etegami" rather than
        // breaking Walk Summary entirely.
        //
        // Stage 10-C TODO: like the seal artwork, the etegami spec
        // bakes the unit-system-dependent stats text at summary-load
        // time. If the user toggles Metric ↔ Imperial in Settings
        // after opening the summary, the rendered postcard at share
        // time still shows the load-time units. Re-rendering on
        // toggle requires reloading the underlying altitude /
        // activity / voice samples and rebuilding the spec at share-
        // time, which crosses a boundary the share flow doesn't
        // currently span. Defer to the future "live re-render of
        // generated artifacts" stage that also handles the seal.
        val etegamiSpec = runCatching {
            org.walktalkmeditate.pilgrim.ui.etegami.composeEtegamiSpec(
                walk = walk,
                routePoints = points,
                sealSpec = sealSpec,
                lightReading = lightReading,
                distanceMeters = distance,
                durationMillis = totalElapsed,
                altitudeSamples = altitudeSamples,
                activityIntervals = activityIntervals,
                voiceRecordings = voiceRecordings,
                units = distanceUnits.value,
                zoneId = ZoneId.systemDefault(),
            )
        }.onFailure {
            android.util.Log.w(TAG, "composeEtegamiSpec failed for walk $walkId", it)
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
                talkMillis = talkMillis,
                activeMillis = activeMillis,
                ascendMeters = ascendMeters,
                routePoints = points,
                routeSegments = routeSegments,
                walkAnnotations = walkAnnotations,
                voiceRecordings = voiceRecordings,
                meditationIntervals = activityIntervals.filter {
                    it.activityType == ActivityType.MEDITATING
                },
                routeSamples = samples,
                altitudeSamples = altitudeSamples,
                sealSpec = sealSpec,
                milestone = milestone,
                lightReading = lightReading,
                etegamiSpec = etegamiSpec,
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
                meditateDurationMillis = (walk.meditationSeconds ?: 0L) * 1000L,
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

    // --- Stage 7-D: etegami share + save ---------------------------------

    /**
     * Guards share+save concurrency via a single mutex. A double-tap
     * on Share while a save is in-flight (or vice versa) becomes a
     * no-op — whichever action is currently running finishes first.
     */
    private val etegamiShareMutex = Mutex()

    /**
     * Which etegami action is currently in-flight, if any. Used by
     * the Composable row so only the tapped button shows a spinner
     * (the other button stays disabled-idle). `null` when no action
     * is running.
     */
    private val _etegamiBusy = MutableStateFlow<EtegamiBusyAction?>(null)
    val etegamiBusy: StateFlow<EtegamiBusyAction?> = _etegamiBusy.asStateFlow()

    enum class EtegamiBusyAction { Share, Save }

    private val _etegamiEvents = MutableSharedFlow<EtegamiShareEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val etegamiEvents: SharedFlow<EtegamiShareEvent> = _etegamiEvents.asSharedFlow()

    sealed interface EtegamiShareEvent {
        data class DispatchShare(val chooser: Intent) : EtegamiShareEvent
        data object SaveSucceeded : EtegamiShareEvent
        data object SaveFailed : EtegamiShareEvent
        data object ShareFailed : EtegamiShareEvent
        data object SaveNeedsPermission : EtegamiShareEvent
    }

    fun shareEtegami(spec: EtegamiSpec) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!etegamiShareMutex.tryLock()) return@launch
            _etegamiBusy.value = EtegamiBusyAction.Share
            try {
                EtegamiCacheSweeper.sweepStale(context)
                val filename = EtegamiFilename.forWalk(spec.startedAtEpochMs)
                val bitmap = EtegamiBitmapRenderer.render(spec, context)
                try {
                    val file = EtegamiPngWriter.writeToCache(bitmap, filename, context)
                    val chooser = EtegamiShareIntentFactory.buildFromFile(
                        context,
                        file,
                        context.getString(R.string.etegami_share_chooser_title),
                    )
                    _etegamiEvents.tryEmit(EtegamiShareEvent.DispatchShare(chooser))
                } finally {
                    // Recycle after the writer completed its compress
                    // read (Stage 7-B Bitmap-lifecycle pattern). On a
                    // cancellation between render and writer return, the
                    // bitmap is abandoned to GC rather than recycled
                    // while compress may still hold a ref.
                    bitmap.recycle()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "shareEtegami failed", t)
                _etegamiEvents.tryEmit(EtegamiShareEvent.ShareFailed)
            } finally {
                _etegamiBusy.value = null
                etegamiShareMutex.unlock()
            }
        }
    }

    /**
     * Surface the "Save needs permission" snackbar after the user
     * denies the API-28-only WRITE_EXTERNAL_STORAGE prompt. Called
     * from the Composable row — keeps the VM as the single source of
     * truth for etegami UI events.
     */
    fun notifyEtegamiSaveNeedsPermission() {
        _etegamiEvents.tryEmit(EtegamiShareEvent.SaveNeedsPermission)
    }

    fun saveEtegamiToGallery(spec: EtegamiSpec) {
        viewModelScope.launch(Dispatchers.Default) {
            if (!etegamiShareMutex.tryLock()) return@launch
            _etegamiBusy.value = EtegamiBusyAction.Save
            try {
                val filename = EtegamiFilename.forWalk(spec.startedAtEpochMs)
                val bitmap = EtegamiBitmapRenderer.render(spec, context)
                try {
                    val ev = when (val result = EtegamiGallerySaver.saveToGallery(bitmap, filename, context)) {
                        is EtegamiGallerySaver.SaveResult.Success -> EtegamiShareEvent.SaveSucceeded
                        is EtegamiGallerySaver.SaveResult.NeedsPermission -> EtegamiShareEvent.SaveNeedsPermission
                        is EtegamiGallerySaver.SaveResult.Failed -> {
                            android.util.Log.w(TAG, "saveEtegamiToGallery failed", result.cause)
                            EtegamiShareEvent.SaveFailed
                        }
                    }
                    _etegamiEvents.tryEmit(ev)
                } finally {
                    bitmap.recycle()
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "saveEtegamiToGallery failed", t)
                _etegamiEvents.tryEmit(EtegamiShareEvent.SaveFailed)
            } finally {
                _etegamiBusy.value = null
                etegamiShareMutex.unlock()
            }
        }
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
