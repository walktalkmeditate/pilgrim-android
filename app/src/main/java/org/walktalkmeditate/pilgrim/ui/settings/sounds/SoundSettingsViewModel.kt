// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.sounds

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset
import org.walktalkmeditate.pilgrim.data.audio.AudioAssetType
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestService
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeFileStore
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeSelectionRepository
import org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository

/**
 * Stage 10-B: ViewModel for [SoundSettingsScreen]. Surfaces every
 * sound-related preference (master toggle, haptic, per-event bell
 * IDs, volumes, breath rhythm, soundscape selection) as passthrough
 * StateFlows + thin setters. Same defensive-launch pattern as
 * [SettingsViewModel.setAppearanceMode]: each setter wraps the
 * suspend write in `runCatching` so a DataStore I/O failure logs and
 * lets the optimistic UI revert on the StateFlow re-emit, rather
 * than crashing the app.
 *
 * Also exposes the bell catalog (filtered to `type = bell`) and the
 * soundscape catalog from [AudioManifestService] — the screen's
 * picker sheets read those lists directly. Total disk usage is
 * derived on demand via [SoundscapeFileStore.totalSize] and
 * republished whenever the file store fires an invalidation
 * (delete / clear-all paths emit on `invalidations`).
 */
@HiltViewModel
class SoundSettingsViewModel @Inject constructor(
    private val soundsPreferences: SoundsPreferencesRepository,
    private val soundscapeSelection: SoundscapeSelectionRepository,
    private val manifestService: AudioManifestService,
    private val fileStore: SoundscapeFileStore,
    private val downloadScheduler: org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeDownloadScheduler,
) : ViewModel() {

    val soundsEnabled: StateFlow<Boolean> = soundsPreferences.soundsEnabled
    val bellHapticEnabled: StateFlow<Boolean> = soundsPreferences.bellHapticEnabled
    val bellVolume: StateFlow<Float> = soundsPreferences.bellVolume
    val soundscapeVolume: StateFlow<Float> = soundsPreferences.soundscapeVolume

    val walkStartBellId: StateFlow<String?> = soundsPreferences.walkStartBellId
    val walkEndBellId: StateFlow<String?> = soundsPreferences.walkEndBellId
    val meditationStartBellId: StateFlow<String?> = soundsPreferences.meditationStartBellId
    val meditationEndBellId: StateFlow<String?> = soundsPreferences.meditationEndBellId
    val breathRhythm: StateFlow<Int> = soundsPreferences.breathRhythm

    val selectedSoundscapeId: StateFlow<String?> = soundscapeSelection.selectedSoundscapeId

    val availableBells: StateFlow<List<AudioAsset>> = manifestService.assets
        .map { catalog -> catalog.filter { it.type == AudioAssetType.BELL } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val availableSoundscapes: StateFlow<List<AudioAsset>> = manifestService.assets
        .map { catalog -> catalog.filter { it.type == AudioAssetType.SOUNDSCAPE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _totalDiskUsageBytes = MutableStateFlow(0L)
    /**
     * Total bytes occupied by cached soundscape files. Recomputed when
     * either the manifest changes or the file store fires an
     * invalidation (delete / clearAll paths). Initial recompute is
     * triggered from `init`.
     */
    val totalDiskUsageBytes: StateFlow<Long> = _totalDiskUsageBytes.asStateFlow()

    init {
        viewModelScope.launch {
            recomputeDiskUsage()
        }
        viewModelScope.launch {
            fileStore.invalidations.collect { recomputeDiskUsage() }
        }
        viewModelScope.launch {
            // Recompute on manifest changes too — the catalog flips from
            // empty to populated on first cache load, and `totalSize`
            // doesn't depend on the manifest contents (it scans the
            // filesystem) but kicking it once after init lands is
            // cheap and produces a stable initial number.
            manifestService.assets.collect { recomputeDiskUsage() }
        }
    }

    fun setSoundsEnabled(value: Boolean) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setSoundsEnabled(value) }
                .onFailure { Log.w(TAG, "failed to persist sounds toggle", it) }
        }
    }

    fun setBellHapticEnabled(value: Boolean) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setBellHapticEnabled(value) }
                .onFailure { Log.w(TAG, "failed to persist bell haptic toggle", it) }
        }
    }

    fun setBellVolume(value: Float) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setBellVolume(value.coerceIn(0f, 1f)) }
                .onFailure { Log.w(TAG, "failed to persist bell volume", it) }
        }
    }

    fun setSoundscapeVolume(value: Float) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setSoundscapeVolume(value.coerceIn(0f, 1f)) }
                .onFailure { Log.w(TAG, "failed to persist soundscape volume", it) }
        }
    }

    fun setWalkStartBellId(value: String?) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setWalkStartBellId(value) }
                .onFailure { Log.w(TAG, "failed to persist walk-start bell id", it) }
        }
    }

    fun setWalkEndBellId(value: String?) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setWalkEndBellId(value) }
                .onFailure { Log.w(TAG, "failed to persist walk-end bell id", it) }
        }
    }

    fun setMeditationStartBellId(value: String?) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setMeditationStartBellId(value) }
                .onFailure { Log.w(TAG, "failed to persist meditation-start bell id", it) }
        }
    }

    fun setMeditationEndBellId(value: String?) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setMeditationEndBellId(value) }
                .onFailure { Log.w(TAG, "failed to persist meditation-end bell id", it) }
        }
    }

    fun setSelectedSoundscapeId(value: String?) {
        viewModelScope.launch {
            runCatching {
                if (value == null) soundscapeSelection.deselect()
                else soundscapeSelection.select(value)
            }.onFailure { Log.w(TAG, "failed to persist soundscape selection", it) }
        }
    }

    fun setBreathRhythm(value: Int) {
        viewModelScope.launch {
            runCatching { soundsPreferences.setBreathRhythm(value) }
                .onFailure { Log.w(TAG, "failed to persist breath rhythm", it) }
        }
    }

    /**
     * Wipes every cached soundscape file. Cancels any in-flight
     * download workers FIRST so a running worker doesn't immediately
     * write the file back to disk after the sweep — leaving a
     * partially-written file that `isAvailable` would reject (silent
     * playback) but the storage UI's byte counter would still report
     * (Stage 5-D delete-then-cancel-worker lesson, applied here).
     *
     * The file store's invalidation stream republishes
     * [totalDiskUsageBytes] back to ~0 once the sweep completes and
     * any downstream availability checks see the deletes immediately.
     */
    fun clearAllDownloads() {
        viewModelScope.launch {
            runCatching {
                manifestService.soundscapes().forEach { asset ->
                    downloadScheduler.cancel(asset.id)
                }
                fileStore.clearAll()
            }.onFailure { Log.w(TAG, "failed to clear all soundscape downloads", it) }
        }
    }

    private suspend fun recomputeDiskUsage() {
        runCatching { fileStore.totalSize() }
            .onSuccess { _totalDiskUsageBytes.value = it }
            .onFailure { Log.w(TAG, "totalSize failed", it) }
    }

    private companion object {
        const val TAG = "SoundSettingsVM"
    }
}
