// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * Stage 13-Cel: 12-sign zodiac. Verbatim port of iOS
 * `AstrologyModels.swift` ZodiacSign + nested Element + Modality.
 * `displayName` and `symbol` are inline data (not strings.xml) — matches
 * iOS hardcoding pattern; localization deferred until Android adds locales.
 */
enum class ZodiacSign(val ordinal0: Int, val displayName: String, val symbol: String, val element: Element, val modality: Modality) {
    Aries(0, "Aries", "♈︎", Element.Fire, Modality.Cardinal),
    Taurus(1, "Taurus", "♉︎", Element.Earth, Modality.Fixed),
    Gemini(2, "Gemini", "♊︎", Element.Air, Modality.Mutable),
    Cancer(3, "Cancer", "♋︎", Element.Water, Modality.Cardinal),
    Leo(4, "Leo", "♌︎", Element.Fire, Modality.Fixed),
    Virgo(5, "Virgo", "♍︎", Element.Earth, Modality.Mutable),
    Libra(6, "Libra", "♎︎", Element.Air, Modality.Cardinal),
    Scorpio(7, "Scorpio", "♏︎", Element.Water, Modality.Fixed),
    Sagittarius(8, "Sagittarius", "♐︎", Element.Fire, Modality.Mutable),
    Capricorn(9, "Capricorn", "♑︎", Element.Earth, Modality.Cardinal),
    Aquarius(10, "Aquarius", "♒︎", Element.Air, Modality.Fixed),
    Pisces(11, "Pisces", "♓︎", Element.Water, Modality.Mutable),
    ;

    enum class Element(val displayName: String, val symbol: String) {
        Fire("Fire", "🜂"),
        Earth("Earth", "🜃"),
        Air("Air", "🜁"),
        Water("Water", "🜄"),
    }

    enum class Modality { Cardinal, Fixed, Mutable }

    companion object {
        fun fromIndex(idx: Int): ZodiacSign = entries[((idx % 12) + 12) % 12]
    }
}
