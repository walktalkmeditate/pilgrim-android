// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SealGeometryTest {
    private fun spec(
        uuid: String = "11111111-2222-3333-4444-555555555555",
        startMillis: Long = 1_700_000_000_000L,
        distance: Double = 5_000.0,
    ) = SealSpec(
        uuid = uuid,
        startMillis = startMillis,
        distanceMeters = distance,
        durationSeconds = 3_600.0,
        displayDistance = "5.0",
        unitLabel = "km",
        ink = Color.Black,
    )

    @Test fun `ring count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "uuid-$i"))
            assertTrue("ring count ${g.rings.size} out of [3, 8]", g.rings.size in 3..8)
        }
    }

    @Test fun `radial line count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "radial-$i"))
            assertTrue("radial count ${g.radialLines.size} out of [4, 12]", g.radialLines.size in 4..12)
        }
    }

    @Test fun `arc count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "arc-$i"))
            assertTrue("arc count ${g.arcs.size} out of [2, 4]", g.arcs.size in 2..4)
        }
    }

    @Test fun `dot count is bounded`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "dot-$i"))
            assertTrue("dot count ${g.dots.size} out of [3, 7]", g.dots.size in 3..7)
        }
    }

    @Test fun `rotation in 0 to 360`() {
        repeat(100) { i ->
            val g = sealGeometry(spec(uuid = "rot-$i"))
            assertTrue("rotation ${g.rotationDeg} out of range", g.rotationDeg in 0f..360f)
        }
    }

    @Test fun `sealGeometry deterministic`() {
        val a = sealGeometry(spec())
        val b = sealGeometry(spec())
        assertEquals(a.rotationDeg, b.rotationDeg, 0.0001f)
        assertEquals(a.rings.size, b.rings.size)
        assertEquals(a.radialLines.size, b.radialLines.size)
        assertEquals(a.arcs.size, b.arcs.size)
        assertEquals(a.dots.size, b.dots.size)
    }

    @Test fun `different uuids produce different rotations with high probability`() {
        // Rotation is derived from byte[0] of the hash output, which is
        // one of 256 distinct values. By birthday-paradox math, 100
        // samples across 256 buckets yield ~81 distinct values on
        // average for a uniform hash — not 100. We allow down to 70 as
        // a conservative floor; anything below that suggests the hash
        // has genuinely poor spread.
        val rotations = (0 until 100).map { i ->
            sealGeometry(spec(uuid = "var-$i")).rotationDeg
        }
        val distinct = rotations.toSet().size
        assertTrue("only $distinct distinct rotations across 100 specs — hash has poor spread", distinct > 70)
    }

    @Test fun `ring radii decrease from outermost to innermost on average`() {
        val g = sealGeometry(spec())
        assertTrue(
            "first ring radius ${g.rings.first().radiusFrac} should exceed last ${g.rings.last().radiusFrac}",
            g.rings.first().radiusFrac > g.rings.last().radiusFrac,
        )
    }

    @Test fun `ring opacity decreases toward center`() {
        val g = sealGeometry(spec())
        assertTrue(
            "outermost ring opacity ${g.rings.first().opacity} should be ≥ innermost ${g.rings.last().opacity}",
            g.rings.first().opacity >= g.rings.last().opacity,
        )
    }

    @Test fun `radial line angles stay in 0 to 360`() {
        repeat(50) { i ->
            val g = sealGeometry(spec(uuid = "angle-$i"))
            g.radialLines.forEach { radial ->
                assertTrue("radial angle ${radial.angleDeg} out of range", radial.angleDeg in 0f..360f)
            }
        }
    }

    @Test fun `arc sweep in 20 to 80 degrees`() {
        repeat(50) { i ->
            val g = sealGeometry(spec(uuid = "sweep-$i"))
            g.arcs.forEach { arc ->
                assertTrue(
                    "arc sweep ${arc.sweepDeg} out of [20, 80]",
                    arc.sweepDeg in 20f..80f,
                )
            }
        }
    }
}
