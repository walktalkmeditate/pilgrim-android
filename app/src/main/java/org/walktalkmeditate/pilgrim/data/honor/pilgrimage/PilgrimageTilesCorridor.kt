// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Objects
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.honor.Way
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry
import org.walktalkmeditate.pilgrim.domain.honor.WayPoint

/**
 * The pure geometry and keys of iOS `PilgrimageTilesManager`
 * (`PilgrimageTilesManager.swift:31,71-103,165-174,385-388@7c200bf`), off
 * the manager so callers build a stage's values on IO before they reach it
 * (spec D C1 §6–§8, §11). Placement only: the numbers and their order are
 * iOS's.
 */
object PilgrimageTilesCorridor {

    /** Each side of a stage's line, for every stage's region. */
    const val HALF_WIDTH_METERS = 500.0

    /** The stage's whole route, every point in route order, altitude and time dropped. */
    fun rings(route: List<WayPoint>): List<List<WayCoordinate>> = WayGeometry.corridor(
        around = route.map { WayCoordinate(lat = it.lat, lon = it.lon) },
        halfWidthMeters = HALF_WIDTH_METERS,
    )

    /**
     * SHA-256 in lowercase hex over iOS's exact bytes (C1 §6, owner decision
     * 1): [version] as an 8-byte little-endian integer, then each ring
     * point's latitude and longitude × 10⁶, rounded as Swift rounds, as the
     * Double's raw IEEE-754 bits, little-endian, in emission order.
     *
     * The version goes first, so a region saved under earlier descriptors
     * fails the check and the next save reloads it. The order is part of
     * the hash, so a reordering would read as a redrawn stage. An empty
     * corridor hashes the version alone.
     */
    fun corridorHash(
        rings: List<List<WayCoordinate>>,
        version: Int = PilgrimageTilesDescriptors.REGION_VERSION,
    ): String {
        val points = rings.sumOf { it.size }
        val bytes = ByteBuffer.allocate(Long.SIZE_BYTES * (1 + 2 * points)).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putLong(version.toLong())
        for (ring in rings) {
            for (point in ring) {
                bytes.putLong(swiftRounded(point.lat * MICRODEGREES_PER_DEGREE).toRawBits())
                bytes.putLong(swiftRounded(point.lon * MICRODEGREES_PER_DEGREE).toRawBits())
            }
        }
        return lowercaseHex(MessageDigest.getInstance("SHA-256").digest(bytes.array()))
    }

