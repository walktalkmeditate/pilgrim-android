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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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

    private fun walkIdOrNull(state: WalkState): Long? = when (state) {
        WalkState.Idle -> null
        is WalkState.Active -> state.walk.walkId
        is WalkState.Paused -> state.walk.walkId
        is WalkState.Meditating -> state.walk.walkId
        is WalkState.Finished -> state.walk.walkId
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
