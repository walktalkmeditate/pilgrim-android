// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.data.honor.WayImporter
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The import half of iOS's `MainCoordinator` (`MainCoordinatorView.swift:20-22,193-303@7c200bf`,
 * shared-walk spec S1 §6.3), in the UI process: the one import state the
 * Ways sheet and the overview both show, the Way a finished import hands
 * to whichever screen opens its overview, and the overview's watch on its
 * media download ([WayMediaDownloader], S4 §8).
 *
 * A newer link cancels the older fetch, and a cancelled fetch writes no
 * state; its Way may still have been saved (pilgrim-ios #114, matched).
 * The walk-screen refusal and the link toast are the link routing's.
 * Everything runs on the main thread, as iOS's `@MainActor` coordinator
 * does, so a cancel and a resolution never interleave.
 */
@Singleton
class HonorImportCoordinator internal constructor(
    private val importShare: suspend (shareId: String) -> Way,
    private val honorEnabled: Boolean,
    private val scope: CoroutineScope,
    private val media: () -> WayMediaDownloader,
) {
    @Inject
    constructor(importer: Provider<WayImporter>, releaseFlags: ReleaseFlags, downloader: Provider<WayMediaDownloader>) : this(
        importShare = { importer.get().importShare(it) },
        honorEnabled = releaseFlags.honor,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        media = downloader::get,
    )

    private val _state = MutableStateFlow<HonorImportState>(HonorImportState.Idle)
    val state: StateFlow<HonorImportState> = _state.asStateFlow()

    private val _fetched = MutableStateFlow<String?>(null)

    /** The id of a Way an import just listed, until the screen that opens its overview takes it. */
    val fetched: StateFlow<String?> = _fetched.asStateFlow()

    private val _outcomes = MutableSharedFlow<HonorImportOutcome>(extraBufferCapacity = OUTCOME_BUFFER)

    /**
     * How each import nobody replaced ended, a link's or a paste's: what
     * the link toast answers with (iOS `openWay`'s two `showLinkToast`
     * calls, S2 §4.2). A cancelled import announces nothing.
     */
    val outcomes: SharedFlow<HonorImportOutcome> = _outcomes.asSharedFlow()

    /**
     * The media download's sets themselves, which change with every file
     * that lands even while [state] holds still (under disk full, say): what
     * an overview follows to find the files that have arrived (S4 §9.3).
     */
    val gathers: Flow<WayGathers> = flow { emitAll(media().gathers) }

    private var importJob: Job? = null
    private var shownOverview: Any? = null
    private var shownWay: Way? = null

    /** iOS's `gatheringCancellable`: the overview's watch on the media download. */
    private var gathering: Job? = null

    /** iOS `chooseWay()`: the Ways sheet opens on no line. An import in flight keeps running (S1-D9, matched). */
    fun chooseWay() {
        _state.value = HonorImportState.Idle
    }

    /**
     * iOS `openWay(shareId:)`: cancels the import in flight, then fetches.
     * Success lists the Way and offers it through [fetched]; any failure
     * that isn't a [WayImportException] is "couldn't reach the walk"
     * (S1 §6.4, pilgrim-ios #114, matched).
     */
    fun openWay(shareId: String) {
        if (!honorEnabled) return
        importJob?.cancel()
        _state.value = HonorImportState.Fetching
        importJob = scope.launch {
            val way = try {
                importShare(shareId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                if (!isActive) return@launch
                val error = (e as? WayImportException)?.error ?: WayError.UNAVAILABLE
                _state.value = HonorImportState.Failed(error)
                _outcomes.tryEmit(HonorImportOutcome.Failed(error))
                return@launch
            }
            // A cancelled import belongs to a link already replaced: neither its Way nor its error lands.
            if (!isActive) return@launch
            _state.value = HonorImportState.Idle
            _outcomes.tryEmit(HonorImportOutcome.Listed)
            _fetched.value = way.id
        }
    }

    /**
     * iOS `startWalk`: a walk starting drops the import in flight and the
     * overview's watch on the media, and leaves the state as it is. The
     * transfers keep running; a file landing mid-walk plays at its spot.
     */
    fun cancelImport() {
        importJob?.cancel()
        stopGathering()
    }

    fun consumeFetched(wayId: String) {
        _fetched.compareAndSet(wayId, null)
    }

    /**
     * iOS `gather(_:)` for the overview now showing [way]: an own walk is
     * ready at once; a share starts its media download and follows the
     * reducer's state over the download's sets, any Way's change
     * recomputing it, as iOS's `combineLatest` sink does (so a second
     * link's fetching line can be overwritten mid-fetch: S4-D4,
     * pilgrim-ios #113, matched). Returns once the first state is set,
     * so the overview's first frame never shows an enabled Begin before
     * it (S4 §8.1).
     */
    suspend fun gather(way: Way, overview: Any) {
        shownOverview = overview
        shownWay = way
        stopGathering()
        if (way.source !is WaySource.Share) {
            _state.value = HonorImportState.Ready
            return
        }
        val downloader = media()
        downloader.download(way)
        // A close or a swap that landed during the download's hop installs no watch (iOS's guard).
        if (shownOverview !== overview) return
        _state.value = downloader.gathers.value.state(way.id)
        gathering = scope.launch {
            downloader.gathers.collect { _state.value = it.state(way.id) }
        }
    }

    /**
     * iOS `openOverview` for the share whose overview is already up (S2 §5
     * row 8): the overview stays and its Way gathers again, which a
     * download already running for it carries on. Any other id, or an
     * overview still loading (it gathers as it shows), changes nothing.
     */
    fun gatherShownAgain(wayId: String) {
        val overview = shownOverview ?: return
        val way = shownWay?.takeIf { it.id == wayId } ?: return
        scope.launch { gather(way, overview) }
    }

    /** iOS `retryMedia(for:)`: "try again" cancels the round and gathers what is still missing. */
    fun retryMedia(way: Way) {
        scope.launch { media().retry(way) }
    }

    /**
     * iOS `walkWithoutMissingVoices()`: the watch goes first, so no later
     * download change moves this overview back; the line and its two
     * buttons go, and Begin was enabled all along (spec correction 6).
     */
    fun walkWithoutMissingVoices() {
        stopGathering()
        _state.value = HonorImportState.Ready
    }

    /**
     * iOS `handleOverviewDismiss` on a real close: the watch goes and the
     * state returns to idle; the transfers keep running. An overview
     * another has replaced changes nothing.
     */
    fun overviewClosed(overview: Any) {
        if (shownOverview !== overview) return
        shownOverview = null
        shownWay = null
        stopGathering()
        _state.value = HonorImportState.Idle
    }

    private fun stopGathering() {
        gathering?.cancel()
        gathering = null
    }

    private companion object {
        /** Outcomes arrive one per import on the main thread, so a few slots never drop one. */
        const val OUTCOME_BUFFER = 4
    }
}

/** How an import the walker still wanted ended. */
sealed interface HonorImportOutcome {
    /** Its Way is listed and offered through [HonorImportCoordinator.fetched]. */
    data object Listed : HonorImportOutcome

    data class Failed(val error: WayError) : HonorImportOutcome
}
