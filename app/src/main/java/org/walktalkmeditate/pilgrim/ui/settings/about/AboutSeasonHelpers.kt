// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.settings.about

import java.time.Instant
import java.time.ZoneId

enum class Season { Spring, Summer, Autumn, Winter }

/**
 * Three-month-bucket season classifier for the AboutView seasonal
 * vignette. Mirrors iOS `SealTimeHelpers.season(for:latitude:)`:
 * months 3-5 = Spring (Autumn south), 6-8 = Summer (Winter south),
 * 9-11 = Autumn (Spring south), 12-2 = Winter (Summer south).
 *
 * Northern hemisphere is `latitude >= 0` (equator treated as northern,
 * matching iOS).
 */
object AboutSeasonHelpers {

    fun season(instant: Instant, latitude: Double, zone: ZoneId = ZoneId.systemDefault()): Season {
        val month = instant.atZone(zone).monthValue
        val northern = latitude >= 0
        return when (month) {
            3, 4, 5 -> if (northern) Season.Spring else Season.Autumn
            6, 7, 8 -> if (northern) Season.Summer else Season.Winter
            9, 10, 11 -> if (northern) Season.Autumn else Season.Spring
            else -> if (northern) Season.Winter else Season.Summer
        }
    }
}
