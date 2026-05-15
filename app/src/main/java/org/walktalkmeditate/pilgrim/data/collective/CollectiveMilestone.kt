// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.collective

import java.util.Locale

data class CollectiveMilestone(
    val number: Int,
    val message: String,
) {
    companion object {
        /**
         * MUST stay ascending — both [CollectiveMilestoneDetector.check]
         * (which `break`s at the first matching threshold) and the
         * fresh-install fast-forward (which calls `lastOrNull` to find
         * the highest already-crossed value) depend on it. iOS lifted
         * this to a `static let` with a `precondition` for the same
         * reason after v1.6.0.
         */
        val SACRED_NUMBERS: List<Int> = listOf(108, 1_080, 2_160, 10_000, 33_333, 88_000, 108_000)
            .also { numbers ->
                require(numbers.zipWithNext().all { (a, b) -> a < b }) {
                    "SACRED_NUMBERS must be strictly ascending; got $numbers"
                }
            }

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
