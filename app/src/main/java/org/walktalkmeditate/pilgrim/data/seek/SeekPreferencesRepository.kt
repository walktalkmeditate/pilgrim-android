// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import kotlinx.coroutines.flow.StateFlow

/**
 * Persists Seek Mode preferences. Mirrors iOS's
 * `UserPreferences.seekSonarEnabled` / `seekSonarVolume` /
 * `seekLastDurationMinutes` / `seekSafetyShown`
 * (`UserPreferences.swift:72-75@c1745e8`, verbatim DataStore keys for
 * cross-platform `.pilgrim` settings parity).
 *
 * Runtime status of each pref (U5):
 *   - `sonarEnabled`: WIRED. Read at play time by
 *     [org.walktalkmeditate.pilgrim.audio.seek.SeekSoundPlayer] so a
 *     mid-walk flip applies to the very next ping. Gates the sonar
 *     ping only — the reveal bowl deliberately ignores it.
 *   - `sonarVolume`: WIRED. Multiplied into every ping and bowl at
 *     play time.
 *   - `lastDurationMinutes`: PERSISTED only. iOS reads it as the
 *     chain-generation fallback duration
 *     (`ActiveWalkViewModel+Seek.swift:83@c1745e8`); the Android
 *     consumer lands with the seek gateway (U8).
 *   - `safetyShown`: PERSISTED only. Gates the one-time seek safety
 *     sheet; consumer lands with the gateway (U8).
 */
interface SeekPreferencesRepository {
    /** Sonar ping audio toggle (default true). */
    val sonarEnabled: StateFlow<Boolean>
    suspend fun setSonarEnabled(value: Boolean)

    /** Sonar/bowl volume in [0, 1] (default 0.5). */
    val sonarVolume: StateFlow<Float>

    /** Clamps to [0, 1]; NaN falls back to the default (0.5). */
    suspend fun setSonarVolume(value: Float)

    /** Last chosen seek duration in minutes (default 60). */
    val lastDurationMinutes: StateFlow<Int>
    suspend fun setLastDurationMinutes(value: Int)

    /** Whether the one-time seek safety sheet has been shown (default false). */
    val safetyShown: StateFlow<Boolean>
    suspend fun setSafetyShown(value: Boolean)
}
