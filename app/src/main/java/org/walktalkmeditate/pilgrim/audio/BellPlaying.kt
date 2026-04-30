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
     * denied. Safe to call at any time. Does NOT pair haptic.
     */
    fun play()

    /**
     * Fire the bell at [scale] × user's bell-volume preference. Used
     * by sites that don't want haptic. Default body delegates to [play]
     * — existing fakes continue to work without compile changes.
     *
     * Does NOT pair haptic. Sites that want haptic must call the 2-arg
     * [play] (scale, withHaptic) overload below explicitly. Note: the
     * milestone overlay calls the 2-arg overload with `withHaptic = true`
     * to match iOS exactly — iOS BellPlayer.swift:14 declares
     * `func play(_ asset, volume: Float = 0.7, withHaptic: Bool = true)`
     * and the milestone caller at PracticeSummaryHeader.swift:92
     * calls `play(asset, volume: 0.4)` without overriding `withHaptic`,
     * so the iOS default (true) fires.
     */
    fun play(scale: Float) {
        play()
    }

    /**
     * Fire the bell at [scale] × user's bell-volume preference, optionally
     * paired with a `.medium` haptic.
     *
     * iOS-faithful default: `withHaptic = true`. Mirrors iOS
     * `BellPlayer.swift:14`'s `func play(_ asset, volume: Float = 0.7,
     * withHaptic: Bool = true)`. The milestone overlay relies on this
     * default — iOS PracticeSummaryHeader.swift:92 calls
     * `play(asset, volume: 0.4)` without overriding `withHaptic`, so
     * iOS DOES pair haptic for milestone celebrations.
     *
     * The implementation gates the haptic on
     * [org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository.bellHapticEnabled];
     * `withHaptic = true` is a request, not a guarantee.
     *
     * Default body delegates to [play] (no scale) so existing fakes that
     * only implement the no-arg [play] keep compiling. Production
     * [org.walktalkmeditate.pilgrim.audio.BellPlayer] overrides this
     * with the real audio + haptic path.
     */
    fun play(scale: Float, withHaptic: Boolean) {
        play(scale)
    }
}
