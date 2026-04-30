// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * Test seam over [WalkRepository] for the About surface. After Stage
 * 11-A the VM aggregates from `Walk.distanceMeters` cache col directly,
 * so the only read it needs is the walks flow itself.
 */
interface AboutWalkSource {
    fun observeAllWalks(): Flow<List<Walk>>
}

@Singleton
class WalkRepositoryAboutSource @Inject constructor(
    private val walkRepository: WalkRepository,
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = walkRepository.observeAllWalks()
}
