// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor.pilgrimage

import android.content.res.Resources
import android.icu.text.BreakIterator
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowCircleDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalog
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogEntry
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageCatalogService
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageError
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageException
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedger
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageLedgerStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository

/**
 * The glyph beside the one route on the phone (iOS `InstallBadge`,
 * `PilgrimageCatalogView.swift:22-40@7c200bf`). [label] is what TalkBack
 * reads; the words are never drawn (P4 correction 3).
 */
enum class InstallBadge(val glyph: ImageVector, @StringRes val label: Int) {
    /** Stone: the installed release differs from the catalog's. */
    UPDATE_READY(Icons.Filled.ArrowCircleDown, R.string.pilgrimage_badge_update_ready),

    /** Moss: installed and current. */
    ON_YOUR_PHONE(Icons.Filled.CheckCircle, R.string.pilgrimage_badge_on_your_phone),
}

/** Which of the catalog's three faces shows (P4 §3.2). */
sealed interface PilgrimageCatalogFace {
    /** A load runs and the service holds no catalog. */
    data object Spinner : PilgrimageCatalogFace

    /** The groups, under a rust line when the last load threw while a catalog was held. */
    @Immutable
    data class Listing(val catalog: PilgrimageCatalog, val failure: PilgrimageError?) : PilgrimageCatalogFace

    /** No catalog, or one with no routes: [error]'s line over "try again". */
    data class Unreachable(val error: PilgrimageError) : PilgrimageCatalogFace
}

/**
 * iOS `PilgrimageCatalogModel` (`PilgrimageCatalogView.swift:3-49@7c200bf`,
 * P1 §10, P4 §3.1): a row's words. The catalog never reads the package
 * manager's phase, so a download shows no progress here, and one that
 * ends leaves the badge as it was until the next load (pilgrim-ios #121,
 * matched).
 */
object PilgrimageCatalogModel {

    /**
     * "ES · 764 km · 33 stages", or, for the installed route, its progress
     * line in place of the count, both from the index's stage count.
     * Installed is the badge's to say, never this line's.
     */
    fun card(
        resources: Resources,
        entry: PilgrimageCatalogEntry,
        ledger: PilgrimageLedger?,
        isInstalled: Boolean,
        units: UnitSystem,
    ): String {
        val parts = mutableListOf<String>()
        entry.country?.takeIf { it.isNotEmpty() }?.let(parts::add)
        parts += StageFormat.distance(entry.distanceKm * 1000, units)
        parts += if (isInstalled) {
            PilgrimageLedger.progressLine(resources, ledger, entry.stageCount) { StageFormat.distance(it, units) }
        } else {
            PilgrimageLedger.stageCountLine(resources, entry.stageCount)
        }
        return parts.joinToString(STAGE_SEPARATOR)
    }

    fun installBadge(isInstalled: Boolean, hasUpdate: Boolean): InstallBadge? {
        if (!isInstalled) return null
        return if (hasUpdate) InstallBadge.UPDATE_READY else InstallBadge.ON_YOUR_PHONE
    }

    /** Its own quiet line under the card line, never inside it. */
    fun sparseNote(resources: Resources, entry: PilgrimageCatalogEntry): String? =
        if (entry.sparse) resources.getString(R.string.pilgrimage_sparse_note) else null

    /**
     * iOS's `hasUpdate(for:)` (`PilgrimageCatalogView.swift:200-202@7c200bf`):
     * any release that differs, older included, so a stale cache can offer
     * an Update that installs an older release (pilgrim-ios #121, matched).
     */
    fun hasUpdate(installed: PilgrimagePackageManager.Installed?, entry: PilgrimageCatalogEntry, catalogRelease: String?): Boolean =
        installed?.let { it.routeId == entry.id && it.release != (catalogRelease ?: "") } ?: false

    /**
     * iOS's `content` (`PilgrimageCatalogView.swift:99-148@7c200bf`): the
     * spinner only while nothing is held, the list whenever the held catalog
     * has a route (during a reload too), and the unreachable copy for
     * everything else, a parsed index with no routes included (P4 correction 6).
     */
    fun face(isLoading: Boolean, catalog: PilgrimageCatalog?, failure: PilgrimageError?): PilgrimageCatalogFace = when {
        isLoading && catalog == null -> PilgrimageCatalogFace.Spinner
        catalog != null && catalog.routes.isNotEmpty() -> PilgrimageCatalogFace.Listing(catalog, failure)
        else -> PilgrimageCatalogFace.Unreachable(failure ?: PilgrimageError.CATALOG_UNREACHABLE)
    }

    /**
     * Swift's `name.prefix(1)`: the first extended grapheme cluster, so an
     * "Ō" written with a combining macron, or a flag, stays whole where
     * `take(1)` would split it (P4 §3.3). ICU's iterator, which knows a
     * flag's two halves belong together, where `java.text`'s on a JVM
     * doesn't.
     */
    fun initial(name: String): String {
        val graphemes = BreakIterator.getCharacterInstance()
        graphemes.setText(name)
        val end = graphemes.next()
        return if (end == BreakIterator.DONE) "" else name.substring(0, end)
    }
}

