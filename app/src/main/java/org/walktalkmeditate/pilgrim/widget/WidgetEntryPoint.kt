// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.walktalkmeditate.pilgrim.data.units.UnitsPreferencesRepository

/**
 * Hilt EntryPoint bridge for the Glance composable. Glance widgets are
 * constructed reflectively by the system and can't be Hilt-injected
 * directly; the composable resolves Singletons via
 * `EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)`
 * at compose time.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetStateRepository(): WidgetStateRepository
    fun unitsPreferencesRepository(): UnitsPreferencesRepository
}
