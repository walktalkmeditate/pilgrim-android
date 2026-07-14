// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.seek

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [SeekPreferencesRepository]'s `stateIn` collections.
 * Same pattern as `PracticePreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SeekPreferencesScope
