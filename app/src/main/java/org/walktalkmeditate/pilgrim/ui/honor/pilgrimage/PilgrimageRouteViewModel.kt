// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageException
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRoute
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageRouteStage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesCorridor
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesManager
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.TileStage
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.domain.honor.digits

/** The route page's three alerts (P4 §4.7); "finish your walk first" is a footer line, not one of them. */
enum class PilgrimageRouteAlert { REPLACE, REMOVE, DOWNLOAD_FIRST }

/**
 * One route page's state, iOS `PilgrimageRouteView`'s `@State` (`PilgrimageRouteView.swift:86-98@7c200bf`,
 * P4 §4.1): the catalog entry and the release it was opened under, what
 * the last reload read, the page's own failure, the preview's two states,
 * and the redraw notice, kept for the page's life once shown.
 *
 * [holds] is Android's: the page's opening and its installs that haven't
 * reloaded yet, each holding the button and the overflow. The opening holds
 * the page until it has read what is installed, so a tap can't skip
 * "Replace?" (iOS's opening reload is synchronous). Each install holds it
 * from its tap through the reload after it, standing in for iOS's early
 * phase and synchronous reload while the guard's database reads run, so a
 * second tap can't read "the download didn't finish". Counted, so an
 * install refused at once can't release another's hold. A Remove holds
 * nothing, as iOS's synchronous Remove never dims the page.
 */
@Immutable
data class PilgrimageRoutePage(
    val entry: PilgrimageCatalogEntry,
    val release: String,
    val installed: PilgrimagePackageManager.Installed? = null,
    val route: PilgrimageRoute? = null,
    val ledger: PilgrimageLedger? = null,
    val failure: PilgrimageError? = null,
    val isLoadingStages: Boolean = false,
    val stagesFailure: PilgrimageError? = null,
    val showRedrawNotice: Boolean = false,
    val holds: Int = 0,
) {
    val isHeld: Boolean get() = holds > 0

    val isInstalled: Boolean get() = PilgrimageCatalogModel.isInstalled(installed, entry)

    /** The catalog's rule, against the release the page was opened under (pilgrim-ios #121, matched). */
    val hasUpdate: Boolean get() = PilgrimageCatalogModel.hasUpdate(installed, entry, release)

    /** This release already on the phone: the download button's gate, drawn and tapped alike. */
    val isCurrent: Boolean get() = isInstalled && !hasUpdate

    /** The installed `route.json`'s, else the preview's; a Remove leaves the removed package's in place. */
    val stages: List<PilgrimageRouteStage> get() = route?.stages.orEmpty()
}

sealed interface PilgrimageRouteUiState {
    /** A page restored in a new process, reading the catalog it was opened from. */
    data object Resolving : PilgrimageRouteUiState

    /** The catalog no longer lists the route: the page closes back to the catalog. */
    data object Gone : PilgrimageRouteUiState

    @Immutable
    data class Ready(val page: PilgrimageRoutePage) : PilgrimageRouteUiState
}

/**
 * The maps row's inputs for one installed release, [routeId] at [release]
 * (iOS's `stageWays`, `mapsEstimateBytes` and `mapsStatus`,
 * `PilgrimageRouteView.swift:77-85@7c200bf`): the stages' values, never
 * their decoded Ways, the estimate from them, and the status last read.
 */
@Immutable
data class PilgrimageMapsRowState(
    val routeId: String,
    val release: String,
    val stages: List<TileStage>,
    val estimateBytes: Long,
    val status: PilgrimageTilesManager.Status,
)

/** iOS `PilgrimageRouteModel` (`PilgrimageRouteView.swift:35-64@7c200bf`, P4 §4) and the page's other words. */
object PilgrimageRouteModel {

    fun redrawNotice(resources: Resources): String = resources.getString(R.string.pilgrimage_route_redraw_notice)

    fun stageLine(resources: Resources, stage: PilgrimageRouteStage, units: UnitSystem): String =
        WayStageFacts.line(resources, stage.distanceKm, stage.gainMeters, stage.hours, stage.difficulty, units)

