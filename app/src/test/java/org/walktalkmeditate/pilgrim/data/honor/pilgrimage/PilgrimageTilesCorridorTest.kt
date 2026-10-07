// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.honor.pilgrimage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesFixtures.stage
import org.walktalkmeditate.pilgrim.data.honor.pilgrimage.PilgrimageTilesFixtures.stages
import org.walktalkmeditate.pilgrim.domain.honor.WayCoordinate
import org.walktalkmeditate.pilgrim.domain.honor.WayGeometry

/**
 * The pure halves of iOS `PilgrimageTilesManagerTests.swift@7c200bf` that
 * spec D moves here (C1 corrections 6–7): the hash test's two equalities
 * and both pack-count tests, names and fixtures kept. Then the Android
 * additions: the seven Swift vectors of C1 §6 as literal hex, the
 * sensitivity of the hash at an exact microdegree (C1 correction 4),
 * Swift's rounding, the region ids of C1 §7, and the per-stage value.
 */
class PilgrimageTilesCorridorTest {

    private val square = listOf(
        WayCoordinate(lat = 0.0, lon = 0.0),
        WayCoordinate(lat = 1.0, lon = 0.0),
        WayCoordinate(lat = 1.0, lon = 1.0),
        WayCoordinate(lat = 0.0, lon = 1.0),
        WayCoordinate(lat = 0.0, lon = 0.0),
    )

    private fun hash(vararg rings: List<WayCoordinate>, version: Int = PilgrimageTilesDescriptors.REGION_VERSION) =
        PilgrimageTilesCorridor.corridorHash(rings.toList(), version)

    private fun assertBits(expected: Double, actual: Double) =
        assertEquals("$expected against $actual", expected.toRawBits(), actual.toRawBits())

    // ---- iOS's tests ----------------------------------------------------------

    @Test
    fun `the pack count is the z11 cells of the whole corridor`() {
        val three = stages(3)
        val rings = three.flatMap { way ->
            val line = way.route.map { WayCoordinate(lat = it.lat, lon = it.lon) }
            WayGeometry.corridor(around = line, halfWidthMeters = 500.0)
        }
        val values = three.map { PilgrimageTilesCorridor.stage(it) }
        assertTrue(PilgrimageTilesCorridor.packCount(values) > 0)
        assertEquals(
            PilgrimageTilesDescriptors.tileCount(rings, zooms = 11..11),
            PilgrimageTilesCorridor.packCount(values),
        )
    }

    /**
     * The store holds a pack once however many stages touch it, so the
     * estimate must too. The fixture's first two stages sit in one z11
     * cell (the first reaches into its western neighbour as well), so
     * counted apart they are three packs and together they are two.
     */
    @Test
    fun `a z11 cell two stages share is counted once`() {
        val two = stages(2).map { PilgrimageTilesCorridor.stage(it) }
        assertEquals(listOf(2, 1), two.map { PilgrimageTilesCorridor.packCount(listOf(it)) })
        assertEquals(2, PilgrimageTilesCorridor.packCount(two))
    }

    /**
     * A region saved under earlier descriptors must not read as saved: the
     * version is part of the hash. iOS's last assertion here (a complete
     * region stored with the version-1 hash reads `isStageSaved == false`)
     * needs the fake loader, so it is the tiles manager's test (U44).
     */
    @Test
    fun `the corridor hash carries the region version`() {
        val way = stage(0)
        val rings = PilgrimageTilesCorridor.rings(way.route)
        val current = PilgrimageTilesDescriptors.REGION_VERSION
        assertEquals(
            PilgrimageTilesCorridor.stage(way).corridorHash,
            PilgrimageTilesCorridor.corridorHash(rings, version = current),
        )
        assertNotEquals(
            PilgrimageTilesCorridor.corridorHash(rings, version = current),
            PilgrimageTilesCorridor.corridorHash(rings, version = current - 1),
        )
    }

    // ---- Swift's vectors (spec D C1 §6) ---------------------------------------

    @Test
    fun `the hand built square hashes to iOS's hex at both versions`() {
        assertEquals("295e29cc256224c652f256f17ff1d31c07d7918b9df0f46c8084677e8e1ef101", hash(square, version = 2))
        assertEquals("7109e14a5ad5907f733bc696e99c4daebf415e636ddaf99878311e49b5d3d436", hash(square, version = 1))
    }

    @Test
    fun `no rings hash the version's eight bytes alone`() {
        assertEquals("d86e8112f3c4c4442126f8e9f44f16867da487f29052bf91b810457db34209a4", hash())
    }

