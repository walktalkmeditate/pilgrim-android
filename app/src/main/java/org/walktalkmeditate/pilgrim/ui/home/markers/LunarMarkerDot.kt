// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.markers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 10×10 dp moon glyph. Full = filled disc; New = stroked circle.
 * Color flips light/dark theme; verbatim iOS RGB literals from
 * `InkScrollView+LunarMarkers.swift:15-29`.
 */
@Composable
fun LunarMarkerDot(isFullMoon: Boolean, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val moonColor = if (dark) {
        Color(red = 0.85f, green = 0.82f, blue = 0.72f)
    } else {
        Color(red = 0.55f, green = 0.58f, blue = 0.65f)
    }
    if (isFullMoon) {
        val alpha = if (dark) 0.6f else 0.4f
        Box(
            modifier = modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(moonColor.copy(alpha = alpha)),
        )
    } else {
        val alpha = if (dark) 0.7f else 0.5f
        Box(
            modifier = modifier
                .size(10.dp)
                .border(width = 1.dp, color = moonColor.copy(alpha = alpha), shape = CircleShape),
        )
    }
}
