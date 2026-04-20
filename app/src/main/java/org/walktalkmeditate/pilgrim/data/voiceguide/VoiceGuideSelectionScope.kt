// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voiceguide

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [VoiceGuideSelectionRepository.selectedPackId]'s
 * `stateIn` collection. Kept as a dedicated qualifier (vs a generic
 * `@ApplicationScope`) so tests can substitute a test dispatcher
 * without affecting other Hilt-provided scopes. Same pattern as
 * `HemisphereRepositoryScope` (Stage 3-D).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoiceGuideSelectionScope
