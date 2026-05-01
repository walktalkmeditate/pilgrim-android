// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.walktalkmeditate.pilgrim.location.DefaultLocationCallbackBinder
import org.walktalkmeditate.pilgrim.location.FusedLocationSource
import org.walktalkmeditate.pilgrim.location.LocationCallbackBinder
import org.walktalkmeditate.pilgrim.location.LocationSource

@Module
@InstallIn(SingletonComponent::class)
abstract class LocationModule {
    @Binds
    abstract fun bindLocationSource(impl: FusedLocationSource): LocationSource

    @Binds
    abstract fun bindLocationCallbackBinder(
        impl: DefaultLocationCallbackBinder,
    ): LocationCallbackBinder
}
