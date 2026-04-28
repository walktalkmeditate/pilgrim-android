// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.practice

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [PracticePreferencesRepository]'s `stateIn` collections.
 * Same pattern as `SoundsPreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PracticePreferencesScope
