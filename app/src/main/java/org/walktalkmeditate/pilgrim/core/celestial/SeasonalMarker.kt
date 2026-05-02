// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.core.celestial

/**
 * 8 turning points of the year — equinoxes/solstices + cross-quarter
 * days. Ports iOS `AstrologyModels.swift` SeasonalMarker. `displayName`
 * is hardcoded English (verbatim iOS); cross-quarter prose uses Gaelic
 * names (Imbolc/Beltane/Lughnasadh/Samhain), not English glosses.
 */
enum class SeasonalMarker(val displayName: String) {
    SpringEquinox("Spring Equinox"),
    SummerSolstice("Summer Solstice"),
    AutumnEquinox("Autumn Equinox"),
    WinterSolstice("Winter Solstice"),
    Imbolc("Imbolc"),
    Beltane("Beltane"),
    Lughnasadh("Lughnasadh"),
    Samhain("Samhain"),
}
