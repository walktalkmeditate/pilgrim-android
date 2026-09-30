// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.honor.WayStore
import org.walktalkmeditate.pilgrim.domain.Clock

@Module
@InstallIn(SingletonComponent::class)
object WayStoreModule {

    /**
     * `noBackupFilesDir/Ways`: never backed up or moved to a new device, so,
     * as iOS's backup exclusion does, a restore can't bring back swept
     * voices, and links and deltas drop on transfer exactly as on iOS.
     */
    @Provides
    @Singleton
    fun provideWayStore(@ApplicationContext context: Context, clock: Clock): WayStore =
        WayStore({ File(context.noBackupFilesDir, WAYS_DIRECTORY) }, clock)

    private const val WAYS_DIRECTORY = "Ways"
}
