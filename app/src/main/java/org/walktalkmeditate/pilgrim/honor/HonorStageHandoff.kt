// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.Way

/**
 * The stage an overview's Begin opened the walk screen on, as that
 * overview loaded it, held in memory for the walk screen and its Start.
 * iOS walks the Way its overview captured at Begin
 * (`MainCoordinatorView.swift:94@7c200bf`, and the finish path's note at
 * `:113-115` that an Update may redraw the stage meanwhile), so a package
 * commit that lands between Begin and Start changes nothing about the walk
 * (pilgrimage-stage spec, owner decision 2).
 *
 * One stage at a time, found only by its own id, so a Start can never take
 * another Way's copy. UI process only, as Begin is: after a UI process death
 * between Begin and Start the hand-off is gone, and the walk screen reads
 * the stage from its package instead.
 */
@Singleton
class HonorStageHandoff @Inject constructor() {

    private val handed = AtomicReference<Way?>(null)

    /** Begin on [stage]'s overview; it replaces whatever an earlier Begin left. */
    fun hand(stage: Way) {
        require(stage.isPilgrimageStage) { "only a pilgrimage stage is handed from its overview" }
        handed.set(stage)
    }

    /** The copy handed over for [wayId]; null when the last Begin was another Way's, or none came since launch. */
    fun stage(wayId: String): Way? = handed.get()?.takeIf { it.id == wayId }

    /**
     * The Way a stored choice names before Start: the stage handed over
     * for [wayId], else [store]'s (a listed share, or a stage's package
     * after a UI process death), read on [io]. The pre-Start map and Start
     * both read it here, so the map shows the copy Start stages.
     */
    suspend fun storedWay(wayId: String, store: WayStore, io: CoroutineDispatcher): Way? =
        stage(wayId) ?: withContext(io) { store.load(wayId) }

    /** [wayId]'s walk has started on its staged copy: a next walk of it begins at its overview again. */
    fun release(wayId: String) {
        handed.getAndUpdate { held -> held?.takeUnless { it.id == wayId } }
    }
}