    private fun lowercaseHex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for ((index, byte) in bytes.withIndex()) {
            val value = byte.toInt() and 0xff
            out[2 * index] = HEX_DIGITS[value ushr 4]
            out[2 * index + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(out)
    }

    /** The one place a decoded stage Way becomes its [TileStage]; the rings and their hash come from one corridor. */
    fun stage(way: Way): TileStage {
        val rings = rings(way.route)
        return TileStage(id = way.id, index = way.stage?.index ?: 0, rings = rings, corridorHash = corridorHash(rings))
    }

    /**
     * Every surface's stage list (C1 §11): one value per stage Way of the
     * route that loads, in index order, a stage that doesn't load skipped,
     * as iOS's `compactMap` skips it, so `status`'s "of" is the list's size.
     * Each Way is dropped once it is a value. Reads the store: call on IO.
     */
    fun stages(store: WayStore, routeId: String, stageCount: Int): List<TileStage> =
        (0 until stageCount).mapNotNull { index -> store.load(WayStore.stageWayId(routeId, index))?.let(::stage) }

    /**
     * Distinct z11 cells the stages' corridors touch, in one sweep over every
     * stage's rings, so a cell two stages share counts once, as the store
     * holds it once. A count, not bytes: the estimate multiplies it by a
     * `Long` bytes-per-pack.
     */
    fun packCount(stages: List<TileStage>): Int {
        val root = PilgrimageTilesDescriptors.PACK_ROOT_ZOOM
        return PilgrimageTilesDescriptors.tileCount(stages.flatMap { it.rings }, zooms = root..root)
    }

    /** Built from [WayStore.stageWayId]'s own prefix, and ends in `:` so `camino` never matches `camino-frances`. */
    fun regionPrefix(routeId: String): String = "${WayStore.STAGE_ID_PREFIX}$routeId:"

    /**
     * The index after [prefix], read as Swift's `Int(String)` reads it: an
     * optional sign, then ASCII digits only (leading zeros allowed), null
     * past 64 bits, Swift's `Int` on every iOS device. Null for another
     * route's region (C1 §7, C2.3).
     */
    fun stageIndex(regionId: String, prefix: String): Long? {
        if (!regionId.startsWith(prefix)) return null
        val suffix = regionId.substring(prefix.length)
        val digits = if (suffix.startsWith('+') || suffix.startsWith('-')) suffix.substring(1) else suffix
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return suffix.toLongOrNull()
    }

    /**
     * Swift's `rounded()`: ties away from zero, a value in (−0.5, 0) gives
     * −0.0, and the result stays a Double. Not `Math.round`/`roundToLong`
     * (ties toward +∞, and integers, so −0.4 hashes as +0.0), not
     * `kotlin.math.round` (ties to even), and not `floor(abs(v) + 0.5)`,
     * which rounds 0.49999999999999994 up (C1 §6, correction 3).
     */
    internal fun swiftRounded(value: Double): Double {
        val whole = truncate(value)
        return if (abs(value - whole) >= 0.5) whole + sign(value) else whole
    }

    private const val MICRODEGREES_PER_DEGREE = 1_000_000.0

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}

/**
 * One holder's [PilgrimageTilesCorridor.stages] and their pack count for
 * the installed release, built together on IO once per `(routeId,
 * release)`. A build cancelled mid-read never fills it. Not thread-safe:
 * each holder confines its own.
 */
class TileStagesCache {

    class Stages(val routeId: String, val release: String, val values: List<TileStage>, val packCount: Int)

    private var cached: Stages? = null

    suspend fun of(installed: PilgrimagePackageManager.Installed, wayStore: WayStore, io: CoroutineDispatcher): Stages {
        cached?.takeIf { it.routeId == installed.routeId && it.release == installed.release }?.let { return it }
        val built = withContext(io) {
            val values = PilgrimageTilesCorridor.stages(wayStore, installed.routeId, installed.route.stageCount)
            Stages(installed.routeId, installed.release, values, PilgrimageTilesCorridor.packCount(values))
        }
        cached = built
        return built
    }

    fun clear() {
        cached = null
    }
}

/**
 * One stage as the tiles engine takes it (spec D C1 §11): everything iOS's
 * manager reads from a stage Way, built by [PilgrimageTilesCorridor.stage]
 * so no decoded Way reaches the engine. [id] is the stage Way's id and so
 * its region's, `pilgrimage:<routeId>:<index>`; [index] is only the save's
 * sort key; [corridorHash] is [rings]' hash.
 *
 * Immutable: the rings are copied in. Equality is structural over all four
 * fields, cheapest first, so values for different lines part at the hash
 * before any ring is compared; the hash code leaves the rings out.
 */
class TileStage(
    val id: String,
    val index: Int,
    rings: List<List<WayCoordinate>>,
    val corridorHash: String,
) {
    val rings: List<List<WayCoordinate>> = rings.map { it.toList() }

    override fun equals(other: Any?): Boolean = this === other ||
        other is TileStage &&
        id == other.id &&
        index == other.index &&
        corridorHash == other.corridorHash &&
        rings == other.rings

    override fun hashCode(): Int = Objects.hash(id, index, corridorHash)

    override fun toString(): String = "TileStage(id=$id, index=$index, parts=${rings.size}, corridorHash=$corridorHash)"
}
