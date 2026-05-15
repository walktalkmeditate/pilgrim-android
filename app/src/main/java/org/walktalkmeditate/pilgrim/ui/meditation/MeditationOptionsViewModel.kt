// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.meditation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import org.walktalkmeditate.pilgrim.audio.soundscape.SoundscapePlayer
import org.walktalkmeditate.pilgrim.audio.voiceguide.VoiceGuidePlayer
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository
import org.walktalkmeditate.pilgrim.data.voice.VoicePreferencesRepository
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogRepository
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePackState

/**
 * iOS parity `MeditationView.swift:394-415 @ db4196e`: the long-press
 * picker on the breathing circle includes a voice-guide section in
 * addition to the breath-rhythm rows. Plus the always-visible
 * soundscape label under the timer (iOS:288-333). Feeds the
 * voice-guide picker, the soundscape display string, and the
 * tap-to-mute / long-press-to-pick affordances.
 */
@HiltViewModel
class MeditationOptionsViewModel @Inject constructor(
    private val catalog: VoiceGuideCatalogRepository,
    voicePreferences: VoicePreferencesRepository,
    soundscapeSelection: SoundscapeSelectionRepository,
    manifestService: AudioManifestService,
    private val soundsPreferences: SoundsPreferencesRepository,
    private val soundscapePlayer: SoundscapePlayer,
    voiceGuidePlayer: VoiceGuidePlayer,
) : ViewModel() {

    /**
     * `true` while a voice-guide prompt is actively playing. iOS
     * `MeditationView.swift:655-695` uses the same signal to drive
     * voice-ring pulses + breath-cycle slowdown.
     */
    val voicePlaying: StateFlow<Boolean> = voiceGuidePlayer.state
        .map { it is VoiceGuidePlayer.State.Playing }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val packStates: StateFlow<List<VoiceGuidePackState>> = catalog.packStates
    val voiceGuideEnabled: StateFlow<Boolean> = voicePreferences.voiceGuideEnabled

    /**
     * Display name of the currently-selected soundscape, or null when
     * the user has cleared the selection. Resolves the selected id
     * through the audio manifest. Refreshes whenever either the
     * selection or the manifest emits.
     */
    val selectedSoundscapeName: StateFlow<String?> = combine(
        soundscapeSelection.selectedSoundscapeId,
        manifestService.assets,
    ) { selectedId, assets ->
        if (selectedId == null) return@combine null
        assets.firstOrNull { it.id == selectedId && it.type == AudioAssetType.SOUNDSCAPE }
            ?.displayName
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    fun setVoiceGuide(packId: String?) {
        viewModelScope.launch {
            if (packId == null) catalog.deselect() else catalog.select(packId)
        }
    }

    fun downloadPack(packId: String) = catalog.download(packId)

    /**
     * iOS parity `MeditationView.swift:295-305 @ db4196e`: tap on the
     * soundscape text toggles mute. When muted the player drops to
     * volume 0; unmute restores the user's persisted volume pref.
     * No-op when no soundscape is selected (the label opens the
     * picker on tap in that case — wired at the call site).
     */
    fun toggleSoundscapeMute() {
        val nowMuted = !_muted.value
        _muted.value = nowMuted
        val targetVolume = if (nowMuted) 0f else soundsPreferences.soundscapeVolume.value
        soundscapePlayer.setVolume(targetVolume)
    }
}
