// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * Deterministic koan picker. Same `(walkId, startedAtEpochMs)`
 * inputs always produce the same [Koan] — important for walk
 * reproducibility (re-opening a walk's summary on a different
 * device installs the same reading).
 *
 * Uses a one-shot bit-mix (multiply by Knuth's MMIX LCG
 * multiplier, XOR with the timestamp) rather than a proper PRNG,
 * since 6-A only picks one koan per walk. iOS uses the same LCG
 * constants (`6364136223846793005`, `1442695040888963407`) for its
 * multi-draw seeded generator; we match the multiplier for
 * potential future cross-platform alignment.
 */
internal object KoanPicker {

    fun pick(walkId: Long, startedAtEpochMs: Long): Koan {
        // Room's @PrimaryKey(autoGenerate = true) produces ids starting
        // at 1; a zero id collapses the LCG multiplier term to 0 and
        // leaves `seed = startedAtEpochMs.toULong()` with reduced
        // entropy (a 0L timestamp then deterministically picks the
        // first koan). Fail fast rather than silently degenerate.
        require(walkId > 0L) { "walkId must be positive (Room autoGenerate starts at 1)" }
        val corpus = Koans.all
        check(corpus.isNotEmpty()) { "Koan corpus must not be empty" }
        val seed = (walkId.toULong() * 6364136223846793005uL) xor startedAtEpochMs.toULong()
        val index = (seed % corpus.size.toULong()).toInt()
        return corpus[index]
    }
}
