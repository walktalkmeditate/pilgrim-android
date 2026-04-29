// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.RouteDataSample
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * Test seam over [WalkRepository] for the About surface — exposes
 * just the two reads the VM needs (walks flow + per-walk route
 * samples). Lets the VM unit-test against fixed data without spinning
 * up Room.
 */
interface AboutWalkSource {
    fun observeAllWalks(): Flow<List<Walk>>
    suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample>
}

@Singleton
class WalkRepositoryAboutSource @Inject constructor(
    private val walkRepository: WalkRepository,
) : AboutWalkSource {
    override fun observeAllWalks(): Flow<List<Walk>> = walkRepository.observeAllWalks()
    override suspend fun locationSamplesFor(walkId: Long): List<RouteDataSample> =
        walkRepository.locationSamplesFor(walkId)
}
