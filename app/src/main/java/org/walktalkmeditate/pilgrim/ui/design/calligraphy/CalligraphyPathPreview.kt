// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * Debug preview screen for Stage 3-C. Wired behind a debug button on
 * `HomeScreen` and deleted once Stage 3-E integrates the renderer
 * into the journal list itself.
 *
 * Stage 3-D: each stroke's color now runs through
 * [SeasonalColorEngine.applySeasonalShift] with the walk's start date
 * + the device hemisphere, so the thread tints by season.
 */
@Composable
fun CalligraphyPathPreviewScreen(
    viewModel: CalligraphyPathPreviewViewModel = hiltViewModel(),
) {
    val previews by viewModel.state.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()

    // Resolve the four base seasonal colors once per theme change.
    // The `@ReadOnlyComposable` reads land in the composable body so
    // they participate in theme invalidation; the actual per-stroke
    // HSB math runs inside `remember` so unrelated recompositions
    // don't rebuild N Color allocations. The map is itself wrapped
    // in `remember` keyed by the four Color values so a recomposition
    // that doesn't cross a theme boundary reuses the same instance.
    val inkColor = SeasonalInkFlavor.Ink.toBaseColor()
    val mossColor = SeasonalInkFlavor.Moss.toBaseColor()
    val rustColor = SeasonalInkFlavor.Rust.toBaseColor()
    val dawnColor = SeasonalInkFlavor.Dawn.toBaseColor()
    val baseColors = remember(inkColor, mossColor, rustColor, dawnColor) {
        mapOf(
            SeasonalInkFlavor.Ink to inkColor,
            SeasonalInkFlavor.Moss to mossColor,
            SeasonalInkFlavor.Rust to rustColor,
            SeasonalInkFlavor.Dawn to dawnColor,
        )
    }

    val strokes = remember(previews, hemisphere, baseColors) {
        previews.map { preview ->
            val walkDate = Instant.ofEpochMilli(preview.spec.startMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val shifted = SeasonalColorEngine.applySeasonalShift(
                base = baseColors.getValue(preview.flavor),
                intensity = SeasonalColorEngine.Intensity.Moderate,
                date = walkDate,
                hemisphere = hemisphere,
            )
            preview.spec.copy(ink = shifted)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
    ) {
        Text(
            text = "Calligraphy preview",
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        CalligraphyPath(strokes = strokes)
    }
}
