// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists practice-related preferences surfaced on the Settings →
 * Practice card. Stage 10-C ships:
 *   - `beginWithIntention` (default false) — auto-show intention sheet
 *     after walk start.
 *   - `celestialAwarenessEnabled` (default false) — gate light-reading
 *     and etegami celestial layers.
 *   - `zodiacSystem` (default Tropical) — astrology system used by
 *     the celestial calculator.
 *   - `walkReliquaryEnabled` (default false) — gate the post-walk
 *     photo picker auto-suggestion.
 *
 * Mirrors iOS's `UserPreferences.beginWithIntention`,
 * `celestialAwarenessEnabled`, `zodiacSystem`, `walkReliquaryEnabled`
 * (verbatim DataStore keys for cross-platform .pilgrim ZIP parity).
 */
interface PracticePreferencesRepository {
    val beginWithIntention: StateFlow<Boolean>
    suspend fun setBeginWithIntention(value: Boolean)

    val celestialAwarenessEnabled: StateFlow<Boolean>
    suspend fun setCelestialAwarenessEnabled(value: Boolean)

    val zodiacSystem: StateFlow<ZodiacSystem>
    suspend fun setZodiacSystem(value: ZodiacSystem)

    val walkReliquaryEnabled: StateFlow<Boolean>
    suspend fun setWalkReliquaryEnabled(value: Boolean)
}
