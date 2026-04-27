// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists the user's sounds-related preferences. Stage 10-B ships
 * only `soundsEnabled` — the master toggle gating bells, soundscapes,
 * and (in subsequent stages) haptics. Future stages add per-event
 * bell IDs, volume sliders, soundscape selection, breath rhythm, and
 * the haptic toggle.
 *
 * Mirrors iOS's `UserPreferences.soundsEnabled` (default true).
 */
interface SoundsPreferencesRepository {
    /** Master sounds toggle. Default true (matches iOS). */
    val soundsEnabled: StateFlow<Boolean>
    suspend fun setSoundsEnabled(value: Boolean)
}
