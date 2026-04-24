// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.etegami

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Stage 7-C: time-of-day palette for the etegami postcard. Matches iOS
 * `EtegamiGenerator.colors(for:)` — NOT the seasonal engine. The quality
 * of light at walk-start is the signal; adding seasonal tint would
 * muddle the reference.
 */
@Immutable
data class EtegamiPalette(
    val paper: Color,
    val ink: Color,
)

object EtegamiPalettes {
    /**
     * Returns the palette for the given [hour] (0-23). Hours 5-7 = dawn,
     * 8-10 = morning, 11-13 = midday, 14-16 = afternoon, 17-19 = evening;
     * everything else (night) inverts to a dark paper with light ink so
     * the postcard remains legible at any walk time.
     */
    fun forHour(hour: Int): EtegamiPalette = when (hour) {
        in 5..7 -> EtegamiPalette(paper = Color(0xFFF5E6C8), ink = Color(0xFF2C241E))
        in 8..10 -> EtegamiPalette(paper = Color(0xFFF5F0E8), ink = Color(0xFF2C241E))
        in 11..13 -> EtegamiPalette(paper = Color(0xFFFAF8F3), ink = Color(0xFF2C241E))
        in 14..16 -> EtegamiPalette(paper = Color(0xFFF0E4C8), ink = Color(0xFF2C241E))
        in 17..19 -> EtegamiPalette(paper = Color(0xFFE8D0C0), ink = Color(0xFF2C241E))
        else -> EtegamiPalette(paper = Color(0xFF1A1E2E), ink = Color(0xFFD0C8B8))
    }
}
