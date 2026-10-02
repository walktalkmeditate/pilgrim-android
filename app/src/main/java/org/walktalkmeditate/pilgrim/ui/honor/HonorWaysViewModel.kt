// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WaySource
import org.walktalkmeditate.pilgrim.honor.HonorImportCoordinator
import org.walktalkmeditate.pilgrim.honor.HonorImportState
import org.walktalkmeditate.pilgrim.honor.HonorLink
import org.walktalkmeditate.pilgrim.honor.WaySweeper

/** One "Shared with you" row: the title over `<medium date> · <counts>` (S4 §6.4). */
@Immutable
data class SharedWayRow(
    val wayId: String,
    val title: String,
    val date: String,
    /** Declared, whether or not the files are on the phone: 1 of 12 here still reads "12 voices". */
    val voiceCount: Int,
    val photoCount: Int,
    /**
     * The detail reads "voices returned to the trail" instead of the counts:
     * a Way with a voice or a photo and nothing in its `media/` folder. Keyed
     * on the folder, not the expiry, as iOS keys it, so a share never
     * gathered reads so too (pilgrim-ios #109, matched as shipped).
     */
    val voicesReturned: Boolean = false,
)

sealed interface SharedWaysUiState {
    data object Loading : SharedWaysUiState

    @Immutable
    data class Loaded(val rows: List<SharedWayRow>) : SharedWaysUiState
}

/** The "Shared with you" list, as iOS's sheet builds it (`HonorWaysSheet.swift:112-141@7c200bf`). */
object HonorWaysModel {

    /** Shared Ways only, in the store's newest-acceptance-first order; dates in the phone's zone. */
    fun rows(ways: List<Way>, hasMedia: (wayId: String) -> Boolean, zone: ZoneId, locale: Locale): List<SharedWayRow> {
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(zone)
        return ways.filter { it.source is WaySource.Share }.map { way ->
            SharedWayRow(
                wayId = way.id,
                title = way.title,
                date = date.format(way.departedAt),
                voiceCount = way.voiceCount,
                photoCount = way.photoCount,
                voicesReturned = way.voiceCount + way.photoCount > 0 && !hasMedia(way.id),
            )
        }
    }
}

/**
 * The Ways sheet's shared sections (S4 §6–§7, S2 §8). Opening the sheet
 * resets the import line, as iOS's `chooseWay` does, and leaves an import
 * in flight running (S1-D9, matched). The expiry sweep runs first, then
 * the list is read, once per opening (iOS's `onAppear`); a row hands over
 * its stored Way with no fetch. "Open" imports through the app's one
 * import, whose line shows inline.
 */
@HiltViewModel
class HonorWaysViewModel internal constructor(
    private val wayStore: WayStore,
    private val imports: HonorImportCoordinator,
    private val sweeper: WaySweeper,
    private val zone: () -> ZoneId,
    private val locale: () -> Locale,
    private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(wayStore: WayStore, imports: HonorImportCoordinator, sweeper: WaySweeper) :
        this(wayStore, imports, sweeper, ZoneId::systemDefault, Locale::getDefault, Dispatchers.IO)

    private val _shared = MutableStateFlow<SharedWaysUiState>(SharedWaysUiState.Loading)
    val shared: StateFlow<SharedWaysUiState> = _shared.asStateFlow()

    val importState: StateFlow<HonorImportState> = imports.state

    /** A Way the import just listed: the sheet closes, then its overview opens (S2 §8.3). */
    val fetched: StateFlow<String?> = imports.fetched

    init {
        imports.chooseWay()
        viewModelScope.launch {
            sweeper.sweep()
            val rows = withContext(ioDispatcher) {
                HonorWaysModel.rows(wayStore.list(), wayStore::hasMedia, zone(), locale())
            }
            _shared.value = SharedWaysUiState.Loaded(rows)
        }
    }

    /** iOS's `onPaste`: parses again, and opens only what parses. */
    fun open(text: String) {
        HonorLink.parse(text = text)?.let(imports::openWay)
    }

    fun consumeFetched(wayId: String) {
        imports.consumeFetched(wayId)
    }
}

/** The app's end of the import: a listed Way no sheet is up to take, and the walk screen's cancel. */
@HiltViewModel
class HonorImportHostViewModel @Inject constructor(
    private val imports: HonorImportCoordinator,
) : ViewModel() {

    val fetched: StateFlow<String?> = imports.fetched

    fun consumeFetched(wayId: String) {
        imports.consumeFetched(wayId)
    }

    /** iOS's `startWalk`: the walk screen opening drops an import in flight, silently. */
    fun walkScreenOpened() {
        imports.cancelImport()
    }
}
