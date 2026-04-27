// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings

/**
 * Sealed interface modeling every navigation destination + system-intent
 * trigger emitted by the Settings screen and its nested cards. Replaces
 * the per-callback signature pattern (which was approaching 10+ params
 * after Stage 10's card additions) with a single `onAction` channel
 * that the host (PilgrimNavHost) routes to navController calls or
 * Intent dispatches.
 *
 * Stage 10-A introduces this with two destinations active (voice guides,
 * soundscapes — preserving the Stage 5-D / 5-F entry points). Subsequent
 * stages (10-B onward) extend this enum WITHOUT changing the
 * `SettingsScreen` signature.
 */
sealed interface SettingsAction {
    data object OpenVoiceGuides : SettingsAction
    data object OpenSoundscapes : SettingsAction

    // Reserved for upcoming stages — defined now so the navigation
    // routing hub in PilgrimNavHost can be exhaustive on `when`.
    data object OpenBellsAndSoundscapes : SettingsAction
    data object OpenRecordings : SettingsAction
    data object OpenAppPermissionSettings : SettingsAction
    data object OpenExportImport : SettingsAction
    data object OpenJourneyViewer : SettingsAction
    data object OpenFeedback : SettingsAction
    data object OpenAbout : SettingsAction
    data object OpenPodcast : SettingsAction
    data object OpenPlayStoreReview : SettingsAction
    data object SharePilgrim : SettingsAction
}
