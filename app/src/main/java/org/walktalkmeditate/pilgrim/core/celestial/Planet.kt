// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * The seven classical planets used by Chaldean planetary-hour
 * calculations. Order follows the Chaldean sequence (slowest to
 * fastest): Saturn, Jupiter, Mars, Sun, Venus, Mercury, Moon.
 * Consumers should not rely on Kotlin's `ordinal` matching that
 * sequence — [PlanetaryHourCalc] defines its own order list.
 *
 * Stage 13-Cel: `displayName` and `symbol` added for the celestial
 * line UI on Walk Summary. Hardcoded English (verbatim iOS pattern);
 * localization deferred until Android adds non-English locales.
 */
enum class Planet(val displayName: String, val symbol: String) {
    Saturn("Saturn", "♄"),
    Jupiter("Jupiter", "♃"),
    Mars("Mars", "♂"),
    Sun("Sun", "☉"),
    Venus("Venus", "♀"),
    Mercury("Mercury", "☿"),
    Moon("Moon", "☽"),
}
