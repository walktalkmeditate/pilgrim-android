// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.home.scenery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import org.walktalkmeditate.pilgrim.ui.home.WalkSnapshot
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine
import java.time.Instant
import java.time.ZoneId

/**
 * Type-dispatch for scenery rendering. Resolves the per-type seasonal
 * tint via [SeasonalColorEngine] (full intensity, walk-date-aware) and
 * delegates to the matching scenery Composable.
 *
 * `pilgrimColors.*` reads MUST happen at this composable layer (the
 * VM cannot read theme tokens). Each per-type Composable receives an
 * already-resolved `tintColor` so the renderer is theme-free.
 */
@Composable
internal fun SceneryItem(
    placement: SceneryPlacement,
    snapshot: WalkSnapshot,
    sizeDp: Dp,
    hemisphere: Hemisphere,
) {
    val walkDate = remember(snapshot.startMs) {
        Instant.ofEpochMilli(snapshot.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }

    val baseTokens = pilgrimColors
    val baseColor = when (placement.type.tintTokenName) {
        "moss" -> baseTokens.moss
        "stone" -> baseTokens.stone
        "dawn" -> baseTokens.dawn
        "fog" -> baseTokens.fog
        "rust" -> baseTokens.rust
        else -> baseTokens.fog
    }
    val tintColor = remember(baseColor, walkDate, hemisphere) {
        SeasonalColorEngine.applySeasonalShift(
            base = baseColor,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = walkDate,
            hemisphere = hemisphere,
        )
    }

    val parchmentColor = baseTokens.parchment
    val dawnColor = remember(baseTokens.dawn, walkDate, hemisphere) {
        SeasonalColorEngine.applySeasonalShift(
            base = baseTokens.dawn,
            intensity = SeasonalColorEngine.Intensity.Full,
            date = walkDate,
            hemisphere = hemisphere,
        )
    }

    when (placement.type) {
        SceneryType.Tree -> TreeScenery(
            sizeDp = sizeDp,
            tintColor = treeSeasonalTint(snapshot, hemisphere, baseTokens),
            walkDateMs = snapshot.startMs,
        )
        SceneryType.Lantern -> LanternScenery(
            sizeDp = sizeDp,
            tintColor = tintColor,
            walkDateMs = snapshot.startMs,
            glowColor = lanternGlowColor(snapshot, hemisphere, baseTokens),
        )
        SceneryType.Butterfly -> ButterflyScenery(
            sizeDp = sizeDp,
            wingColor = butterflySeasonalColor(snapshot, hemisphere, baseTokens),
        )
        SceneryType.Mountain -> MountainScenery(
            sizeDp = sizeDp,
            tintColor = tintColor,
            walkDateMs = snapshot.startMs,
            dawnColor = dawnColor,
        )
        SceneryType.Grass -> GrassScenery(
            sizeDp = sizeDp,
            grassColor = grassSeasonalColor(snapshot, hemisphere, baseTokens),
            walkDateMs = snapshot.startMs,
        )
        SceneryType.Torii -> ToriiScenery(
            sizeDp = sizeDp,
            tintColor = tintColor,
            dawnColor = dawnColor,
        )
        SceneryType.Moon -> MoonScenery(
            sizeDp = sizeDp,
            tintColor = tintColor,
            walkDateMs = snapshot.startMs,
            parchmentColor = parchmentColor,
        )
    }
}

@Composable
private fun treeSeasonalTint(
    snapshot: WalkSnapshot,
    hemisphere: Hemisphere,
    tokens: org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors,
): Color {
    val walkDate = remember(snapshot.startMs) {
        Instant.ofEpochMilli(snapshot.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val month = walkDate.monthValue
    return when (month) {
        in 3..5 -> SeasonalColorEngine.applySeasonalShift(tokens.moss, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
        in 6..8 -> SeasonalColorEngine.applySeasonalShift(tokens.stone, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
        in 9..11 -> SeasonalColorEngine.applySeasonalShift(tokens.dawn, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
        else -> SeasonalColorEngine.applySeasonalShift(tokens.ink, SeasonalColorEngine.Intensity.Moderate, walkDate, hemisphere)
    }
}

@Composable
private fun lanternGlowColor(
    snapshot: WalkSnapshot,
    hemisphere: Hemisphere,
    tokens: org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors,
): Color {
    val walkDate = remember(snapshot.startMs) {
        Instant.ofEpochMilli(snapshot.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val month = walkDate.monthValue
    val isWinter = month == 12 || month <= 2
    val base = if (isWinter) tokens.dawn else tokens.stone
    return SeasonalColorEngine.applySeasonalShift(base, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
}

@Composable
private fun butterflySeasonalColor(
    snapshot: WalkSnapshot,
    hemisphere: Hemisphere,
    tokens: org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors,
): Color {
    val walkDate = remember(snapshot.startMs) {
        Instant.ofEpochMilli(snapshot.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val month = walkDate.monthValue
    return when (month) {
        in 3..5 -> Color(0xFFFFB3CC)
        in 6..8 -> SeasonalColorEngine.applySeasonalShift(tokens.dawn, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
        in 9..11 -> SeasonalColorEngine.applySeasonalShift(tokens.rust, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
        else -> Color.White.copy(alpha = 0.8f)
    }
}

@Composable
private fun grassSeasonalColor(
    snapshot: WalkSnapshot,
    hemisphere: Hemisphere,
    tokens: org.walktalkmeditate.pilgrim.ui.theme.PilgrimColors,
): Color {
    val walkDate = remember(snapshot.startMs) {
        Instant.ofEpochMilli(snapshot.startMs).atZone(ZoneId.systemDefault()).toLocalDate()
    }
    val month = walkDate.monthValue
    val isWinter = month == 12 || month <= 2
    val base = if (isWinter) tokens.dawn else tokens.moss
    return SeasonalColorEngine.applySeasonalShift(base, SeasonalColorEngine.Intensity.Full, walkDate, hemisphere)
}
