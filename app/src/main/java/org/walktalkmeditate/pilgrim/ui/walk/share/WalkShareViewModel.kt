// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.share

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.share.CachedShare
import org.walktalkmeditate.pilgrim.data.share.CachedShareStore
import org.walktalkmeditate.pilgrim.data.share.ExpiryOption
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.data.share.ShareError
import org.walktalkmeditate.pilgrim.data.share.ShareInputs
import org.walktalkmeditate.pilgrim.data.share.SharePayloadBuilder
import org.walktalkmeditate.pilgrim.data.share.ShareService
import org.walktalkmeditate.pilgrim.data.share.WalkShareOptions
import org.walktalkmeditate.pilgrim.domain.ActivityType
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

/**
 * Stage 8-A: Modal VM for the "Share Journey" flow. Takes walkId via
 * SavedStateHandle and re-fetches walk + route + altitude + intervals
 * + recordings + waypoints from the repo independently of
 * [WalkSummaryViewModel] (no cross-screen VM sharing).
 *
 * State surface:
 *  - [uiState] — Loading | Loaded(ShareInputs, derived-stat labels) | NotFound
 *  - [journal], [selectedExpiry], [includeDistance] … [includeWaypoints] —
 *    mutable modal options.
 *  - [isSharing] — true while a share POST is in flight; gates re-tap.
 *  - [canShare] — derived from isSharing + at-least-one-toggle + route
 *    point count (iOS `hasRoute` parity).
 *  - [cachedShare] — observer-flow of the per-walk cache. When non-null
 *    + not-expired on init emission, the modal jumps straight to the
 *    Shared state (no re-POST on re-entry, iOS parity).
 *
 * Events:
 *  - [events] — fire-and-forget SharedFlow of [WalkShareEvent] for
 *    the UI to collect and map to snackbars / navigation.
 */
