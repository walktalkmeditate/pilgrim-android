// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.seek

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.walktalkmeditate.pilgrim.domain.seek.SeekChainGenerator
import org.walktalkmeditate.pilgrim.domain.seek.SeekPoint

class SeekQaOverridesTest {

    private val origin = SeekPoint(latitude = 35.0116, longitude = 135.7681)

    private fun chain() = SeekChainGenerator.generate(
        durationMinutes = 120,
        start = origin,
        rng = Random(7),
    )

    @Test
    fun compressionHugsTheOrigin() {
        val compressed = SeekQaOverrides.compressTowardOrigin(chain(), origin)
        compressed.clearings.forEachIndexed { index, clearing ->
            val d = SeekChainGenerator.distance(origin, clearing.center)
            val expected = SeekQaOverrides.QA_BASE_DISTANCE_METERS +
                SeekQaOverrides.QA_STEP_METERS * index
            assertEquals("clearing $index sits on the QA ladder", expected, d, 1.0)
            assertTrue(
                "tester at origin is inside clearing $index (cascade without walking)",
                d < clearing.radiusMeters,
            )
        }
    }

    @Test
    fun compressionPreservesShape() {
        val original = chain()
        val compressed = SeekQaOverrides.compressTowardOrigin(original, origin)
        assertEquals(original.clearings.size, compressed.clearings.size)
        assertEquals(original.budgetMeters, compressed.budgetMeters, 0.0)
        original.clearings.zip(compressed.clearings).forEach { (o, c) ->
            assertEquals(o.radiusMeters, c.radiusMeters, 0.0)
            val bearingDelta = Math.floorMod(
                (SeekChainGenerator.bearingDegrees(origin, o.center) -
                    SeekChainGenerator.bearingDegrees(origin, c.center)).toInt() + 180,
                360,
            ) - 180
            assertTrue("bearing preserved within 1°", Math.abs(bearingDelta) <= 1)
        }
    }
}
