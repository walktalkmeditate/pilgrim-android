// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.ui.settings.about.AboutWalkSource
import org.walktalkmeditate.pilgrim.ui.settings.about.WalkRepositoryAboutSource

@Module
@InstallIn(SingletonComponent::class)
abstract class AboutModule {
    @Binds
    @Singleton
    abstract fun bindAboutWalkSource(impl: WalkRepositoryAboutSource): AboutWalkSource
}
