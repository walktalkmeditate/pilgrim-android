// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

/**
 * Sum of haversine distances between consecutive [LocationPoint]s.
 * Returns 0 for lists with fewer than two samples. Pure domain
 * function so both [org.walktalkmeditate.pilgrim.walk.WalkController]
 * (restoring from Room) and the summary VM (replaying a finished
 * walk) share one implementation. When we add an accuracy filter or
 * null-island rejection in Stage 1-G, this is the only place it
 * needs to go.
 */
fun walkDistanceMeters(points: List<LocationPoint>): Double {
    var distance = 0.0
    var last: LocationPoint? = null
    for (point in points) {
        if (last != null) distance += haversineMeters(last, point)
        last = point
    }
    return distance
}