    /**
     * P4 §4.4's rule, in order: no stage left (or none at all) → "you have
     * walked the whole way"; the first stage not completed was begun, at
     * any fraction, 0 included → "continue from where you stopped", stage 1
     * too; it is stage 1, never begun → "start with stage 1"; else "next:
     * stage N".
     */
    fun nextRow(resources: Resources, ledger: PilgrimageLedger?, stageCount: Int): String {
        val next = (ledger ?: PilgrimageLedger(routeId = "")).next(stageCount)
            ?: return resources.getString(R.string.pilgrimage_next_whole_way)
        if (next.resumeFrac != null) return resources.getString(R.string.pilgrimage_next_continue)
        return if (next.index == 0) {
            resources.getString(R.string.pilgrimage_next_start)
        } else {
            resources.getString(R.string.pilgrimage_next_stage, digits(next.index + 1))
        }
    }

    /**
     * The stage the next row opens: the next one, or the first of the list
     * once every stage is walked. `resumeFrac` picks the row's words and
     * nothing else.
     */
    fun nextIndex(page: PilgrimageRoutePage): Int? =
        (page.ledger ?: PilgrimageLedger(page.entry.id)).next(page.entry.stageCount)?.index
            ?: page.stages.firstOrNull()?.index

    fun buttonLabel(resources: Resources, isInstalled: Boolean, hasUpdate: Boolean): String = resources.getString(
        when {
            !isInstalled -> R.string.pilgrimage_route_download
            hasUpdate -> R.string.pilgrimage_route_update
            else -> R.string.pilgrimage_route_on_your_phone
        },
    )

    /**
     * iOS's `isBusy` (`PilgrimageRouteView.swift:308-312@7c200bf`) holds the
     * button and the overflow while any download or map save runs, this
     * page's or not; [held] is the page's own ([PilgrimageRoutePage.holds]).
     * The stage rows, the alerts and the maps row stay live (pilgrim-ios
     * #121, matched).
     */
    fun isBusy(phase: PilgrimagePackageManager.Phase, tilesPhase: PilgrimageTilesManager.Phase, held: Boolean): Boolean =
        phase is PilgrimagePackageManager.Phase.Downloading || tilesPhase is PilgrimageTilesManager.Phase.Saving || held

    /**
     * iOS `mapsRowIsHeld` (`PilgrimageRouteView.swift:57-64@7c200bf`): a save
     * begun mid-Update hashes stage lines being rewritten, so a package
     * download holds the maps row. A save is not one, so the row's own
     * "cancel" stays reachable. The page adds its own holds (spec D C4 §1.4).
     */
    fun mapsRowIsHeld(phase: PilgrimagePackageManager.Phase): Boolean = phase is PilgrimagePackageManager.Phase.Downloading

    /** "stage d of n" from the manager's phase: both sides drop `route.json`, the one file that isn't a stage. */
    fun downloadProgress(resources: Resources, phase: PilgrimagePackageManager.Phase): String? {
        val downloading = phase as? PilgrimagePackageManager.Phase.Downloading ?: return null
        return resources.getString(
            R.string.pilgrimage_route_progress,
            digits(max(downloading.done - 1, 0)),
            digits(downloading.total - 1),
        )
    }

    /**
     * `route?.summary ?? "<Tradition> · <region>"`, the fallback only with a
     * tradition, and ending on its separator with no region (pilgrim-ios #121,
     * matched).
     */
    fun summary(resources: Resources, page: PilgrimageRoutePage): String? =
        page.route?.summary ?: page.entry.tradition?.let { tradition ->
            resources.getString(R.string.pilgrimage_route_summary_fallback, capitalized(tradition), page.entry.region.orEmpty())
        }

    fun stageTitle(resources: Resources, stage: PilgrimageRouteStage): String =
        resources.getString(R.string.pilgrimage_route_stage_title, digits(stage.index + 1), stage.name)

