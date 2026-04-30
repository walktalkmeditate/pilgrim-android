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
     * by the milestone overlay (scale=0.4f) so a user who muted bells
     * still hears no sound. Default body delegates to [play] — existing
     * fakes continue to work without compile changes.
     *
     * Does NOT pair haptic (iOS milestone path is silent on haptic).
     * Stage 12-C added the haptic-coupled [play] (scale, withHaptic)
     * overload below; sites that need haptic must call that one
     * explicitly.
     */
    fun play(scale: Float) {
        play()
    }

    /**
     * Fire the bell at [scale] × user's bell-volume preference, optionally
     * paired with a `.medium` haptic.
     *
     * iOS-faithful default: `withHaptic = true`. Sites that don't want
     * haptic (e.g. milestone overlay, which iOS does NOT pair with
     * haptic) pass `withHaptic = false` explicitly.
     *
     * The implementation gates the haptic on
     * [org.walktalkmeditate.pilgrim.data.sounds.SoundsPreferencesRepository.bellHapticEnabled];
     * `withHaptic = true` is a request, not a guarantee.
     *
     * Default body delegates to [play] (no scale) so existing fakes that
     * only implement the no-arg [play] keep compiling. Production
     * [org.walktalkmeditate.pilgrim.audio.BellPlayer] overrides this
     * with the real audio + haptic path.
     *
     * Note: callers like the milestone overlay invoking
     * `play(scale = 0.4f)` still bind to the 1-arg [play] (scale)
     * overload — Kotlin prefers the more specific match over an
     * overload with default args.
     */
    fun play(scale: Float, withHaptic: Boolean) {
        play(scale)
    }
}
