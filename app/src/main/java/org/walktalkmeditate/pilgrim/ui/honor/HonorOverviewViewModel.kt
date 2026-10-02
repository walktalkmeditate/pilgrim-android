// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.File
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.audio.PlaybackState
import org.walktalkmeditate.pilgrim.audio.VoicePlaybackController
import org.walktalkmeditate.pilgrim.audio.WaveformGenerator
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording
import org.walktalkmeditate.pilgrim.data.honor.HonorPreferencesRepository
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.data.weather.WeatherFetching
import org.walktalkmeditate.pilgrim.domain.LocationPoint
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayMedia
import org.walktalkmeditate.pilgrim.domain.honor.WayMomentKind
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
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
     * Each voice moment whose file is here to play, by moment id: an own
     * walk's recording, or a shared Way's `media/audio/<n>.m4a` once it
     * has landed. A shared voice still gathering has none yet.
     */
    val playableVoices: Map<String, PreviewVoice>,
    /** Each photo moment's image on the phone, by moment id; a shared photo not here has none. */
    val photoUris: Map<String, String>,
    /** "Today is …": a `WeatherCondition` raw value, once the one fetch lands. */
    val todayCondition: String? = null,
    /** From the phone's last fix to the Way's start, measured once; null until a fix is there. */
    val distanceToStartMeters: Double? = null,
)

