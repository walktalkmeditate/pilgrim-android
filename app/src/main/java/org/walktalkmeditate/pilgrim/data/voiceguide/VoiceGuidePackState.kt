// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import androidx.compose.runtime.Immutable

/**
 * Picker-ready DTO describing the combined state of one pack:
 * download status (filesystem) × selection status (DataStore) ×
 * in-flight progress (WorkManager). Composed by
 * [VoiceGuideCatalogRepository].
 *
 * `@Immutable` annotations help Compose skip recomposition when a
 * LazyColumn row's state is unchanged between emissions. Without
 * them, the enclosing [VoiceGuidePack] (which holds lists of other
 * data classes) would be marked Unstable and re-render on every
 * list emission.
 */
@Immutable
sealed class VoiceGuidePackState {
    abstract val pack: VoiceGuidePack
    abstract val isSelected: Boolean

    @Immutable
    data class NotDownloaded(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
    ) : VoiceGuidePackState()

    @Immutable
    data class Downloading(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
        val completed: Int,
        val total: Int,
    ) : VoiceGuidePackState() {
        val fraction: Float
            get() = if (total == 0) 0f else completed.toFloat() / total.toFloat()
    }

    @Immutable
    data class Downloaded(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
    ) : VoiceGuidePackState()

    @Immutable
    data class Failed(
        override val pack: VoiceGuidePack,
        override val isSelected: Boolean,
        val reason: String,
    ) : VoiceGuidePackState()
}
