// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.appearance

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [AppearancePreferencesRepository.appearanceMode]'s
 * `stateIn` collection. Same pattern as `VoiceGuideSelectionScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppearancePreferencesScope
