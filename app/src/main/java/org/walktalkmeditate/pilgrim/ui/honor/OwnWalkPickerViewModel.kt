// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.honor

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.data.units.UnitSystem
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository
import org.walktalkmeditate.pilgrim.honor.OwnWalkWays
import org.walktalkmeditate.pilgrim.ui.walk.WalkFormat

/** One row of the "Walk again" picker: the title over the distance. */
@Immutable
data class OwnWalkPickerRow(
    val walkId: Long,
    val title: String,
    val distance: String,
)

sealed interface OwnWalkPickerUiState {
    data object Loading : OwnWalkPickerUiState

    @Immutable
    data class Loaded(val rows: List<OwnWalkPickerRow>) : OwnWalkPickerUiState
}

/** The picker's list, as iOS's `OwnWalkPicker` builds it (`HonorWaysSheet.swift:148-206@7c200bf`, F §5). */
object OwnWalkPickerModel {

    /**
     * Walks whose stored distance is above zero, newest first. Nothing else
     * filters: an archived walk keeps its distance but loses its route, so it
     * is listed and every tap on it fails (pilgrim-ios #110, matched). iOS
     * keeps no unfinished walk to list.
     */
    fun eligible(walks: List<Walk>): List<Walk> =
        walks
            .filter { it.endTimestamp != null && (it.distanceMeters ?: 0.0) > 0.0 }
            .sortedByDescending { it.startTimestamp }

    /**
     * The walk's intention, trimmed, else its start date in medium style;
     * the row shows no date when it has an intention.
     */
    fun title(walk: Walk, zone: ZoneId, locale: Locale): String {
        val intention = walk.intention?.trim()
        if (!intention.isNullOrEmpty()) return intention
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .withZone(zone)
            .format(Instant.ofEpochMilli(walk.startTimestamp))
    }

    /** The distance in the walker's units, by the app's house formatter (F §5.2). */
    fun rows(walks: List<Walk>, units: UnitSystem, zone: ZoneId, locale: Locale): List<OwnWalkPickerRow> =
        eligible(walks).map { walk ->
            OwnWalkPickerRow(
                walkId = walk.id,
                title = title(walk, zone, locale),
                distance = WalkFormat.distance(walk.distanceMeters ?: 0.0, units),
            )
        }
}

/**
 * The "Walk again" picker. A row tap builds the Way on the spot, as iOS
 * does: a Way opens the overview ([picked]); no Way raises iOS's alert and
 * the picker stays up ([showsUnwalkable]).
 */
@HiltViewModel
class OwnWalkPickerViewModel internal constructor(
    repository: WalkRepository,
    unitsPreferences: UnitsPreferencesRepository,
    private val ownWalkWays: OwnWalkWays,
    zone: () -> ZoneId,
    locale: () -> Locale,
) : ViewModel() {

    @Inject
    constructor(
        repository: WalkRepository,
        unitsPreferences: UnitsPreferencesRepository,
        ownWalkWays: OwnWalkWays,
    ) : this(repository, unitsPreferences, ownWalkWays, ZoneId::systemDefault, Locale::getDefault)

    val state: StateFlow<OwnWalkPickerUiState> =
        combine(repository.observeAllWalks(), unitsPreferences.distanceUnits) { walks, units ->
            OwnWalkPickerUiState.Loaded(OwnWalkPickerModel.rows(walks, units, zone(), locale()))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS), OwnWalkPickerUiState.Loading)

    private val _showsUnwalkable = MutableStateFlow(false)
    val showsUnwalkable: StateFlow<Boolean> = _showsUnwalkable.asStateFlow()

    private val _picked = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    /** The source walk of a Way that built; the screen opens its overview. */
    val picked: SharedFlow<Long> = _picked.asSharedFlow()

    private val building = AtomicBoolean(false)

    fun pick(walkId: Long) {
        if (!building.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                when (ownWalkWays.build(walkId)) {
                    is OwnWalkWays.Built.Ready -> _picked.emit(walkId)
                    OwnWalkWays.Built.NotWalkable, OwnWalkWays.Built.SourceMissing -> _showsUnwalkable.value = true
                }
            } finally {
                building.set(false)
            }
        }
    }

    fun dismissUnwalkable() {
        _showsUnwalkable.value = false
    }

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
