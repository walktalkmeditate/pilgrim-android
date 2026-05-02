// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * One planet's full state at a moment in time.
 *
 * `longitude` is geocentric ecliptic longitude in [0, 360) degrees.
 * `tropical` and `sidereal` are the same point projected into each
 * zodiac convention (sidereal = tropical - ayanamsa).
 * `isRetrograde` is computed from a 1-day ephemeris delta. Sun and
 * Moon always return `false`.
 * `isIngress` is true when within 1° of a sign boundary.
 */
data class PlanetaryPosition(
    val planet: Planet,
    val longitude: Double,
    val tropical: ZodiacPosition,
    val sidereal: ZodiacPosition,
    val isRetrograde: Boolean,
    val isIngress: Boolean,
)