    /**
     * Foundation's `capitalized`, as probed on macOS 26: a letter after a
     * cased letter is lower-cased, any other is title-cased, so "christian"
     * reads "Christian", "shinto-buddhist" "Shinto-Buddhist", "CAMINO"
     * "Camino" and "1st" "1St". Apostrophes, combining marks and format
     * characters are passed over, so "o'brien" reads "O'brien". Each code
     * point maps alone, so a final sigma stays σ, as Foundation leaves it.
     */
    fun capitalized(text: String): String {
        val out = StringBuilder(text.length)
        var lastCased = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            offset += Character.charCount(codePoint)
            if (isCaseIgnorable(codePoint)) {
                out.appendCodePoint(codePoint)
                continue
            }
            if (lastCased) out.appendCodePoint(Character.toLowerCase(codePoint)) else out.append(titlecase(codePoint))
            lastCased = Character.isUpperCase(codePoint) || Character.isLowerCase(codePoint) || Character.isTitleCase(codePoint)
        }
        return out.toString()
    }

    private fun isCaseIgnorable(codePoint: Int): Boolean = codePoint == '\''.code || codePoint == '’'.code ||
        when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(), Character.FORMAT.toInt() -> true
            else -> false
        }

    /** "ß" title-cases to "Ss" and "ǆ" to "ǅ", one-to-many where a single code point has no form. */
    private fun titlecase(codePoint: Int): String =
        if (Character.isBmpCodePoint(codePoint)) codePoint.toChar().titlecase() else String(Character.toChars(Character.toTitleCase(codePoint)))
}

/**
 * iOS `PilgrimageRouteView` (`PilgrimageRouteView.swift:66-401@7c200bf`, P4
 * §4): one route, what it is, where the walker is in it, and every stage.
 *
 * The page opens on a route id and takes the entry and the release from
 * the catalog the service holds, as iOS takes them from the tapped row and
 * the catalog of that moment. A page restored in a new process reads the
 * catalog first (a cache read, never forced) and closes back to the
 * catalog if it no longer lists the route; the download died with the old
 * process, so the phase is idle (P4 §4.11, A-3).
 *
 * The manager's phase is the page's progress from its first frame, so a
 * page opened while any download runs shows it. Nothing reloads when the
 * phase ends: a page opened mid-download keeps what it read on opening
 * after the commit, as iOS's does (pilgrim-ios #121, matched). Every
 * package action runs in the manager's own scope, so leaving the page
 * never cancels one; its outcome lands only on a page still here.
 *
 * **The maps row** (spec D C4 §1). Its stage values are built on IO from
 * the page's own `installed`, in [reload] only, so a page opened mid-Update
 * keeps the old release's lines after the commit (#121 item 5, matched).
 * The row is published once its estimate and its status are both in, and
 * hidden again only when the installed release moves. The status is read
 * again on regions-changed and when the tiles phase turns idle, iOS's two
 * triggers. Every read of the tiles manager, and every write of the row,
 * runs on [tilesDispatcher], the manager's one thread, so the latest
 * values always win. The manager is resolved only for an installed page
 * with the release flag on (C4 §5); a page for another route doesn't see a
 * running save, so its button isn't held by one.
 */
