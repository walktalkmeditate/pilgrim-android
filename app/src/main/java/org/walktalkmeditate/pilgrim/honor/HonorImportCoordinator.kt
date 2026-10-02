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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Ways sheet and the overview both show, and the Way a finished import
 * hands to whichever screen opens its overview.
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
) {
    @Inject
    constructor(importer: Provider<WayImporter>, releaseFlags: ReleaseFlags) : this(
        importShare = { importer.get().importShare(it) },
        honorEnabled = releaseFlags.honor,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    )

    private val _state = MutableStateFlow<HonorImportState>(HonorImportState.Idle)
    val state: StateFlow<HonorImportState> = _state.asStateFlow()

    private val _fetched = MutableStateFlow<String?>(null)

    /** The id of a Way an import just listed, until the screen that opens its overview takes it. */
    val fetched: StateFlow<String?> = _fetched.asStateFlow()

    private var importJob: Job? = null
    private var shownOverview: Any? = null

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
                _state.value = HonorImportState.Failed((e as? WayImportException)?.error ?: WayError.UNAVAILABLE)
                return@launch
            }
            // A cancelled import belongs to a link already replaced: neither its Way nor its error lands.
            if (!isActive) return@launch
            _state.value = HonorImportState.Idle
            _fetched.value = way.id
        }
    }

    /** iOS `startWalk`: a walk starting drops the import in flight and leaves the state as it is. */
    fun cancelImport() {
        importJob?.cancel()
    }

    fun consumeFetched(wayId: String) {
        _fetched.compareAndSet(wayId, null)
    }

    /**
     * iOS `gather(_:)` for the overview now showing [way]: an own walk is
     * ready at once; a share takes the reducer's state over the media
     * download's sets, which stay empty until that download exists.
     * Synchronous, so the overview's first frame already has it.
     */
    fun gather(way: Way, overview: Any) {
        shownOverview = overview
        _state.value = if (way.source is WaySource.Share) {
            HonorImportReducer.state(
                wayId = way.id,
                progress = emptyMap(),
                active = emptySet(),
                failures = emptyMap(),
                diskFull = emptySet(),
            )
        } else {
            HonorImportState.Ready
        }
    }

    /** iOS `handleOverviewDismiss` on a real close: back to idle. An overview another has replaced changes nothing. */
    fun overviewClosed(overview: Any) {
        if (shownOverview !== overview) return
        shownOverview = null
        _state.value = HonorImportState.Idle
    }
}
