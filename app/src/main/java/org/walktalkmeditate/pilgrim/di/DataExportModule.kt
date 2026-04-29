// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.voice.VoiceRecordingFileSystem
import org.walktalkmeditate.pilgrim.ui.settings.data.DataExportEnv
import org.walktalkmeditate.pilgrim.ui.settings.data.RecordingsCountSource
import org.walktalkmeditate.pilgrim.ui.settings.data.WalkRepositoryRecordingsCountSource

/**
 * Stage 10-D: DI for the Data settings screen export feature.
 *
 *  - [DataExportEnv] — source dir is the canonical recordings root,
 *    target dir is `cacheDir/recordings_export/` (FileProvider-shareable
 *    per `res/xml/file_paths.xml`).
 *  - [RecordingsCountSource] — bound via the sibling [DataExportBindingsModule]
 *    so the `@Provides object` here stays simple.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataExportModule {

    @Provides
    @Singleton
    fun provideDataExportEnv(
        @ApplicationContext context: Context,
        fileSystem: VoiceRecordingFileSystem,
    ): DataExportEnv = DataExportEnv(
        sourceDir = { fileSystem.rootDir() },
        targetDir = { File(context.cacheDir, "recordings_export") },
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DataExportBindingsModule {

    @Binds
    @Singleton
    abstract fun bindRecordingsCountSource(
        impl: WalkRepositoryRecordingsCountSource,
    ): RecordingsCountSource
}
