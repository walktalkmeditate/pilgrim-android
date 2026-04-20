// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogScope
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadScheduler
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideSelectionScope
import org.walktalkmeditate.pilgrim.data.voiceguide.WorkManagerVoiceGuideDownloadScheduler

/**
 * Voice-guide layer bindings: scheduler interface → WorkManager
 * implementation, plus the two long-lived [CoroutineScope]s
 * used by the catalog and selection repositories. Mirrors the
 * shape of `AudioModule` (abstract class with `@Binds` + nested
 * companion `@Provides`).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class VoiceGuideModule {

    @Binds
    @Singleton
    abstract fun bindDownloadScheduler(
        impl: WorkManagerVoiceGuideDownloadScheduler,
    ): VoiceGuideDownloadScheduler

    companion object {
        /**
         * Long-lived scope for
         * [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideCatalogRepository]'s
         * `stateIn` collection + [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideDownloadObserver]'s
         * auto-select observer. `SupervisorJob` so one failed emission
         * doesn't tear the whole scope down; `Dispatchers.Default`
         * because the work is pure Flow composition + occasional
         * filesystem reads via explicit `withContext(Dispatchers.IO)`
         * where appropriate.
         */
        @Provides
        @Singleton
        @VoiceGuideCatalogScope
        fun provideVoiceGuideCatalogScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Long-lived scope for [VoiceGuideSelectionRepository]'s `stateIn`. */
        @Provides
        @Singleton
        @VoiceGuideSelectionScope
        fun provideVoiceGuideSelectionScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
