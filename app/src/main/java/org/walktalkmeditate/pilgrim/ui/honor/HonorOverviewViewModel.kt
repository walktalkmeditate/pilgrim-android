// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherFetching
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.honor.HonorImportCoordinator
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorWayChoice
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.location.LocationSource
import org.walktalkmeditate.pilgrim.ui.recordings.WaveformCache
import org.walktalkmeditate.pilgrim.ui.recordings.WaveformLoader
import org.walktalkmeditate.pilgrim.ui.recordings.nextPlaybackSpeed
import org.walktalkmeditate.pilgrim.ui.walk.map.HonorWayLine
import org.walktalkmeditate.pilgrim.ui.walk.map.WayPin
import org.walktalkmeditate.pilgrim.ui.walk.map.wayPins
import org.walktalkmeditate.pilgrim.ui.walk.summary.MapCameraBounds

/** Everything the overview draws for one Way, derived once (iOS `WayRendering`, F §9.1). */
@Immutable
data class HonorOverview(
    /** What Begin walks: the source walk, or the listed Way. */
    val choice: HonorWayChoice,
    val way: Way,
    val line: HonorWayLine,
    val pins: List<WayPin>,
    val bounds: MapCameraBounds?,
    /**
     * Each voice moment whose recording is here to play, by moment id. A
     * shared Way's voices have no recording row, so it has none yet.
     */
    val playableVoices: Map<String, VoiceRecording>,
    /** Each photo moment's image on the phone, by moment id; a shared photo not here has none. */
    val photoUris: Map<String, String>,
    /** "Today is …": a `WeatherCondition` raw value, once the one fetch lands. */
    val todayCondition: String? = null,
    /** The one probe from the phone's last fix to the Way's start; null without a fix. */
    val distanceToStartMeters: Double? = null,
)

sealed interface HonorOverviewUiState {
    data object Loading : HonorOverviewUiState

    /** The walk is gone or has no route to follow: the overview closes. */
    data object Unavailable : HonorOverviewUiState

    @Immutable
    data class Ready(val overview: HonorOverview) : HonorOverviewUiState
}

/**
 * iOS `HonorOverviewView`'s state (`HonorOverviewView.swift:62-418@7c200bf`,
 * parity spec F §8–§14, shared-walk spec S4 §8–§9). The Way is built once
 * from the source walk, or read once from the Ways store for a shared Way,
 * and held for the overview's life, as iOS carries it by value: a Way gone
 * while the overview is open still shows, and the walk screen's Start
 * refuses it. Either argument survives a process death in the route.
 *
 * The import line is the app's one import state ([HonorImportCoordinator]):
 * the overview gathers its Way as it shows it and hands the state back on
 * a real close, as iOS's `gather` and `handleOverviewDismiss` do.
 *
 * The moment preview plays an own-walk recording through the app's one
 * [VoicePlaybackController], the summary's and Recordings list's player with
 * its own audio focus. U18's arbiter isn't involved: it lives in `:tracker`
 * for a walk, and no walk can be running while the overview is open.
 */
