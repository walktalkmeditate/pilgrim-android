// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkSnapshotTest {

    private fun sample(
        talk: Long = 0L,
        meditate: Long = 0L,
        durationSec: Double = 1800.0,
    ) = WalkSnapshot(
        id = 1L,
        uuid = "uuid-1",
        startMs = 0L,
        distanceM = 5_000.0,
        durationSec = durationSec,
        averagePaceSecPerKm = 360.0,
        cumulativeDistanceM = 5_000.0,
        talkDurationSec = talk,
        meditateDurationSec = meditate,
        favicon = null,
        isShared = false,
        weatherCondition = null,
    )

    @Test
    fun `walkOnlyDurationSec subtracts talk and meditate`() {
        val s = sample(talk = 300L, meditate = 600L, durationSec = 1800.0)
        assertEquals(900L, s.walkOnlyDurationSec)
    }

    @Test
    fun `walkOnlyDurationSec floors at zero`() {
        val s = sample(talk = 1000L, meditate = 1000L, durationSec = 1500.0)
        assertEquals(0L, s.walkOnlyDurationSec)
    }

    @Test
    fun `hasTalk and hasMeditate flag sub-second values`() {
        val none = sample()
        assertFalse(none.hasTalk)
        assertFalse(none.hasMeditate)
        val both = sample(talk = 1L, meditate = 1L)
        assertTrue(both.hasTalk)
        assertTrue(both.hasMeditate)
    }
}
