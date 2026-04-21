// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import androidx.compose.runtime.Immutable
import org.walktalkmeditate.pilgrim.data.audio.AudioAsset

/**
 * Picker-ready DTO describing the combined state of one soundscape
 * asset: download status (filesystem) × selection status (DataStore)
 * × in-flight progress (WorkManager). Composed by
 * [SoundscapeCatalogRepository].
 *
 * `@Immutable` so LazyColumn rows skip recomposition when state is
 * unchanged — Stage 5-D lesson (without it, `AudioAsset`'s List
 * field would cascade Unstable).
 *
 * Simpler than `VoiceGuidePackState` — no `completed`/`total`
 * counts (soundscapes are single-file-per-asset, no progress
 * breakdown).
 */
@Immutable
sealed class SoundscapeState {
    abstract val asset: AudioAsset
    abstract val isSelected: Boolean

    @Immutable
    data class NotDownloaded(
        override val asset: AudioAsset,
        override val isSelected: Boolean,
    ) : SoundscapeState()

    @Immutable
    data class Downloading(
        override val asset: AudioAsset,
        override val isSelected: Boolean,
    ) : SoundscapeState()

    @Immutable
    data class Downloaded(
        override val asset: AudioAsset,
        override val isSelected: Boolean,
    ) : SoundscapeState()

    @Immutable
    data class Failed(
        override val asset: AudioAsset,
        override val isSelected: Boolean,
        val reason: String,
    ) : SoundscapeState()
}
