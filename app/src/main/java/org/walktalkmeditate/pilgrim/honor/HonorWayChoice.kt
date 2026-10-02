// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

/**
 * The Way an overview shows and its Begin walks. iOS carries the Way
 * itself from the door to Start; Android carries what finds it again
 * after a process death: an own walk is rebuilt from its source walk's
 * rows each time, and a shared Way is read back from the Ways store.
 */
sealed interface HonorWayChoice {
    data class OwnWalk(val sourceWalkId: Long) : HonorWayChoice

    /** A listed Way, by its store id (`share:<id>`). */
    data class Stored(val wayId: String) : HonorWayChoice
}
