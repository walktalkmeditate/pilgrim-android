// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import androidx.compose.runtime.compositionLocalOf

/**
 * iOS parity `PilgrimLogoView.swift:12-22@db4196e` — the active voice
 * guide pack id, or null when voice guide is disabled / unselected.
 * Resolved at MainActivity scope as
 * `selectedPackId.takeIf { voiceGuideEnabled }` so consumers (e.g.
 * [PilgrimLogo]) can read it directly without a second flag.
 *
 * One of `breeze`, `drift`, `dusk`, `ember`, `river`, `sage`, `stone`,
 * or any future pack id. Composables that swap themed art based on the
 * active guide read this. Other consumers (audio playback, manifest
 * lookup) read from [VoiceGuideSelectionRepository] directly.
 */
val LocalActiveVoiceGuideId = compositionLocalOf<String?> { null }
