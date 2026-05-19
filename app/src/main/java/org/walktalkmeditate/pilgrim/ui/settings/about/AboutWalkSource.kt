// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk
import org.walktalkmeditate.pilgrim.domain.WalkState
import org.walktalkmeditate.pilgrim.walk.WalkController

/**
 * Test seam over [WalkRepository] for the About surface. After Stage
 * 11-A the VM aggregates from `Walk.distanceMeters` cache col directly,
 * so the only read it needs is the walks flow itself.
 *
 * [isWalkActive] gates the post-icon-switch process restart
 * ([IconSwitcher.restartForLauncherIconRefresh]) — restarting the app
 * mid-walk would tear the foreground tracking service down.
 */
interface AboutWalkSource {
    fun observeAllWalks(): Flow<List<Walk>>
    fun isWalkActive(): Boolean
}

@Singleton
class WalkRepositoryAboutSource @Inject constructor(
    private val walkRepository: WalkRepository,
    private val walkController: WalkController,
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = walkRepository.observeAllWalks()

    override fun isWalkActive(): Boolean = when (walkController.state.value) {
        is WalkState.Active,
        is WalkState.Paused,
        is WalkState.Meditating,
        -> true
        else -> false
    }
}
