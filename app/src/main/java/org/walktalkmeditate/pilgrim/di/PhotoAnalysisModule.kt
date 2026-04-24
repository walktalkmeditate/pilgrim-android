// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.data.photo.BitmapLoader
import org.walktalkmeditate.pilgrim.data.photo.ContentResolverBitmapLoader
import org.walktalkmeditate.pilgrim.data.photo.MlKitPhotoLabeler
import org.walktalkmeditate.pilgrim.data.photo.PhotoAnalysisScheduler
import org.walktalkmeditate.pilgrim.data.photo.PhotoLabeler
import org.walktalkmeditate.pilgrim.data.photo.WorkManagerPhotoAnalysisScheduler

/**
 * Stage 7-B bindings for the photo-analysis pipeline. Matches the
 * shape of [TranscriptionModule] — interface-at-the-boundary so tests
 * can substitute fakes via Hilt test modules.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PhotoAnalysisModule {

    @Binds
    @Singleton
    abstract fun bindPhotoAnalysisScheduler(
        impl: WorkManagerPhotoAnalysisScheduler,
    ): PhotoAnalysisScheduler

    @Binds
    @Singleton
    abstract fun bindPhotoLabeler(
        impl: MlKitPhotoLabeler,
    ): PhotoLabeler

    @Binds
    @Singleton
    abstract fun bindBitmapLoader(
        impl: ContentResolverBitmapLoader,
    ): BitmapLoader
}
