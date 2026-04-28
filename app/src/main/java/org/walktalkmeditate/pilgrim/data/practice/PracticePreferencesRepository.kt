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
 *
 * Runtime status of each pref (Stage 10-C Chunk E):
 *   - `beginWithIntention`: WIRED. Read by `WalkViewModel` and
 *     observed in `ActiveWalkScreen` to auto-prompt the intention
 *     dialog 0.5s after the walk transitions to Active. Mirrors iOS
 *     `ActiveWalkView.swift:374`.
 *   - `celestialAwarenessEnabled`: WIRED. Read by
 *     `WalkSummaryViewModel.buildState` to gate the
 *     `WalkSummary.lightReading` field — when the pref is off the
 *     summary card is suppressed at the VM layer. Mirrors iOS
 *     `ActiveWalkView.swift:379`.
 *   - `zodiacSystem`: PERSISTED only. iOS feeds the value into
 *     `CelestialCalculator.snapshot(for:Date(), system:)` to compute a
 *     zodiac sign + planetary hour with sidereal ayanamsa correction.
 *     Android's celestial calculator (Stage 6-A) is tropical-only and
 *     does not yet emit a zodiac sign — the runtime consumer lands
 *     when the zodiac/planetary-hour port is implemented.
 *   - `walkReliquaryEnabled`: PERSISTED only. iOS gates a post-walk
 *     photo-suggestion auto-prompt. Android's `PhotoReliquarySection`
 *     does not auto-prompt — the user opens the picker via the FAB —
 *     so the runtime consumer lands when photo-auto-suggestion is
 *     implemented.
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
