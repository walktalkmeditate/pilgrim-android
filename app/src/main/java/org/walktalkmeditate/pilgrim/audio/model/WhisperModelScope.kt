// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.model

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * backing [WhisperModelStore.state]'s `stateIn`. Same shape as
 * `VoiceGuideCatalogScope` (Stage 5-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WhisperModelScope
