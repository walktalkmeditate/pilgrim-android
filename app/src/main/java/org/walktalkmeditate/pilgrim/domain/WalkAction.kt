// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain

sealed class WalkAction {
    data class Start(val walkId: Long, val at: Long) : WalkAction()
    data class Pause(val at: Long) : WalkAction()
    data class Resume(val at: Long) : WalkAction()
    data class MeditateStart(val at: Long) : WalkAction()
    data class MeditateEnd(val at: Long) : WalkAction()
    data class Finish(val at: Long) : WalkAction()
    data class LocationSampled(val point: LocationPoint) : WalkAction()
}
