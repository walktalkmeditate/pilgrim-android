// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.permissions

/**
 * The "grant ritual" decision for the onboarding permission screen (iOS PR
 * #45 / #43, `PermissionRitual.swift`): whether the celebratory bell should
 * sound when a permission is granted, plus the once-per-grant persistence
 * that stops it replaying when the user re-enters onboarding or relaunches.
 *
 * The decision is a pure function so the bell-once-per-grant logic and the
 * `soundsEnabled` gate are unit-testable without DataStore or audio.
 *
 * Mirrors iOS's three ritual permissions — notification is deliberately
 * excluded (iOS's `Permission` enum rituals location/microphone/motion only);
 * Android's "motion" analogue is activity recognition.
 */
object PermissionRitual {

    enum class Permission(val key: String) {
        Location("location"),
        Microphone("microphone"),
        Activity("activity"),
    }

    /**
     * Should the grant bell fire for this permission right now? Pure: every
     * input is passed in. The bell sounds only when the permission was just
     * granted, the user keeps sounds on, and the bell hasn't already been
     * played for this permission on a previous pass.
     */
    fun shouldPlayBell(
        granted: Boolean,
        soundsEnabled: Boolean,
        alreadyPlayed: Boolean,
    ): Boolean = granted && soundsEnabled && !alreadyPlayed
}

/**
 * Persists the once-per-grant flag for the [PermissionRitual] bell. Extracted
 * as an interface so [PermissionsViewModel] can be unit-tested against an
 * in-memory fake instead of a real DataStore. The production binding is
 * [PermissionsRepository].
 */
interface PermissionRitualStore {

    /**
     * Atomically decide whether the grant bell should fire for [permission]
     * (granted is implied — only called on a real grant) and, if so, mark it
     * played so a second grant event for the same permission stays silent.
     * Returns `true` exactly once per permission while [soundsEnabled].
     *
     * A silenced grant (sounds off) does NOT consume the flag, so the bell
     * can still ring the first time the user grants with sounds back on.
     */
    suspend fun consumeBellGrant(
        permission: PermissionRitual.Permission,
        soundsEnabled: Boolean,
    ): Boolean

    /** Whether the grant bell has already fired for [permission]. */
    suspend fun hasPlayedBell(permission: PermissionRitual.Permission): Boolean
}
