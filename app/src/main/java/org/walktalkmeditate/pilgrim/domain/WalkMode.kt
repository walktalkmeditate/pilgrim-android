// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * The contemplative posture for a walk session, in iOS's order
 * (`WalkMode.swift:3-4@7c200bf`: `wander, honor, seek`). Never persisted:
 * a walk's mode is derived from its events ([walkModeFromEvents]).
 */
enum class WalkMode {
    Wander, Honor, Seek;

    /**
     * iOS offers every mode (`isAvailable { true }`). Honor ships dark
     * behind the release flag, so with [honorEnabled] off its slot keeps
     * the "coming soon" state Android showed before 2.0.0.
     */
    fun isAvailable(honorEnabled: Boolean): Boolean = this != Honor || honorEnabled

    companion object {
        /**
         * Forgiving parse for wire values (nav arguments, service intent
         * extras), which are the enum names. Unknown or absent values
         * collapse to [Wander] so a stale intent from a future binary can
         * never crash the start path — mirrors the UNKNOWN convention in
         * [org.walktalkmeditate.pilgrim.domain.WalkEventType].
         */
        fun fromWire(value: String?): WalkMode =
            entries.firstOrNull { it.name == value } ?: Wander
    }
}
