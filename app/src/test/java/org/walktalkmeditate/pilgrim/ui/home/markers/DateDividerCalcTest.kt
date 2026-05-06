// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.walktalkmeditate.pilgrim.ui.design.calligraphy.DotPosition
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot

class DateDividerCalcTest {

    private var savedLocale: Locale = Locale.getDefault()

    @Before fun forceUSLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private fun snap(id: Long, ms: Long) = WalkSnapshot(
        id = id, uuid = "u$id", startMs = ms, distanceM = 1000.0, durationSec = 600.0,
        averagePaceSecPerKm = 360.0, cumulativeDistanceM = 1000.0,
        talkDurationSec = 0L, meditateDurationSec = 0L, favicon = null,
        isShared = false, weatherCondition = null,
    )

    @Test
    fun emits_first_walk_and_each_month_transition() {
        val zone = ZoneOffset.UTC
        val s = listOf(
            snap(5, 1_719_273_600_000L),  // 2024-06-25
            snap(4, 1_718_064_000_000L),  // 2024-06-11
            snap(3, 1_714_608_000_000L),  // 2024-05-02
            snap(2, 1_713_398_400_000L),  // 2024-04-18
            snap(1, 1_712_275_200_000L),  // 2024-04-05
        )
        val positions = listOf(
            DotPosition(centerXPx = 220f, yPx = 50f),
            DotPosition(centerXPx = 220f, yPx = 150f),
            DotPosition(centerXPx = 140f, yPx = 250f),
            DotPosition(centerXPx = 220f, yPx = 350f),
            DotPosition(centerXPx = 140f, yPx = 450f),
        )
        val dividers = computeDateDividers(
            snapshots = s,
            dotPositions = positions,
            viewportWidthPx = 360f,
            monthMarginPx = 100f,
            locale = Locale.US,
            zone = zone,
        )
        assertEquals(3, dividers.size)
        assertEquals("Jun", dividers[0].text)
        assertEquals("May", dividers[1].text)
        assertEquals("Apr", dividers[2].text)
    }

    @Test
    fun divider_x_lands_on_opposite_side_of_dot() {
        val zone = ZoneOffset.UTC
        val s = listOf(snap(2, 1_719_273_600_000L), snap(1, 1_712_275_200_000L))
        val viewport = 360f
        val margin = 100f
        val dividers = computeDateDividers(
            snapshots = s,
            dotPositions = listOf(
                DotPosition(centerXPx = 250f, yPx = 50f),
                DotPosition(centerXPx = 110f, yPx = 150f),
            ),
            viewportWidthPx = viewport,
            monthMarginPx = margin,
            locale = Locale.US,
            zone = zone,
        )
        assertTrue(dividers.size >= 1)
        assertEquals(margin, dividers[0].xPx, 0.001f)
        if (dividers.size >= 2) {
            assertEquals(viewport - margin, dividers[1].xPx, 0.001f)
        }
    }

    @Test
    fun empty_input_returns_empty() {
        assertEquals(emptyList<DateDivider>(),
            computeDateDividers(emptyList(), emptyList(), 360f, 100f,
                Locale.US, ZoneId.systemDefault()))
    }
}
