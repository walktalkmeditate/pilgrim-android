// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoNarrativeArcBuilderTest {

    @Test
    fun build_emptyEntries_returnsSentinel() {
        assertEquals(NarrativeArc.EMPTY, PhotoNarrativeArcBuilder.build(emptyList()))
    }

    @Test
    fun build_singleEntry_returnsSentinel() {
        val entries = listOf(fakeEntry(0))
        assertEquals(NarrativeArc.EMPTY, PhotoNarrativeArcBuilder.build(entries))
    }

    @Test
    fun build_multipleEntries_returnsSentinel() {
        val entries = (0 until 5).map { fakeEntry(it) }
        assertEquals(NarrativeArc.EMPTY, PhotoNarrativeArcBuilder.build(entries))
    }

    private fun fakeEntry(idx: Int = 0) = PhotoContextEntry(
        index = idx,
        distanceIntoWalkMeters = 100.0 * idx,
        time = 1_000_000L + idx * 60_000L,
        coordinate = null,
        context = PhotoContext(
            tags = emptyList(),
            detectedText = emptyList(),
            people = 0,
            outdoor = false,
            dominantColor = "#000000",
        ),
    )
}
