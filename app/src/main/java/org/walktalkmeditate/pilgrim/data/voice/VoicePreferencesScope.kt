// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.voice

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [VoicePreferencesRepository]'s `stateIn` collections.
 * Same pattern as `PracticePreferencesScope` and `SoundsPreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VoicePreferencesScope
