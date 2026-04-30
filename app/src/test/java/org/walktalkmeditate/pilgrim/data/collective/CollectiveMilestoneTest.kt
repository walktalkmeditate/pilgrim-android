// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectiveMilestoneTest {
    @Test
    fun forNumber_messageVerbatim_108() {
        assertEquals(
            "108 walks. One for each bead on the mala.",
            CollectiveMilestone.forNumber(108).message,
        )
    }

    @Test
    fun forNumber_messageVerbatim_1080() {
        assertEquals(
            "1,080 walks. The mala, turned ten times.",
            CollectiveMilestone.forNumber(1_080).message,
        )
    }

    @Test
    fun forNumber_messageVerbatim_2160() {
        assertEquals(
            "2,160 walks. One full age of the zodiac.",
            CollectiveMilestone.forNumber(2_160).message,
        )
    }

    @Test
    fun forNumber_messageVerbatim_10000IncludesKanji() {
        val msg = CollectiveMilestone.forNumber(10_000).message
        assertEquals("10,000 walks. 万 — all things.", msg)
        assertTrue("must contain U+4E07", msg.contains('万'))
    }

    @Test
    fun forNumber_messageVerbatim_33333() {
        assertEquals(
            "33,333 walks. The Saigoku pilgrimage, a thousandfold.",
            CollectiveMilestone.forNumber(33_333).message,
        )
    }

    @Test
    fun forNumber_messageVerbatim_88000() {
        assertEquals(
            "88,000 walks. Shikoku's 88 temples, a thousand times over.",
            CollectiveMilestone.forNumber(88_000).message,
        )
    }

    @Test
    fun forNumber_messageVerbatim_108000() {
        assertEquals(
            "108,000 walks. The great mala, complete.",
            CollectiveMilestone.forNumber(108_000).message,
        )
    }

    @Test
    fun forNumber_unknownNumberFormatsLocaleUS() {
        assertEquals(
            "1,234 walks. You were one of them.",
            CollectiveMilestone.forNumber(1_234).message,
        )
    }

    @Test
    fun sacredNumbers_orderedAscending() {
        assertEquals(
            listOf(108, 1_080, 2_160, 10_000, 33_333, 88_000, 108_000),
            CollectiveMilestone.SACRED_NUMBERS,
        )
    }
}
