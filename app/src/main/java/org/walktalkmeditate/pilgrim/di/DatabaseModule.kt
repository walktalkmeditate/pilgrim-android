// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.PilgrimDatabase
// PilgrimDatabase is provided below and then consumed by DAO providers and
// also injected into WalkRepository directly for transactional operations.
import org.walktalkmeditate.pilgrim.data.dao.ActivityIntervalDao
import org.walktalkmeditate.pilgrim.data.dao.AltitudeSampleDao
import org.walktalkmeditate.pilgrim.data.dao.RouteDataSampleDao
import org.walktalkmeditate.pilgrim.data.dao.VoiceRecordingDao
import org.walktalkmeditate.pilgrim.data.dao.WalkDao
import org.walktalkmeditate.pilgrim.data.dao.WalkEventDao
import org.walktalkmeditate.pilgrim.data.dao.WalkPhotoDao
import org.walktalkmeditate.pilgrim.data.dao.WaypointDao

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun providePilgrimDatabase(
        @ApplicationContext context: Context,
    ): PilgrimDatabase = Room.databaseBuilder(
        context,
        PilgrimDatabase::class.java,
        PilgrimDatabase.DATABASE_NAME,
    )
        .addMigrations(
            PilgrimDatabase.MIGRATION_2_3,
            PilgrimDatabase.MIGRATION_3_4,
            PilgrimDatabase.MIGRATION_4_5,
            PilgrimDatabase.MIGRATION_5_6,
        )
        .build()

    @Provides
    fun provideWalkDao(db: PilgrimDatabase): WalkDao = db.walkDao()

    @Provides
    fun provideRouteDataSampleDao(db: PilgrimDatabase): RouteDataSampleDao = db.routeDataSampleDao()

    @Provides
    fun provideAltitudeSampleDao(db: PilgrimDatabase): AltitudeSampleDao = db.altitudeSampleDao()

    @Provides
    fun provideWalkEventDao(db: PilgrimDatabase): WalkEventDao = db.walkEventDao()

    @Provides
    fun provideActivityIntervalDao(db: PilgrimDatabase): ActivityIntervalDao = db.activityIntervalDao()

    @Provides
    fun provideWaypointDao(db: PilgrimDatabase): WaypointDao = db.waypointDao()

    @Provides
    fun provideVoiceRecordingDao(db: PilgrimDatabase): VoiceRecordingDao = db.voiceRecordingDao()

    @Provides
    fun provideWalkPhotoDao(db: PilgrimDatabase): WalkPhotoDao = db.walkPhotoDao()
}
