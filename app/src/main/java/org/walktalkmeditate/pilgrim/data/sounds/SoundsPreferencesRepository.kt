// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's sounds-related preferences. The master toggle
 * [soundsEnabled] gates bells, soundscapes, and (in subsequent stages)
 * haptics; the rest mirror iOS `UserPreferences` so a `.pilgrim` ZIP
 * round-trips between platforms.
 *
 * Wiring the new prefs into players + UI lands in Stage 10-B Chunks D
 * and E; this surface only ships the data layer.
 *
 * Mirrors iOS's `UserPreferences` (Models/Preferences/UserPreferences.swift).
 */
interface SoundsPreferencesRepository {
    /** Master sounds toggle. Default true (matches iOS). */
    val soundsEnabled: StateFlow<Boolean>
    suspend fun setSoundsEnabled(value: Boolean)

    /** Bell-haptic toggle. iOS default true. Gates the haptic
     *  vibration that accompanies a bell strike (NOT all haptics —
     *  see [LocalSoundsEnabled] for the master haptic gate). */
    val bellHapticEnabled: StateFlow<Boolean>
    suspend fun setBellHapticEnabled(value: Boolean)

    /** Bell volume in [0.0, 1.0]. iOS default 0.7. Applied at
     *  BellPlayer.play() time. */
    val bellVolume: StateFlow<Float>
    suspend fun setBellVolume(value: Float)

    /** Soundscape volume in [0.0, 1.0]. iOS default 0.4. Applied at
     *  SoundscapePlayer.play() time. */
    val soundscapeVolume: StateFlow<Float>
    suspend fun setSoundscapeVolume(value: Float)

    /** Asset id of the bell to play at walk-start. Nullable means
     *  "no bell selected." Currently NO consumer on Android (no
     *  walk-start/end bell ports yet); persisted for cross-platform
     *  .pilgrim ZIP parity. */
    val walkStartBellId: StateFlow<String?>
    suspend fun setWalkStartBellId(value: String?)

    val walkEndBellId: StateFlow<String?>
    suspend fun setWalkEndBellId(value: String?)

    /** Asset id of the bell to play at meditation start. Consumed by
     *  [org.walktalkmeditate.pilgrim.audio.MeditationBellObserver]
     *  (wiring lands in Chunk D). */
    val meditationStartBellId: StateFlow<String?>
    suspend fun setMeditationStartBellId(value: String?)

    val meditationEndBellId: StateFlow<String?>
    suspend fun setMeditationEndBellId(value: String?)

    /** Index into [BreathRhythm.all]. iOS default 0 (Calm). Consumed
     *  by [org.walktalkmeditate.pilgrim.ui.meditation.BreathingCircle]
     *  (wiring lands in Chunk D). */
    val breathRhythm: StateFlow<Int>
    suspend fun setBreathRhythm(value: Int)
}
