// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyQuoteCaseTest {

    @Test fun walkAndTalkAndMeditate_returnsWalkTalkMeditate() {
        val result = classifyJourneyQuote(
            talkMillis = 60_000L,
            meditateMillis = 60_000L,
            distanceMeters = 2_000.0,
        )
        assertEquals(JourneyQuoteCase.WalkTalkMeditate, result)
    }

    @Test fun meditateOnly_underHundredMeters_returnsMeditateShort() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 60_000L,
            distanceMeters = 50.0,
        )
        assertEquals(JourneyQuoteCase.MeditateShort, result)
    }

    @Test fun meditateOnly_atHundredMetersBoundary_returnsMeditateWithDistance() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 60_000L,
            distanceMeters = 100.0,
        )
        assertTrue(result is JourneyQuoteCase.MeditateWithDistance)
        assertEquals(100.0, (result as JourneyQuoteCase.MeditateWithDistance).distanceMeters, 0.0)
    }

    @Test fun meditateOnly_overHundredMeters_returnsMeditateWithDistance_carryingMeters() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 60_000L,
            distanceMeters = 500.0,
        )
        assertTrue(result is JourneyQuoteCase.MeditateWithDistance)
        assertEquals(500.0, (result as JourneyQuoteCase.MeditateWithDistance).distanceMeters, 0.0)
    }

    @Test fun talkOnly_returnsTalkOnly() {
        val result = classifyJourneyQuote(
            talkMillis = 60_000L,
            meditateMillis = 0L,
            distanceMeters = 2_000.0,
        )
        assertEquals(JourneyQuoteCase.TalkOnly, result)
    }

    @Test fun walkOnly_overFiveKm_returnsLongRoad() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 0L,
            distanceMeters = 6_000.0,
        )
        assertEquals(JourneyQuoteCase.LongRoad, result)
    }

    @Test fun walkOnly_overOneKm_returnsSmallArrival() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 0L,
            distanceMeters = 2_000.0,
        )
        assertEquals(JourneyQuoteCase.SmallArrival, result)
    }

    @Test fun walkOnly_underOneKm_returnsQuietWalk() {
        val result = classifyJourneyQuote(
            talkMillis = 0L,
            meditateMillis = 0L,
            distanceMeters = 800.0,
        )
        assertEquals(JourneyQuoteCase.QuietWalk, result)
    }
}
