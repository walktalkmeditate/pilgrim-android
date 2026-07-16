// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WalkThresholdTest {

    private fun ref(
        id: Long,
        startMs: Long = id * 1_000L,
        uuid: String = "uuid-" + id.toString().padStart(3, '0'),
    ) = WalkThresholds.WalkRef(walkId = id, uuid = uuid, startMs = startMs)

    @Test
    fun `practice gates stand at walk 1 and every 10th`() {
        val walks = (1L..25L).map { ref(it) }
        val thresholds = WalkThresholds.compute(walks, emptyMap())
        assertEquals(
            mapOf(
                1L to WalkThreshold.Practice,
                10L to WalkThreshold.Practice,
                20L to WalkThreshold.Practice,
            ),
            thresholds,
        )
    }

    @Test
    fun `first arrival walk stands at a seeking gate`() {
        val walks = (1L..5L).map { ref(it) }
        val thresholds = WalkThresholds.compute(walks, mapOf(3L to 1))
        assertEquals(WalkThreshold.Seeking, thresholds[3L])
    }

    @Test
    fun `seeking outranks practice on a milestone-crossing tenth walk`() {
        val walks = (1L..10L).map { ref(it) }
        val thresholds = WalkThresholds.compute(walks, mapOf(10L to 1))
        assertEquals(
            "mystery outranks routine — the tenth walk that found its first unknown stands at a seeking gate",
            WalkThreshold.Seeking,
            thresholds[10L],
        )
    }

    @Test
    fun `arrivals without a crossing earn no gate`() {
        // Walk 2 carries the first arrival (FirstUnknown); walk 3 adds one
        // more with arrivalsBefore = 1 — no milestone, no gate.
        val walks = (1L..3L).map { ref(it) }
        val thresholds = WalkThresholds.compute(walks, mapOf(2L to 1, 3L to 1))
        assertEquals(WalkThreshold.Seeking, thresholds[2L])
        assertNull(thresholds[3L])
    }

    @Test
    fun `the gate lands on the crossing walk - arrivalsBefore counts strictly earlier walks excluding self`() {
        // Walk 2: first arrivals (8) → FirstUnknown. Walk 4: 2 more —
        // before = 8, total = 10, crosses the 10 threshold. Walk 5: 1 more —
        // before = 10, total = 11, crosses nothing. Walk 4's own arrivals
        // never count as "before" (otherwise it would see 10 and lose the
        // crossing).
        val walks = (1L..5L).map { ref(it) }
        val thresholds = WalkThresholds.compute(walks, mapOf(2L to 8, 4L to 2, 5L to 1))
        assertEquals(WalkThreshold.Seeking, thresholds[2L])
        assertEquals(WalkThreshold.Seeking, thresholds[4L])
        assertNull(thresholds[5L])
    }

    @Test
    fun `zero-arrival walks never stand at a seeking gate`() {
        val walks = (1L..9L).map { ref(it) }
        val thresholds = WalkThresholds.compute(walks, emptyMap())
        assertEquals(setOf(1L), thresholds.keys)
    }

    @Test
    fun `same startMs ties break by uuid`() {
        val earlier = WalkThresholds.WalkRef(walkId = 1L, uuid = "zzz-first", startMs = 1_000L)
        val counts = mapOf(2L to 1, 3L to 1)

        val thresholds = WalkThresholds.compute(
            listOf(
                WalkThresholds.WalkRef(walkId = 3L, uuid = "bbb", startMs = 5_000L),
                earlier,
                WalkThresholds.WalkRef(walkId = 2L, uuid = "aaa", startMs = 5_000L),
            ),
            counts,
        )
        assertEquals("uuid 'aaa' orders first and takes FirstUnknown", WalkThreshold.Seeking, thresholds[2L])
        assertNull(thresholds[3L])

        // Swapped uuids flip which tied walk stands at the gate.
        val swapped = WalkThresholds.compute(
            listOf(
                earlier,
                WalkThresholds.WalkRef(walkId = 2L, uuid = "bbb", startMs = 5_000L),
                WalkThresholds.WalkRef(walkId = 3L, uuid = "aaa", startMs = 5_000L),
            ),
            counts,
        )
        assertNull(swapped[2L])
        assertEquals(WalkThreshold.Seeking, swapped[3L])
    }

    @Test
    fun `input order does not change the result`() {
        val walks = (1L..12L).map { ref(it) }
        val counts = mapOf(5L to 1, 9L to 2)
        val fromSorted = WalkThresholds.compute(walks, counts)
        val fromShuffled = WalkThresholds.compute(walks.shuffled(Random(42)), counts)
        assertEquals(fromSorted, fromShuffled)
    }
}
