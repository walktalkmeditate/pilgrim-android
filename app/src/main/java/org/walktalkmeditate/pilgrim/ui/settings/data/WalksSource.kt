// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.Walk

/**
 * Test seam over [WalkRepository.observeAllWalks] so
 * [DataSettingsViewModel] unit tests don't need the full repository
 * graph. Production binds this to [WalkRepositoryWalksSource].
 *
 * Mirrors the pattern established by [RecordingsCountSource].
 */
interface WalksSource {
    fun observeAllWalks(): Flow<List<Walk>>
}

@Singleton
class WalkRepositoryWalksSource @Inject constructor(
    private val walkRepository: WalkRepository,
) : WalksSource {
    override fun observeAllWalks(): Flow<List<Walk>> =
        walkRepository.observeAllWalks()
}
