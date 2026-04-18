// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.OrphanRecordingSweeper
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.replayWalkEventTotals
import org.walktalkmeditate.pilgrim.domain.walkDistanceMeters

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
)

@HiltViewModel
class WalkSummaryViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val playback: VoicePlaybackController,
    private val sweeper: OrphanRecordingSweeper,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val walkId: Long = requireNotNull(savedStateHandle.get<Long>(ARG_WALK_ID)) {
        "walkId argument missing from nav savedStateHandle"
    }

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
    }

    fun playRecording(recording: VoiceRecording) = playback.play(recording)
    fun pausePlayback() = playback.pause()
    fun stopPlayback() = playback.stop()

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
            ),
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
