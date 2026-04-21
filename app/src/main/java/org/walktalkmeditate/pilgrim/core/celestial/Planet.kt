// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * The seven classical planets used by Chaldean planetary-hour
 * calculations. Order follows the Chaldean sequence (slowest to
 * fastest): Saturn, Jupiter, Mars, Sun, Venus, Mercury, Moon.
 * Consumers should not rely on Kotlin's `ordinal` matching that
 * sequence — [PlanetaryHourCalc] defines its own order list.
 */
enum class Planet { Saturn, Jupiter, Mars, Sun, Venus, Mercury, Moon }
