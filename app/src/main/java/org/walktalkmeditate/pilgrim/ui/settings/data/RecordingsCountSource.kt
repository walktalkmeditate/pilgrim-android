// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.data.entity.VoiceRecording

/**
 * Tiny test seam over [WalkRepository.observeAllVoiceRecordings] so
 * [DataSettingsViewModel] unit tests don't need the full repository
 * graph. Production binds this to [WalkRepositoryRecordingsCountSource].
 */
interface RecordingsCountSource {
    fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>>
}

@Singleton
class WalkRepositoryRecordingsCountSource @Inject constructor(
    private val walkRepository: WalkRepository,
) : RecordingsCountSource {
    override fun observeAllVoiceRecordings(): Flow<List<VoiceRecording>> =
        walkRepository.observeAllVoiceRecordings()
}
