// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * backing [SoundscapeSelectionRepository.selectedSoundscapeId]'s
 * `stateIn`. Same shape as `VoiceGuideSelectionScope` from Stage
 * 5-D.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundscapeSelectionScope
