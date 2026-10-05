// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import android.content.res.Resources
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.core.flags.ReleaseFlags
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.honor.HonorDao
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimagePackageManager
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.honor.WayMediaDownloader
import org.walktalkmeditate.pilgrim.honor.WaySweeper

/** One Settings → Ways row: the title over `<medium date> · <size or "voices returned to the trail">`. */
@Immutable
data class WayListRow(
    val wayId: String,
    val title: String,
    val date: String,
    /** Allocated bytes of the Way's folder; ignored once its voices have returned to the trail. */
    val bytes: Long,
    /** A voice or a photo, and nothing in `media/`: iOS's test (pilgrim-ios #109, matched as shipped). */
    val voicesReturned: Boolean,
)

/** The Ways the Data card's row counts, and their size. */
@Immutable
data class WaysTotals(val count: Int, val bytes: Long)

sealed interface WaysListUiState {
    data object Loading : WaysListUiState

    @Immutable
    data class Loaded(
        val rows: List<WayListRow>,
        /** The installed route's `route.json` name; null with no route installed. */
        val packageRouteName: String? = null,
        /** Package-owned stage Ways in the store, of every route ([WayStore.stageWayIds]). */
        val packageStageCount: Int = 0,
    ) : WaysListUiState
}

/**
 * iOS `WaysListModel` (`WaysListView.swift:5-28@7c200bf`) and the row
 * details of `WaysListView` (shared-walk spec S4 §2–§4), kept pure so the
 * Data card counts exactly what the list shows.
 */
object WaysListModel {

    /** Own-walk and shared Ways; a pilgrimage stage is its route page's, never this list's. */
    fun listable(ways: List<Way>): List<Way> = ways.filterNot { it.source.isPackageOwned }

    /** `"1 way · 2.3 MB"`, `"3 ways · 12.0 MB"`, `"0 ways · 0.0 MB"`: the word by iOS's `count == 1`, in every locale. */
    fun rowDetail(resources: Resources, totals: WaysTotals): String {
        val res = if (totals.count == 1) R.string.settings_ways_count_one else R.string.settings_ways_count
        val count = resources.getString(res, String.format(Locale.US, "%d", totals.count))
        return resources.getString(R.string.settings_ways_detail, count, megabytes(resources, totals.bytes))
    }

    /**
     * iOS `packageFooter(routeName:stageCount:)` (`WaysListView.swift:22-28@7c200bf`):
     * one line under the list while package stages are on the phone, so a
     * walker who downloaded a route doesn't look for it here. Null with no
     * stages, and with no installed route to name them by (a Replace cut
     * short says nothing). The word by iOS's `stageCount == 1`.
     */
    fun packageFooter(resources: Resources, routeName: String?, stageCount: Int): String? {
        if (routeName == null || stageCount <= 0) return null
        val res = if (stageCount == 1) R.string.settings_ways_package_footer_one else R.string.settings_ways_package_footer
        return resources.getString(res, routeName, String.format(Locale.US, "%d", stageCount))
    }

    /** `"Sep 14, 2026 · 23.5 MB"`, or `"Sep 14, 2026 · voices returned to the trail"`. */
    fun detail(resources: Resources, row: WayListRow): String {
        val tail = if (row.voicesReturned) {
            resources.getString(R.string.honor_ways_voices_returned)
        } else {
            megabytes(resources, row.bytes)
        }
        return resources.getString(R.string.settings_ways_detail, row.date, tail)
    }

    fun rows(ways: List<Way>, store: WayStore, zone: ZoneId, locale: Locale): List<WayListRow> {
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
        return ways.map { way ->
            WayListRow(
                wayId = way.id,
                title = way.title,
                date = date.format(way.departedAt),
                bytes = store.diskUsage(way.id),
                voicesReturned = way.voiceCount + way.photoCount > 0 && !store.hasMedia(way.id),
            )
        }
    }

    /** iOS's `String(format: "%.1f MB", bytes / 1_000_000)`: decimal megabytes, one decimal, a `.` always. */
    private fun megabytes(resources: Resources, bytes: Long): String =
        resources.getString(R.string.settings_ways_megabytes, String.format(Locale.US, "%.1f", bytes / BYTES_PER_MEGABYTE))

    private const val BYTES_PER_MEGABYTE = 1_000_000.0
}

/**
 * Whether Settings → Ways shows at all: with the release flag on, and
 * neither a walk on nor a finished one still waiting for its Honor step.
 * iOS can't reach Settings during a walk and has no such step; Android's
 * step outlives the walk screen, so this is a dated R6 addition at the
 * gate (spec correction 14), keeping a delete off a Way a walk still needs.
 */
class WaysAvailability internal constructor(
    val shown: Flow<Boolean>,
    /**
     * What to show before [shown]'s first answer: the release flag, so the
     * Data card's row is there on its first frame, as iOS's unconditional
     * row is, and goes only if a walk turns out to be on.
     */
    val shownAtFirst: Boolean,
) {
    @Inject
    constructor(releaseFlags: ReleaseFlags, walkRepository: WalkRepository, honorDao: HonorDao) : this(
        shown = if (!releaseFlags.honor) {
            flowOf(false)
        } else {
            combine(walkRepository.observeActiveWalk(), honorDao.observeLiveSessionCount()) { active, sessions ->
                active == null && sessions == 0
            }
        },
        shownAtFirst = releaseFlags.honor,
    )
}