/** A voice the preview can play, by file, through the app's one voice player. */
@Immutable
data class PreviewVoice(
    /** The player's id for it: an own walk's recording id, or a negative stand-in no recording row has. */
    val playbackId: Long,
    val file: File,
    val totalSeconds: Double,
    /** An own walk's recording, whose waveform the app's cache keeps; null for a shared voice. */
    val recordingId: Long?,
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
 * The moment preview plays an own-walk recording, or a shared Way's landed
 * voice file, through the app's one [VoicePlaybackController], the
 * summary's and Recordings list's player with its own audio focus. U18's
 * arbiter isn't involved: it lives in `:tracker` for a walk, and no walk
 * can be running while the overview is open.
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
    /** A shared voice's bars: iOS's peaks, read from its `.m4a` (S4 §9.3). */
    private val loadSharedWaveform: suspend (File) -> FloatArray? = { WaveformGenerator.generate(it, WAVEFORM_BARS) },
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

    /** Waveform samples by [PreviewVoice.playbackId], read off the main thread once a preview opens. */
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
                ?.let { it to sharedVoices(it) }
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
        if (way.source is WaySource.Share) followLandingMedia(way)
        val here = awaitLastKnownFix() ?: return
        updateOverview { it.copy(distanceToStartMeters = HonorOverviewModel.distanceToStartMeters(here, way)) }
        // "Today is …": the walk's own weather source, on the walker's
        // current fix; silent offline or without a fix (F §10.5).
        val today = weatherFetching.fetchCurrent(here.latitude, here.longitude) ?: return
        updateOverview { it.copy(todayCondition = today.condition.rawValue) }
    }

    fun setVoicesEnabled(enabled: Boolean) {
        viewModelScope.launch { honorPreferences.setVoicesEnabled(enabled) }
    }

    /** "try again" (S4 §8.4): the round is cancelled and what is still missing gathers again. */
    fun retryMedia() {
        val way = (_state.value as? HonorOverviewUiState.Ready)?.overview?.way ?: return
        imports.retryMedia(way)
    }

    /** "walk without the missing voices": the line and both buttons go; Begin was enabled all along. */
    fun walkWithoutMissingVoices() {
        imports.walkWithoutMissingVoices()
    }

    /** The preview showing now. */
    private var openPreviewMomentId: String? = null

    /** The preview whose voice has taken the player to 1x; a second open of it (a rotation, say) keeps it as it is. */
    private var tunedPreviewMomentId: String? = null

    /** The shared player's speed before a preview took it over, given back when the preview closes. */
    private var speedBeforePreview: Float? = null

    /**
     * iOS's per-preview player starts at 1x and its rate dies with the
     * preview (F §14.3). Android's player is the app's one, so the speed
     * it had is kept aside and given back by [closePreview]. The waveform
     * is read once. A shared voice that lands while its preview is open
     * opens again here, then reads its bars (iOS's `.task(id: mediaURL)`).
     */
    fun openPreview(momentId: String) {
        openPreviewMomentId = momentId
        val voice = playableVoice(momentId) ?: return
        if (tunedPreviewMomentId != momentId) {
            tunedPreviewMomentId = momentId
            if (speedBeforePreview == null) speedBeforePreview = playback.playbackSpeed.value
            playback.setPlaybackSpeed(1f)
        }
        if (voice.playbackId in _waveforms.value) return
        val recordingId = voice.recordingId
        val cached = recordingId?.let(waveformCache::get)
        if (cached != null) {
            _waveforms.update { it + (voice.playbackId to cached) }
            return
        }
        viewModelScope.launch {
            val samples = if (recordingId != null) {
                withContext(ioDispatcher) { WaveformLoader.load(voice.file, WAVEFORM_BARS) }.also {
                    waveformCache.put(recordingId, it)
                }
            } else {
                loadSharedWaveform(voice.file) ?: return@launch
            }
            _waveforms.update { it + (voice.playbackId to samples) }
        }
    }

    fun togglePreviewVoice(momentId: String) {
        val voice = playableVoice(momentId) ?: return
        val current = playback.state.value
        if (current is PlaybackState.Playing && current.recordingId == voice.playbackId) {
            playback.pause()
        } else {
            playback.playFile(voice.playbackId, voice.file)
        }
    }

    fun cyclePreviewSpeed() {
        playback.setPlaybackSpeed(nextPlaybackSpeed(playback.playbackSpeed.value))
    }

    /** A scrub before play starts the voice, then seeks, as iOS's waveform does. */
    fun seekPreviewVoice(momentId: String, fraction: Float) {
        val voice = playableVoice(momentId) ?: return
        viewModelScope.launch {
            if (!isCurrent(voice)) {
                playback.playFile(voice.playbackId, voice.file)
                delay(SEEK_AFTER_START_DELAY_MILLIS)
            }
            playback.seek(fraction)
        }
    }

    /** Playback stops when the preview closes, and the player gets its own speed back. */
    fun closePreview() {
        playback.stop()
        openPreviewMomentId = null
        tunedPreviewMomentId = null
        speedBeforePreview?.let(playback::setPlaybackSpeed)
        speedBeforePreview = null
    }

    override fun onCleared() {
        closePreview()
        imports.overviewClosed(overview = this)
        super.onCleared()
    }

    private fun isCurrent(voice: PreviewVoice): Boolean = when (val current = playback.state.value) {
        is PlaybackState.Playing -> current.recordingId == voice.playbackId
        is PlaybackState.Paused -> current.recordingId == voice.playbackId
        else -> false
    }

    private fun playableVoice(momentId: String): PreviewVoice? =
        (_state.value as? HonorOverviewUiState.Ready)?.overview?.playableVoices?.get(momentId)

    /**
     * A voice whose recording file has gone since the Way was built shows
     * "their voice is still on its way here" (F §14.3).
     */
    private suspend fun playableVoices(way: Way, recordings: List<VoiceRecording>): Map<String, PreviewVoice> =
        withContext(ioDispatcher) {
            val byPath = recordings.associateBy { it.fileRelativePath }
            way.moments.mapNotNull { moment ->
                val voice = moment.kind as? WayMomentKind.Voice ?: return@mapNotNull null
                val media = voice.media as? WayMedia.Recording ?: return@mapNotNull null
                val recording = byPath[media.relativePath]?.takeIf(ownWalkWays::isPresent) ?: return@mapNotNull null
                moment.id to PreviewVoice(
                    playbackId = recording.id,
                    file = recordingFiles.absolutePath(recording.fileRelativePath),
                    totalSeconds = recording.durationMillis / MILLIS_PER_SECOND,
                    recordingId = recording.id,
                )
            }.toMap()
        }

    /**
     * A shared Way's voices whose `media/audio/<n>.m4a` is here, each under
     * a negative id of its own place in the Way; one still gathering, or
     * swept, says its voice is still on its way (S4 §9.3, pilgrim-ios #115).
     */
    private suspend fun sharedVoices(way: Way): Map<String, PreviewVoice> = withContext(ioDispatcher) {
        way.moments.withIndex().mapNotNull { (index, moment) ->
            val voice = moment.kind as? WayMomentKind.Voice ?: return@mapNotNull null
            val media = voice.media as? WayMedia.File ?: return@mapNotNull null
            val file = wayStore.mediaFile(way.id, media.path)?.takeIf { it.isFile } ?: return@mapNotNull null
            moment.id to PreviewVoice(playbackId = -(index + 1L), file = file, totalSeconds = voice.duration, recordingId = null)
        }.toMap()
    }

    /**
     * Files land while the overview is up: each change of this Way's
     * gather looks again, so a preview opened before its voice arrived
     * turns into the player once it has (S4 §9.3), and keeps doing so
     * under disk full or after "walk without the missing voices", when the
     * import line no longer moves. The map's inputs don't change with it.
     */
    private fun followLandingMedia(way: Way) {
        viewModelScope.launch {
            imports.gathers.map { it.of(way.id) }.distinctUntilChanged().collect {
                val voices = sharedVoices(way)
                val photos = photoUris(way)
                updateOverview { shown ->
                    if (shown.playableVoices == voices && shown.photoUris == photos) {
                        shown
                    } else {
                        shown.copy(playableVoices = voices, photoUris = photos)
                    }
                }
            }
        }
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

    /**
     * iOS reads CoreLocation's cached fix once, as the overview appears
     * (`HonorOverviewView.swift:339-343,371-373@7c200bf`), a cache that is
     * rarely empty there. The fused provider's can be (after a reboot, or
     * after another app's mock mode) until the overview's own puck fills it
     * a few seconds later, so the read is retried until a fix is there, for
     * up to two minutes. Each retry only reads the cache; nothing here asks
     * the GPS for a fix of its own.
     */
    private suspend fun awaitLastKnownFix(): LocationPoint? {
        repeat(FIX_RETRIES) {
            lastKnownFix()?.let { return it }
            delay(FIX_RETRY_INTERVAL_MILLIS)
        }
        return lastKnownFix()
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
        private const val MILLIS_PER_SECOND = 1000.0

        /** The Recordings list's hop before a seek on a just-started player (iOS's 0.1 s). */
        private const val SEEK_AFTER_START_DELAY_MILLIS = 100L

        internal const val FIX_RETRY_INTERVAL_MILLIS = 2_000L
        internal const val FIX_RETRIES = 60
    }
}
