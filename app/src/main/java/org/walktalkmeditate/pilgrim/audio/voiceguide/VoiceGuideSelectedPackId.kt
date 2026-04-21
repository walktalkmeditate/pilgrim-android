// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.audio.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the read-only `StateFlow<String?>` of the currently
 * selected voice-guide pack id. [VoiceGuideOrchestrator] takes this
 * flow (not the full `VoiceGuideSelectionRepository`) so tests can
 * inject a `MutableStateFlow<String?>` directly. Production wiring
 * in `VoiceGuideModule` binds
 * `VoiceGuideSelectionRepository.selectedPackId`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideSelectedPackId
