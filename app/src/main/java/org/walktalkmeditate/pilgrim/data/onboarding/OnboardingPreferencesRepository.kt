// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.onboarding

import kotlinx.coroutines.flow.StateFlow

/**
 * Tracks whether the user has completed the first-launch Welcome
 * ritual. iOS parity v1.6.0 — Welcome only shows once. Subsequent
 * launches skip straight to the Permissions screen (which itself
 * skips to PATH if all required permissions are granted).
 */
interface OnboardingPreferencesRepository {
    /** True once the user has tapped Begin on the Welcome screen. */
    val welcomeCompleted: StateFlow<Boolean>

    suspend fun markWelcomeCompleted()
}
