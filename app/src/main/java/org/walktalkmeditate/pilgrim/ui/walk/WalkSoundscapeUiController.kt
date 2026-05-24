// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionRepository
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionScope
import org.walktalkmeditate.pilgrim.walk.WalkActionPublisher

/** One pickable soundscape for the WalkOptionsSheet picker. */
data class SoundscapeChoice(val id: String, val displayName: String)

/**
 * UI-side soundscape surface for the active walk's options sheet (iOS
 * parity `WalkOptionsSheet` Audio section). Reads display data (selected
 * name + downloaded list) in the UI process and routes the toggle /
 * select commands to `:tracker` via [WalkActionPublisher] — playback
 * itself lives in the `:tracker` `SoundscapeOrchestrator`, and the
 * single-process `pilgrim_prefs` DataStore can't carry a UI write across
 * to it.
 */
interface WalkSoundscapeUiController {
    /** Display name of the currently selected soundscape, or null. */
    val selectedName: StateFlow<String?>

    /** Id of the currently selected soundscape (drives the picker check). */
    val selectedId: StateFlow<String?>

    /** Downloaded soundscapes the picker can offer. */
    val available: StateFlow<List<SoundscapeChoice>>

    /** Turn walk-long soundscape playback on/off. */
    fun setEnabled(on: Boolean)

    /** Pick a soundscape: persist for next time + play it now. */
    fun select(assetId: String)
}

@Singleton
class DefaultWalkSoundscapeUiController @Inject constructor(
    private val manifestService: AudioManifestService,
    private val selectionRepository: SoundscapeSelectionRepository,
    private val fileStore: SoundscapeFileStore,
    private val actionPublisher: WalkActionPublisher,
    @SoundscapeSelectionScope private val scope: CoroutineScope,
) : WalkSoundscapeUiController {

    override val selectedName: StateFlow<String?> =
        combine(
            selectionRepository.selectedSoundscapeId,
            manifestService.assets,
        ) { id, _ -> id?.let { manifestService.asset(it)?.displayName } }
            .stateIn(scope, SharingStarted.Eagerly, null)

    override val selectedId: StateFlow<String?> = selectionRepository.selectedSoundscapeId

    override val available: StateFlow<List<SoundscapeChoice>> =
        manifestService.assets
            .map { assets ->
                assets
                    .filter { it.type == AudioAssetType.SOUNDSCAPE && fileStore.isAvailable(it) }
                    .map { SoundscapeChoice(it.id, it.displayName) }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun setEnabled(on: Boolean) {
        actionPublisher.setSoundscapeEnabled(on)
    }

    override fun select(assetId: String) {
        // Persist for next time (UI-process DataStore) AND command
        // :tracker to play it now — the DataStore write alone wouldn't
        // reach the tracker process.
        scope.launch { selectionRepository.select(assetId) }
        actionPublisher.selectSoundscape(assetId)
    }
}