@HiltViewModel
class HonorOverviewViewModel internal constructor(
    savedStateHandle: SavedStateHandle,
    private val ownWalkWays: OwnWalkWays,
    private val wayStore: WayStore,
    private val imports: HonorImportCoordinator,
    private val honorPreferences: HonorPreferencesRepository,
    unitsPreferences: UnitsPreferencesRepository,
    private val locationSource: LocationSource,
    private val weatherFetching: WeatherFetching,
    private val playback: VoicePlaybackController,
    private val recordingFiles: VoiceRecordingFileSystem,
    private val waveformCache: WaveformCache,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        ownWalkWays: OwnWalkWays,
        wayStore: WayStore,
        imports: HonorImportCoordinator,
        honorPreferences: HonorPreferencesRepository,
        unitsPreferences: UnitsPreferencesRepository,
        locationSource: LocationSource,
        weatherFetching: WeatherFetching,
        playback: VoicePlaybackController,
        recordingFiles: VoiceRecordingFileSystem,
        waveformCache: WaveformCache,
    ) : this(
        savedStateHandle, ownWalkWays, wayStore, imports, honorPreferences, unitsPreferences, locationSource,
        weatherFetching, playback, recordingFiles, waveformCache, Dispatchers.IO,
    )

    private val choice: HonorWayChoice = choiceOf(savedStateHandle)

    private val _state = MutableStateFlow<HonorOverviewUiState>(HonorOverviewUiState.Loading)
    val state: StateFlow<HonorOverviewUiState> = _state.asStateFlow()

    /**
     * iOS's "walk with their voice": the sticky preference itself, default
     * on. It shows on even while the app's sounds are off, though no voice
     * will play then (pilgrim-ios #109, matched).
     */
    val voicesEnabled: StateFlow<Boolean> = honorPreferences.voicesEnabled

    /** The import line under the counts, and whether Begin may go (S4 §8.3). */
    val importState: StateFlow<HonorImportState> = imports.state

    val units: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    val playbackState: StateFlow<PlaybackState> = playback.state
    val playbackPositionMillis: StateFlow<Long> = playback.playbackPositionMillis
    val playbackSpeed: StateFlow<Float> = playback.playbackSpeed

    private val _waveforms = MutableStateFlow<Map<Long, FloatArray>>(emptyMap())

    /** Waveform samples by recording id, read off the main thread once a preview opens. */
    val waveforms: StateFlow<Map<Long, FloatArray>> = _waveforms.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val (way, playable) = when (val chosen = choice) {
            is HonorWayChoice.OwnWalk -> {
                val built = ownWalkWays.build(chosen.sourceWalkId) as? OwnWalkWays.Built.Ready
                built?.let { it.way to playableVoices(it.way, it.recordings) }
            }
            is HonorWayChoice.Stored -> withContext(ioDispatcher) { wayStore.load(chosen.wayId) }
                ?.let { it to emptyMap<String, VoiceRecording>() }
        } ?: run {
            _state.value = HonorOverviewUiState.Unavailable
            return
        }
        val photos = photoUris(way)
        // Before the card shows, so its first frame already has the gathered state (S4 §8.1).
        imports.gather(way, overview = this)
        _state.value = HonorOverviewUiState.Ready(
            HonorOverview(
                choice = choice,
                way = way,
                line = HonorWayLine.of(way),
                pins = wayPins(way, heardVoiceIds = emptySet()),
                bounds = HonorOverviewModel.bounds(way),
                playableVoices = playable,
                photoUris = photos,
            ),
        )
        val here = lastKnownFix() ?: return
        updateOverview { it.copy(distanceToStartMeters = HonorOverviewModel.distanceToStartMeters(here, way)) }
        // "Today is …": the walk's own weather source, on the walker's
        // current fix; silent offline or without a fix (F §10.5).
        val today = weatherFetching.fetchCurrent(here.latitude, here.longitude) ?: return
        updateOverview { it.copy(todayCondition = today.condition.rawValue) }
    }

    fun setVoicesEnabled(enabled: Boolean) {
        viewModelScope.launch { honorPreferences.setVoicesEnabled(enabled) }
    }

    /** The preview showing now; a second open of it (a rotation, say) keeps its player as it is. */
    private var openPreviewMomentId: String? = null

    /** The shared player's speed before a preview took it over, given back when the preview closes. */
    private var speedBeforePreview: Float? = null

    /**
     * iOS's per-preview player starts at 1x and its rate dies with the
     * preview (F §14.3). Android's player is the app's one, so the speed
     * it had is kept aside and given back by [closePreview]. The waveform
     * is read once.
     */
    fun openPreview(momentId: String) {
        if (momentId == openPreviewMomentId) return
        val overview = (_state.value as? HonorOverviewUiState.Ready)?.overview ?: return
        openPreviewMomentId = momentId
        val recording = overview.playableVoices[momentId] ?: return
        if (speedBeforePreview == null) speedBeforePreview = playback.playbackSpeed.value
        playback.setPlaybackSpeed(1f)
        if (recording.id in _waveforms.value) return
        val cached = waveformCache.get(recording.id)
        if (cached != null) {
            _waveforms.update { it + (recording.id to cached) }
            return
        }
        viewModelScope.launch {
            val samples = withContext(ioDispatcher) {
                WaveformLoader.load(recordingFiles.absolutePath(recording.fileRelativePath), WAVEFORM_BARS)
            }
            waveformCache.put(recording.id, samples)
            _waveforms.update { it + (recording.id to samples) }
        }
    }

    fun togglePreviewVoice(momentId: String) {
        val recording = playableVoice(momentId) ?: return
        val current = playback.state.value
        if (current is PlaybackState.Playing && current.recordingId == recording.id) {
            playback.pause()
        } else {
            playback.play(recording)
        }
    }

    fun cyclePreviewSpeed() {
        playback.setPlaybackSpeed(nextPlaybackSpeed(playback.playbackSpeed.value))
    }

    /** A scrub before play starts the voice, then seeks, as iOS's waveform does. */
    fun seekPreviewVoice(momentId: String, fraction: Float) {
        val recording = playableVoice(momentId) ?: return
        viewModelScope.launch {
            if (!isCurrent(recording)) {
                playback.play(recording)
                delay(SEEK_AFTER_START_DELAY_MILLIS)
            }
            playback.seek(fraction)
        }
    }

    /** Playback stops when the preview closes, and the player gets its own speed back. */
    fun closePreview() {
        playback.stop()
        openPreviewMomentId = null
        speedBeforePreview?.let(playback::setPlaybackSpeed)
        speedBeforePreview = null
    }

    override fun onCleared() {
        closePreview()
        imports.overviewClosed(overview = this)
        super.onCleared()
    }

    private fun isCurrent(recording: VoiceRecording): Boolean = when (val current = playback.state.value) {
        is PlaybackState.Playing -> current.recordingId == recording.id
        is PlaybackState.Paused -> current.recordingId == recording.id
        else -> false
    }

    private fun playableVoice(momentId: String): VoiceRecording? =
        (_state.value as? HonorOverviewUiState.Ready)?.overview?.playableVoices?.get(momentId)

    /**
     * A voice whose recording file has gone since the Way was built shows
     * "their voice is still on its way here" (F §14.3).
     */
    private suspend fun playableVoices(way: Way, recordings: List<VoiceRecording>): Map<String, VoiceRecording> =
        withContext(ioDispatcher) {
            val byPath = recordings.associateBy { it.fileRelativePath }
            way.moments.mapNotNull { moment ->
                val voice = moment.kind as? WayMomentKind.Voice ?: return@mapNotNull null
                val media = voice.media as? WayMedia.Recording ?: return@mapNotNull null
                val recording = byPath[media.relativePath]?.takeIf(ownWalkWays::isPresent) ?: return@mapNotNull null
                moment.id to recording
            }.toMap()
        }

    /**
     * An own walk's photos by their content URI; a shared photo by its file
     * in the Way's `media/` folder, once that file is here.
     */
    private suspend fun photoUris(way: Way): Map<String, String> = withContext(ioDispatcher) {
        way.moments.mapNotNull { moment ->
            val uri = when (val media = (moment.kind as? WayMomentKind.Photo)?.media) {
                is WayMedia.PhotoAsset -> media.localIdentifier
                is WayMedia.File -> wayStore.mediaFile(way.id, media.path)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }
                is WayMedia.Recording, null -> null
            }
            uri?.let { moment.id to it }
        }.toMap()
    }

    private suspend fun lastKnownFix() = try {
        locationSource.lastKnownLocation()
    } catch (_: SecurityException) {
        null
    }

    private inline fun updateOverview(change: (HonorOverview) -> HonorOverview) {
        _state.update { state ->
            if (state is HonorOverviewUiState.Ready) HonorOverviewUiState.Ready(change(state.overview)) else state
        }
    }

    companion object {
        /** The own walk to rebuild a Way from; [NO_SOURCE_WALK] when the route names a stored Way. */
        const val ARG_SOURCE_WALK_ID = "sourceWalkId"

        /** A listed Way's store id; absent for an own walk. */
        const val ARG_WAY_ID = "wayId"

        const val NO_SOURCE_WALK = -1L

        private fun choiceOf(savedStateHandle: SavedStateHandle): HonorWayChoice {
            savedStateHandle.get<String>(ARG_WAY_ID)?.let { return HonorWayChoice.Stored(it) }
            val sourceWalkId = savedStateHandle.get<Long>(ARG_SOURCE_WALK_ID)?.takeIf { it != NO_SOURCE_WALK }
            return HonorWayChoice.OwnWalk(
                requireNotNull(sourceWalkId) { "neither a source walk nor a Way id in the overview's route" },
            )
        }

        private const val WAVEFORM_BARS = 64

        /** The Recordings list's hop before a seek on a just-started player (iOS's 0.1 s). */
        private const val SEEK_AFTER_START_DELAY_MILLIS = 100L
    }
}
