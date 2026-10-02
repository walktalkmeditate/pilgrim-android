// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.honor.WayMediaDownloadScheduler
import org.walktalkmeditate.pilgrim.data.honor.WorkManagerWayMediaDownloadScheduler

/** The shared-walk media download's WorkManager end (UI process only). */
@Module
@InstallIn(SingletonComponent::class)
abstract class WayMediaModule {

    @Binds
    @Singleton
    abstract fun bindWayMediaDownloadScheduler(impl: WorkManagerWayMediaDownloadScheduler): WayMediaDownloadScheduler
}
