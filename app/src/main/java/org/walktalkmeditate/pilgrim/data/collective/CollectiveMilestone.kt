// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import java.util.Locale

data class CollectiveMilestone(
    val number: Int,
    val message: String,
) {
    companion object {
        val SACRED_NUMBERS = listOf(108, 1_080, 2_160, 10_000, 33_333, 88_000, 108_000)

        fun forNumber(number: Int): CollectiveMilestone {
            val message = when (number) {
                108 -> "108 walks. One for each bead on the mala."
                1_080 -> "1,080 walks. The mala, turned ten times."
                2_160 -> "2,160 walks. One full age of the zodiac."
                10_000 -> "10,000 walks. 万 — all things."
                33_333 -> "33,333 walks. The Saigoku pilgrimage, a thousandfold."
                88_000 -> "88,000 walks. Shikoku's 88 temples, a thousand times over."
                108_000 -> "108,000 walks. The great mala, complete."
                else -> String.format(Locale.US, "%,d walks. You were one of them.", number)
            }
            return CollectiveMilestone(number, message)
        }
    }
}
