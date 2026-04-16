// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Domain enum for walk-lifecycle events. Persisted via
 * [org.walktalkmeditate.pilgrim.data.entity.WalkEvent] and referenced by
 * the reducer's side-effect contract in [WalkEffect].
 *
 * Lives in the domain package (not the Room entity package) so the pure
 * state machine has no compile-time dependency on the persistence layer.
 */
enum class WalkEventType {
    PAUSED,
    RESUMED,
    MEDITATION_START,
    MEDITATION_END,
    WAYPOINT_MARKED,
}