/** What a load has read beside the catalog (iOS's `isLoading`, `failure`, `installed`, `ledgers`). */
@Immutable
data class PilgrimageCatalogUiState(
    val isLoading: Boolean = true,
    val failure: PilgrimageError? = null,
    val installed: PilgrimagePackageManager.Installed? = null,
    val ledgers: Map<String, PilgrimageLedger> = emptyMap(),
)

/**
 * iOS `PilgrimageCatalogView`'s state and `load` (`PilgrimageCatalogView.swift:54-234@7c200bf`,
 * P4 §3.4). The catalog is the service's, held for the whole process, so a
 * second opening lists at once while it loads again; this screen keeps only
 * what a load read beside it. The first load runs as the screen opens; the
 * screen coming back on top, as the route page closes over it, loads again,
 * never forced, so a route just downloaded or removed reads right. Only
 * "try again" forces a fetch.
 *
 * Two Android additions. Only the latest load writes, so an older one that
 * fails after a newer began can't put the rust line over a list the newer
 * loaded, nor drop the spinner for the unreachable face meanwhile. And the
 * first time on top is kept in [SavedStateHandle], so a catalog restored
 * under its route page after a process death reloads when that page
 * closes, as the catalog of an unbroken process would.
 */
@HiltViewModel
class PilgrimageCatalogViewModel internal constructor(
    private val savedState: SavedStateHandle,
    private val catalogs: PilgrimageCatalogService,
    private val packages: PilgrimagePackageManager,
    private val ledgerStore: PilgrimageLedgerStore,
    unitsPreferences: UnitsPreferencesRepository,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        savedState: SavedStateHandle,
        catalogs: PilgrimageCatalogService,
        packages: PilgrimagePackageManager,
        ledgerStore: PilgrimageLedgerStore,
        unitsPreferences: UnitsPreferencesRepository,
    ) : this(savedState, catalogs, packages, ledgerStore, unitsPreferences, Dispatchers.IO)

    val catalog: StateFlow<PilgrimageCatalog?> = catalogs.catalog

    val units: StateFlow<UnitSystem> = unitsPreferences.distanceUnits

    private val _state = MutableStateFlow(PilgrimageCatalogUiState())
    val state: StateFlow<PilgrimageCatalogUiState> = _state.asStateFlow()

    /** The latest [load]'s number; a load that finds a later one has begun writes nothing. */
    @Volatile private var latestLoad = 0

    init {
        load()
    }

    /** The screen is on top again: a reload, except the first time, which [init] covers (iOS's `hasAppearedOnce`). */
    fun resumed() {
        if (savedState.get<Boolean>(KEY_RESUMED_ONCE) == true) load() else savedState[KEY_RESUMED_ONCE] = true
    }

    /**
     * iOS `load(force:)`: the catalog, then `installed()` once (the UI
     * process's read, which can finish an interrupted Replace), then the
     * ledger of every listed route, the first of a repeated id kept.
     * [PilgrimageCatalogService.load] throws only with no cache at all.
     * An older load still running is left to finish, never cancelled, so
     * `installed()` is never cut off mid-swap; it just writes nothing.
     */
    fun load(force: Boolean = false) {
        val number = ++latestLoad
        _state.update { it.copy(isLoading = true, failure = null) }
        viewModelScope.launch {
            val result = read(force)
            if (number != latestLoad) return@launch
            _state.update { current ->
                when (result) {
                    is LoadResult.Read -> current.copy(isLoading = false, installed = result.installed, ledgers = result.ledgers)
                    is LoadResult.Failed -> current.copy(isLoading = false, failure = result.error)
                }
            }
        }
    }

    private suspend fun read(force: Boolean): LoadResult = try {
        val catalog = catalogs.load(force)
        val installed = packages.installed()
        val ledgers = withContext(ioDispatcher) {
            LinkedHashMap<String, PilgrimageLedger>().apply {
                catalog.routes.forEach { entry -> ledgerStore.load(entry.id)?.let { putIfAbsent(entry.id, it) } }
            }
        }
        LoadResult.Read(installed, ledgers)
    } catch (e: CancellationException) {
        throw e
    } catch (e: PilgrimageException) {
        LoadResult.Failed(e.error)
    } catch (e: Exception) {
        // iOS's `(error as? PilgrimageError) ?? .catalogUnreachable`: `installed()`'s own reads can fail here.
        LoadResult.Failed(PilgrimageError.CATALOG_UNREACHABLE)
    }

    private sealed interface LoadResult {
        class Read(val installed: PilgrimagePackageManager.Installed?, val ledgers: Map<String, PilgrimageLedger>) : LoadResult

        class Failed(val error: PilgrimageError) : LoadResult
    }

    companion object {
        internal const val KEY_RESUMED_ONCE = "resumedOnce"
    }
}
