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
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.WayError
import org.walktalkmeditate.pilgrim.data.honor.WayImportException
import org.walktalkmeditate.pilgrim.data.honor.WayImporter
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageException
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WaySource

/**
 * The import half of iOS's `MainCoordinator` (`MainCoordinatorView.swift:20-22,193-303@7c200bf`,
 * shared-walk spec S1 §6.3), in the UI process: the one import state the
 * Ways sheet and the overview both show, the Way a finished import hands
 * to whichever screen opens its overview, and the overview's watch on its
 * media download ([WayMediaDownloader], S4 §8). Since `3b78728` it also
 * resolves stage links ([openStage]) on the same job, so the newest link of
 * either kind wins (`MainCoordinatorView.swift:270-330@3b78728`).
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
    /** iOS's four stage-link seams (`MainCoordinatorView.swift:43-46@3b78728`); the defaults reach no disk and no network. */
    private val installedRouteId: suspend () -> String? = { null },
    private val loadStageWay: suspend (wayId: String) -> Way? = { null },
    private val cachedCatalog: () -> PilgrimageCatalog? = { null },
    private val loadCatalog: suspend (force: Boolean) -> PilgrimageCatalog = {
        throw PilgrimageException(PilgrimageError.CATALOG_UNREACHABLE)
    },
) {
    @Inject
    constructor(
        importer: Provider<WayImporter>,
        releaseFlags: ReleaseFlags,
        downloader: Provider<WayMediaDownloader>,
        packages: Provider<PilgrimagePackageManager>,
        wayStore: Provider<WayStore>,
        catalogs: Provider<PilgrimageCatalogService>,
    ) : this(
        importShare = { importer.get().importShare(it) },
        honorEnabled = releaseFlags.honor,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        media = downloader::get,
        installedRouteId = { packages.get().installedRoute()?.routeId },
        loadStageWay = { id -> withContext(Dispatchers.IO) { wayStore.get().load(id) } },
        cachedCatalog = { catalogs.get().catalog.value },
        loadCatalog = { force -> catalogs.get().load(force) },
    )

    private val _state = MutableStateFlow<HonorImportState>(HonorImportState.Idle)
    val state: StateFlow<HonorImportState> = _state.asStateFlow()

    private val _fetched = MutableStateFlow<String?>(null)

    /** The id of a Way an import just listed, until the screen that opens its overview takes it. */
    val fetched: StateFlow<String?> = _fetched.asStateFlow()

    private val _routeLink = MutableStateFlow<HonorRouteLink?>(null)

    /**
     * A route page a stage link resolved to, until the nav host opens it:
     * iOS's `pendingRouteLink`/`honorRouteLink` as one slot. Publishing it
     * clears [fetched] and the reverse, so the newest request wins and at
     * most one is set (`MainCoordinatorView.swift:336-369@3b78728`).
     */
    val routeLink: StateFlow<HonorRouteLink?> = _routeLink.asStateFlow()

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
            publishFetched(way.id)
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

    fun consumeRouteLink(link: HonorRouteLink) {
        _routeLink.compareAndSet(link, null)
    }

    /**
     * iOS `openStage(routeId:index:)` (`MainCoordinatorView.swift:270-330@3b78728`):
     * what tapping that stage on the route page does. It cancels the share
     * import or stage resolution in flight, and a share's stranded spinner
     * goes with it. Then, on the main thread, it reads the installed route
     * once and the stage Way once, and only for the installed route;
     * resolves against the catalog in memory; and loads only when it must,
     * unforced for no catalog, forced exactly once for a route the catalog
     * lacks, each load announced "reaching for the route…". A replaced
     * resolution lands nothing and says nothing. The walk screen opening
     * cancels it ([cancelImport]), so a Begin always wins.
     *
     * iOS's `wirePilgrimageManagers()` has nothing to wire here: the package
     * and tiles managers take their walk signals at construction
     * (`PilgrimagePackageManager.kt:120`, `di/PilgrimageTilesModule.kt:78`).
     * A failed read of the installed route reads as none installed, which
     * opens the route page; iOS's read can't fail.
     */
    fun openStage(routeId: String, index: Int) {
        if (!honorEnabled) return
        importJob?.cancel()
        if (_state.value == HonorImportState.Fetching) _state.value = HonorImportState.Idle
        importJob = scope.launch {
            val installed = installedRouteOrNone()
            if (!isActive) return@launch
            val way = if (installed == routeId) loadStageWay(WayStore.stageWayId(routeId, index)) else null
            if (!isActive) return@launch
            fun resolve(catalog: PilgrimageCatalog?): StageLinkOutcome =
                StageLinkResolver.resolve(routeId, index, installed, stageWayExists = way != null, catalog = catalog)
            var outcome = resolve(cachedCatalog())
            var forced = false
            while (outcome == StageLinkOutcome.NeedsCatalog || (outcome == StageLinkOutcome.NotInCatalog && !forced)) {
                val force = outcome == StageLinkOutcome.NotInCatalog
                forced = forced || force
                _outcomes.tryEmit(HonorImportOutcome.ReachingForRoute)
                val catalog = try {
                    loadCatalog(force)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    if (!isActive) return@launch
                    val error = (e as? PilgrimageException)?.error ?: PilgrimageError.CATALOG_UNREACHABLE
                    _outcomes.tryEmit(HonorImportOutcome.CatalogFailed(error))
                    return@launch
                }
                if (!isActive) return@launch
                outcome = resolve(catalog)
            }
            when (outcome) {
                is StageLinkOutcome.Overview -> {
                    _outcomes.tryEmit(HonorImportOutcome.Listed)
                    publishFetched(outcome.wayId)
                }
                is StageLinkOutcome.RouteSheet -> {
                    _outcomes.tryEmit(HonorImportOutcome.Listed)
                    publishRouteLink(outcome.link)
                }
                StageLinkOutcome.NotInCatalog, StageLinkOutcome.NeedsCatalog ->
                    _outcomes.tryEmit(HonorImportOutcome.RouteNotInPilgrim)
            }
        }
    }

    private suspend fun installedRouteOrNone(): String? = try {
        installedRouteId()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (e: Exception) {
        null
    }

    private fun publishFetched(wayId: String) {
        _routeLink.value = null
        _fetched.value = wayId
    }

    private fun publishRouteLink(link: HonorRouteLink) {
        _fetched.value = null
        _routeLink.value = link
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

/** How an import, or a stage link's resolution, the walker still wanted ended. */
sealed interface HonorImportOutcome {
    /** Its Way is offered through [HonorImportCoordinator.fetched], or its route page through `routeLink`. */
    data object Listed : HonorImportOutcome

    data class Failed(val error: WayError) : HonorImportOutcome

    /** A stage link is about to load the catalog. */
    data object ReachingForRoute : HonorImportOutcome

    /** The catalog, forced once, still lacks the route. */
    data object RouteNotInPilgrim : HonorImportOutcome

    data class CatalogFailed(val error: PilgrimageError) : HonorImportOutcome
}