    @Test
    fun `ties round away from zero in the hash`() {
        assertEquals(
            "859cbda43b8da9ab9f567720cc193ca28792108bdc4042cddaaa8c4335085dd1",
            hash(listOf(WayCoordinate(lat = 0.0000005, lon = -0.0000005))),
        )
    }

    @Test
    fun `a value just below zero hashes as negative zero`() {
        assertEquals(
            "540efeba500e7dada9dedac0a1d2f2fbc0648077d188f2f4d03347b38d75a40b",
            hash(listOf(WayCoordinate(lat = -0.0000004, lon = 0.0000004))),
        )
    }

    @Test
    fun `the stage fixture's corridor hashes to iOS's hex at both versions`() {
        val rings = PilgrimageTilesCorridor.rings(stage(0).route)
        assertEquals(
            "4bcadd51358decf228c35590cf597823a2da545a0632af3e2b4b743d4822effc",
            PilgrimageTilesCorridor.corridorHash(rings, version = 2),
        )
        assertEquals(
            "ef15543381b21f8900115f86e72ed6b42fc9c621065bc7decbedf1f5de1704ec",
            PilgrimageTilesCorridor.corridorHash(rings, version = 1),
        )
    }

    // ---- What the hash sees ---------------------------------------------------

    /** From an exact microdegree, under half of one keeps the hash; past half, or a whole one, changes it. */
    @Test
    fun `the hash keeps a move under half a microdegree and changes past it`() {
        val ring = listOf(
            WayCoordinate(lat = 42.0, lon = -8.0),
            WayCoordinate(lat = 42.0, lon = -7.99),
            WayCoordinate(lat = 42.01, lon = -7.99),
            WayCoordinate(lat = 42.01, lon = -8.0),
            WayCoordinate(lat = 42.0, lon = -8.0),
        )
        fun moved(by: Double) = listOf(ring[0], ring[1].copy(lat = 42.0 + by)) + ring.drop(2)
        assertEquals(hash(ring), hash(moved(by = 4e-7)))
        assertNotEquals(hash(ring), hash(moved(by = 6e-7)))
        assertNotEquals(hash(ring), hash(moved(by = 1e-6)))
    }

    @Test
    fun `negative zero and positive zero hash differently`() {
        assertNotEquals(
            hash(listOf(WayCoordinate(lat = -0.0, lon = 1.0))),
            hash(listOf(WayCoordinate(lat = 0.0, lon = 1.0))),
        )
        assertNotEquals(
            hash(listOf(WayCoordinate(lat = 1.0, lon = -0.0000004))),
            hash(listOf(WayCoordinate(lat = 1.0, lon = 0.0000004))),
        )
    }

    @Test
    fun `the hash reads the parts in emission order`() {
        val other = square.map { WayCoordinate(lat = it.lat + 2, lon = it.lon) }
        assertNotEquals(hash(square, other), hash(other, square))
    }

    // ---- Swift's rounded() ----------------------------------------------------

    @Test
    fun `swift rounding takes ties away from zero`() {
        assertBits(1.0, PilgrimageTilesCorridor.swiftRounded(0.5))
        assertBits(-1.0, PilgrimageTilesCorridor.swiftRounded(-0.5))
        assertBits(2.0, PilgrimageTilesCorridor.swiftRounded(1.5))
        assertBits(-2.0, PilgrimageTilesCorridor.swiftRounded(-1.5))
        assertBits(3.0, PilgrimageTilesCorridor.swiftRounded(2.5))
        assertBits(-3.0, PilgrimageTilesCorridor.swiftRounded(-2.5))
    }

    @Test
    fun `swift rounding keeps the sign of a value that rounds to zero`() {
        assertBits(-0.0, PilgrimageTilesCorridor.swiftRounded(-0.4))
        assertBits(0.0, PilgrimageTilesCorridor.swiftRounded(0.4))
        assertBits(-0.0, PilgrimageTilesCorridor.swiftRounded(-0.0))
    }

    /** `floor(abs(v) + 0.5)` rounds this to 1, because `v + 0.5` rounds up in floating point; C's `round` gives 0. */
    @Test
    fun `swift rounding leaves the largest double below a half at zero`() {
        assertBits(0.0, PilgrimageTilesCorridor.swiftRounded(0.49999999999999994))
        assertBits(-0.0, PilgrimageTilesCorridor.swiftRounded(-0.49999999999999994))
    }

    @Test
    fun `swift rounding leaves whole and non-finite values alone`() {
        assertBits(42_000_000.0, PilgrimageTilesCorridor.swiftRounded(42_000_000.0))
        assertBits(-8_123_457.0, PilgrimageTilesCorridor.swiftRounded(-8_123_456.5))
        assertBits(Double.POSITIVE_INFINITY, PilgrimageTilesCorridor.swiftRounded(Double.POSITIVE_INFINITY))
        assertTrue(PilgrimageTilesCorridor.swiftRounded(Double.NaN).isNaN())
    }

