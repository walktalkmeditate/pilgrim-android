// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.sounds

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [SoundsPreferencesRepository.soundsEnabled]'s `stateIn`.
 * Same pattern as `AppearancePreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SoundsPreferencesScope