/**
 * The Data card's "Ways" row (iOS `DataCard`, S4 §2): always shown while
 * [WaysAvailability] allows, its detail counted on each appearance over
 * the Ways the list shows. It doesn't sweep, as iOS's card doesn't
 * (pilgrim-ios #115, matched).
 */
@HiltViewModel
class WaysRowViewModel internal constructor(
    private val store: WayStore,
    availability: WaysAvailability,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(store: WayStore, availability: WaysAvailability) : this(store, availability, Dispatchers.IO)

    val shown: StateFlow<Boolean> = availability.shown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), availability.shownAtFirst)

    private val _totals = MutableStateFlow<WaysTotals?>(null)

    /** Null until the first count lands: iOS's detail starts as `""`. */
    val totals: StateFlow<WaysTotals?> = _totals.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _totals.value = withContext(ioDispatcher) {
                val listed = WaysListModel.listable(store.list())
                WaysTotals(count = listed.size, bytes = store.diskUsage(listed))
            }
        }
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}

/**
 * Settings → Ways (iOS `WaysListView`, S4 §3–§5): every own-walk and
 * shared Way, newest acceptance first, each with its date and its size or
 * "voices returned to the trail". Each load and each delete sweeps first
 * (iOS's `reload`). A delete cancels the Way's downloads, then removes its
 * folder and every link to it; the walks' reply recordings and their honor
 * events stay. Swipe deletes one at once; "Delete all Ways" asks first.
 *
 * The last row names where the hidden pilgrimage stages went (P2 §11):
 * every route's stages, walked ones a Replace or Remove kept included,
 * under the installed route's name, as iOS counts them (pilgrim-ios #120
 * item 6, matched as shipped).
 */
@HiltViewModel
class WaysListViewModel internal constructor(
    private val store: WayStore,
    private val sweeper: WaySweeper,
    private val cancelGather: (wayId: String) -> Unit,
    /** iOS's `installed()?.route.name`, which can finish an interrupted Replace: the UI process only. */
    private val installedRouteName: suspend () -> String?,
    availability: WaysAvailability,
    private val zone: () -> ZoneId,
    private val locale: () -> Locale,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        store: WayStore,
        sweeper: WaySweeper,
        downloader: Provider<WayMediaDownloader>,
        packages: Provider<PilgrimagePackageManager>,
        availability: WaysAvailability,
    ) : this(
        store = store,
        sweeper = sweeper,
        cancelGather = { downloader.get().cancel(it) },
        installedRouteName = { packages.get().installed()?.route?.name },
        availability = availability,
        zone = ZoneId::systemDefault,
        locale = Locale::getDefault,
        ioDispatcher = Dispatchers.IO,
    )

    private val _state = MutableStateFlow<WaysListUiState>(WaysListUiState.Loading)
    val state: StateFlow<WaysListUiState> = _state.asStateFlow()

    /** True once a walk starts or a finished one waits for its Honor step: the screen leaves. */
    val hidden: StateFlow<Boolean> = availability.shown.map { !it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), !availability.shownAtFirst)

    private val changes = Mutex()

    init {
        viewModelScope.launch { changes.withLock { reload() } }
    }

    /** iOS's `.onDelete`: unconfirmed. */
    fun delete(wayId: String) {
        viewModelScope.launch {
            changes.withLock {
                remove(listOf(wayId))
                reload()
            }
        }
    }

    /** iOS's "Delete" in its "Delete all Ways?" alert: every Way the list shows, never a stage. */
    fun deleteAll() {
        viewModelScope.launch {
            changes.withLock {
                val shown = (_state.value as? WaysListUiState.Loaded)?.rows?.map { it.wayId }.orEmpty()
                remove(shown)
                reload()
            }
        }
    }

    private suspend fun remove(wayIds: List<String>) {
        wayIds.forEach(cancelGather)
        withContext(ioDispatcher) { wayIds.forEach(store::delete) }
    }

    /** iOS's order: the store is read before `installed()`, so its count is the store as it stood. */
    private suspend fun reload() {
        sweeper.sweep()
        val (rows, stageCount) = withContext(ioDispatcher) {
            WaysListModel.rows(WaysListModel.listable(store.list()), store, zone(), locale()) to store.stageWayIds().size
        }
        _state.value = WaysListUiState.Loaded(
            rows = rows,
            packageRouteName = routeNameOrNone(),
            packageStageCount = stageCount,
        )
    }

    /**
     * iOS's `installed()` can't fail. Android's can, in the live-session
     * read its marker branch makes before retiring an abandoned route; then
     * no route is named, and the footer says nothing.
     */
    private suspend fun routeNameOrNone(): String? = try {
        installedRouteName()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
