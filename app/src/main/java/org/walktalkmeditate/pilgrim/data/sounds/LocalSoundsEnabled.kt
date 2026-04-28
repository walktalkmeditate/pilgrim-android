// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import androidx.compose.runtime.compositionLocalOf

/**
 * CompositionLocal exposing the user's master sounds preference to
 * any Composable that fires haptics (or other gated audio surfaces).
 * Default `true` keeps every screen audible if the provider is missing
 * — fail-open is the safer default than fail-silent for first-launch
 * before DataStore returns. The provider sits at MainActivity.setContent
 * level so the value cascades to every screen the user navigates to.
 *
 * Use:
 * ```
 * val soundsEnabled = LocalSoundsEnabled.current
 * if (soundsEnabled) haptic.performHapticFeedback(...)
 * ```
 */
val LocalSoundsEnabled = compositionLocalOf { true }
