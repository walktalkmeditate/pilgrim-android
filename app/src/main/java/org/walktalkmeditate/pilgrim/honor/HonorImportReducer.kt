// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.honor

import android.content.res.Resources
import java.util.Locale
import kotlin.math.roundToInt
import org.walktalkmeditate.pilgrim.R
import org.walktalkmeditate.pilgrim.data.honor.WayError

/** iOS `HonorImportState` (`HonorImportReducer.swift:3-5@7c200bf`): one value for the whole app (S1 §6.3). */
sealed interface HonorImportState {
    data object Idle : HonorImportState

    data object Fetching : HonorImportState

    /** [progress] is files done over files declared, 0 to 1. */
    data class Gathering(val progress: Double) : HonorImportState

    data object Ready : HonorImportState

    /** The relative paths that didn't land. Nothing ever shows them (S1 §6.6). */
    data class MediaMissing(val files: List<String>) : HonorImportState

    data class Failed(val error: WayError) : HonorImportState
}

/**
 * iOS `HonorImportReducer`: the media download's four published sets to
 * the overview's state, first match winning (S1 §6.2). It never yields
 * idle, fetching, or a failure but disk full; the coordinator sets those.
 */
object HonorImportReducer {
    fun state(
        wayId: String,
        progress: Map<String, Double>,
        active: Set<String>,
        failures: Map<String, List<String>>,
        diskFull: Set<String>,
    ): HonorImportState {
        if (wayId in diskFull) return HonorImportState.Failed(WayError.DISK_FULL)
        if (wayId in active) return HonorImportState.Gathering(progress[wayId] ?: 0.0)
        val missing = failures[wayId]
        if (!missing.isNullOrEmpty()) return HonorImportState.MediaMissing(missing)
        return HonorImportState.Ready
    }
}

/**
 * iOS `HonorImportCopy` (`HonorImportReducer.swift:24-37@7c200bf`): the
 * line each state speaks, every string exact (S1 §6.1). English Swift
 * literals on iOS, with no localization key.
 */
object HonorImportCopy {
    fun line(resources: Resources, state: HonorImportState): String? = when (state) {
        HonorImportState.Idle, HonorImportState.Ready -> null
        HonorImportState.Fetching -> resources.getString(R.string.honor_import_fetching)
        is HonorImportState.Gathering -> resources.getString(R.string.honor_import_gathering, percent(state.progress))
        is HonorImportState.MediaMissing -> resources.getString(R.string.honor_import_media_missing)
        is HonorImportState.Failed -> resources.getString(
            when (state.error) {
                WayError.NOT_FOUND -> R.string.honor_import_not_found
                WayError.RETURNED_TO_TRAIL -> R.string.honor_import_returned_to_trail
                WayError.UNAVAILABLE -> R.string.honor_import_unavailable
                WayError.DISK_FULL -> R.string.honor_import_disk_full
            },
        )
    }

    /** iOS `Int((p * 100).rounded())`: half away from zero, which [roundToInt] is for a non-negative share. */
    private fun percent(progress: Double): String {
        val whole = if (progress.isNaN()) 0 else (progress * 100).roundToInt()
        return String.format(Locale.US, "%d", whole)
    }
}
