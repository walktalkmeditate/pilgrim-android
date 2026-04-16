// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Font families are placeholders for now. Phase 1/3 swaps these for bundled
// Cormorant Garamond and Lato loaded via res/font/.
private val Display = FontFamily.Serif
private val Text = FontFamily.SansSerif

@Stable
data class PilgrimTypography(
    val displayLarge: TextStyle,
    val displayMedium: TextStyle,
    val heading: TextStyle,
    val timer: TextStyle,
    val statValue: TextStyle,
    val statLabel: TextStyle,
    val body: TextStyle,
    val button: TextStyle,
    val caption: TextStyle,
    val annotation: TextStyle,
    val micro: TextStyle,
    val microBold: TextStyle,
)

fun pilgrimTypography() = PilgrimTypography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 34.sp),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Light, fontSize = 28.sp),
    heading = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
    timer = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 48.sp),
    statValue = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 20.sp),
    statLabel = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    body = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 17.sp),
    button = TextStyle(fontFamily = Text, fontWeight = FontWeight.Bold, fontSize = 17.sp),
    caption = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 12.sp),
    annotation = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 11.sp),
    micro = TextStyle(fontFamily = Text, fontWeight = FontWeight.Normal, fontSize = 9.sp),
    microBold = TextStyle(fontFamily = Text, fontWeight = FontWeight.Bold, fontSize = 9.sp),
)

val LocalPilgrimTypography = staticCompositionLocalOf { pilgrimTypography() }

val pilgrimType: PilgrimTypography
    @Composable
    @ReadOnlyComposable
    get() = LocalPilgrimTypography.current
