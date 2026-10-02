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

    /**
     * Written once at recording start when the walk is a seek
     * (iOS `WalkEvent.EventType.seekMode`). Its timestamp is the
     * summary's "seeded at" provenance source.
     */
    SEEK_MODE,

    /**
     * Written per reached clearing, paired with a waypoint carrying
     * [org.walktalkmeditate.pilgrim.domain.seek.SeekPersistence.ARRIVAL_WAYPOINT_ICON]
     * (iOS `WalkEvent.EventType.seekArrival`).
     */
    SEEK_ARRIVAL,

    /**
     * Written once at recording start when the walk honors a Way
     * (iOS `WalkEvent.EventType.honorMode`). Until the flag-gated Honor
     * mode lands, [walkModeFromEvents] still reads such a walk as Wander;
     * the event is kept so `.pilgrim` round-trips preserve it.
     */
    HONOR_MODE,

    /**
     * Written on reaching the end of the Way, paired with a waypoint
     * carrying [org.walktalkmeditate.pilgrim.domain.honor.HonorPersistence.ARRIVAL_WAYPOINT_ICON]
     * (iOS `WalkEvent.EventType.honorArrival`).
     */
    HONOR_ARRIVAL,

    /**
     * Forward-compat fallback (iOS `WalkEvent.EventType.unknown`): a
     * stored or imported value this binary doesn't know reads as
     * UNKNOWN instead of crashing or masquerading as a real event.
     * Point marker with no replay semantics.
     */
    UNKNOWN,
}
