// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.soundscape

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * backing [SoundscapeCatalogRepository.soundscapeStates]'s
 * `stateIn` collection. Same shape as `VoiceGuideCatalogScope`
 * (Stage 5-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundscapeCatalogScope
