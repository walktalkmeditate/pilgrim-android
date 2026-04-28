// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Minimal bell-playing abstraction so [MeditationBellObserver] tests
 * can inject a counting fake without constructing a real [BellPlayer]
 * (which requires a Robolectric Application context + AudioManager).
 *
 * Stage 5-D may later introduce a voice-guide-aware player that also
 * implements this interface — the observer stays decoupled from the
 * concrete implementation across sub-stages.
 */
interface BellPlaying {
    /**
     * Fire the bell once at the user's configured bell volume (read
     * from [org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository.bellVolume]
     * by the implementation). Non-blocking. No-op if audio focus is
     * denied. Safe to call at any time.
     */
    fun play()
}
