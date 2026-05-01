// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.walk

import org.junit.Assert.assertEquals
import org.junit.Test
import org.walktalkmeditate.pilgrim.data.entity.AltitudeSample

class ElevationCalcTest {
    private fun sample(t: Long, alt: Double) = AltitudeSample(
        walkId = 1L,
        timestamp = t,
        altitudeMeters = alt,
    )

    @Test fun emptySamples_returnsZero() {
        assertEquals(0.0, computeAscend(emptyList()), 0.0)
    }

    @Test fun singleSample_returnsZero() {
        assertEquals(0.0, computeAscend(listOf(sample(0L, 100.0))), 0.0)
    }

    @Test fun monotonicAscent_sumsDeltas() {
        val samples = listOf(
            sample(0L, 100.0),
            sample(1L, 110.0),
            sample(2L, 125.0),
        )
        assertEquals(25.0, computeAscend(samples), 0.0001)
    }

    @Test fun mixedDeltas_sumsOnlyPositive() {
        val samples = listOf(
            sample(0L, 100.0),
            sample(1L, 110.0),
            sample(2L, 105.0),
            sample(3L, 120.0),
        )
        assertEquals(25.0, computeAscend(samples), 0.0001)
    }

    @Test fun monotonicDescent_returnsZero() {
        val samples = listOf(
            sample(0L, 100.0),
            sample(1L, 80.0),
            sample(2L, 50.0),
        )
        assertEquals(0.0, computeAscend(samples), 0.0001)
    }
}
