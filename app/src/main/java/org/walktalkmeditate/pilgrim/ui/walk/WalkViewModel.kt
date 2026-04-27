// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.VoiceRecorder
import org.walktalkmeditate.pilgrim.audio.VoiceRecorderError
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
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
) : ViewModel() {

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
        viewModelScope.launch {
            controller.state.collect { state ->
                if (state is WalkState.Finished &&
                    _voiceRecorderState.value is VoiceRecorderUiState.Recording
                ) {
                    _voiceRecorderState.value = VoiceRecorderUiState.Idle
                }
            }
        }
    }

    fun startWalk(intention: String? = null) {
        viewModelScope.launch {
            // Two separate try blocks with different semantics. Catching
            // both in one block would let an IllegalStateException from
            // the controller trigger the service-start rollback, which
            // would finish a walk that's ALREADY running — effectively
            // cancelling a legitimate earlier startWalk call.
            try {
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
            try {
                ContextCompat.startForegroundService(
                    context,
                    WalkTrackingService.startIntent(context),
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                // Service refused to start — most commonly
                // ForegroundServiceStartNotAllowedException (API 31+ when
                // triggered from a background state) or SecurityException
                // (FINE_LOCATION revoked between our gate and this call).
                // Roll back the in-memory walk so state and "actually
                // tracking" stay consistent.
                Log.w(TAG, "could not start walk tracking service", e)
                controller.finishWalk()
            }
        }
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

    fun endMeditation() {
        viewModelScope.launch { controller.endMeditation() }
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
        viewModelScope.launch { controller.discardWalk() }
    }

    /**
     * Restore a walk row left in Room when the process was killed
     * mid-walk. Returns the restored [Walk] if one existed, or null
     * when there's nothing to resume.
     */
    suspend fun restoreActiveWalk(): Walk? = controller.restoreActiveWalk()

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
    }
}