@HiltViewModel
class PilgrimageRouteViewModel internal constructor(
    private val savedState: SavedStateHandle,
    private val catalogs: PilgrimageCatalogService,
    private val packages: PilgrimagePackageManager,
    private val ledgerStore: PilgrimageLedgerStore,
    private val wayStore: WayStore,
    unitsPreferences: UnitsPreferencesRepository,
    private val releaseFlags: ReleaseFlags,
    private val tilesProvider: Provider<PilgrimageTilesManager>,
    private val ioDispatcher: CoroutineDispatcher,
    /** The tiles manager's thread, where its main-only readers and `cancel` run: the main thread in production. */
    private val tilesDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        savedState: SavedStateHandle,
        catalogs: PilgrimageCatalogService,
        packages: PilgrimagePackageManager,
        ledgerStore: PilgrimageLedgerStore,
        wayStore: WayStore,
        unitsPreferences: UnitsPreferencesRepository,
        releaseFlags: ReleaseFlags,
        tiles: Provider<PilgrimageTilesManager>,
    ) : this(
        savedState,
        catalogs,
        packages,
        ledgerStore,
        wayStore,
        unitsPreferences,
        releaseFlags,
        tiles,
        Dispatchers.IO,
        Dispatchers.Main.immediate,
    )

    private val routeId: String? = savedState[ARG_ROUTE_ID]

    val units: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    val phase: StateFlow<PilgrimagePackageManager.Phase> = packages.phase

    private val _state = MutableStateFlow(openingState())
    val state: StateFlow<PilgrimageRouteUiState> = _state.asStateFlow()

    private val _tilesPhase = MutableStateFlow<PilgrimageTilesManager.Phase>(PilgrimageTilesManager.Phase.Idle)

    /** The tiles manager's phase, live from the page's first installed reload: a page reopened mid-save shows it. */
    val tilesPhase: StateFlow<PilgrimageTilesManager.Phase> = _tilesPhase.asStateFlow()

    private val _mapsRow = MutableStateFlow<PilgrimageMapsRowState?>(null)

    /** Null while there is no row: not installed, no stage Way loads, the flag off, or its first reads not yet in. */
    val mapsRow: StateFlow<PilgrimageMapsRowState?> = _mapsRow.asStateFlow()

    @Volatile private var tiles: PilgrimageTilesManager? = null

    /** On [tilesDispatcher]: the reload's row derivation, replaced by the next reload's. */
    private var mapsJob: Job? = null

    /** On [tilesDispatcher]: the last release's stage values and pack count, built once per `(routeId, release)`. */
    private var stagesCache: TileStages? = null

    /** On [tilesDispatcher]: the store's first answer waited for, once per page, before its first status read. */
    private var storeAwaited = false

    private val _alert = MutableStateFlow<PilgrimageRouteAlert?>(null)
    val alert: StateFlow<PilgrimageRouteAlert?> = _alert.asStateFlow()

    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** A stage Way to open: the sheets close, then its overview opens. */
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    private val page: PilgrimageRoutePage? get() = (_state.value as? PilgrimageRouteUiState.Ready)?.page

    init {
        viewModelScope.launch {
            if (_state.value == PilgrimageRouteUiState.Resolving) resolve()
            if (page == null) return@launch
            try {
                reload()
            } finally {
                releaseHold()
            }
            loadStagesIfNeeded(force = false)
        }
    }

    /** The download button: nothing while busy or with this release on the phone, as iOS's disabled button. */
    fun onDownloadTapped() {
        val page = page ?: return
        if (PilgrimageRouteModel.isBusy(phase.value, tilesPhaseNow(), page.isHeld) || page.isCurrent) return
        beginInstall()
    }

    /** The overflow's Remove, held while busy as iOS's menu is. */
    fun onRemoveTapped() {
        val page = page ?: return
        if (PilgrimageRouteModel.isBusy(phase.value, tilesPhaseNow(), page.isHeld)) return
        _alert.value = PilgrimageRouteAlert.REMOVE
    }

    /**
     * The maps row's button, "Tap to save again" too: the page's stage
     * values into the manager's own scope, so leaving the page never stops
     * the save. Its outcome is dropped; the phase carries it (iOS's `save()`,
     * `PilgrimageMapsRow.swift:93-99@7c200bf`). Nothing while the row is held.
     */
    fun onSaveMaps() {
        val page = page ?: return
        val row = _mapsRow.value ?: return
        val manager = tiles ?: return
        if (PilgrimageRouteModel.mapsRowIsHeld(phase.value) || page.isHeld) return
        manager.save(row.routeId, row.stages)
    }

    /** The saving face's "cancel": the manager's own, at once and on the main thread; the regions already saved stay. */
    fun onCancelMaps() {
        val page = page ?: return
        if (PilgrimageRouteModel.mapsRowIsHeld(phase.value) || page.isHeld) return
        tiles?.cancel()
    }

    private fun tilesPhaseNow(): PilgrimageTilesManager.Phase = tiles?.phase?.value ?: PilgrimageTilesManager.Phase.Idle

    /**
     * iOS `open(index:)`: the stage's overview when this route is installed
     * and its `way.json` reads; otherwise "Download this route first?",
     * even for an installed route whose stage is missing. No busy check, as
     * iOS has none (pilgrim-ios #119, matched).
     */
    fun open(index: Int) {
        val page = page ?: return
        viewModelScope.launch {
            val stageWayId = WayStore.stageWayId(page.entry.id, index)
            val way = if (page.isInstalled) withContext(ioDispatcher) { wayStore.load(stageWayId) } else null
            if (way == null) _alert.value = PilgrimageRouteAlert.DOWNLOAD_FIRST else _opened.emit(way.id)
        }
    }

    fun openNext() {
        val page = page ?: return
        PilgrimageRouteModel.nextIndex(page)?.let(::open)
    }

    fun retryStages() {
        viewModelScope.launch { loadStagesIfNeeded(force = true) }
    }

    fun confirmReplace() {
        _alert.value = null
        install(replacing = true)
    }

    fun confirmRemove() {
        _alert.value = null
        remove()
    }

    /** "Download" on "Download this route first?": the button's own gate, so a Replace still asks. */
    fun confirmDownloadFirst() {
        _alert.value = null
        beginInstall()
    }

    fun dismissAlert() {
        _alert.value = null
    }

    /** iOS `beginInstall`: every install starts here, so the Replace confirmation can't be skipped. */
    private fun beginInstall() {
        val page = page ?: return
        if (page.installed != null && !page.isInstalled) {
            _alert.value = PilgrimageRouteAlert.REPLACE
        } else {
            install(replacing = false)
        }
    }

    /**
     * iOS `install(replacing:)`: Update before Replace before Download, the
     * failure cleared as it starts and set by its end, and a reload either
     * way, since a failed Update's rollback removed the package. The work
     * starts in the manager's scope at the tap. The page stays held through
     * its reload, which reads off the main thread here, so no tap lands on
     * the label from before the commit.
     */
    private fun install(replacing: Boolean) {
        val page = page ?: return
        updatePage { it.copy(failure = null, holds = it.holds + 1) }
        val operation = when {
            page.hasUpdate -> packages.update(page.entry, page.release)
            replacing -> packages.replace(page.entry, page.release)
            else -> packages.download(page.entry, page.release)
        }
        viewModelScope.launch {
            try {
                val failure = failureOf(PilgrimageError.INCOMPLETE) { operation.await() }
                updatePage { it.copy(failure = failure) }
                reload()
            } finally {
                releaseHold()
            }
        }
    }

    /** iOS `removeRoute`: a reload on success; a refusal's line otherwise, the page left as it stood, never held. */
    private fun remove() {
        val page = page ?: return
        val operation = packages.remove(page.entry.id)
        viewModelScope.launch {
            val failure = failureOf(PilgrimageError.INCOMPLETE) { operation.await() }
            if (failure == null) reload() else updatePage { it.copy(failure = failure) }
        }
    }

    private fun releaseHold() {
        updatePage { it.copy(holds = it.holds - 1) }
    }

    /**
     * iOS `reload()`: what is installed now (an installed route's stages are
     * its own `route.json`'s), and the ledger, read whether or not the route
     * is on the phone. A pending redraw notice shows, and is cleared in the
     * file at once under the ledger's locks, so no later opening says it
     * again; the page keeps it in its saved state, so a restore after
     * process death still shows a notice the walker may not have read.
     * Last, the maps row starts again from what this reload read; a row
     * from another release is hidden before the page's hold lets go.
     */
    private suspend fun reload() {
        val routeId = page?.entry?.id ?: return
        val installed = try {
            packages.installed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // iOS's `installed()` can't fail; a failed read here leaves the page as it stood.
            page?.installed
        }
        val ledger = withContext(ioDispatcher) {
            ledgerStore.load(routeId).also { if (it?.redrawNoticePending == true) clearRedrawNotice(routeId) }
        }
        val notice = ledger?.redrawNoticePending == true
        if (notice) savedState[KEY_REDRAW_NOTICE] = true
        updatePage { current ->
            current.copy(
                installed = installed,
                route = installed?.takeIf { it.routeId == routeId }?.route ?: current.route,
                ledger = ledger,
                showRedrawNotice = current.showRedrawNotice || notice,
            )
        }
        withContext(tilesDispatcher) { restartMapsRow() }
    }

    /**
     * On [tilesDispatcher]. The row follows the page's own `installed`,
     * never a live read; a release that moved hides it until its own
     * estimate and status are in, so no tap lands on the old release's lines.
     */
    private fun restartMapsRow() {
        mapsJob?.cancel()
        val page = page
        val installed = page?.installed?.takeIf { page.isInstalled && releaseFlags.honor }
        if (installed == null) {
            _mapsRow.value = null
            return
        }
        val shown = _mapsRow.value
        if (shown != null && (shown.routeId != installed.routeId || shown.release != installed.release)) _mapsRow.value = null
        mapsJob = viewModelScope.launch(tilesDispatcher) { deriveMapsRow(installed) }
    }

    /**
     * iOS's reload of `stageWays`, `mapsEstimateBytes` and `mapsStatus`,
     * the estimate and the stage values off the main thread (C1 §12). The
     * pack count is the release's; bytes per pack is read every time, since
     * a save calibrates it without a release. The first status waits for
     * the store's first answer; past the bound the cache is read anyway,
     * iOS's own cold face, which regions-changed then corrects.
     */
    private suspend fun deriveMapsRow(installed: PilgrimagePackageManager.Installed) {
        val manager = resolveTiles()
        val stages = stagesOf(installed)
        if (stages.values.isEmpty()) {
            _mapsRow.value = null
            return
        }
        val estimate = stages.packCount.toLong() * manager.bytesPerPack(installed.routeId)
        if (!storeAwaited) {
            manager.awaitStore()
            storeAwaited = true
        }
        _mapsRow.value = PilgrimageMapsRowState(
            routeId = installed.routeId,
            release = installed.release,
            stages = stages.values,
            estimateBytes = estimate,
            status = manager.status(stages.values),
        )
    }

    private suspend fun stagesOf(installed: PilgrimagePackageManager.Installed): TileStages {
        stagesCache?.takeIf { it.routeId == installed.routeId && it.release == installed.release }?.let { return it }
        val built = withContext(ioDispatcher) {
            val values = PilgrimageTilesCorridor.stages(wayStore, installed.routeId, installed.route.stageCount)
            TileStages(installed.routeId, installed.release, values, PilgrimageTilesCorridor.packCount(values))
        }
        stagesCache = built
        return built
    }

    /** At the first installed reload, on [tilesDispatcher]; the page then follows the manager's phase and regions while it lives. */
    private fun resolveTiles(): PilgrimageTilesManager = tiles ?: tilesProvider.get().also { manager ->
        tiles = manager
        viewModelScope.launch(tilesDispatcher, start = CoroutineStart.UNDISPATCHED) {
            manager.regionsChanged.collect { refreshMapsStatus() }
        }
        // A save whose regions were all present loads only packs and ends
        // with no regions signal, and a cancel ends with none either.
        viewModelScope.launch(tilesDispatcher, start = CoroutineStart.UNDISPATCHED) {
            manager.phase.collect { phase ->
                _tilesPhase.value = phase
                if (phase == PilgrimageTilesManager.Phase.Idle) refreshMapsStatus()
            }
        }
    }

    /**
     * iOS `refreshMapsStatus`, over the stage values the row already holds;
     * a packs-only answer never calls it (D4, matched). A held page has its
     * reload still to come, which reads the status itself: an Update's
     * removals land before it, and iOS's synchronous Update never draws
     * their figure against the old lines (C4 §6 step 2).
     */
    private fun refreshMapsStatus() {
        val manager = tiles ?: return
        val row = _mapsRow.value ?: return
        if (page?.isHeld == true) return
        _mapsRow.value = row.copy(status = manager.status(row.stages))
    }

    private class TileStages(val routeId: String, val release: String, val values: List<TileStage>, val packCount: Int)

    /** iOS's `try?` save: a clear that can't be written shows the notice again next time. */
    private fun clearRedrawNotice(routeId: String) {
        try {
            ledgerStore.clearRedrawNotice(routeId)
        } catch (e: IOException) {
            // The notice stays pending in the file, and says itself on the next opening.
        }
    }

    /**
     * iOS `loadStagesIfNeeded(force:)`: the preview only when nothing for
     * this route is installed, or on "try again"; never for an empty
     * release. A failed preview is its own line in the stage section, never
     * the download's.
     */
    private suspend fun loadStagesIfNeeded(force: Boolean) {
        val page = page ?: return
        if (!(force || page.route == null) || page.release.isEmpty()) return
        updatePage { it.copy(isLoadingStages = true, stagesFailure = null) }
        val failure = failureOf(PilgrimageError.CATALOG_UNREACHABLE) {
            val route = catalogs.routePreview(page.entry, page.release)
            updatePage { it.copy(route = route) }
        }
        if (failure != null) updatePage { it.copy(stagesFailure = failure) }
        updatePage { it.copy(isLoadingStages = false) }
    }

    /** The in-memory catalog when the service holds one (the catalog of the tap); a new process reads it in [resolve]. */
    private fun openingState(): PilgrimageRouteUiState {
        val id = routeId ?: return PilgrimageRouteUiState.Gone
        val held = catalogs.catalog.value ?: return PilgrimageRouteUiState.Resolving
        return readyOrGone(held, id)
    }

    private suspend fun resolve() {
        val id = routeId ?: return
        val catalog = try {
            catalogs.load()
        } catch (e: PilgrimageException) {
            null
        }
        _state.value = catalog?.let { readyOrGone(it, id) } ?: PilgrimageRouteUiState.Gone
    }

    /** A ready page is held by its opening reload, which the init block runs and releases. */
    private fun readyOrGone(catalog: PilgrimageCatalog, id: String): PilgrimageRouteUiState {
        val entry = catalog.routes.firstOrNull { it.id == id } ?: return PilgrimageRouteUiState.Gone
        val page = PilgrimageRoutePage(
            entry = entry,
            release = catalog.release,
            showRedrawNotice = savedState.get<Boolean>(KEY_REDRAW_NOTICE) == true,
            holds = 1,
        )
        return PilgrimageRouteUiState.Ready(page)
    }

    private fun updatePage(change: (PilgrimageRoutePage) -> PilgrimageRoutePage) {
        _state.update { state -> (state as? PilgrimageRouteUiState.Ready)?.let { PilgrimageRouteUiState.Ready(change(it.page)) } ?: state }
    }

    companion object {
        const val ARG_ROUTE_ID = "routeId"
        internal const val KEY_REDRAW_NOTICE = "showRedrawNotice"
    }
}

/**
 * iOS's `(error as? PilgrimageError) ?? fallback`: null when [action]
 * ends, the error a [PilgrimageException] carries, or [fallback] for any
 * other failure. Cancellation is rethrown: the screen's going is no failure.
 */
internal suspend fun failureOf(fallback: PilgrimageError, action: suspend () -> Unit): PilgrimageError? = try {
    action()
    null
} catch (e: CancellationException) {
    throw e
} catch (e: PilgrimageException) {
    e.error
} catch (e: Exception) {
    fallback
}
