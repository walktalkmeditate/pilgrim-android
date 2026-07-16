// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.runtime.Immutable
import kotlin.math.min
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

/**
 * Deterministic scenery picker — port of iOS
 * `pilgrim-ios/Pilgrim/Models/SceneryGenerator.swift`. Same FNV-1a +
 * SplitMix64 hash, same 35% chance, same lottery weights, and the same
 * deterministic branch ahead of the lottery (threshold gates + found-place
 * cairns). Stable output for the same `WalkSnapshot` inputs
 * (uuid + startMs + distanceM + dur + threshold/seek fields).
 *
 * `Scenery` is `@Immutable` per Stage 4-C / 13-Cel cascade lesson.
 */
object SceneryGenerator {

    private const val SCENERY_CHANCE: Double = 0.35

    // The random torii is retired: gates now mark real thresholds only
    // (see the deterministic branch in `pick`). Its old band belongs to
    // drift — the season's breath — so every other walk's rolled item
    // stays exactly what it has always been.
    private val WEIGHTS: List<Pair<SceneryType, Double>> = listOf(
        SceneryType.Tree to 0.27,
        SceneryType.Lantern to 0.18,
        SceneryType.Grass to 0.22,
        SceneryType.Butterfly to 0.14,
        SceneryType.Mountain to 0.11,
        SceneryType.Drift to 0.05,
        SceneryType.Moon to 0.03,
    )

    fun pick(snapshot: WalkSnapshot): SceneryPlacement? {
        val seed = deterministicSeed(snapshot)
        val roll3 = seededRandom(seed, 3uL)
        val side = if (roll3 < 0.5) ScenerySide.Left else ScenerySide.Right
        val roll4 = seededRandom(seed, 4uL)
        val offset = (roll4 * 15.0 - 7.5).toFloat()

        // Meaning outranks the lottery: threshold walks stand at a gate,
        // and a seek that found places raises a cairn.
        snapshot.threshold?.let { threshold ->
            return SceneryPlacement(SceneryType.Torii, side, offset, gateKind = threshold)
        }
        if (snapshot.isSeek && snapshot.foundPlaces > 0) {
            return SceneryPlacement(
                type = SceneryType.Cairn,
                side = side,
                offset = offset,
                stones = min(2 + snapshot.foundPlaces, 5),
            )
        }

        val roll1 = seededRandom(seed, 1uL)
        if (roll1 >= SCENERY_CHANCE) return null

        val roll2 = seededRandom(seed, 2uL)
        return SceneryPlacement(pickType(roll2), side, offset)
    }

    /**
     * Size derived from `walk.uuid` bytes — separate FNV mix so size and
     * placement vary independently. iOS InkScrollView.swift:562-565.
     * Returns size in dp (0-1) range — caller multiplies by base + variation.
     */
    fun sizeVariation01(snapshot: WalkSnapshot): Double {
        var h: ULong = FNV_OFFSET_BASIS
        uuidBytes(snapshot.uuid).forEach { byte ->
            h = (h xor byte.toULong()) * FNV_PRIME
        }
        return (h % 20uL).toDouble() / 20.0
    }

    private fun pickType(roll: Double): SceneryType {
        var cumulative = 0.0
        for ((type, weight) in WEIGHTS) {
            cumulative += weight
            if (roll < cumulative) return type
        }
        return SceneryType.Tree
    }

    private fun deterministicSeed(snapshot: WalkSnapshot): ULong {
        var h: ULong = FNV_OFFSET_BASIS
        // Mix UUID's 16 raw bytes (matches iOS withUnsafeBytes(of: uuid)).
        uuidBytes(snapshot.uuid).forEach { byte ->
            h = (h xor byte.toULong()) * FNV_PRIME
        }
        // iOS used `startDate.timeIntervalSince1970` (seconds, double) cast
        // to Int64 — match by using seconds-since-epoch for parity.
        h = (h xor (snapshot.startMs / 1000L).toULong()) * FNV_PRIME
        // distance × 100 truncated to long — same rounding as iOS.
        h = (h xor (snapshot.distanceM * 100.0).toLong().toULong()) * FNV_PRIME
        h = (h xor snapshot.durationSec.toLong().toULong()) * FNV_PRIME
        return h
    }

    /**
     * SplitMix64 with the three canonical magic constants — iOS verbatim.
     * Returns a double in [0.0, 1.0).
     */
    private fun seededRandom(seed: ULong, salt: ULong): Double {
        var mixed = seed + salt * 6364136223846793005uL
        mixed = mixed xor (mixed shr 33)
        mixed *= 0xff51afd7ed558ccduL
        mixed = mixed xor (mixed shr 33)
        mixed *= 0xc4ceb9fe1a85ec53uL
        mixed = mixed xor (mixed shr 33)
        return (mixed % 10000uL).toDouble() / 10000.0
    }

    /**
     * Decodes a canonical UUID string into its 16 raw bytes — matches the
     * iOS `withUnsafeBytes(of: uuid)` byte stream. Falls back to a single
     * 0-byte if the string is malformed (defensive: production walk UUIDs
     * are always canonical).
     */
    private fun uuidBytes(uuid: String): ByteArray {
        return try {
            val u = java.util.UUID.fromString(uuid)
            val msb = u.mostSignificantBits
            val lsb = u.leastSignificantBits
            val out = ByteArray(16)
            for (i in 0 until 8) out[i] = (msb shr ((7 - i) * 8)).toByte()
            for (i in 0 until 8) out[8 + i] = (lsb shr ((7 - i) * 8)).toByte()
            out
        } catch (_: IllegalArgumentException) {
            ByteArray(1)
        }
    }

    private const val FNV_OFFSET_BASIS: ULong = 14695981039346656037uL
    private const val FNV_PRIME: ULong = 1099511628211uL
}

enum class SceneryType {
    Tree, Lantern, Butterfly, Mountain, Grass, Torii, Moon, Cairn, Drift;

    /** Token name into pilgrimColors.* — same naming as iOS. */
    val tintTokenName: String
        get() = when (this) {
            Tree -> "moss"
            Lantern -> "stone"
            Butterfly -> "dawn"
            Mountain -> "fog"
            Grass -> "moss"
            Torii -> "stone"
            Moon -> "fog"
            Cairn -> "stone"
            Drift -> "fog"
        }
}

enum class ScenerySide { Left, Right }

@Immutable
data class SceneryPlacement(
    val type: SceneryType,
    val side: ScenerySide,
    /** Random ±7.5 dp jitter on top of side-offset. */
    val offset: Float,
    /**
     * Cairns only: stones in the stack — a two-stone base plus one per
     * found place, capped at five.
     */
    val stones: Int = 3,
    /** Gates only: which kind of threshold the torii marks. */
    val gateKind: WalkThreshold? = null,
) {
    /**
     * Practice gates stand vermilion (rust); everything else keeps its
     * type's tint — seeking gates weathered stone among them.
     */
    val tintTokenName: String
        get() = if (type == SceneryType.Torii && gateKind == WalkThreshold.Practice) {
            "rust"
        } else {
            type.tintTokenName
        }
}
