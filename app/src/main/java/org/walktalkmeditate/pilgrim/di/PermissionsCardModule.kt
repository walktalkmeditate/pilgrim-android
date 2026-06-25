// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.permissions.PermissionRitualStore
import org.walktalkmeditate.pilgrim.permissions.PermissionsRepository
import org.walktalkmeditate.pilgrim.ui.settings.permissions.AskedFlagSource
import org.walktalkmeditate.pilgrim.ui.settings.permissions.LivePermissionChecks
import org.walktalkmeditate.pilgrim.ui.settings.permissions.PermissionAskedStoreAdapter
import org.walktalkmeditate.pilgrim.ui.settings.permissions.PermissionChecksAdapter

@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionsCardModule {

    @Binds
    @Singleton
    abstract fun bindLiveChecks(impl: PermissionChecksAdapter): LivePermissionChecks

    @Binds
    @Singleton
    abstract fun bindAskedFlags(impl: PermissionAskedStoreAdapter): AskedFlagSource

    // PermissionsRepository is already an @Inject @Singleton; bind its
    // PermissionRitualStore facet so PermissionsViewModel can depend on the
    // narrow interface (and tests can fake it).
    @Binds
    @Singleton
    abstract fun bindPermissionRitualStore(impl: PermissionsRepository): PermissionRitualStore
}
