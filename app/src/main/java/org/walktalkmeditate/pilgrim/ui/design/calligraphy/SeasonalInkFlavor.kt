// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors

/**
 * Northern-hemisphere month → base color bucket. Stage 3-D will wrap
 * this with the HSB shift + hemisphere inversion; for 3-C we pick the
 * nearest base PilgrimColors token.
 */
enum class SeasonalInkFlavor {
    Ink, Moss, Rust, Dawn;

    companion object {
        fun forMonth(startMillis: Long, zone: ZoneId = ZoneId.systemDefault()): SeasonalInkFlavor {
            val month = Instant.ofEpochMilli(startMillis).atZone(zone).monthValue
            return when (month) {
                in 3..5 -> Moss
                in 6..8 -> Rust
                in 9..11 -> Dawn
                else -> Ink
            }
        }
    }
}

@Composable
@ReadOnlyComposable
fun SeasonalInkFlavor.toColor(): Color = when (this) {
    SeasonalInkFlavor.Ink -> pilgrimColors.ink
    SeasonalInkFlavor.Moss -> pilgrimColors.moss
    SeasonalInkFlavor.Rust -> pilgrimColors.rust
    SeasonalInkFlavor.Dawn -> pilgrimColors.dawn
}
