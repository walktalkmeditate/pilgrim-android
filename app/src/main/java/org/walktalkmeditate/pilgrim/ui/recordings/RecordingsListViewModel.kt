// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.audio.TranscriptionScheduler
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem

/**
 * UI state for the Recordings tab. The flow joins all voice recordings
 * with all walks, groups recordings under their walk (newest walk
 * first, then by recording start ascending within each walk),
 * applies a case-insensitive transcription search, and surfaces the
 * currently playing recording's id + position fraction.
 *
 * `Loading` is observed only at first composition while the upstream
 * Room flows publish their first emission; after that the state is
 * always `Loaded` (an empty database lands as `Loaded(visibleSections=[])`).
 */
sealed interface RecordingsListUiState {
    data object Loading : RecordingsListUiState

    @Immutable
    data class Loaded(
        val visibleSections: List<RecordingsSection>,
        val hasAnyRecordings: Boolean,
        val searchQuery: String,
        val playingRecordingId: Long?,
        val playbackPositionFraction: Float,
        val playbackSpeed: Float,
        val editingRecordingId: Long?,
    ) : RecordingsListUiState
}

/**
 * Brain of the Recordings screen. Joins seven sources — recordings,
 * walks, search query, playback state + speed + position, edit-mode
 * id — and orchestrates play / pause / seek / edit / delete /
 * retranscribe operations against the repo, controller, scheduler,
 * and file system.
 *
 * `SharingStarted.Eagerly` (not `WhileSubscribed`): the playback-
 * position tick keeps the upstream `combine` hot when the user
 * backgrounds the screen mid-playback. `WhileSubscribed(5_000)`
 * would unsubscribe and resubscribe, dropping the position cursor
 * on backgrounding. The flow lives for the VM's lifetime;
 * `viewModelScope` cancels it on `onCleared`.
 */
