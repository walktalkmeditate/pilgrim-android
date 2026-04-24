// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.photo

/**
 * Test fake for [PhotoAnalysisScheduler]. Records every scheduled
 * walk id so VM tests can assert the "after pinPhotos, analysis gets
 * scheduled" wiring without actually touching WorkManager.
 */
class FakePhotoAnalysisScheduler : PhotoAnalysisScheduler {
    val scheduleForWalkCalls: MutableList<Long> = mutableListOf()

    override fun scheduleForWalk(walkId: Long) {
        scheduleForWalkCalls += walkId
    }
}
