// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import org.walktalkmeditate.pilgrim.data.entity.Walk
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

    /** Per-buffer RMS level published by VoiceRecorder. Normalized 0f..1f. */
    val audioLevel: StateFlow<Float> = voiceRecorder.audioLevel

    /**
     * Live count of VoiceRecording rows for the current walk. Swapped
     * via flatMapLatest whenever walkIdOrNull changes so we don't leak
     * a DAO subscription across walks.
     */
    val recordingsCount: StateFlow<Int> = controller.state
        .map { walkIdOrNull(it) }
        .distinctUntilChanged()
        .flatMapLatest { walkId ->
            if (walkId == null) flowOf(0)
            else repository.observeVoiceRecordings(walkId).map { it.size }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = 0,
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
        _voiceRecorderState.value = VoiceRecorderUiState.Error(
            message = "microphone permission required to record",
            kind = VoiceRecorderUiState.Kind.PermissionDenied,
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
                    _voiceRecorderState.value = VoiceRecorderUiState.Error(
                        message = "couldn't save the recording",
                        kind = VoiceRecorderUiState.Kind.Other,
                    )
                }
            },
            onFailure = { _voiceRecorderState.value = mapStopFailure(it) },
        )
    }

    private fun mapStartFailure(err: Throwable): VoiceRecorderUiState.Error = when (err) {
        is VoiceRecorderError.PermissionMissing -> VoiceRecorderUiState.Error(
            "microphone permission required to record",
            VoiceRecorderUiState.Kind.PermissionDenied,
        )
        is VoiceRecorderError.AudioCaptureInitFailed -> VoiceRecorderUiState.Error(
            "couldn't start the microphone",
            VoiceRecorderUiState.Kind.CaptureInitFailed,
        )
        is VoiceRecorderError.FileSystemError -> VoiceRecorderUiState.Error(
            "couldn't save the recording",
            VoiceRecorderUiState.Kind.Other,
        )
        is VoiceRecorderError.ConcurrentRecording -> VoiceRecorderUiState.Error(
            "a recording is already in progress",
            VoiceRecorderUiState.Kind.Other,
        )
        else -> VoiceRecorderUiState.Error(
            err.message ?: "recording failed",
            VoiceRecorderUiState.Kind.Other,
        )
    }

    private fun mapStopFailure(err: Throwable): VoiceRecorderUiState = when (err) {
        // EmptyRecording is "user tapped stop too fast" or a silent
        // background-kill. Either way, no banner — return to Idle.
        is VoiceRecorderError.EmptyRecording -> VoiceRecorderUiState.Idle
        else -> VoiceRecorderUiState.Error(
            message = err.message ?: "stop failed",
            kind = VoiceRecorderUiState.Kind.Other,
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
        // Auto-stop when the walk finalizes mid-recording. Runs in
        // viewModelScope; toggleRecording dispatches to IO so this
        // collector doesn't block on VoiceRecorder.stop(). Placed
        // after property declarations so _voiceRecorderState is
        // already initialized when this runs.
        viewModelScope.launch {
            controller.state.collect { state ->
                if (state is WalkState.Finished &&
                    _voiceRecorderState.value is VoiceRecorderUiState.Recording
                ) {
                    toggleRecording()
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

    fun finishWalk() {
        viewModelScope.launch { controller.finishWalk() }
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
