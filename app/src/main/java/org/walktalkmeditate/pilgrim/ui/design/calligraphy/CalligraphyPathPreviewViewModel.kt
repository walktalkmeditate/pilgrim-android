// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.ui.design.calligraphy

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.walktalkmeditate.pilgrim.data.WalkRepository
import org.walktalkmeditate.pilgrim.domain.Clock
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.Hemisphere
import org.walktalkmeditate.pilgrim.ui.theme.seasonal.HemisphereRepository

/**
 * Debug-only VM for the Stage 3-C preview screen. Observes all walks
 * from Room and emits a list of [PreviewStroke] pairing a
 * [CalligraphyStrokeSpec] (minus its final color) with the
 * [SeasonalInkFlavor] that downstream rendering maps to a seasonally-
 * shifted [Color] — the VM leaves `ink` as a placeholder because the
 * color resolution needs a `@Composable` context for theme reads.
 *
 * The preview screen inlines a
 * [org.walktalkmeditate.pilgrim.ui.theme.seasonal.SeasonalColorEngine.applySeasonalShift]
 * call (rather than using the convenience
 * [SeasonalInkFlavor.toSeasonalColor] extension) so the four base
 * PilgrimColors reads can be memoized via `remember`. Stage 3-E's
 * journal integration will use the extension directly.
 *
 * Observes via a Flow rather than a one-shot `allWalks()` fetch so
 * that navigating away, finishing a new walk, and navigating back
 * reflects the new walk — the VM is scoped to the NavBackStackEntry
 * and survives pop+re-entry, so a one-shot `init { load() }` would
 * pin to stale data. (Closing-review catch.)
 *
 * If the device has no finished walks yet, falls back to eight
 * synthetic strokes spanning the year for visual verification.
 *
 * Also exposes [hemisphere] so the preview screen can re-tint colors
 * when the cached hemisphere updates.
 */
@HiltViewModel
class CalligraphyPathPreviewViewModel @Inject constructor(
    private val repository: WalkRepository,
    private val clock: Clock,
    hemisphereRepository: HemisphereRepository,
) : ViewModel() {

    /**
     * Current hemisphere, proxied from [HemisphereRepository] so the
     * preview screen can observe it alongside [state]. The repository
     * exposes a `stateIn(WhileSubscribed)` StateFlow; subscribing from
     * the preview screen is what drives the underlying DataStore read.
     */
    val hemisphere: StateFlow<Hemisphere> = hemisphereRepository.hemisphere

    init {
        viewModelScope.launch {
            hemisphereRepository.refreshFromLocationIfNeeded()
        }
    }

    val state: StateFlow<List<PreviewStroke>> = repository.observeAllWalks()
        .map { walks ->
            val finished = walks.filter { it.endTimestamp != null }
            if (finished.isNotEmpty()) {
                finished.map { walk ->
                    val samples = repository.locationSamplesFor(walk.id)
                    // `ink` is a placeholder — the preview Composable
                    // resolves the real color via a direct
                    // SeasonalColorEngine.applySeasonalShift call
                    // (reusing a memoized base-colors map), which
                    // needs a @Composable context for theme reads.
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
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            // WhileSubscribed matches HomeViewModel's pattern: unit
            // tests that don't subscribe don't leave a never-
            // completing collector running in viewModelScope.
            started = SharingStarted.WhileSubscribed(SUBSCRIBER_GRACE_MS),
            initialValue = emptyList(),
        )

    private fun synthetic(): List<PreviewStroke> {
        val baseMillis = clock.now() - 240L * 86_400_000L
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

    private companion object {
        const val SUBSCRIBER_GRACE_MS = 5_000L
    }
}
