// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalligraphyHashTest {
    private fun spec(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMillis: Long = 1_700_000_000_000L,
        distance: Double = 4321.0,
        pace: Double = 600.0,
    ) = CalligraphyStrokeSpec(uuid, startMillis, distance, pace, Color.Black)

    @Test fun `hash is deterministic for identical specs`() {
        assertEquals(fnv1aHash(spec()), fnv1aHash(spec()))
    }

    @Test fun `hash differs for different uuids`() {
        val a = fnv1aHash(spec(uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        val b = fnv1aHash(spec(uuid = "bbbbbbbb-cccc-dddd-eeee-ffffffffffff"))
        assertNotEquals(a, b)
    }

    @Test fun `hash differs for different timestamps`() {
        val a = fnv1aHash(spec(startMillis = 1_000L))
        val b = fnv1aHash(spec(startMillis = 2_000L))
        assertNotEquals(a, b)
    }

    @Test fun `hash differs for different distances`() {
        val a = fnv1aHash(spec(distance = 1000.0))
        val b = fnv1aHash(spec(distance = 2000.0))
        assertNotEquals(a, b)
    }

    @Test fun `meander seed stays in range -1 to 1`() {
        repeat(100) { i ->
            val s = spec(uuid = "seed-$i", startMillis = 1_000L + i, distance = i.toDouble())
            val m = meanderSeed(s)
            assertTrue("seed=$m for spec=$s", m in -1f..1f)
        }
    }

    @Test fun `xOffset stays within centerX plus-or-minus maxMeander times 0_8`() {
        val centerX = 500f
        val max = 100f
        repeat(100) { i ->
            val s = spec(uuid = "x-$i", startMillis = i.toLong(), distance = (i * 10).toDouble())
            val x = xOffsetPx(s, centerX, max)
            val delta = x - centerX
            assertTrue("x=$x delta=$delta", delta in -max * 0.8f..max * 0.8f)
        }
    }
}
