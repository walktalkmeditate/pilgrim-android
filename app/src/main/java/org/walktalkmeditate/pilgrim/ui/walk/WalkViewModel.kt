// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.practice.PracticePreferencesRepository
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.weather.WeatherFetching
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.domain.WalkStats
import org.walktalkmeditate.pilgrim.service.WalkTrackingService
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * UI snapshot that combines the authoritative [WalkState] from
 * [WalkController] with a wall-clock reading so the Active Walk screen's
 * timer + pace can re-render every second even when no new location
 * sample has arrived.
 */
/**
 * `@Immutable` because [walkState] is a sealed type whose subclasses
 * carry reference-typed [org.walktalkmeditate.pilgrim.domain.Walk]
 * payloads. Without the annotation Compose infers the field as
 * Unstable and consumers skip-fail their recompose checks. Same
 * lesson as Stage 4-D `WalkSummary` (data classes carrying
 * cross-module reference types).
 */
@androidx.compose.runtime.Immutable
data class WalkUiState(
    val walkState: WalkState,
    val nowMillis: Long,
) {
    val totalElapsedMillis: Long get() = WalkStats.totalElapsedMillis(walkState, nowMillis)
    val activeWalkingMillis: Long get() = WalkStats.activeWalkingMillis(walkState, nowMillis)
    val distanceMeters: Double get() = WalkStats.distanceMeters(walkState)
    val paceSecondsPerKm: Double? get() = WalkStats.averagePaceSecondsPerKm(walkState, nowMillis)
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WalkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val controller: WalkController,
    private val repository: WalkRepository,
    private val clock: Clock,
    private val voiceRecorder: VoiceRecorder,
    private val locationSource: LocationSource,
    private val walkRecoveryRepository:
        org.walktalkmeditate.pilgrim.data.recovery.WalkRecoveryRepository,
    unitsPreferences: UnitsPreferencesRepository,
    private val practicePreferences: PracticePreferencesRepository,
    private val weatherFetching: WeatherFetching,
    collectiveStats: org.walktalkmeditate.pilgrim.data.collective.CollectiveStatsSource,
    private val soundsPreferences:
        org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository,
    private val whisperService:
        org.walktalkmeditate.pilgrim.data.whisper.WhisperService,
    private val cairnService:
        org.walktalkmeditate.pilgrim.data.cairn.CairnService,
    private val whisperManifestService:
        org.walktalkmeditate.pilgrim.data.whisper.WhisperManifestService,
    private val geoCacheService:
        org.walktalkmeditate.pilgrim.data.proximity.GeoCacheService,
    private val proximityService:
        org.walktalkmeditate.pilgrim.data.proximity.ProximityDetectionService,
    private val whisperPlayer:
        org.walktalkmeditate.pilgrim.data.whisper.WhisperPlayer,
    private val stonePlayer:
        org.walktalkmeditate.pilgrim.data.cairn.StonePlayer,
    private val intentionHistory:
        org.walktalkmeditate.pilgrim.data.intention.IntentionHistoryRepository,
    private val voiceGuidePauseController:
        org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePauseController,
) : ViewModel() {

    /**
     * iOS parity `ActiveWalkViewModel.swift:weatherSnapshot@db4196e`.
     * Set after `fetchAndPersistWeather` succeeds; cleared on every
     * terminal transition by the controller-state observer. Drives
     * the `WeatherGreetingOverlay` on the active-walk screen — when
     * this goes non-null AND status is Active, the greeting fades in
     * for 3.5s then fades out. Declared HERE (above the init block
     * that mutates it) so property-initialization order is correct;
     * a later declaration would have the init's `viewModelScope
     * .launch { controller.state.collect { ... _activeWeather.value =
     * null } }` see a null `_activeWeather` field at first emission
     * (compose Stage 2-E init-block-ordering trap, replayed).
     */
    private val _activeWeather =
        MutableStateFlow<org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot?>(null)
    val activeWeather: StateFlow<org.walktalkmeditate.pilgrim.data.weather.WeatherSnapshot?> =
        _activeWeather.asStateFlow()

    /**
     * iOS parity `ActiveWalkView.swift:735-764@db4196e` — celestial
     * greeting computed once per walk-start (based on the walk's
     * start timestamp + system zone + tropical zodiac). The overlay
     * schedules its own 5s pre-delay before fading in; this flow
     * just provides the text payload.
     *
     * Cleared on terminal transitions by the controller-state
     * observer so a back-to-back walk gets a fresh computation.
     */
    private val _activeCelestialGreeting = MutableStateFlow<String?>(null)
    val activeCelestialGreeting: StateFlow<String?> = _activeCelestialGreeting.asStateFlow()

    /**
     * Structured celestial snapshot for the active walk — drives the
     * map-corner CelestialVignette (planetary-hour planet symbol + moon
     * sign glyph). Distinct from [activeCelestialGreeting] (the prose
     * line). Computed alongside the greeting at walk start; cleared on
     * the terminal transition. iOS parity `CelestialVignetteView`.
     */
    private val _activeCelestialSnapshot =
        MutableStateFlow<org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot?>(null)
    val activeCelestialSnapshot:
        StateFlow<org.walktalkmeditate.pilgrim.core.celestial.CelestialSnapshot?> =
        _activeCelestialSnapshot.asStateFlow()

    /**
     * iOS parity `ActiveWalkViewModel.swift:44-46@db4196e` — per-walk
     * placement caps. Reset to zero on every Idle/Finished →
     * subsequent-Active transition by the controller-state observer.
     *
     *  - whispersPlacedThisWalk: max [WHISPER_PER_WALK_CAP] (7)
     *  - stonePlacedThisWalk: max 1 (boolean)
     *
     * Caller (ActiveWalkScreen) gates row enablement on the derived
     * [canPlaceWhisper] / [canPlaceStone] StateFlows below uiState.
     */
    private val _whispersPlacedThisWalk = MutableStateFlow(0)
    val whispersPlacedThisWalk: StateFlow<Int> = _whispersPlacedThisWalk.asStateFlow()

    private val _stonePlacedThisWalk = MutableStateFlow(false)
    val stonePlacedThisWalk: StateFlow<Boolean> = _stonePlacedThisWalk.asStateFlow()

    /**
     * One-shot UI events for whisper + stone placement results. The
     * sheet host on [ActiveWalkScreen] collects these to drive the
     * haptic-on-success / banner-on-failure ordering iOS specifies.
     */
    private val _placementEvents = MutableSharedFlow<PlacementEvent>(extraBufferCapacity = 4)
    val placementEvents: SharedFlow<PlacementEvent> = _placementEvents.asSharedFlow()

    /**
     * iOS parity `ActiveWalkView.swift:handleProximityEvent@db4196e`.
     * Filters the raw event stream to entered-only (matches iOS UI
     * which silently drops `.exited` events) and maps to a
     * UI-friendly [ProximityNotification] enriched with the cached
     * cairn's stoneCount for tier-based banner copy.
     */
    val proximityNotifications:
        SharedFlow<org.walktalkmeditate.pilgrim.ui.walk.ProximityNotification> =
        proximityService.events
            .filter {
                it.direction == org.walktalkmeditate.pilgrim.data.proximity
                    .ProximityEvent.Direction.Entered
            }
            .map { event ->
                when (event.target.type) {
                    org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget.Type.Whisper ->
                        org.walktalkmeditate.pilgrim.ui.walk.ProximityNotification.Whisper
                    org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget.Type.Cairn -> {
                        val cacheId = event.target.id.removePrefix("cairn-")
                        val cairn = geoCacheService.cairns.value
                            .firstOrNull { it.id == cacheId }
                        org.walktalkmeditate.pilgrim.ui.walk.ProximityNotification.Cairn(
                            tier = cairn?.tier
                                ?: org.walktalkmeditate.pilgrim.data.cairn.CairnTier.Faint,
                            stoneCount = cairn?.stoneCount ?: 1,
                        )
                    }
                }
            }
            .shareIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS))

    /**
     * iOS parity `ActiveWalkView.swift:574-659@db4196e` — proximity pin
     * list, already filtered (2000m radius + 30 cap + 15m same-type
     * separation). PilgrimMap consumes this directly. Updates on every
     * geocache emission AND every controller state change (location
     * delta drives a re-filter).
     */
    val proximityPins:
        StateFlow<List<org.walktalkmeditate.pilgrim.ui.walk.ProximityPinFilter.Pin>> =
        combine(controller.state, geoCacheService.whispers, geoCacheService.cairns) {
                state, whispers, cairns ->
            val location = state.activeOrPausedWalk()?.lastLocation
                ?: return@combine emptyList()
            org.walktalkmeditate.pilgrim.ui.walk.ProximityPinFilter.build(
                whispers = whispers,
                cairns = cairns,
                userLatitude = location.latitude,
                userLongitude = location.longitude,
            )
            // Structural-equal lists collapse — `data class Pin` provides
            // content equality, so the operator drops a re-emission when
            // the filtered set is unchanged. The filter still RECOMPUTES
            // on every location tick (cheap haversine over <= 30 items);
            // we just avoid downstream bitmap+annotation churn when the
            // pin set is stable. Reviewer-flagged.
        }.distinctUntilChanged().stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            emptyList(),
        )

    /**
     * iOS parity `ActiveWalkView.swift:895-906` —
     * `nearestCachedCairn(within: 42)`. The closest cached cairn to
     * the current walk's last location, or null if none within 42m.
     * Drives the StonePlacementSheet branch (existing-cairn copy +
     * stone count vs new-cairn copy).
     */
    val nearbyCairn: StateFlow<org.walktalkmeditate.pilgrim.data.cairn.CachedCairn?> =
        combine(controller.state, geoCacheService.cairns) { state, cairns ->
            val location = state.activeOrPausedWalk()?.lastLocation ?: return@combine null
            cairns
                .mapNotNull { c ->
                    val out = FloatArray(1)
                    android.location.Location.distanceBetween(
                        location.latitude, location.longitude,
                        c.latitude, c.longitude, out,
                    )
                    val dist = out[0].toDouble()
                    if (dist <= NEAREST_CAIRN_RADIUS_M) c to dist else null
                }
                .minByOrNull { it.second }
                ?.first
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            null,
        )

    /**
     * iOS parity `WalkStartView.swift:164-168@db4196e` —
     * `CollectiveCounterService.$stats.walkedInLastHour`. When true,
     * the breathing logo on Path picks up a 1.2s scale+shadow pulse to
     * signal that someone else is walking right now. Mapped from
     * [CollectiveRepository.stats] via `walkedInLastHour()`. Nullable
     * stats (cold start, no fetch yet) collapse to false.
     */
    val collectivePulseActive: StateFlow<Boolean> = collectiveStats.stats
        .map { it?.walkedInLastHour() == true }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = false,
        )

    /**
     * Stage 10-C: passthrough of the units preference. ActiveWalkScreen
     * reads this and feeds it to [WalkStatsSheet] for live distance /
     * pace formatting.
     */
    val distanceUnits: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    /**
     * Stage 10-C: passthrough of the "Begin with Intention" practice
     * preference. ActiveWalkScreen observes this together with the
     * walk state + current intention to auto-prompt the intention
     * dialog 0.5s after entering Active when the pref is on AND no
     * intention has been set yet (mirrors iOS
     * `ActiveWalkView.swift:374`).
     */
    val beginWithIntention: StateFlow<Boolean> = practicePreferences.beginWithIntention

    /**
     * Gates the map-corner CelestialVignette — iOS only shows the
     * planetary-hour / moon-sign pill when the user has opted into
     * celestial awareness (`UserPreferences.celestialAwarenessEnabled`).
     */
    val celestialAwarenessEnabled: StateFlow<Boolean> =
        practicePreferences.celestialAwarenessEnabled

    /**
     * Id of a walk that was auto-finalized by `WalkTrackingService.onTaskRemoved`
     * (user swiped the app away from recents while a walk was in progress).
     * Path screen renders the recovery banner while non-null and clears via
     * [dismissRecovery] after the banner auto-times-out.
     */
    val recoveredWalkId: StateFlow<Long?> = walkRecoveryRepository.recoveredWalkId

    fun dismissRecovery() {
        viewModelScope.launch { walkRecoveryRepository.clearRecovered() }
    }

    val uiState: StateFlow<WalkUiState> = combine(
        controller.state,
        tickerFlow(TICK_INTERVAL_MS),
    ) { walkState, _ ->
        WalkUiState(walkState = walkState, nowMillis = clock.now())
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
        initialValue = WalkUiState(WalkState.Idle, clock.now()),
    )

    /**
     * iOS parity `ActiveWalkViewModel.swift:50-53@db4196e`. Derived
     * unlock predicates from the in-progress walk's active-walking
     * milliseconds. Recomputed on every [uiState] emission (1 Hz
     * tick), so the unlock flips visibly the second the threshold is
     * hit.
     *
     *  - `isWhisperUnlocked` at [WHISPER_UNLOCK_SECONDS] (7 minutes)
     *  - `isStoneUnlocked`   at [STONE_UNLOCK_SECONDS]   (12 minutes)
     *
     * `canPlaceX` folds in the per-walk cap so the UI single-source-of
     * truth is the canPlace flag.
     */
    val isWhisperUnlocked: StateFlow<Boolean> = uiState
        .map { it.activeWalkingMillis / 1000L >= WHISPER_UNLOCK_SECONDS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), false)

    val isStoneUnlocked: StateFlow<Boolean> = uiState
        .map { it.activeWalkingMillis / 1000L >= STONE_UNLOCK_SECONDS }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), false)

    val canPlaceWhisper: StateFlow<Boolean> = combine(
        isWhisperUnlocked,
        _whispersPlacedThisWalk,
    ) { unlocked, placed -> unlocked && placed < WHISPER_PER_WALK_CAP }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), false)

    val canPlaceStone: StateFlow<Boolean> = combine(
        isStoneUnlocked,
        _stonePlacedThisWalk,
    ) { unlocked, placed -> unlocked && !placed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), false)

    /**
     * Raw walk-state passthrough for navigation observers.
     * Intentionally NOT routed through [uiState]'s WhileSubscribed
     * stateIn — during a long meditation, ActiveWalk's composition
     * is disposed by NavHost, uiState loses its sole subscriber,
     * the 5s grace expires, upstream unsubscribes, and uiState.value
     * freezes at the stale Meditating snapshot. When the user taps
     * Done, MeditationScreen pops → ActiveWalk re-composes → reads
     * stale uiState.value (Meditating) → fires `onEnterMeditation()`
     * → loops back into MeditationScreen. Device QA caught this
     * with meditations >5s. Using `controller.state` (Singleton,
     * always hot) bypasses the stale-cache trap.
     */
    val walkState: StateFlow<WalkState> = controller.state

    /**
     * iOS parity `ActiveWalkViewModel.steps` (`ActiveWalkViewModel
     * .swift:179-181@db4196e`) — live step count for the in-progress
     * walk, rendered in the WalkStatsSheet Steps column. Direct
     * passthrough of the @Singleton controller's hot StateFlow (no
     * `WhileSubscribed` re-wrap — Stage 5-G stale-cache trap, same
     * rationale as [walkState] / [voiceGuidePackName]). Null until the
     * first sensor sample, or permanently when the step sensor /
     * ACTIVITY_RECOGNITION permission is unavailable, in which case the
     * sheet falls back to the "—" dash.
     */
    val steps: StateFlow<Int?> = controller.liveSteps

    /**
     * Live ascent (meters of elevation gained) for the active walk.
     * Observes `altitude_samples` cross-process: the `:tracker`
     * process inserts samples via the [WalkEffect.PersistLocation]
     * effect; multi-instance Room invalidation re-emits in the UI
     * process so the stats sheet's Ascent column recomputes on every
     * new sample.
     *
     * Null when no active walk OR no samples yet OR cumulative
     * ascent is at-or-below the 1 m display threshold (iOS parity
     * `walk.ascend > 1` gate). The sheet renders "—" for null and
     * the formatted altitude otherwise.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ascendMeters: StateFlow<Double?> = walkState
        .map { walkIdOrNull(it) }
        .distinctUntilChanged()
        .flatMapLatest { walkId ->
            if (walkId == null) {
                flowOf(null)
            } else {
                repository.observeAltitudeSamples(walkId).map { samples ->
                    val ascend = org.walktalkmeditate.pilgrim.data.walk.computeAscend(samples)
                    ascend.takeIf { it > 1.0 }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * iOS parity `ActiveWalkViewModel.voiceGuidePackName` /
     * `voiceGuideManagement.isPaused` (`ActiveWalkView.swift:433-443`).
     * Direct passthrough of the @Singleton orchestrator's hot
     * `MutableStateFlow.asStateFlow()` flows — no `WhileSubscribed`
     * re-wrapping (Stage 5-G stale-cache trap: a re-wrapped flow that
     * loses subscribers during a long composition pause would freeze
     * the indicator at a stale value). [voiceGuidePackName] is non-null
     * only while a guide scheduler is running, gating the in-walk
     * play/pause control's visibility. [isVoiceGuidePaused] drives the
     * play-vs-pause icon.
     */
    val voiceGuidePackName: StateFlow<String?> = voiceGuidePauseController.activePackName
    val isVoiceGuidePaused: StateFlow<Boolean> = voiceGuidePauseController.isPaused

    /**
     * Toggle the in-walk voice guide between paused and resumed. iOS
     * parity `ActiveWalkView.swift:436-441` — the bottom-left audio
     * indicator flips `voiceGuideManagement.pauseGuide()` /
     * `resumeGuide()`. Non-suspend: the orchestrator's pause/resume
     * are synchronous flag flips (+ a player stop on pause), safe from
     * the Compose click handler.
     */
    fun toggleVoiceGuidePause() {
        if (voiceGuidePauseController.isPaused.value) {
            voiceGuidePauseController.resume()
        } else {
            voiceGuidePauseController.pause()
        }
    }

    /**
     * Live polyline for the Active Walk map. Observes Room's route
     * sample table for the current walk's id and maps to domain
     * [LocationPoint]s. Emits an empty list while no walk is in progress.
     *
     * Maps state → walkId first and applies distinctUntilChanged: Active
     * → Active emissions (triggered by every LocationSampled updating the
     * accumulator) would otherwise cancel and re-subscribe the DAO flow
     * on every GPS fix, which is wasteful on long walks.
     */
    val routePoints: StateFlow<List<LocationPoint>> = controller.state
        .map { walkIdOrNull(it) }
        .distinctUntilChanged()
        .flatMapLatest { walkId ->
            if (walkId == null) {
                flowOf(emptyList())
            } else {
                repository.observeLocationSamples(walkId).map { samples ->
                    samples.map { sample ->
                        LocationPoint(
                            timestamp = sample.timestamp,
                            latitude = sample.latitude,
                            longitude = sample.longitude,
                            horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
                            speedMetersPerSecond = sample.speedMetersPerSecond,
                        )
                    }
                }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    /**
     * Recent walking pace (min/km, newest last; non-positive while
     * standing) for the ambient live sparkline. Port of iOS
     * `ActiveWalkViewModel.paceHistory` — derived from the same route
     * sample stream rather than maintained incrementally; mapping the
     * full sample list then capping to the last 60 yields the identical
     * window iOS produces by appending one entry per new sample.
     */
    val paceHistory: StateFlow<List<Double>> = routePoints
        .map { points ->
            // Bound BEFORE mapping: routePoints grows by one per GPS fix
            // on a 45-90 min walk, so mapping the full list every fix is
            // O(n) churn for a fixed-size sparkline. Take only the last
            // PACE_HISTORY_CAP speeds; livePaceHistory's own tail-cap
            // then leaves the output identical to mapping the whole list.
            val recent = points.takeLast(PACE_HISTORY_CAP).map { it.speedMetersPerSecond }
            livePaceHistory(recent)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    // ---- Voice recording (Stage 2-C) ----

    private val _voiceRecorderState = MutableStateFlow<VoiceRecorderUiState>(VoiceRecorderUiState.Idle)
    val voiceRecorderState: StateFlow<VoiceRecorderUiState> = _voiceRecorderState.asStateFlow()

    /**
     * Monotonic counter so that two equal-by-content errors (same
     * message + kind) emitted back-to-back still produce !=
     * VoiceRecorderUiState.Error instances. The Compose
     * `LaunchedEffect(error)` keys on the full state — without
     * different ids the auto-dismiss timer wouldn't reset for repeat
     * errors landing inside the dismiss window.
     *
     * AtomicLong because emit sites span both the main thread
     * (`emitPermissionDenied` from the Compose permission callback)
     * and `Dispatchers.IO` (start/stop failure paths). A plain
     * `++Long` read-modify-write would race and could collide ids.
     */
    private val errorIdCounter = AtomicLong(0L)
    private fun nextErrorId(): Long = errorIdCounter.incrementAndGet()

    private fun errorState(message: String, kind: VoiceRecorderUiState.Kind) =
        VoiceRecorderUiState.Error(message, kind, nextErrorId())

    /** Per-buffer RMS level published by VoiceRecorder. Normalized 0f..1f. */
    val audioLevel: StateFlow<Float> = voiceRecorder.audioLevel

    /**
     * One-shot last-known GPS fix to seed the Active Walk map's initial
     * camera so the first paint lands near the user rather than at
     * Mapbox Android's global default (which historically renders over
     * the US east coast). Populates asynchronously on VM init via
     * [LocationSource.lastKnownLocation]. Null until either the call
     * completes or the device has no cached fix.
     */
    private val _initialCameraCenter = MutableStateFlow<LocationPoint?>(null)
    val initialCameraCenter: StateFlow<LocationPoint?> = _initialCameraCenter.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _initialCameraCenter.value = seedLocation()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                // Seed lookup is best-effort; a failure just means we
                // fall through to Mapbox's default camera. Logging here
                // keeps the initialization path observable without
                // spamming the UI.
                Log.w(TAG, "initial camera seed lookup failed", t)
            }
        }
    }

    /**
     * Cascading fallback for the Active Walk map's initial camera, in
     * order of freshness:
     *
     *  1. [LocationSource.lastKnownLocation] — typically the most recent
     *     system-cached GPS fix (FusedLocationProvider).
     *  2. Most recent finished walk's LAST route sample — where the user
     *     was when they last finished a walk. Usually close to where
     *     they are now if they're walking from the same starting point.
     *  3. null — caller (PilgrimMap) leaves Mapbox's default camera.
     *
     * (A walk with zero route samples — service killed before any GPS
     * fix landed — falls through to 3; a separate "first sample"
     * fallback would behave identically because both queries are LIMIT 1
     * over the same row set.)
     */
    private suspend fun seedLocation(): LocationPoint? {
        locationSource.lastKnownLocation()?.let { return it }
        // LIMIT 1 SELECTs so a long history (thousands of walks, tens
        // of thousands of samples) doesn't slurp the whole dataset on
        // every cold app start for a one-point seed.
        val mostRecent = repository.mostRecentFinishedWalk() ?: return null
        val sample = repository.lastLocationSampleFor(mostRecent.id) ?: return null
        return LocationPoint(
            timestamp = sample.timestamp,
            latitude = sample.latitude,
            longitude = sample.longitude,
        )
    }

    /**
     * Single source for voice-recording rows. Both [recordingsCount] and
     * [talkMillis] derive from this flow so we open ONE Room subscription
     * even when both downstream consumers are active.
     *
     * Uses [SharingStarted.Eagerly] (not WhileSubscribed) because this
     * flow is `private` — the only consumers are the public downstream
     * [recordingsCount] and [talkMillis] derivations. A WhileSubscribed
     * intermediate would create a three-tier chain where this flow
     * silently caches a stale initial value if anything ever calls
     * `voiceRecordings.value` directly without first subscribing (the
     * Stage 5-F + 7-A trap: `.value` reads do NOT count as subscribers
     * for WhileSubscribed). Upstream cost is zero — `controller.state`
     * is a hot Singleton that's always live, and the Room flow only
     * activates when there's an active walkId (flatMapLatest → flowOf).
     */
    private val voiceRecordings: StateFlow<List<VoiceRecording>> = controller.state
        .map { walkIdOrNull(it) }
        .distinctUntilChanged()
        .flatMapLatest { walkId ->
            if (walkId == null) flowOf(emptyList())
            else repository.observeVoiceRecordings(walkId)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList(),
        )

    /**
     * Live count of VoiceRecording rows for the current walk. Derives
     * from [voiceRecordings] to share the upstream subscription with
     * [talkMillis].
     */
    val recordingsCount: StateFlow<Int> = voiceRecordings
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = 0,
        )

    /**
     * Live total voice-recording duration, summed across all rows for
     * the current walk. Drives the Talk time chip in the active-walk
     * sheet.
     */
    val talkMillis: StateFlow<Long> = voiceRecordings
        .map { rows -> rows.sumOf { it.durationMillis } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = 0L,
        )

    /**
     * Live count of Waypoint rows for the current walk. Drives the
     * Drop Waypoint subtitle in the WalkOptionsSheet.
     */
    val waypointCount: StateFlow<Int> = controller.state
        .map { walkIdOrNull(it) }
        .distinctUntilChanged()
        .flatMapLatest { walkId ->
            if (walkId == null) flowOf(0)
            else repository.observeWaypointCount(walkId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = 0,
        )

    /**
     * Live list of Waypoint rows for the current walk. Drives the
     * point annotations rendered on the Active Walk map.
     */
    val waypoints: StateFlow<List<org.walktalkmeditate.pilgrim.data.entity.Waypoint>> =
        controller.state
            .map { walkIdOrNull(it) }
            .distinctUntilChanged()
            .flatMapLatest { walkId ->
                if (walkId == null) flowOf(emptyList())
                else repository.observeWaypoints(walkId)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
                initialValue = emptyList(),
            )

    fun dropWaypoint(label: String? = null, icon: String? = null) {
        viewModelScope.launch { controller.recordWaypoint(label = label, icon = icon) }
    }

    /**
     * Bump-counter that lets [intention] re-read the Walk row after a
     * [setIntention] call. WalkAccumulator does not carry the intention
     * (Stage 9.5-C decision: avoid cascading the field through the
     * reducer + restore paths), so the controller's state flow does not
     * fire on intention writes. This counter changes synchronously after
     * each setIntention to retrigger the upstream re-read. Initial value
     * 0 also triggers the first read on subscribe.
     */
    private val intentionRefreshTick = MutableStateFlow(0L)

    /**
     * Currently-set intention for the active walk, or null when no
     * walk is in progress / no intention set. Re-reads the Walk row
     * whenever the active walkId changes OR setIntention bumps
     * [intentionRefreshTick]. Drives the WalkOptionsSheet subtitle.
     *
     * Uses [SharingStarted.Eagerly] (not WhileSubscribed) because
     * the IntentionSettingDialog can sit open longer than the
     * SUBSCRIBER_GRACE_MS window with no other subscriber. If the
     * upstream unsubscribes mid-edit, [setIntention]'s
     * [intentionRefreshTick] bump fires into a cold flow and the
     * emission is lost — when the dialog dismisses and ActiveWalk
     * re-subscribes, it gets the stale initial null. Same trap
     * pattern as Stage 5-F's `WhileSubscribed(5s)`-on-cold-consumer
     * regression.
     */
    val intention: StateFlow<String?> = combine(
        controller.state.map { walkIdOrNull(it) }.distinctUntilChanged(),
        intentionRefreshTick,
    ) { walkId, _ -> walkId }
        .flatMapLatest { walkId ->
            if (walkId == null) flowOf<String?>(null)
            else flow<String?> { emit(repository.getWalk(walkId)?.intention) }
        }
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = null,
        )

    fun setIntention(text: String) {
        viewModelScope.launch {
            controller.setIntention(text)
            intentionRefreshTick.update { it + 1L }
        }
    }

    /** Recent intentions (MRU-first) for the intention sheet's Recent list. */
    val recentIntentions: StateFlow<List<String>> = intentionHistory.intentions

    /** iOS `IntentionHistoryStore.add` — call BEFORE committing onSet. */
    fun rememberIntention(text: String) {
        viewModelScope.launch { intentionHistory.add(text) }
    }

    /**
     * Celestial intention suggestions, gated on celestial-awareness like
     * iOS (`IntentionSettingView` only shows the section when the pref
     * is on). Empty when off — the sheet then hides the section.
     */
    fun intentionSuggestions(): List<String> =
        if (!practicePreferences.celestialAwarenessEnabled.value) {
            emptyList()
        } else {
            IntentionSuggestions.celestial(
                atEpochMillis = System.currentTimeMillis(),
                system = practicePreferences.zodiacSystem.value,
            )
        }

    /**
     * Toggle recording on/off. Dispatches to IO because
     * VoiceRecorder.stop() blocks on doneLatch (~100 ms) while the
     * capture loop finishes its last buffer — never call directly from
     * a Compose click handler on the main looper.
     */
    fun toggleRecording() {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _voiceRecorderState.value
            if (current is VoiceRecorderUiState.Recording) {
                stopRecording()
            } else {
                startRecording()
            }
        }
    }

    /** Called by Compose when the mic-permission launcher returns denied. */
    fun emitPermissionDenied() {
        _voiceRecorderState.value = errorState(
            "microphone permission required to record",
            VoiceRecorderUiState.Kind.PermissionDenied,
        )
    }

    fun dismissRecorderError() {
        if (_voiceRecorderState.value is VoiceRecorderUiState.Error) {
            _voiceRecorderState.value = VoiceRecorderUiState.Idle
        }
    }

    private suspend fun startRecording() {
        val info = walkInfoOrNull() ?: return // walk ended between tap and dispatch
        val result = voiceRecorder.start(walkId = info.walkId, walkUuid = info.walkUuid)
        result.fold(
            onSuccess = { _voiceRecorderState.value = VoiceRecorderUiState.Recording },
            onFailure = { _voiceRecorderState.value = mapStartFailure(it) },
        )
    }

    private suspend fun stopRecording() {
        val result = voiceRecorder.stop()
        result.fold(
            onSuccess = { recording ->
                // If the insert fails we have a .wav on disk with no DB
                // row — Stage 2-E's sweeper cleans orphans. Surface the
                // failure to the user as a generic Other-kind banner.
                try {
                    repository.recordVoice(recording)
                    _voiceRecorderState.value = VoiceRecorderUiState.Idle
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (_: Exception) {
                    _voiceRecorderState.value = errorState(
                        "couldn't save the recording",
                        VoiceRecorderUiState.Kind.Other,
                    )
                }
            },
            onFailure = { _voiceRecorderState.value = mapStopFailure(it) },
        )
    }

    // NOTE: the design spec's error table maps ConcurrentRecording and
    // NoActiveRecording to Other-kind banners, but the implementation
    // silences them as harmless double-tap races (the first action
    // succeeded; the second is a UI race). Kept in sync with that
    // behavior here; spec table is the older intent.
    private fun mapStartFailure(err: Throwable): VoiceRecorderUiState = when (err) {
        is VoiceRecorderError.PermissionMissing -> errorState(
            "microphone permission required to record",
            VoiceRecorderUiState.Kind.PermissionDenied,
        )
        is VoiceRecorderError.AudioCaptureInitFailed -> errorState(
            "couldn't start the microphone",
            VoiceRecorderUiState.Kind.CaptureInitFailed,
        )
        is VoiceRecorderError.FileSystemError -> errorState(
            "couldn't save the recording",
            VoiceRecorderUiState.Kind.Other,
        )
        // ConcurrentRecording on start = a UI double-tap raced ahead of
        // the first start's state propagation. The first start succeeded;
        // surfacing a banner for the second tap is noise. Stay in
        // Recording (which the first start is about to set anyway).
        is VoiceRecorderError.ConcurrentRecording -> VoiceRecorderUiState.Recording
        else -> errorState(
            err.message ?: "recording failed",
            VoiceRecorderUiState.Kind.Other,
        )
    }

    private fun mapStopFailure(err: Throwable): VoiceRecorderUiState = when (err) {
        // EmptyRecording is "user tapped stop too fast" or a silent
        // background-kill. Either way, no banner — return to Idle.
        is VoiceRecorderError.EmptyRecording -> VoiceRecorderUiState.Idle
        // NoActiveRecording on stop = a UI double-tap raced ahead of
        // the first stop's completion. The first stop succeeded;
        // ignoring the second is the correct UX.
        is VoiceRecorderError.NoActiveRecording -> VoiceRecorderUiState.Idle
        else -> errorState(
            err.message ?: "stop failed",
            VoiceRecorderUiState.Kind.Other,
        )
    }

    private data class WalkInfo(val walkId: Long, val walkUuid: String)

    private suspend fun walkInfoOrNull(): WalkInfo? {
        val walkId = walkIdOrNull(controller.state.value) ?: return null
        val walk = repository.getWalk(walkId) ?: return null
        return WalkInfo(walkId = walkId, walkUuid = walk.uuid)
    }

    private fun walkIdOrNull(state: WalkState): Long? = when (state) {
        WalkState.Idle -> null
        is WalkState.Active -> state.walk.walkId
        is WalkState.Paused -> state.walk.walkId
        is WalkState.Meditating -> state.walk.walkId
        is WalkState.Finished -> state.walk.walkId
    }

    init {
        // The actual voice-recorder auto-stop on Finished now lives in
        // WalkFinalizationObserver (app-lifetime scope, can't be
        // cancelled by nav-pop). Here we only mirror the UI state to
        // Idle when the walk finishes — keeping the previous in-VM
        // toggleRecording() call would race the observer's stop and
        // re-introduce the cancellation bug the observer was added to
        // eliminate (see Stage 9-B's WalkFinalizationObserver kdoc).
        // Stage 9.5-C: WalkLifecycleObserver also stops the recorder on
        // Active→Idle (discard path), not just Active→Finished. Mirror the
        // VM UI state for both terminal transitions so the mic button
        // doesn't briefly render Recording after a discard.
        viewModelScope.launch {
            controller.state.collect { state ->
                if ((state is WalkState.Finished || state is WalkState.Idle) &&
                    _voiceRecorderState.value is VoiceRecorderUiState.Recording
                ) {
                    _voiceRecorderState.value = VoiceRecorderUiState.Idle
                }
                // Stage 12-A: cancel the +2s/+10s weather fetch on every
                // terminal transition. discardWalk also cancels
                // synchronously (see [discardWalk] kdoc); the cancel
                // here covers paths the VM doesn't initiate — finish
                // tapped from the foreground-service notification, or
                // a controller-driven rollback after a service-start
                // failure. weatherJob.cancel() is idempotent.
                if (state is WalkState.Finished || state is WalkState.Idle) {
                    weatherJob?.cancel()
                    _activeWeather.value = null
                    _activeCelestialGreeting.value = null
                    _activeCelestialSnapshot.value = null
                    // iOS parity `ActiveWalkViewModel.swift:44-46@db4196e`
                    // — caps reset at VM init (new walk). On Android the
                    // VM is scoped to NavBackStackEntry which persists
                    // across multiple walks within the same nav surface;
                    // the equivalent reset point is "leaving in-progress
                    // state." Resetting on Finished AND Idle (discard)
                    // covers both terminal paths.
                    _whispersPlacedThisWalk.value = 0
                    _stonePlacedThisWalk.value = false
                    // iOS parity `ActiveWalkViewModel.swift:210-228` —
                    // proximity teardown on walk end. `stopListening`
                    // cancels the location subscription AND clears the
                    // dedup set so the next walk starts fresh.
                    proximityService.stopListening()
                    geoCacheService.invalidateLastFetch()
                }
            }
        }
        // iOS parity `ActiveWalkViewModel.swift:bindProximity@db4196e`.
        // Pipe the active/paused walk's last-location through to the
        // proximity service. Sampled internally at 5s by the service.
        viewModelScope.launch {
            proximityService.bindToLocation(
                controller.state
                    .map { state ->
                        when (state) {
                            is WalkState.Active -> state.walk.lastLocation
                            is WalkState.Paused -> state.walk.lastLocation
                            else -> null
                        }
                    }
                    .map { loc ->
                        loc?.let {
                            android.location.Location("walk").apply {
                                latitude = it.latitude
                                longitude = it.longitude
                            }
                        }
                    },
            )
        }
        // iOS parity `ActiveWalkViewModel.swift:405-419` — 300s
        // throttle on geo cache re-fetch. The service's own 10km
        // threshold gates whether the fetch hits the network; the
        // 300s here just rate-limits the eligibility check.
        //
        // Manual timestamp throttle (NOT `Flow.sample`): `sample`
        // schedules a periodic ticker via `fixedPeriodTicker`, which
        // emits forever in virtual time and hangs `runTest`'s
        // `advanceUntilIdle`. CI timed out at 25min on the original
        // implementation. Manual timestamp gate has no scheduled
        // ticker — drains cleanly under both real + virtual time.
        viewModelScope.launch {
            var lastFetchAt = 0L
            controller.state
                .map { state ->
                    when (state) {
                        is WalkState.Active -> state.walk.lastLocation
                        is WalkState.Paused -> state.walk.lastLocation
                        else -> null
                    }
                }
                .filterNotNull()
                .collect { loc ->
                    val now = clock.now()
                    if (now - lastFetchAt < GEOCACHE_FETCH_THROTTLE_MS) return@collect
                    lastFetchAt = now
                    geoCacheService.fetchIfNeeded(loc.latitude, loc.longitude)
                }
        }
        // iOS parity `ActiveWalkView.swift:944-951@db4196e` — auto-play
        // a random placeable whisper from the encountered cache entry's
        // category on every proximity-entry event, gated on both
        // `autoPlayWhisperOnProximity` AND `soundsEnabled` prefs.
        // The banner + haptic fire regardless of these prefs (handled
        // upstream in `proximityNotifications`); only the AUDIO needs
        // the gate. Tap-on-pin is a separate path with no pref gate
        // — wired in ActiveWalkScreen.
        viewModelScope.launch {
            proximityService.events
                .filter {
                    it.direction == org.walktalkmeditate.pilgrim.data.proximity
                        .ProximityEvent.Direction.Entered
                }
                .collect { event ->
                    if (event.target.type != org.walktalkmeditate.pilgrim.data.proximity
                            .ProximityTarget.Type.Whisper) return@collect
                    if (!practicePreferences.autoPlayWhisperOnProximity.value) return@collect
                    if (!soundsPreferences.soundsEnabled.value) return@collect
                    val cacheId = event.target.id.removePrefix("whisper-")
                    val cached = geoCacheService.whispers.value
                        .firstOrNull { it.id == cacheId } ?: return@collect
                    val category = cached.resolvedCategory ?: return@collect
                    val definition = whisperManifestService.randomWhisper(category)
                        ?: return@collect
                    whisperPlayer.play(definition)
                }
        }
        // iOS parity `ActiveWalkViewModel.swift:421-427` — whenever
        // the geo cache emits new whispers or cairns, rebuild the
        // proximity target set. `notifiedTargetIDs` is independent
        // of `targets` so in-flight dedup state survives updates.
        viewModelScope.launch {
            combine(geoCacheService.whispers, geoCacheService.cairns) { whispers, cairns ->
                buildSet<
                    org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget,
                > {
                    whispers.forEach { w ->
                        add(
                            org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget(
                                id = org.walktalkmeditate.pilgrim.data.proximity
                                    .ProximityTarget.whisperId(w.id),
                                latitude = w.latitude,
                                longitude = w.longitude,
                                radius = org.walktalkmeditate.pilgrim.data.proximity
                                    .ProximityDetectionService.WHISPER_RADIUS_M,
                                type = org.walktalkmeditate.pilgrim.data.proximity
                                    .ProximityTarget.Type.Whisper,
                            ),
                        )
                    }
                    cairns.forEach { c ->
                        add(
                            org.walktalkmeditate.pilgrim.data.proximity.ProximityTarget(
                                id = org.walktalkmeditate.pilgrim.data.proximity
                                    .ProximityTarget.cairnId(c.id),
                                latitude = c.latitude,
                                longitude = c.longitude,
                                radius = org.walktalkmeditate.pilgrim.data.proximity
                                    .ProximityDetectionService.CAIRN_RADIUS_M,
                                type = org.walktalkmeditate.pilgrim.data.proximity
                                    .ProximityTarget.Type.Cairn,
                            ),
                        )
                    }
                }
            }.collect { proximityService.updateTargets(it) }
        }
    }

    fun startWalk(intention: String? = null) {
        viewModelScope.launch {
            // Two separate try blocks with different semantics. Catching
            // both in one block would let an IllegalStateException from
            // the controller trigger the service-start rollback, which
            // would finish a walk that's ALREADY running — effectively
            // cancelling a legitimate earlier startWalk call.
            val started = try {
                controller.startWalk(intention)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: IllegalStateException) {
                // Controller rejects start from non-Idle/non-Finished
                // state — usually a double-tap race where the first
                // startWalk already succeeded. Treat as a no-op and let
                // the first call's state transition drive the UI.
                Log.d(TAG, "startWalk ignored — controller is not idle: ${e.message}")
                return@launch
            }
            // Stage 12-A: schedule the +2s/+10s weather fetch as soon
            // as we have the new walkId. Runs independently of the
            // foreground-service start below — even if the service
            // refuses to start (rollback path), the +2s delay's
            // CancellationException from the rollback's transition
            // through Finished tears the weatherJob down before any
            // fetch is issued.
            scheduleWeatherFetch(started.id)
            // Compute celestial greeting text once per walk start. Cheap
            // (pure math); cleared on terminal transition by the
            // controller-state observer below.
            try {
                val snapshot = org.walktalkmeditate.pilgrim.core.celestial
                    .CelestialSnapshotCalc.snapshot(
                        atEpochMillis = started.startTimestamp,
                    )
                _activeCelestialGreeting.value =
                    celestialGreetingText(snapshot, context.resources)
                _activeCelestialSnapshot.value = snapshot
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Log.w(TAG, "celestial greeting compute failed", t)
            }
            // No explicit startForegroundService call here under the
            // `:tracker` process split — [UiWalkController.startWalk]
            // fires ACTION_START via [WalkActionPublisher] inside the
            // controller, which is the same channel notification-button
            // taps already use. The 5s await inside that call gives the
            // tracker process time to spin up + insert the walk row;
            // a timeout there bubbles as IllegalStateException which
            // the catch above handles identically to the same-process
            // start-rejection path.
        }
    }

    /**
     * Stage 12-A: schedule the +2s/+10s weather-fetch sequence. Cancels
     * any prior in-flight job before launching a fresh one (defends
     * against a back-to-back finish→start where a stale job from the
     * prior walk could otherwise keep running and write weather to the
     * wrong walkId — the cancel-on-Finished observer already catches
     * the common case, but defending here too is cheap).
     */
    private fun scheduleWeatherFetch(walkId: Long) {
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            delay(WEATHER_FIRST_DELAY_MS)
            if (!fetchAndPersistWeather(walkId)) {
                delay(WEATHER_RETRY_DELAY_MS)
                fetchAndPersistWeather(walkId)
            }
        }.also { job ->
            // KDoc claims the field is cleared on completion — actually
            // wire that up so a finished Job reference doesn't pin until
            // the VM is cleared.
            job.invokeOnCompletion { weatherJob = null }
        }
    }

    /**
     * Returns true when both the location seed AND the weather fetch
     * succeeded (and the row was persisted). Returns false on missing
     * seed location (no cached fix) or null snapshot (network/parse
     * failure) — caller may schedule a single retry per the iOS-faithful
     * +10s policy.
     *
     * Reads `lastKnownLocation()` rather than a live GPS subscription:
     * a fresh `requestLocationUpdates` round-trip would race the
     * fetch's +2s budget on cold device starts, and the spec
     * accepts "weather unavailable" as a valid first-call outcome
     * (which is what the +10s retry exists to recover from).
     */
    private suspend fun fetchAndPersistWeather(walkId: Long): Boolean {
        val location = try {
            locationSource.lastKnownLocation()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            // FINE_LOCATION may have been revoked between walk-start and
            // the +2s tick. Treat missing location the same as no
            // cached fix: signal failure so the +10s retry runs (in
            // case permission comes back) and don't crash the VM.
            Log.w(TAG, "weather fetch: location lookup failed", t)
            return false
        } ?: return false
        val snapshot = weatherFetching.fetchCurrent(location.latitude, location.longitude)
            ?: return false
        repository.updateWeather(walkId, snapshot)
        _activeWeather.value = snapshot
        return true
    }


    fun pauseWalk() {
        viewModelScope.launch { controller.pauseWalk() }
    }

    fun resumeWalk() {
        viewModelScope.launch { controller.resumeWalk() }
    }

    fun startMeditation() {
        viewModelScope.launch { controller.startMeditation() }
    }

    /**
     * @param endMillis explicit end timestamp from the UI. iOS parity
     *   `MeditationView.swift:609-615@db4196e` — pass the Done-tap
     *   millis so the closing ceremony's 6.5s playback doesn't
     *   inflate the recorded meditation interval. Default to
     *   `clock.now()` for non-ceremony paths.
     */
    fun endMeditation(endMillis: Long = clock.now()) {
        viewModelScope.launch { controller.endMeditation(endMillis) }
    }

    /**
     * UI-facing access to the same [Clock] the controller dispatches
     * against. The meditation closing ceremony captures Done-tap
     * millis via this so its end-timestamp is on the same time base
     * as the rest of the reducer (otherwise tests with a mocked clock
     * and the on-device wall clock would diverge — flagged in PR
     * review).
     */
    fun nowMillis(): Long = clock.now()

    /**
     * iOS parity `MeditationView.swift:559-568@db4196e` — write the
     * selected breath-rhythm id to settings DataStore. Same write path
     * that the Settings tab uses; `LocalBreathRhythm.current` updates
     * on next composition so MeditationScreen's `key(breathRhythm.id)`
     * triggers a fresh BreathingCircle cycle.
     */
    fun setBreathRhythm(id: Int) {
        viewModelScope.launch { soundsPreferences.setBreathRhythm(id) }
    }

    /**
     * CAS guard for double-tap dedup. The Finish button's enabled
     * state derives from `walkState`, which only flips to Finished
     * after `controller.finishWalk()` dispatches + Room finalizes —
     * the user has a multi-frame window to tap twice. Without this
     * guard, the second tap re-runs the entire finishWalk body. The
     * post-finish side-effects themselves now live in
     * [WalkFinalizationObserver], which has its own per-walkId dedup
     * — this CAS is purely about saving the second click from
     * needlessly going through the controller mutex + voice settle
     * wait. CAS to true on entry; we don't reset — finished is
     * finished, viewModel is scoped to nav, double-tap dedup lives
     * for the screen.
     */
    private val finishInFlight = AtomicBoolean(false)

    /**
     * Stage 12-A: handle for the +2s/+10s weather fetch coroutine
     * scheduled at walk-start. Cancelled at every terminal transition
     * so a slow Open-Meteo round-trip can't write weather columns
     * onto a discarded (deleted) row or onto a finished walk after
     * the summary screen has already painted. Cleared back to null
     * once the job completes so we don't pin a dead Job reference
     * for the VM's lifetime.
     *
     * Process death = lost fetch. The +2s/+10s timing window is
     * short enough that an OS kill mid-walk-start is rare; on
     * recovery (`restoreActiveWalk`) we deliberately do NOT
     * re-schedule because the captured weather wouldn't reflect
     * walk-start conditions any more.
     */
    private var weatherJob: Job? = null

    fun finishWalk() {
        if (!finishInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            // Voice auto-stop + finalize side-effects all live in
            // WalkFinalizationObserver, which subscribes to
            // controller.state on an app-lifetime scope (so neither
            // VM cancellation from the nav-pop nor the user closing
            // the app can wedge them). All this VM has to do is hand
            // off to the controller and let the observer take over.
            controller.finishWalk()
        }
    }

    /**
     * Stage 9.5-C: leave the walk without saving. The controller's
     * `discardWalk` reduces Active|Paused|Meditating → Idle and
     * cascade-deletes the walk row + samples + events via the
     * `PurgeWalk` effect. Voice auto-stop runs in
     * [org.walktalkmeditate.pilgrim.walk.WalkLifecycleObserver] on the
     * Active → Idle transition (app-lifetime scope, survives VM
     * nav-pop) and intentionally drops the recording row since its
     * parent walk no longer exists.
     */
    fun discardWalk() {
        // Stage 12-A: cancel the +2s/+10s weather fetch BEFORE the
        // discard fires. The state observer below also cancels on the
        // Active→Idle transition, but doing it here too closes the
        // window between this fun's call and the controller's
        // PurgeWalk effect committing — without this synchronous
        // cancel, a fetch in flight under its delay() could resume
        // post-purge and try to UPDATE a deleted walk row.
        weatherJob?.cancel()
        viewModelScope.launch { controller.discardWalk() }
    }

    /**
     * Restore a walk row left in Room when the process was killed
     * mid-walk. Returns the restored [Walk] if one existed, or null
     * when there's nothing to resume.
     */
    suspend fun restoreActiveWalk(): Walk? = controller.restoreActiveWalk()

    /**
     * iOS parity `ActiveWalkView.swift:911-919@db4196e` — tap-on-pin
     * plays a random placeable whisper from [category] at full volume.
     * Always fires (no `autoPlayWhisperOnProximity` gate); only
     * gated by `soundsEnabled` inside WhisperPlayer.
     */
    fun playRandomWhisperInCategory(
        category: org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory,
    ) {
        viewModelScope.launch {
            val def = whisperManifestService.randomWhisper(category) ?: run {
                // Lazy manifest fetch if not yet loaded.
                if (whisperManifestService.manifest.value == null) {
                    whisperManifestService.refresh()
                }
                whisperManifestService.randomWhisper(category)
            } ?: return@launch
            whisperPlayer.play(def)
        }
    }

    /**
     * iOS parity `WhisperPlacementSheet.swift:85-96@db4196e` — preview
     * button on a category row picks a random whisper from the
     * manifest (including non-placeable/retired) and plays at
     * preview volume.
     */
    fun previewWhisperCategory(
        category: org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory,
    ) {
        viewModelScope.launch {
            if (whisperManifestService.manifest.value == null) {
                whisperManifestService.refresh()
            }
            val def = whisperManifestService.randomWhisper(category) ?: return@launch
            whisperPlayer.preview(def)
        }
    }

    /**
     * Stop the whisper preview channel. Called from
     * WhisperPlacementSheet.onDismiss + stop button. Uses
     * [WhisperPlayer.stopPreviewOnly] so a main-channel whisper
     * (proximity / tap / placement) playing concurrently survives a
     * sheet dismiss. Reviewer-flagged: prior `stop()` killed both
     * channels and cut mid-sentence whisper audio.
     */
    fun stopWhisperPreview() {
        whisperPlayer.stopPreviewOnly()
    }

    /** Pass-through for WhisperPlacementSheet's per-row play/stop toggle. */
    val isWhisperPreviewing:
        kotlinx.coroutines.flow.StateFlow<Boolean> get() = whisperPlayer.isPlaying

    /**
     * Look up a [CachedCairn] by its server id. Used by the map-tap
     * handler to resolve a tapped pin to the full cached object before
     * opening [CairnDetailSheet]. Returns null if the cache no longer
     * contains it (rare — would require a fetch round-trip evicting
     * the cairn between pin render and tap).
     */
    fun cachedCairnById(id: String): org.walktalkmeditate.pilgrim.data.cairn.CachedCairn? =
        geoCacheService.cairns.value.firstOrNull { it.id == id }

    /**
     * iOS parity `ActiveWalkView.swift:786-834@db4196e` —
     * server-confirm-then-haptic placement.
     *
     * Fire-and-forget by design: this function launches into
     * [viewModelScope], not the caller's composition scope, so a
     * configuration change (rotation) mid-HTTP does NOT cancel the
     * request — important because server-side the whisper IS placed
     * and the local cap MUST increment to keep client/server agreement.
     * The composition's `rememberCoroutineScope()` would cancel on
     * disposal and lose the cap increment after server-success.
     *
     * `placementMutex` serializes whisper + stone calls so a rapid
     * double-tap can't bypass the per-walk cap (the previous
     * cap-check-then-suspend-then-increment had a TOCTOU window —
     * `Mutex.withLock` closes it).
     */
    fun placeWhisper(category: org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory) {
        viewModelScope.launch {
            placementMutex.withLock { placeWhisperImpl(category) }
        }
    }

    private suspend fun placeWhisperImpl(
        category: org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory,
    ) {
        if (_whispersPlacedThisWalk.value >= WHISPER_PER_WALK_CAP) return
        if (whisperManifestService.manifest.value == null) {
            val ok = whisperManifestService.refresh()
            if (!ok) {
                _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "Couldn't load whisper catalog"))
                return
            }
        }
        val whisper = whisperManifestService.randomWhisper(category)
        if (whisper == null) {
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "No whispers available for this category"))
            return
        }
        // iOS allows placement during Paused state — the Walk accumulator
        // still carries `lastLocation`. Casting to Active-only here
        // would emit a spurious "No GPS fix yet" any time the user
        // taps Place while paused. Unwrap both Active + Paused.
        val activeWalk = controller.state.value.activeOrPausedWalk()
        if (activeWalk == null) {
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "No active walk"))
            return
        }
        val location = activeWalk.lastLocation
        if (location == null) {
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "No GPS fix yet"))
            return
        }
        val capturedWalkId = activeWalk.walkId
        try {
            val placeResult = whisperService.placeWhisper(
                latitude = location.latitude,
                longitude = location.longitude,
                whisperId = whisper.id,
                category = category,
                expiry = org.walktalkmeditate.pilgrim.data.whisper.ExpiryDuration.DEFAULT,
            )
            // Walk-id guard: if the user ended/discarded the walk while
            // the HTTP call was in-flight, the cap reset has already
            // fired (controller-state observer above). Skipping the
            // increment AND the success event keeps the count
            // consistent with the post-reset zero AND avoids firing
            // a "Whisper left along the way" snackbar on whatever
            // screen the user navigated to (Summary, Idle, etc.) —
            // the `placementEvents` collector is scoped to the VM, not
            // the ActiveWalkScreen composition, so a late emit would
            // surface a context-less snackbar mid-summary. Same
            // pattern protects placeStoneImpl below. Server-side the
            // whisper IS placed; we just don't pester the user.
            if (controller.state.value.activeOrPausedWalk()?.walkId == capturedWalkId) {
                _whispersPlacedThisWalk.update { it + 1 }
                _placementEvents.emit(PlacementEvent.WhisperPlaced(whisper.id))
                // iOS parity `ActiveWalkView.swift:823` — suppress
                // the just-placed whisper so the user doesn't get a
                // proximity banner on their own placement.
                proximityService.suppressTarget(
                    org.walktalkmeditate.pilgrim.data.proximity
                        .ProximityTarget.whisperId(whisper.id),
                )
                // iOS parity `ActiveWalkView.swift:826-833` — append the
                // just-placed whisper to the geo cache so its map marker
                // appears immediately (the next refetch is gated on a
                // ~10km move, so without this the user never sees the
                // whisper they just left).
                val expiry = org.walktalkmeditate.pilgrim.data.whisper.ExpiryDuration.DEFAULT
                geoCacheService.addPlacedWhisper(
                    org.walktalkmeditate.pilgrim.data.whisper.CachedWhisper(
                        id = placeResult.id,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        whisperId = whisper.id,
                        category = category.apiValue,
                        expiresAt = java.time.Instant
                            .ofEpochMilli(clock.now() + expiry.days.toLong() * 86_400_000L)
                            .toString(),
                    ),
                )
                // iOS parity `ActiveWalkView.swift:817-819@db4196e` —
                // play the just-placed whisper after server confirm.
                // WhisperPlayer.play short-circuits when soundsEnabled
                // is off, so the gate stays at the player not here.
                whisperPlayer.play(whisper)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: org.walktalkmeditate.pilgrim.data.whisper.WhisperError.RateLimited) {
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "Too many whispers placed today"))
        } catch (e: org.walktalkmeditate.pilgrim.data.whisper.WhisperError.NetworkError) {
            // iOS parity `GeoCacheService.swift:enqueuePending` — on a
            // network failure (not server-rejected), enqueue the
            // placement for replay on the next successful geo-cache
            // fetch. 7-day TTL + 50-cap enforced by the service.
            geoCacheService.enqueuePending(
                org.walktalkmeditate.pilgrim.data.proximity.PendingPlacement(
                    type = org.walktalkmeditate.pilgrim.data.proximity
                        .PendingPlacement.PlacementType.Whisper,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    payload = buildWhisperPayload(whisper.id, category),
                    timestampMs = clock.now(),
                ),
            )
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "Saved for retry — no network"))
        } catch (e: org.walktalkmeditate.pilgrim.data.whisper.WhisperError) {
            Log.w(TAG, "placeWhisper failed: ${e.message}")
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Whisper, "Couldn't place whisper"))
        }
    }

    @kotlinx.serialization.Serializable
    private data class WhisperReplayPayload(
        @kotlinx.serialization.SerialName("whisper_id") val whisperId: String,
        val category: String,
        @kotlinx.serialization.SerialName("expiry_option") val expiryOption: String,
    )

    private fun buildWhisperPayload(
        whisperId: String,
        category: org.walktalkmeditate.pilgrim.data.whisper.WhisperCategory,
    ): String {
        // Coordinates omitted — `GeoCacheService.injectCoords` adds
        // them at replay time from the stored lat/lon. Use the
        // project Json singleton (not string interpolation) so an
        // exotic whisperId / category value can never break the
        // wire format. (Both are enum / server-assigned today, but
        // the safety belt costs one struct.)
        return jsonForPayload.encodeToString(
            WhisperReplayPayload.serializer(),
            WhisperReplayPayload(
                whisperId = whisperId,
                category = category.apiValue,
                expiryOption = org.walktalkmeditate.pilgrim.data.whisper
                    .ExpiryDuration.DEFAULT.apiValue,
            ),
        )
    }

    private val jsonForPayload = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /**
     * iOS parity `ActiveWalkView.swift:836-893@db4196e` — same
     * server-confirm-then-haptic + rotation-safe + mutex-serialized
     * shape as [placeWhisper]. Result emits the server-confirmed
     * `stoneCount` so a future bell-tier player can pick the right
     * `stone-tier-N.m4a` sample (audio deferred to a follow-up PR).
     */
    fun placeStone() {
        viewModelScope.launch {
            placementMutex.withLock { placeStoneImpl() }
        }
    }

    private suspend fun placeStoneImpl() {
        if (_stonePlacedThisWalk.value) return
        val activeWalk = controller.state.value.activeOrPausedWalk()
        if (activeWalk == null) {
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Stone, "No active walk"))
            return
        }
        val location = activeWalk.lastLocation
        if (location == null) {
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Stone, "No GPS fix yet"))
            return
        }
        val capturedWalkId = activeWalk.walkId
        try {
            val result = cairnService.placeStone(
                latitude = location.latitude,
                longitude = location.longitude,
            )
            // Walk-id guard mirrors placeWhisperImpl — if the user
            // ended the walk while the cairn POST was in-flight, the
            // reset has already cleared `_stonePlacedThisWalk` so we
            // skip setting it to true (next walk starts with stone
            // unused) AND skip the success snackbar (don't pop a
            // "stone for your cairn" toast on the summary screen).
            if (controller.state.value.activeOrPausedWalk()?.walkId == capturedWalkId) {
                _stonePlacedThisWalk.value = true
                _placementEvents.emit(PlacementEvent.StonePlaced(result.id, result.stoneCount))
                proximityService.suppressTarget(
                    org.walktalkmeditate.pilgrim.data.proximity
                        .ProximityTarget.cairnId(result.id),
                )
                // iOS parity `ActiveWalkView.swift:855-861@db4196e` —
                // tier bell fires after server confirm, scaled by the
                // post-placement stone count. Silent when `soundsEnabled`
                // is off (StonePlayer reads the pref internally).
                stonePlayer.playForCount(result.stoneCount)
                // Append/bump the cairn in the geo cache so its map marker
                // appears (or grows) immediately — same rationale as the
                // whisper path above.
                val nowIso = java.time.Instant.ofEpochMilli(clock.now()).toString()
                geoCacheService.addOrUpdatePlacedCairn(
                    org.walktalkmeditate.pilgrim.data.cairn.CachedCairn(
                        id = result.id,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        stoneCount = result.stoneCount,
                        lastPlacedAt = nowIso,
                        createdAt = nowIso,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: org.walktalkmeditate.pilgrim.data.cairn.CairnError.NetworkError) {
            geoCacheService.enqueuePending(
                org.walktalkmeditate.pilgrim.data.proximity.PendingPlacement(
                    type = org.walktalkmeditate.pilgrim.data.proximity
                        .PendingPlacement.PlacementType.Stone,
                    latitude = location.latitude,
                    longitude = location.longitude,
                    payload = "{}",
                    timestampMs = clock.now(),
                ),
            )
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Stone, "Saved for retry — no network"))
        } catch (e: org.walktalkmeditate.pilgrim.data.cairn.CairnError) {
            Log.w(TAG, "placeStone failed: ${e.message}")
            _placementEvents.emit(PlacementEvent.Failed(PlacementKind.Stone, "Couldn't place stone"))
        }
    }

    private val placementMutex = kotlinx.coroutines.sync.Mutex()

    private fun tickerFlow(periodMillis: Long): Flow<Long> = flow {
        while (true) {
            emit(clock.now())
            delay(periodMillis)
        }
    }

    private companion object {
        const val TICK_INTERVAL_MS = 1_000L
        const val SUBSCRIBER_GRACE_MS = 5_000L
        const val TAG = "WalkViewModel"
        // Stage 12-A: iOS-faithful weather-fetch delays. First attempt
        // 2s after walk-start; on `null` (no fix or null snapshot)
        // retry once after another 10s.
        const val WEATHER_FIRST_DELAY_MS = 2_000L
        const val WEATHER_RETRY_DELAY_MS = 10_000L
        // iOS parity `ActiveWalkViewModel.swift:50-53@db4196e`
        const val WHISPER_UNLOCK_SECONDS = 7 * 60L
        const val STONE_UNLOCK_SECONDS = 12 * 60L
        const val WHISPER_PER_WALK_CAP = 7
        // iOS parity `ActiveWalkViewModel.swift:407` — 300s throttle
        // on GeoCache fetch eligibility checks.
        const val GEOCACHE_FETCH_THROTTLE_MS = 300_000L
        // iOS parity `ActiveWalkView.swift:898` — nearest-cairn merge
        // radius for the StonePlacementSheet "Add to cairn" branch.
        const val NEAREST_CAIRN_RADIUS_M = 42.0
    }
}

/**
 * One-shot UI event surfaced by [WalkViewModel.placementEvents]. The
 * ActiveWalkScreen collector maps these to a haptic + banner per
 * iOS's server-confirm-then-haptic ordering.
 */
sealed class PlacementEvent {
    data class WhisperPlaced(val serverId: String) : PlacementEvent()
    data class StonePlaced(val serverId: String, val stoneCount: Int) : PlacementEvent()
    data class Failed(val kind: PlacementKind, val message: String) : PlacementEvent()
}

enum class PlacementKind { Whisper, Stone }

/**
 * Convenience for placement code paths: a [WalkAccumulator] exists
 * during both [WalkState.Active] AND [WalkState.Paused], and iOS
 * allows placement during either. Returns null on Idle / Meditating
 * / Finished.
 */
private fun WalkState.activeOrPausedWalk():
    org.walktalkmeditate.pilgrim.domain.WalkAccumulator? = when (this) {
    is WalkState.Active -> walk
    is WalkState.Paused -> walk
    else -> null
}
