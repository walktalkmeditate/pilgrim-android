// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.content.res.Resources
import androidx.annotation.MainThread
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.TileStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.TileStagesCache
import org.walktalkmeditate.pilgrim.domain.honor.digits
import org.walktalkmeditate.pilgrim.ui.honor.pilgrimage.PilgrimageMapsRowModel

/** What Settings → Maps and the Data card's Maps row show (iOS `OfflineMapsView`'s `saved`, spec D C4 §3). */
sealed interface OfflineMapsUiState {
    /** Before the first read lands: the screen draws nothing and the row's detail is blank, as iOS's starts `""` (C4 A10). */
    data object Loading : OfflineMapsUiState

    /** Nothing installed, no stage Way of it loads, or its regions hold no bytes: "none saved", "no maps saved". */
    data object Empty : OfflineMapsUiState

    /**
     * iOS `OfflineMapsModel.Saved`. [bytes] covers every region with the
     * route's prefix, stale and partial ones too, so Delete stays reachable;
     * [savedStages] counts the stages complete on their current line, and
     * [totalStages] the route's stage Ways that load (C4 correction 10).
     */
    @Immutable
    data class Saved(val routeName: String, val bytes: Long, val savedStages: Int, val totalStages: Int) : OfflineMapsUiState
}

/** iOS `OfflineMapsModel` (`OfflineMapsView.swift:8-46@7c200bf`, spec D C4 §3.2): the words, and one read of the store. */
object OfflineMapsModel {

    /** The Data card's detail: "none saved", or the route and its bytes. */
    fun rowDetail(resources: Resources, saved: OfflineMapsUiState.Saved?): String {
        saved ?: return resources.getString(R.string.settings_maps_none_saved)
        return resources.getString(R.string.settings_maps_detail, saved.routeName, PilgrimageMapsRowModel.megabytes(resources, saved.bytes))
    }

    /** "26 MB · 12 of 33 stages", plural at 1 as iOS's line is (C4-3, matched). */
    fun savedLine(resources: Resources, saved: OfflineMapsUiState.Saved): String = resources.getString(
        R.string.settings_maps_saved_line,
        PilgrimageMapsRowModel.megabytes(resources, saved.bytes),
        digits(saved.savedStages),
        digits(saved.totalStages),
    )

    /**
     * Null with no stages, which is no route to report on (the launch
     * reconcile clears regions nothing references), and with no bytes in
     * the store; a partial save is still bytes on the phone. One store read
     * for the whole route.
     */
    @MainThread
    fun load(routeName: String, routeId: String, stages: List<TileStage>, tiles: PilgrimageTilesManager): OfflineMapsUiState.Saved? {
        if (stages.isEmpty()) return null
        val footprint = tiles.footprint(routeId, stages)
        if (footprint.bytes <= 0) return null
        return OfflineMapsUiState.Saved(routeName, footprint.bytes, footprint.savedStages, totalStages = stages.size)
    }
}

/**
 * iOS `OfflineMapsModel.loadInstalled(packages:tiles:)` (`OfflineMapsView.swift:37-45@7c200bf`):
 * the installed route's stages through the tiles manager, what the Data
 * card's row and the Maps screen both read. The route is `installed()`'s,
 * as iOS's is, a throw read as nothing installed (C4 A9). The stage values
 * are built off the main thread once per installed release, and each read
 * asks the manager once.
 *
 * Each holder collects [reads] once; each read waits for the store's first
 * answer, bounded, so neither surface opens on "none saved" right after
 * launch (C4 correction 18). A failed answer, or none by the bound, reads
 * the cache, iOS's cold face, and the next read asks the store again.
 */
