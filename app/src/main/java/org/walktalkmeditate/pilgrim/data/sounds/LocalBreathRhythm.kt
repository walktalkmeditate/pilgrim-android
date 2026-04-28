// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal exposing the user's chosen breath-rhythm id to
 * [org.walktalkmeditate.pilgrim.ui.meditation.BreathingCircle] (and any
 * future composable that wants to react to the meditation cadence).
 *
 * Provided at MainActivity.setContent level from
 * [SoundsPreferencesRepository.breathRhythm]; default
 * [BreathRhythm.DEFAULT_ID] (Calm — 5/7) keeps the circle animating
 * sensibly if the provider is missing (preview, headless test).
 *
 * Use:
 * ```
 * val rhythm = BreathRhythm.byId(LocalBreathRhythm.current)
 * BreathingCircle(moss = ..., breathRhythm = rhythm)
 * ```
 *
 * Sibling of [LocalSoundsEnabled] / [LocalBellHapticEnabled] — the
 * trio bridges sounds-related prefs to UI without retrofitting every
 * composable's ViewModel.
 */
val LocalBreathRhythm = compositionLocalOf { BreathRhythm.DEFAULT_ID }
