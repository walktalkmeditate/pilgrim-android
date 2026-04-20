// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * backing [VoiceGuideCatalogRepository.packStates]'s `stateIn` and
 * [VoiceGuideDownloadObserver]'s auto-select observer. Same shape
 * as `MeditationBellScope` (Stage 5-B) and `VoiceGuideManifestScope`
 * (Stage 5-C).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideCatalogScope
