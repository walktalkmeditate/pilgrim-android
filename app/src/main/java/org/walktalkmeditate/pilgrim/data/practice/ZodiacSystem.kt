// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

/**
 * Astrology system for celestial calculations. Mirrors iOS's
 * `UserPreferences.zodiacSystem` (default `"tropical"`).
 *
 * Stored on disk as the lowercase string form of [name] for cross-
 * platform parity with iOS UserDefaults.
 */
enum class ZodiacSystem {
    Tropical,
    Sidereal;

    fun storageValue(): String = name.lowercase()

    companion object {
        val DEFAULT: ZodiacSystem = Tropical

        fun fromStorageValue(stored: String?): ZodiacSystem = when (stored) {
            "tropical" -> Tropical
            "sidereal" -> Sidereal
            else -> DEFAULT
        }
    }
}
