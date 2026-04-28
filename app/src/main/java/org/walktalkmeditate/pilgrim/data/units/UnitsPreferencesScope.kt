// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.data.units

import javax.inject.Qualifier

/**
 * Qualifier for the long-lived [kotlinx.coroutines.CoroutineScope]
 * that backs [UnitsPreferencesRepository.distanceUnits]'s `stateIn`.
 * Same pattern as `SoundsPreferencesScope`.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnitsPreferencesScope
