// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

object PilgrimPaletteLight {
    val stone = Color(0xFF8B7355)
    val ink = Color(0xFF2C2416)
    val parchment = Color(0xFFF5F0E8)
    val parchmentSecondary = Color(0xFFEDE6D8)
    val parchmentTertiary = Color(0xFFE4DBC9)
    val moss = Color(0xFF7A8B6F)
    val rust = Color(0xFFA0634B)
    val fog = Color(0xFFB8AFA2)
    val dawn = Color(0xFFC4956A)
}

object PilgrimPaletteDark {
    val stone = Color(0xFFB8976E)
    val ink = Color(0xFFF0EBE1)
    val parchment = Color(0xFF1C1914)
    val parchmentSecondary = Color(0xFF262118)
    val parchmentTertiary = Color(0xFF302920)
    val moss = Color(0xFF95A888)
    val rust = Color(0xFFC47E63)
    val fog = Color(0xFF6B6359)
    val dawn = Color(0xFFD4A87A)
}

@Stable
data class PilgrimColors(
    val stone: Color,
    val ink: Color,
    val parchment: Color,
    val parchmentSecondary: Color,
    val parchmentTertiary: Color,
    val moss: Color,
    val rust: Color,
    val fog: Color,
    val dawn: Color,
) {
    val background: Color get() = parchment
    val secondaryBackground: Color get() = parchmentSecondary
    val tertiaryBackground: Color get() = parchmentTertiary
    val onBackground: Color get() = ink
}

fun pilgrimLightColors() = PilgrimColors(
    stone = PilgrimPaletteLight.stone,
    ink = PilgrimPaletteLight.ink,
    parchment = PilgrimPaletteLight.parchment,
    parchmentSecondary = PilgrimPaletteLight.parchmentSecondary,
    parchmentTertiary = PilgrimPaletteLight.parchmentTertiary,
    moss = PilgrimPaletteLight.moss,
    rust = PilgrimPaletteLight.rust,
    fog = PilgrimPaletteLight.fog,
    dawn = PilgrimPaletteLight.dawn,
)

fun pilgrimDarkColors() = PilgrimColors(
    stone = PilgrimPaletteDark.stone,
    ink = PilgrimPaletteDark.ink,
    parchment = PilgrimPaletteDark.parchment,
    parchmentSecondary = PilgrimPaletteDark.parchmentSecondary,
    parchmentTertiary = PilgrimPaletteDark.parchmentTertiary,
    moss = PilgrimPaletteDark.moss,
    rust = PilgrimPaletteDark.rust,
    fog = PilgrimPaletteDark.fog,
    dawn = PilgrimPaletteDark.dawn,
)

val LocalPilgrimColors = staticCompositionLocalOf { pilgrimLightColors() }

val pilgrimColors: PilgrimColors
    @Composable
    @ReadOnlyComposable
    get() = LocalPilgrimColors.current