@HiltViewModel
class WalkShareViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val shareService: ShareService,
    private val cachedShareStore: CachedShareStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walkId: Long = requireNotNull(savedStateHandle.get<Long>(ARG_WALK_ID)) {
        "WalkShareViewModel requires a `walkId` nav arg"
    }

    private val _uiState = MutableStateFlow<WalkShareUiState>(WalkShareUiState.Loading)
    val uiState: StateFlow<WalkShareUiState> = _uiState.asStateFlow()

    private val _journal = MutableStateFlow("")
    val journal: StateFlow<String> = _journal.asStateFlow()

    private val _selectedExpiry = MutableStateFlow(ExpiryOption.Season)
    val selectedExpiry: StateFlow<ExpiryOption> = _selectedExpiry.asStateFlow()

    private val _includeDistance = MutableStateFlow(true)
    val includeDistance: StateFlow<Boolean> = _includeDistance.asStateFlow()
    private val _includeDuration = MutableStateFlow(true)
    val includeDuration: StateFlow<Boolean> = _includeDuration.asStateFlow()
    private val _includeElevation = MutableStateFlow(true)
    val includeElevation: StateFlow<Boolean> = _includeElevation.asStateFlow()
    private val _includeActivityBreakdown = MutableStateFlow(true)
    val includeActivityBreakdown: StateFlow<Boolean> = _includeActivityBreakdown.asStateFlow()
    private val _includeSteps = MutableStateFlow(false)
    val includeSteps: StateFlow<Boolean> = _includeSteps.asStateFlow()
    private val _includeWaypoints = MutableStateFlow(false)
    val includeWaypoints: StateFlow<Boolean> = _includeWaypoints.asStateFlow()

    private val _isSharing = MutableStateFlow(false)
    val isSharing: StateFlow<Boolean> = _isSharing.asStateFlow()

    private val _events = MutableSharedFlow<WalkShareEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val events: SharedFlow<WalkShareEvent> = _events.asSharedFlow()

    /** Observer for the per-walk cached share. Re-emits on store writes. */
    private val _cachedShare = MutableStateFlow<CachedShare?>(null)
    val cachedShare: StateFlow<CachedShare?> = _cachedShare.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.Default) { loadInputs() }
        viewModelScope.launch {
            // Walk UUID comes from the loaded inputs — wait for Loaded
            // before starting the observer. `collectLatest` cancels
            // the previous observer if uiState transitions (only ever
            // once: Loading → Loaded), so no observer leaks.
            _uiState.collectLatest { state ->
                if (state is WalkShareUiState.Loaded) {
                    cachedShareStore.observe(state.inputs.walk.uuid)
                        .collect { _cachedShare.value = it }
                }
            }
        }
    }

    fun updateJournal(next: String) {
        // Silent truncation at ShareConfig.JOURNAL_MAX_LEN — iOS
        // `WalkShareView.swift:225-228` parity. Drop overflow chars
        // rather than rejecting input (feels better with fast typing).
        _journal.value = if (next.length <= ShareConfig.JOURNAL_MAX_LEN) {
            next
        } else {
            next.substring(0, ShareConfig.JOURNAL_MAX_LEN)
        }
    }

    fun updateExpiry(option: ExpiryOption) { _selectedExpiry.value = option }
    fun toggleDistance(on: Boolean) { _includeDistance.value = on }
    fun toggleDuration(on: Boolean) { _includeDuration.value = on }
    fun toggleElevation(on: Boolean) { _includeElevation.value = on }
    fun toggleActivityBreakdown(on: Boolean) { _includeActivityBreakdown.value = on }
    fun toggleSteps(on: Boolean) { _includeSteps.value = on }
    fun toggleWaypoints(on: Boolean) { _includeWaypoints.value = on }

    /**
     * Entry point for the "Share" button. Guarded by a
     * compareAndSet on isSharing so a rapid double-tap on the modal
     * button spawns exactly one request (Stage 5-C dedup pattern).
     */
    fun share() {
        if (!_isSharing.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                val loaded = _uiState.value as? WalkShareUiState.Loaded ?: run {
                    _events.tryEmit(WalkShareEvent.Failed("Walk not loaded yet."))
                    return@launch
                }
                val options = WalkShareOptions(
                    expiry = _selectedExpiry.value,
                    journal = _journal.value,
                    includeDistance = _includeDistance.value,
                    includeDuration = _includeDuration.value,
                    includeElevation = _includeElevation.value,
                    includeActivityBreakdown = _includeActivityBreakdown.value,
                    includeSteps = _includeSteps.value,
                    includeWaypoints = _includeWaypoints.value,
                )
                val payload = withContext(Dispatchers.Default) {
                    SharePayloadBuilder.build(loaded.inputs, options)
                }
                val result = shareService.share(payload)
                val nowMs = Instant.now().toEpochMilli()
                val expiryMs = nowMs + options.expiry.days * MILLIS_PER_DAY
                cachedShareStore.put(
                    walkUuid = loaded.inputs.walk.uuid,
                    share = CachedShare(
                        url = result.url,
                        id = result.id,
                        expiryEpochMs = expiryMs,
                        shareDateEpochMs = nowMs,
                        expiryOption = options.expiry,
                    ),
                )
                _events.tryEmit(WalkShareEvent.Success(result.url))
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: ShareError.RateLimited) {
                _events.tryEmit(WalkShareEvent.RateLimited)
            } catch (e: ShareError) {
                Log.w(TAG, "share failed", e)
                _events.tryEmit(WalkShareEvent.Failed(e.message.orEmpty()))
            } catch (t: Throwable) {
                Log.w(TAG, "share failed with unexpected throwable", t)
                _events.tryEmit(WalkShareEvent.Failed("Unexpected error."))
            } finally {
                _isSharing.value = false
            }
        }
    }

    private suspend fun loadInputs() {
        val walk = try {
            repository.getWalk(walkId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // Transient Room error → surface as NotFound so the UI
            // doesn't stay stuck in Loading forever. User can
            // dismiss + re-open; the modal is re-navigable.
            Log.w(TAG, "getWalk($walkId) threw", t)
            _uiState.value = WalkShareUiState.NotFound
            return
        }
        if (walk == null) {
            _uiState.value = WalkShareUiState.NotFound
            return
        }
        val endTs = walk.endTimestamp ?: walk.startTimestamp
        val samples: List<org.walktalkmeditate.pilgrim.data.entity.RouteDataSample>
        val altitudes: List<org.walktalkmeditate.pilgrim.data.entity.AltitudeSample>
        val events: List<org.walktalkmeditate.pilgrim.data.entity.WalkEvent>
        val intervals: List<org.walktalkmeditate.pilgrim.data.entity.ActivityInterval>
        val recordings: List<org.walktalkmeditate.pilgrim.data.entity.VoiceRecording>
        val waypoints: List<org.walktalkmeditate.pilgrim.data.entity.Waypoint>
        try {
            samples = repository.locationSamplesFor(walkId)
            altitudes = repository.altitudeSamplesFor(walkId)
            events = repository.eventsFor(walkId)
            intervals = repository.activityIntervalsFor(walkId)
            recordings = repository.voiceRecordingsFor(walkId)
            waypoints = repository.waypointsFor(walkId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "walk-related DAO read threw for walk $walkId", t)
            _uiState.value = WalkShareUiState.NotFound
            return
        }

        val points = samples.map {
            LocationPoint(
                timestamp = it.timestamp,
                latitude = it.latitude,
                longitude = it.longitude,
            )
        }
        val totals = replayWalkEventTotals(events = events, closeAt = endTs)
        val totalElapsedMs = endTs - walk.startTimestamp
        val activeWalkingMs = (totalElapsedMs - totals.totalPausedMillis - totals.totalMeditatedMillis)
            .coerceAtLeast(0)
        val distance = walkDistanceMeters(points)

        // Elevation: sum of positive deltas on the timestamp-sorted
        // altitude series (mirrors Stage 7-C composeEtegamiSpec). The
        // descent side is the negated version of the same scan.
        val sortedAlts = altitudes.sortedBy { it.timestamp }
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until sortedAlts.size) {
            val delta = sortedAlts[i].altitudeMeters - sortedAlts[i - 1].altitudeMeters
            if (delta.isFinite()) {
                if (delta > 0) ascent += delta else descent += -delta
            }
        }

        val meditateSeconds = intervals
            .filter { it.activityType == ActivityType.MEDITATING }
            .sumOf { (it.endTimestamp - it.startTimestamp) / 1_000.0 }
        val talkSeconds = recordings.sumOf { (it.endTimestamp - it.startTimestamp) / 1_000.0 }

        val inputs = ShareInputs(
            walk = walk,
            routePoints = points,
            altitudeSamples = altitudes,
            activityIntervals = intervals,
            voiceRecordings = recordings,
            waypoints = waypoints,
            distanceMeters = distance,
            activeDurationSeconds = activeWalkingMs / 1_000.0,
            meditateDurationSeconds = meditateSeconds,
            talkDurationSeconds = talkSeconds,
            elevationAscentMeters = ascent,
            elevationDescentMeters = descent,
            // Steps not yet tracked on Android (Phase 1/2 scope gap);
            // always null for 8-A. Backend accepts null.
            steps = null,
        )
        _uiState.value = WalkShareUiState.Loaded(inputs = inputs)
    }

    val toggledStatsCount: StateFlow<Int> = MutableStateFlow(0).also { counter ->
        // Manual fan-in — the five toggle flows sum into this StateFlow.
        // A `combine` chain would be cleaner but 5 args requires
        // `combine(flow1, flow2, flow3, flow4, flow5)` which is fine.
        viewModelScope.launch {
            combine(
                _includeDistance,
                _includeDuration,
                _includeElevation,
                _includeActivityBreakdown,
                _includeSteps,
            ) { d, du, e, a, s ->
                listOf(d, du, e, a, s).count { it }
            }.collect { counter.value = it }
        }
    }

    val canShare: StateFlow<Boolean> = MutableStateFlow(false).also { canShareFlow ->
        viewModelScope.launch {
            combine(
                _isSharing,
                toggledStatsCount,
                _uiState,
            ) { sharing, toggles, state ->
                !sharing &&
                    toggles > 0 &&
                    state is WalkShareUiState.Loaded &&
                    state.inputs.routePoints.size >= ShareConfig.ROUTE_MIN_POINTS
            }.collect { canShareFlow.value = it }
        }
    }

    companion object {
        const val ARG_WALK_ID = "walkId"
        private const val TAG = "WalkShareVM"
        private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1_000L
    }
}

sealed interface WalkShareUiState {
    data object Loading : WalkShareUiState
    data object NotFound : WalkShareUiState
    data class Loaded(val inputs: ShareInputs) : WalkShareUiState
}

sealed interface WalkShareEvent {
    data class Success(val url: String) : WalkShareEvent
    data object RateLimited : WalkShareEvent
    data class Failed(val message: String) : WalkShareEvent
}
