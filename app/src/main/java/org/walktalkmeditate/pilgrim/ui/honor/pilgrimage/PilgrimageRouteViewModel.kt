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
import kotlin.math.max
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
     * button and the overflow while any download runs, this page's or not;
     * a map save joins it in Stage 21-3; [held] is the page's own
     * ([PilgrimageRoutePage.holds]). The stage rows and the alerts stay
     * live (pilgrim-ios #121, matched).
     */
    fun isBusy(phase: PilgrimagePackageManager.Phase, held: Boolean): Boolean =
        phase is PilgrimagePackageManager.Phase.Downloading || held

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
 */
@HiltViewModel
class PilgrimageRouteViewModel internal constructor(
    private val savedState: SavedStateHandle,
    private val catalogs: PilgrimageCatalogService,
    private val packages: PilgrimagePackageManager,
    private val ledgerStore: PilgrimageLedgerStore,
    private val wayStore: WayStore,
    unitsPreferences: UnitsPreferencesRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        savedState: SavedStateHandle,
        catalogs: PilgrimageCatalogService,
        packages: PilgrimagePackageManager,
        ledgerStore: PilgrimageLedgerStore,
        wayStore: WayStore,
        unitsPreferences: UnitsPreferencesRepository,
    ) : this(savedState, catalogs, packages, ledgerStore, wayStore, unitsPreferences, Dispatchers.IO)

    private val routeId: String? = savedState[ARG_ROUTE_ID]

    val units: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    val phase: StateFlow<PilgrimagePackageManager.Phase> = packages.phase

    private val _state = MutableStateFlow(openingState())
    val state: StateFlow<PilgrimageRouteUiState> = _state.asStateFlow()

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
        if (PilgrimageRouteModel.isBusy(phase.value, page.isHeld) || page.isCurrent) return
        beginInstall()
    }

    /** The overflow's Remove, held while busy as iOS's menu is. */
    fun onRemoveTapped() {
        val page = page ?: return
        if (PilgrimageRouteModel.isBusy(phase.value, page.isHeld)) return
        _alert.value = PilgrimageRouteAlert.REMOVE
    }

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
    }

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
