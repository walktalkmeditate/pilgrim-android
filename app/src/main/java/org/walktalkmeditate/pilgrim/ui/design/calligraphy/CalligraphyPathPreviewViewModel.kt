// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository

/**
 * Debug-only VM for the Stage 3-C preview screen. Loads finished walks
 * from the repository and emits a list of [PreviewStroke] pairing a
 * [CalligraphyStrokeSpec] (minus its final color) with the
 * [SeasonalInkFlavor] the preview Composable will resolve into a real
 * [Color] via [SeasonalInkFlavor.toColor].
 *
 * If the device has no finished walks yet, falls back to eight
 * synthetic strokes spanning the year for visual verification.
 */
@HiltViewModel
class CalligraphyPathPreviewViewModel @Inject constructor(
    private val repository: WalkRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<List<PreviewStroke>>(emptyList())
    val state: StateFlow<List<PreviewStroke>> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val walks = repository.allWalks().filter { it.endTimestamp != null }
            _state.value = if (walks.isNotEmpty()) {
                walks.map { walk ->
                    val samples = repository.locationSamplesFor(walk.id)
                    // `ink` is a placeholder — the preview Composable
                    // resolves the real color via SeasonalInkFlavor.toColor()
                    // which needs a @Composable context.
                    val spec = walk.toStrokeSpec(samples = samples, ink = Color.Transparent)
                    PreviewStroke(
                        spec = spec,
                        flavor = SeasonalInkFlavor.forMonth(walk.startTimestamp),
                    )
                }
            } else {
                synthetic()
            }
        }
    }

    private fun synthetic(): List<PreviewStroke> {
        val baseMillis = System.currentTimeMillis() - 240L * 86_400_000L
        return (0 until 8).map { i ->
            val start = baseMillis + i * 30L * 86_400_000L
            val pace = if (i % 2 == 0) 400.0 else 800.0
            PreviewStroke(
                spec = CalligraphyStrokeSpec(
                    uuid = "synthetic-$i",
                    startMillis = start,
                    distanceMeters = 2_500.0 + i * 400.0,
                    averagePaceSecPerKm = pace,
                    ink = Color.Transparent,
                ),
                flavor = SeasonalInkFlavor.forMonth(start),
            )
        }
    }

    data class PreviewStroke(
        val spec: CalligraphyStrokeSpec,
        val flavor: SeasonalInkFlavor,
    )
}
