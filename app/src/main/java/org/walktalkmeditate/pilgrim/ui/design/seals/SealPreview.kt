// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.seals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine

/**
 * Debug-only preview screen for Stage 4-A's seal renderer. Reached via
 * a `BuildConfig.DEBUG`-gated button on HomeScreen; deleted when Stage
 * 4-B's reveal animation integrates the renderer into the real
 * walk-finish flow.
 */
@Composable
fun SealPreviewScreen(
    viewModel: SealPreviewViewModel = hiltViewModel(),
) {
    val previews by viewModel.state.collectAsStateWithLifecycle()
    val hemisphere by viewModel.hemisphere.collectAsStateWithLifecycle()
    val baseInk = pilgrimColors.rust

    val resolvedSpecs = remember(previews, hemisphere, baseInk) {
        previews.map { preview ->
            val walkDate = Instant.ofEpochMilli(preview.walkStartMillis)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val ink = SeasonalColorEngine.applySeasonalShift(
                base = baseInk,
                intensity = SeasonalColorEngine.Intensity.Full,
                date = walkDate,
                hemisphere = hemisphere,
            )
            preview.spec.copy(ink = ink)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PilgrimSpacing.big),
        verticalArrangement = Arrangement.spacedBy(PilgrimSpacing.big),
    ) {
        Text(
            text = "Seal preview",
            style = pilgrimType.displayMedium,
            color = pilgrimColors.ink,
        )
        if (resolvedSpecs.isEmpty()) {
            Text(
                text = "Loading seals…",
                style = pilgrimType.body,
                color = pilgrimColors.fog,
            )
        } else {
            resolvedSpecs.forEach { spec ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    SealRenderer(
                        spec = spec,
                        modifier = Modifier.size(240.dp),
                    )
                }
            }
        }
    }
}
