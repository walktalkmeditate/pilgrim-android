// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

/**
 * Why the walk screen's Start refused an honor walk, as the walker is told
 * (owner decision 5, 2026-09-30). iOS has neither case: it carries the Way
 * in memory from the door to Start, so nothing can be missing or unstaged.
 */
enum class HonorStartRefusal {
    /** Staging the Way failed: "Pilgrim couldn't get this walk ready to follow. Try again." */
    CouldNotPrepare,

    /**
     * The source walk went away, or no longer has a route to follow: "This
     * walk isn't here to follow anymore. Try another."
     */
    Gone,
    ;

    companion object {
        /** Null for the flag-off refusal, which no surface can reach. */
        fun of(reason: BeginHonorWalk.Refusal): HonorStartRefusal? = when (reason) {
            BeginHonorWalk.Refusal.DISABLED -> null
            BeginHonorWalk.Refusal.STAGING_FAILED -> CouldNotPrepare
            BeginHonorWalk.Refusal.SOURCE_MISSING, BeginHonorWalk.Refusal.NOT_WALKABLE -> Gone
        }
    }
}
