// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

/**
 * User-selectable app appearance. Mirrors the iOS `appearanceMode`
 * preference (`UserPreferences.appearanceMode`):
 *
 * - [System]: respect `isSystemInDarkTheme()` (default for fresh installs).
 * - [Light]: force light theme regardless of system setting.
 * - [Dark]: force dark theme regardless of system setting.
 * - [Constellation]: resolves to a dark color scheme but swaps the
 *   parchment palette for a starlit indigo/lavender override, and the
 *   app renders a `ConstellationOverlay` (animated stars + nebulae +
 *   cosmic gradient) on top of every surface. Matches iOS v1.6.0
 *   `AppearanceMode.constellation`.
 *
 * Stored in DataStore as the lowercase string form of [name]
 * (`"system" / "light" / "dark" / "constellation"`) for cross-platform
 * parity with iOS's `UserDefaults` storage.
 */
enum class AppearanceMode {
    System,
    Light,
    Dark,
    Constellation;

    /** Lowercase storage form (matches iOS string keys). */
    fun storageValue(): String = name.lowercase()

    /** True for the Constellation override; convenience for theme plumbing. */
    val isConstellation: Boolean get() = this == Constellation

    companion object {
        /** Default for fresh installs: respect system. */
        val DEFAULT: AppearanceMode = System

        /**
         * Decode a stored string back into an [AppearanceMode]. Unknown
         * values fall back to [DEFAULT] — guards against manual DataStore
         * edits and forward-compat (a future build adding e.g. `"sepia"`
         * shouldn't crash on downgrade).
         */
        fun fromStorageValue(stored: String?): AppearanceMode = when (stored) {
            "system" -> System
            "light" -> Light
            "dark" -> Dark
            "constellation" -> Constellation
            else -> DEFAULT
        }
    }
}
