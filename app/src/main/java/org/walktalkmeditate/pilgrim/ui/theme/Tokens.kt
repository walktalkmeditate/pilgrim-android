// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.theme

import androidx.compose.runtime.Stable
import androidx.compose.ui.unit.dp

@Stable
object PilgrimSpacing {
    val xs = 4.dp
    val small = 8.dp
    val normal = 16.dp
    val big = 24.dp
    val breathingRoom = 64.dp
}

@Stable
object PilgrimCornerRadius {
    val small = 8.dp
    val normal = 12.dp
    val big = 20.dp
}

@Stable
object PilgrimMotion {
    const val GENTLE_MS = 600
    const val BREATH_MS = 1200
    const val APPEAR_MS = 400
}

@Stable
object PilgrimOpacity {
    const val SUBTLE = 0.06f
    const val LIGHT = 0.12f
    const val MEDIUM = 0.30f
}

@Stable
object PilgrimSeasonal {
    const val SPRING_PEAK_DAY = 105
    const val SUMMER_PEAK_DAY = 196
    const val AUTUMN_PEAK_DAY = 288
    const val WINTER_PEAK_DAY = 15

    const val SPREAD = 91f

    const val SPRING_HUE = 0.02f
    const val SUMMER_HUE = 0.01f
    const val AUTUMN_HUE = 0.03f
    const val WINTER_HUE = -0.02f

    const val SPRING_SAT = 0.10f
    const val SUMMER_SAT = 0.15f
    const val AUTUMN_SAT = 0.05f
    const val WINTER_SAT = -0.15f

    const val SPRING_BRIGHT = 0.05f
    const val SUMMER_BRIGHT = 0.03f
    const val AUTUMN_BRIGHT = -0.03f
    const val WINTER_BRIGHT = -0.05f
}
