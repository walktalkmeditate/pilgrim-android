// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.recordings

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class RecordingsListViewModelSpeedCycleTest {

    @Test
    fun cycleFrom1_0_to1_5() {
        assertEquals(1.5f, nextPlaybackSpeed(1.0f))
    }

    @Test
    fun cycleFrom1_5_to2_0() {
        assertEquals(2.0f, nextPlaybackSpeed(1.5f))
    }

    @Test
    fun cycleFrom2_0_to1_0() {
        assertEquals(1.0f, nextPlaybackSpeed(2.0f))
    }

    @Test
    fun unknownSpeed_resetsTo1_0() {
        assertEquals(1.0f, nextPlaybackSpeed(1.3f))
        assertEquals(1.0f, nextPlaybackSpeed(0.5f))
        assertEquals(1.0f, nextPlaybackSpeed(3.0f))
    }
}