@HiltViewModel
class RecordingsListViewModel @Inject constructor(
    private val walkRepository: WalkRepository,
    private val playbackController: VoicePlaybackController,
    private val transcriptionScheduler: TranscriptionScheduler,
    /**
     * Plumbed through the VM (rather than re-injected at the screen
     * boundary) so [RecordingRow] resolves file existence + size at the
     * same single seam used by [onDeleteFile] / [onDeleteAllFiles].
     * Exposed via the [recordingFileSystem] accessor below for
     * Compose-side reads — the constructor parameter stays private so
     * direct mutation paths still flow through this VM.
     */
    private val fileSystem: VoiceRecordingFileSystem,
    @Suppress("UNUSED_PARAMETER")
    @ApplicationContext context: Context,
) : ViewModel() {

    /** Read-only view of the bound [VoiceRecordingFileSystem]. */
    val recordingFileSystem: VoiceRecordingFileSystem get() = fileSystem

    private val searchQuery = MutableStateFlow("")
    private val editingRecordingId = MutableStateFlow<Long?>(null)

    val state: StateFlow<RecordingsListUiState> = combine(
        walkRepository.observeAllVoiceRecordings(),
        walkRepository.observeAllWalks(),
        searchQuery,
        playbackController.state,
        playbackController.playbackSpeed,
        playbackController.playbackPositionMillis,
        editingRecordingId,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val recordings = args[0] as List<VoiceRecording>
        @Suppress("UNCHECKED_CAST")
        val walks = args[1] as List<Walk>
        val query = args[2] as String
        val playbackState = args[3] as PlaybackState
        val speed = args[4] as Float
        val posMs = args[5] as Long
        val editingId = args[6] as Long?

        val sections = walks
            .filter { walk -> recordings.any { it.walkId == walk.id } }
            .sortedByDescending { it.startTimestamp }
            .map { walk ->
                RecordingsSection(
                    walk = walk,
                    recordings = recordings
                        .filter { it.walkId == walk.id }
                        .sortedBy { it.startTimestamp },
                )
            }

        val visible = if (query.isBlank()) {
            sections
        } else {
            val q = query.lowercase()
            sections.mapNotNull { section ->
                val filtered = section.recordings.filter {
                    (it.transcription ?: "").lowercase().contains(q)
                }
                if (filtered.isEmpty()) null else section.copy(recordings = filtered)
            }
        }

        val playingId = (playbackState as? PlaybackState.Playing)?.recordingId
        val playingRecording = playingId?.let { id -> recordings.firstOrNull { it.id == id } }
        val fraction = if (playingRecording != null && playingRecording.durationMillis > 0L) {
            (posMs.toFloat() / playingRecording.durationMillis.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        RecordingsListUiState.Loaded(
            visibleSections = visible,
            hasAnyRecordings = sections.isNotEmpty(),
            searchQuery = query,
            playingRecordingId = playingId,
            playbackPositionFraction = fraction,
            playbackSpeed = speed,
            editingRecordingId = editingId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RecordingsListUiState.Loading)
    // No `flowOn(Dispatchers.Default)`: the combine body is pure
    // in-memory list transforms — section grouping, filter, fraction
    // math — running them on the Main dispatcher is cheap. Hopping
    // to Default would also bypass the test dispatcher injected via
    // `Dispatchers.setMain(...)`, breaking virtual-time tests for
    // the search/edit-mode toggle paths.

    fun onSearchChange(query: String) {
        searchQuery.value = query
    }

    fun onPlay(recordingId: Long) {
        viewModelScope.launch {
            val recording = walkRepository.getVoiceRecording(recordingId) ?: return@launch
            playbackController.play(recording)
        }
    }

    fun onPause() {
        playbackController.pause()
    }

    /**
     * Seek is anchored to the currently playing recording's id — a
     * stale tap on a row that's no longer the active player is a
     * no-op. The fraction is forwarded raw; the controller coerces
     * to [0, 1] and clamps the resolved millisecond target.
     */
    fun onSeek(recordingId: Long, fraction: Float) {
        val playing = (playbackController.state.value as? PlaybackState.Playing)?.recordingId
        if (playing != recordingId) return
        playbackController.seek(fraction)
    }

    /**
     * Global speed cycle 1.0 -> 1.5 -> 2.0 -> 1.0. The thresholds use
     * inequality midpoints so a speed coerced by the controller
     * (e.g. an external rate change in a future feature) still lands
     * on a sensible next step.
     */
    fun onSpeedCycle() {
        val current = playbackController.playbackSpeed.value
        val next = when {
            current < 1.25f -> 1.5f
            current < 1.75f -> 2.0f
            else -> 1.0f
        }
        playbackController.setPlaybackSpeed(next)
    }

    fun onTranscriptionEdit(recordingId: Long, newText: String) {
        viewModelScope.launch {
            val recording = walkRepository.getVoiceRecording(recordingId) ?: return@launch
            walkRepository.updateVoiceRecording(recording.copy(transcription = newText))
            editingRecordingId.value = null
        }
    }

    /**
     * Delete the WAV but keep the Room row. Transcription stays
     * visible; the row composable resolves "file unavailable" by
     * checking [VoiceRecordingFileSystem.fileExists] against the
     * preserved [VoiceRecording.fileRelativePath]. Per Stage 5-D
     * memory, the delete path computes its target through the same
     * helper the write path used.
     */
    fun onDeleteFile(recordingId: Long) {
        viewModelScope.launch {
            val recording = walkRepository.getVoiceRecording(recordingId) ?: return@launch
            try {
                fileSystem.deleteFile(recording.fileRelativePath)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "deleteFile failed for recording $recordingId", t)
            }
        }
    }

    fun onDeleteAllFiles() {
        viewModelScope.launch {
            // Snapshot recordings per walk via the suspend reader — a
            // single `observeAllVoiceRecordings().first()` would also
            // work, but iterating walks lets a per-walk failure log
            // its walk id. The user's intent is "delete what I see":
            // any insert that lands mid-loop is fine to keep.
            val walks = try {
                walkRepository.allWalks()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                android.util.Log.w(TAG, "deleteAllFiles: allWalks() failed", t)
                return@launch
            }
            for (walk in walks) {
                val recordings = try {
                    walkRepository.voiceRecordingsFor(walk.id)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    android.util.Log.w(
                        TAG,
                        "deleteAllFiles: voiceRecordingsFor(${walk.id}) failed",
                        t,
                    )
                    continue
                }
                for (recording in recordings) {
                    try {
                        fileSystem.deleteFile(recording.fileRelativePath)
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        android.util.Log.w(
                            TAG,
                            "deleteAllFiles: deleteFile failed for recording ${recording.id}",
                            t,
                        )
                    }
                }
            }
        }
    }

    /**
     * Clear the existing transcription so the row shows "Transcribing..."
     * while WorkManager runs, then enqueue a transcription pass for
     * the recording's walk. The scheduler iterates pending rows
     * within the walk, so a per-recording API is unnecessary —
     * unaffected siblings are simply re-checked and skipped.
     */
    fun onRetranscribe(recordingId: Long) {
        viewModelScope.launch {
            val recording = walkRepository.getVoiceRecording(recordingId) ?: return@launch
            walkRepository.updateVoiceRecording(
                recording.copy(transcription = null, wordsPerMinute = null),
            )
            transcriptionScheduler.scheduleForWalk(recording.walkId)
        }
    }

    fun onStartEditing(recordingId: Long) {
        editingRecordingId.value = recordingId
    }

    fun onStopEditing() {
        editingRecordingId.value = null
    }

    private companion object {
        const val TAG = "RecordingsListVM"
    }
}
