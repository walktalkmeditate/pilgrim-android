// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.ui.graphics.Color
import org.walktalkmeditate.pilgrim.core.celestial.SeasonalMarker
import org.walktalkmeditate.pilgrim.core.celestial.turningMarkerForEpochMillis
import org.walktalkmeditate.pilgrim.data.entity.WalkFavicon

/**
 * Goshuin seal ink palette — verbatim port of iOS `SealColorPalette.swift`
 * @fcd2255 (v1.6.0). The seal's color is chosen by the walk's mood tag
 * (favicon → a color family) indexed by a deterministic hash byte, with a
 * turning-day override that paints solstice/equinox seals in the cardinal
 * accent. iOS `SealGenerator` uses this color RAW — no seasonal shift
 * (`SealGenerator.swift:43`).
 *
 * Each [SealColor] carries a light + dark hex; the seal renderer resolves
 * the variant from the active theme. Cross-platform note: the seal
 * *geometry* hash already diverges by design (iOS SHA-256 vs Android
 * FNV/SplitMix — see [sealHashBytes]), so this port matches the color
 * *system* — same family-by-favicon behavior and turning override — not a
 * byte-exact color for a given walk.
 */
object SealColorPalette {

    data class SealColor(val light: Color, val dark: Color)

    // Warm — Transformative / flame
    val rust = SealColor(Color(0xFFA0634B), Color(0xFFC47E63))
    val ember = SealColor(Color(0xFFB5553A), Color(0xFFD4735A))
    val sienna = SealColor(Color(0xFF946B4E), Color(0xFFB88A6A))
    val copper = SealColor(Color(0xFFB87333), Color(0xFFD4955E))

    // Cool — Peaceful / leaf
    val moss = SealColor(Color(0xFF7A8B6F), Color(0xFF95A895))
    val sage = SealColor(Color(0xFF8A9A7B), Color(0xFFA3B396))
    val seaGlass = SealColor(Color(0xFF6B8E8E), Color(0xFF89ABAB))
    val mist = SealColor(Color(0xFF8FA3A3), Color(0xFFA8B8B8))

    // Accent — Extraordinary / star
    val indigo = SealColor(Color(0xFF4B5A78), Color(0xFF6E7F9E))
    val gold = SealColor(Color(0xFFB8973E), Color(0xFFD4B35E))
    val twilight = SealColor(Color(0xFF6B5B7B), Color(0xFF8E7E9E))
    val amethyst = SealColor(Color(0xFF7B6B8B), Color(0xFF9E8EAE))

    // Neutral — Unmarked
    val stone = SealColor(Color(0xFF8B7355), Color(0xFFB8976E))
    val dawn = SealColor(Color(0xFFC4956A), Color(0xFFD4A87A))
    val fog = SealColor(Color(0xFF6B6359), Color(0xFFB8AFA2))

    val warmColors = listOf(rust, ember, sienna, copper)
    val coolColors = listOf(moss, sage, seaGlass, mist)
    val accentColors = listOf(indigo, gold, twilight, amethyst)
    val neutralColors = listOf(stone, dawn, fog)

    // Turning (solstice / equinox overrides — not in the families above)
    val turningJade = SealColor(Color(0xFF74B495), Color(0xFF88C5A0))
    val turningGold = SealColor(Color(0xFFC9A646), Color(0xFFD5B55D))
    val turningClaret = SealColor(Color(0xFF8B4455), Color(0xFFA26070))
    val turningIndigo = SealColor(Color(0xFF2377A4), Color(0xFF4691BA))

    /** Favicon → color family, indexed by [hashByte] modulo the family size. */
    fun color(favicon: WalkFavicon?, hashByte: Int): SealColor = when (favicon) {
        WalkFavicon.FLAME -> warmColors[hashByte % warmColors.size]
        WalkFavicon.LEAF -> coolColors[hashByte % coolColors.size]
        WalkFavicon.STAR -> accentColors[hashByte % accentColors.size]
        null -> neutralColors[hashByte % neutralColors.size]
    }

    /** The cardinal-turning seal color, or null for cross-quarter / non-turning. */
    fun turningSealColor(marker: SeasonalMarker?): SealColor? = when (marker) {
        SeasonalMarker.SpringEquinox -> turningJade
        SeasonalMarker.SummerSolstice -> turningGold
        SeasonalMarker.AutumnEquinox -> turningClaret
        SeasonalMarker.WinterSolstice -> turningIndigo
        else -> null
    }

    /**
     * Resolve the seal ink. Turning days win and use the turning color's
     * LIGHT variant unconditionally (iOS `uiColor(for:)` returns
     * `sealColor.light` even in dark mode; the turning light/dark hexes
     * are near-identical). Otherwise the favicon family resolves to the
     * theme-appropriate variant.
     */
    fun sealInk(favicon: WalkFavicon?, marker: SeasonalMarker?, hashByte: Int, isDark: Boolean): Color {
        turningSealColor(marker)?.let { return it.light }
        val seal = color(favicon, hashByte)
        return if (isDark) seal.dark else seal.light
    }

    /**
     * Convenience entry for a [SealSpec]: derives the turning from
     * [SealSpec.startMillis] and the family index from the seal hash's
     * 31st byte (iOS `bytes[30]`). iOS `SealColorPalette.uiColor(for:)`.
     */
    fun sealInk(spec: SealSpec, isDark: Boolean): Color =
        sealInk(
            favicon = spec.favicon,
            marker = turningMarkerForEpochMillis(spec.startMillis),
            hashByte = sealHashBytes(spec).u(SEAL_COLOR_HASH_INDEX),
            isDark = isDark,
        )

    /** iOS `bytes[30]` — the hash byte driving family selection. */
    private const val SEAL_COLOR_HASH_INDEX = 30
}
