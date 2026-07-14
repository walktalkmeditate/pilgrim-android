// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.domain.seek

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.random.Random
import org.walktalkmeditate.pilgrim.domain.LocationPoint

/**
 * The seed of a seek: the walker's intention and the moment they crossed
 * the gateway, folded together with OS entropy — all local, nothing
 * communal. The intention is one voice in the seed, never the whole of it:
 * mixed with the moment and fresh entropy so a repeated question never
 * repeats a way, and it enters only as a one-way hash, so nothing personal
 * is derivable from the seed.
 *
 * Byte-identical to the iOS `SeekSeed` construction (SHA-256 over UTF-8
 * intention when non-empty, then little-endian IEEE-754 bits of the epoch
 * seconds and fix components, then the entropy word; first 8 digest bytes
 * assembled little-endian). Port spec: docs/parity/2026-07-14-port-seek-chain-u2.md.
 */
object SeekSeed {

    private val entropySource = SecureRandom()

    fun make(
        intention: String?,
        momentEpochMillis: Long,
        fix: LocationPoint? = null,
        entropy: ULong = entropySource.nextLong().toULong(),
    ): ULong {
        val digest = MessageDigest.getInstance("SHA-256")
        if (!intention.isNullOrEmpty()) {
            digest.update(intention.toByteArray(Charsets.UTF_8))
        }
        digest.update(momentEpochMillis / 1000.0)
        if (fix != null) {
            digest.update(fix.latitude)
            digest.update(fix.longitude)
            digest.update(fix.altitudeMeters ?: 0.0)
            digest.update((fix.horizontalAccuracyMeters ?: 0f).toDouble())
        }
        digest.update(littleEndianBytes(entropy.toLong()))

        val bytes = digest.digest()
        var seed = 0uL
        for (index in 0 until 8) {
            seed = seed or (bytes[index].toUByte().toULong() shl (8 * index))
        }
        return seed
    }

    private fun MessageDigest.update(value: Double) {
        update(littleEndianBytes(value.toRawBits()))
    }

    private fun littleEndianBytes(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}

/**
 * SplitMix64 — a full-period generator whose whole state is the 64-bit
 * seed, so one seed is one seek. Not for cryptography; the secrecy budget
 * is spent inside [SeekSeed]'s hash, this only has to be deterministic and
 * well-mixed. [nextULong] is bit-identical to the iOS generator; derived
 * draws come from [Random] over the top bits of each output (spec D1).
 */
class SeekSeededGenerator(seed: ULong) : Random() {

    private var state: ULong = seed

    fun nextULong(): ULong {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return z xor (z shr 31)
    }

    override fun nextBits(bitCount: Int): Int =
        (nextULong() shr 32).toInt().ushr(32 - bitCount) and (-bitCount shr 31)
}
