// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

/**
 * The Way an overview shows and its Begin walks. iOS carries the Way
 * itself from the door to Start; Android carries what finds it again
 * after a process death: an own walk is rebuilt from its source walk's
 * rows each time, and a shared Way is read back from the Ways store. A
 * stage's copy also goes from the door to Start in memory
 * ([HonorStageHandoff]), and is read back from its package only once
 * that is gone.
 */
sealed interface HonorWayChoice {
    data class OwnWalk(val sourceWalkId: Long) : HonorWayChoice

    /** A Way in the store by its id: a listed share (`share:<id>`) or a downloaded stage (`pilgrimage:<route>:<n>`). */
    data class Stored(val wayId: String) : HonorWayChoice
}
