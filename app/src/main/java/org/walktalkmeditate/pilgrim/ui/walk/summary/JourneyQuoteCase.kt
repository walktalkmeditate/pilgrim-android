// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.walk.summary

/**
 * Pure classification of which contextual journey quote to render on
 * Walk Summary, based on the user's mix of walking, talking, and
 * meditation. Verbatim port of iOS `WalkSummaryView.generateJourneyQuote`
 * (`WalkSummaryView.swift:396-417`).
 *
 * The composable [WalkJourneyQuote] resolves the case to a localized
 * string via `stringResource`, with `MeditateWithDistance` injecting
 * the formatted distance.
 */
internal sealed class JourneyQuoteCase {
    data object WalkTalkMeditate : JourneyQuoteCase()
    data object MeditateShort : JourneyQuoteCase()
    data class MeditateWithDistance(val distanceMeters: Double) : JourneyQuoteCase()
    data object TalkOnly : JourneyQuoteCase()
    data object LongRoad : JourneyQuoteCase()
    data object SmallArrival : JourneyQuoteCase()
    data object QuietWalk : JourneyQuoteCase()
}

internal fun classifyJourneyQuote(
    talkMillis: Long,
    meditateMillis: Long,
    distanceMeters: Double,
): JourneyQuoteCase {
    val hasTalk = talkMillis > 0L
    val hasMed = meditateMillis > 0L
    val distanceKm = distanceMeters / 1_000.0
    return when {
        hasTalk && hasMed -> JourneyQuoteCase.WalkTalkMeditate
        hasMed && distanceKm < 0.1 -> JourneyQuoteCase.MeditateShort
        hasMed -> JourneyQuoteCase.MeditateWithDistance(distanceMeters)
        hasTalk -> JourneyQuoteCase.TalkOnly
        distanceKm > 5.0 -> JourneyQuoteCase.LongRoad
        distanceKm > 1.0 -> JourneyQuoteCase.SmallArrival
        else -> JourneyQuoteCase.QuietWalk
    }
}
