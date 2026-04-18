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
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.walktalkmeditate.pilgrim.ui.theme.PilgrimSpacing
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimColors
import org.walktalkmeditate.pilgrim.ui.theme.pilgrimType

/**
 * Debug preview screen for Stage 3-C. Wired behind a debug button on
 * `HomeScreen` and deleted once Stage 3-E integrates the renderer
 * into the journal list itself.
 */
@Composable
fun CalligraphyPathPreviewScreen(
    viewModel: CalligraphyPathPreviewViewModel = hiltViewModel(),
) {
    val previews by viewModel.state.collectAsStateWithLifecycle()
    val strokes = previews.map { it.spec.copy(ink = it.flavor.toColor()) }
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