    // ---- Region ids (spec D C1 §7, C2.3) --------------------------------------

    @Test
    fun `the region prefix ends in a colon and prefixes every stage id`() {
        val prefix = PilgrimageTilesCorridor.regionPrefix("camino-frances")
        assertEquals("pilgrimage:camino-frances:", prefix)
        for (index in 0..32) {
            val id = WayStore.stageWayId(routeId = "camino-frances", stageIndex = index)
            assertTrue(id.startsWith(prefix))
            assertEquals(index.toLong(), PilgrimageTilesCorridor.stageIndex(id, prefix))
        }
    }

    @Test
    fun `another route's region has no stage index, even one whose id starts the same`() {
        val id = WayStore.stageWayId(routeId = "camino-frances", stageIndex = 0)
        assertNull(PilgrimageTilesCorridor.stageIndex(id, PilgrimageTilesCorridor.regionPrefix("camino")))
        assertNull(PilgrimageTilesCorridor.stageIndex(id, PilgrimageTilesCorridor.regionPrefix("camino-norte")))
    }

    /** Swift's `Int(String)`, probed: a sign, leading zeros and ASCII digits, nothing else, in 64 bits. */
    @Test
    fun `the stage index parses as Swift's Int does`() {
        val prefix = PilgrimageTilesCorridor.regionPrefix("camino-frances")
        fun index(suffix: String) = PilgrimageTilesCorridor.stageIndex(prefix + suffix, prefix)
        assertEquals(3L, index("3"))
        assertEquals(3L, index("+3"))
        assertEquals(-1L, index("-1"))
        assertEquals(3L, index("03"))
        assertEquals(Long.MAX_VALUE, index("9223372036854775807"))
        assertEquals(Long.MIN_VALUE, index("-9223372036854775808"))
        for (refused in listOf("", "+", "-", " 3", "3 ", "1:x", "+-3", "3.0", "99999999999999999999", "٣")) {
            assertNull("\"$refused\"", index(refused))
        }
    }

    // ---- The per-stage value (spec D C1 §11) ----------------------------------

    @Test
    fun `a stage carries its way's id, index, rings, and their hash`() {
        val way = stage(2, lonOffset = 0.08)
        val value = PilgrimageTilesCorridor.stage(way)
        val rings = PilgrimageTilesCorridor.rings(way.route)
        assertEquals("pilgrimage:camino-frances:2", value.id)
        assertEquals(2, value.index)
        assertEquals(rings, value.rings)
        assertEquals(PilgrimageTilesCorridor.corridorHash(rings), value.corridorHash)
    }

    @Test
    fun `a way with no stage block sorts as index zero`() {
        assertEquals(0, PilgrimageTilesCorridor.stage(stage(4).copy(stage = null)).index)
    }

    /** iOS's calibrate test relies on it: a lineless stage has no rings, so no packs. */
    @Test
    fun `a way with no line has no rings, no packs, and the version's own hash`() {
        val value = PilgrimageTilesCorridor.stage(stage(0).copy(route = emptyList()))
        assertEquals(emptyList<List<WayCoordinate>>(), value.rings)
        assertEquals(0, PilgrimageTilesCorridor.packCount(listOf(value)))
        assertEquals("d86e8112f3c4c4442126f8e9f44f16867da487f29052bf91b810457db34209a4", value.corridorHash)
        assertEquals(0, PilgrimageTilesCorridor.packCount(emptyList()))
    }

    @Test
    fun `stages built from one way are equal and a redrawn one is not`() {
        val first = PilgrimageTilesCorridor.stage(stage(1, lonOffset = 0.04))
        val again = PilgrimageTilesCorridor.stage(stage(1, lonOffset = 0.04))
        val redrawn = PilgrimageTilesCorridor.stage(stage(1, lonOffset = 0.05))
        assertEquals(first, again)
        assertEquals(first.hashCode(), again.hashCode())
        assertNotEquals(first, redrawn)
    }

    @Test
    fun `a stage's rings never change after it is built`() {
        val part = square.toMutableList()
        val rings = mutableListOf<List<WayCoordinate>>(part)
        val value = TileStage(id = "pilgrimage:camino-frances:0", index = 0, rings = rings, corridorHash = hash(square))
        part[1] = WayCoordinate(lat = 9.0, lon = 9.0)
        rings.add(square)
        assertEquals(listOf(square), value.rings)
    }
}
