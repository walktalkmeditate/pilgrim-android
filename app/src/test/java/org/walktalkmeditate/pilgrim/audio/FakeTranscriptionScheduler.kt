// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio

/**
 * Production callers schedule from background coroutines while tests
 * assert from the test thread, so the recorded ids cross threads. Each
 * write publishes a fresh immutable snapshot under the instance lock:
 * the `@Volatile` read gives the reader a happens-before edge to the
 * write, and the snapshot can't be mutated mid-comparison by a
 * concurrent scheduler call.
 */
class FakeTranscriptionScheduler : TranscriptionScheduler {
    @Volatile private var scheduled: List<Long> = emptyList()
    @Volatile private var rescheduled: List<Long> = emptyList()

    val scheduledWalkIds: List<Long> get() = scheduled
    val rescheduledWalkIds: List<Long> get() = rescheduled

    @Synchronized
    override fun scheduleForWalk(walkId: Long) {
        scheduled = scheduled + walkId
    }

    @Synchronized
    override fun rescheduleForWalk(walkId: Long) {
        rescheduled = rescheduled + walkId
    }
}
