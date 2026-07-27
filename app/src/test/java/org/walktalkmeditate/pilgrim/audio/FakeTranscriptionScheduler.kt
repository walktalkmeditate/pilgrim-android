// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

class FakeTranscriptionScheduler : TranscriptionScheduler {
    val scheduledWalkIds = mutableListOf<Long>()
    val rescheduledWalkIds = mutableListOf<Long>()

    override fun scheduleForWalk(walkId: Long) {
        scheduledWalkIds += walkId
    }

    override fun rescheduleForWalk(walkId: Long) {
        rescheduledWalkIds += walkId
    }
}