class InstalledMaps internal constructor(
    private val installed: suspend () -> PilgrimagePackageManager.Installed?,
    private val wayStore: WayStore,
    private val tiles: Provider<PilgrimageTilesManager>,
    private val ioDispatcher: CoroutineDispatcher,
    /** The tiles manager's thread, where its main-only readers run: the main thread in production. */
    private val tilesDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(packages: Provider<PilgrimagePackageManager>, wayStore: WayStore, tiles: Provider<PilgrimageTilesManager>) : this(
        installed = { packages.get().installed() },
        wayStore = wayStore,
        tiles = tiles,
        ioDispatcher = Dispatchers.IO,
        tilesDispatcher = Dispatchers.Main.immediate,
    )

    /** One read's answer: [state] is `Empty` or `Saved`, and [routeId] the installed route Delete removes. */
    data class Read(val state: OfflineMapsUiState, val routeId: String?)

    /** Touched by one read at a time ([reads]' `mapLatest` joins the one it replaces). */
    private val stagesCache = TileStagesCache()

    /**
     * A read at once, then one per regions-changed signal and per [requests]
     * value, the latest winning (iOS's `onAppear` and `onReceive`). Subscribed
     * to the signal before the first read, so a change in between isn't
     * lost. Regions only: a save's every step would otherwise decode every
     * stage again.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun reads(requests: Flow<Unit> = emptyFlow()): Flow<Read> = flow {
        val manager = tiles.get()
        val triggers = merge(manager.regionsChanged.onSubscription { emit(Unit) }, requests)
        emitAll(triggers.mapLatest { read(manager) })
    }

    /**
     * iOS's `tiles.remove(routeId:)`: any running save is cancelled first,
     * then every region with the route's prefix goes. Posted, so the screen
     * reads the result on the regions-changed it fires, not right after.
     */
    fun remove(routeId: String) = tiles.get().remove(routeId)

    private suspend fun read(manager: PilgrimageTilesManager): Read {
        val route = installedOrNone() ?: return Read(OfflineMapsUiState.Empty, routeId = null)
        val stages = stagesCache.of(route, wayStore, ioDispatcher).values
        val saved = withContext(tilesDispatcher) {
            manager.awaitStore()
            OfflineMapsModel.load(route.route.name, route.routeId, stages, manager)
        }
        return Read(saved ?: OfflineMapsUiState.Empty, route.routeId)
    }

    /** iOS's `installed()` can't fail; Android's can, and a throw shows "none saved" and no Delete, never a destructive path. */
    private suspend fun installedOrNone(): PilgrimagePackageManager.Installed? = try {
        installed()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}

/**
 * The Data card's "Maps" row (iOS `DataCard`, `DataCard.swift:25-29,38-50@7c200bf`,
 * spec D C4 §3.1), beside [WaysRowViewModel], and shown with it, by
 * [WaysAvailability] (owner decision 6), so never with the flag off or
 * while a walk or its Honor step is pending. Its detail is blank until the
 * first read lands, then read again on each entry to Settings and on each
 * regions-changed while the model lives. With the flag off nothing here
 * resolves the tiles manager or the package manager (C4 §5).
 */
@HiltViewModel
class MapsRowViewModel @Inject constructor(
    private val releaseFlags: ReleaseFlags,
    private val maps: InstalledMaps,
) : ViewModel() {

    private val _detail = MutableStateFlow<OfflineMapsUiState>(OfflineMapsUiState.Loading)
    val detail: StateFlow<OfflineMapsUiState> = _detail.asStateFlow()

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var following: Job? = null

    /** Each entry to Settings, iOS's `onAppear`; the first starts following the store, whose first read is this entry's. */
    fun refresh() {
        if (!releaseFlags.honor) return
        if (following == null) {
            following = viewModelScope.launch { maps.reads(requests).collect { _detail.value = it.state } }
        } else {
            requests.tryEmit(Unit)
        }
    }
}

/**
 * Settings → Maps (iOS `OfflineMapsView`, `OfflineMapsView.swift:48-103@7c200bf`,
 * spec D C4 §3.3): written for one pilgrimage, all the phone ever holds. It
 * never starts a save; the route page's button is the one door to that.
 * Read on opening and again on each regions-changed, so a save running
 * elsewhere ticks it up a region at a time.
 *
 * "Delete maps?" is plain model state, never restored after process
 * death, so a restored dialog can't send a Delete against a cold cache
 * that removes nothing (C2.N U47). The screen leaves once a walk starts or
 * one waits for its Honor step (owner decision 6).
 */
@HiltViewModel
class OfflineMapsViewModel @Inject constructor(
    availability: WaysAvailability,
    private val maps: InstalledMaps,
) : ViewModel() {

    private val _state = MutableStateFlow<OfflineMapsUiState>(OfflineMapsUiState.Loading)
    val state: StateFlow<OfflineMapsUiState> = _state.asStateFlow()

    val hidden: StateFlow<Boolean> = availability.shown.map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), !availability.shownAtFirst)

    private val _confirmingDelete = MutableStateFlow(false)
    val confirmingDelete: StateFlow<Boolean> = _confirmingDelete.asStateFlow()

    /** The route the last read found, as iOS's alert removes the `routeId` its last reload read. */
    private var routeId: String? = null

    init {
        viewModelScope.launch {
            maps.reads().collect { read ->
                routeId = read.routeId
                _state.value = read.state
            }
        }
    }

    /** "Delete maps": asks first. */
    fun onDeleteTapped() {
        if (_state.value is OfflineMapsUiState.Saved) _confirmingDelete.value = true
    }

    fun onDeleteCancelled() {
        _confirmingDelete.value = false
    }

    /**
     * The dialog's "Delete": the route's maps go, a save running anywhere
     * stopped first. The screen reads "no maps saved" on the regions-changed
     * the removal fires, since the call returns before the removal runs.
     */
    fun onDeleteConfirmed() {
        _confirmingDelete.value = false
        routeId?.let(maps::remove)
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
