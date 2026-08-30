// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import org.walktalkmeditate.pilgrim.core.threads.AutoTranscriptionSkipState
import org.walktalkmeditate.pilgrim.core.threads.DefaultAutoTranscriptionSkipState
import org.walktalkmeditate.pilgrim.core.threads.ThreadsBackfillScheduler
import org.walktalkmeditate.pilgrim.core.threads.WorkManagerThreadsBackfillScheduler

/** One Hilt module per concern (`di/` convention) — U6's backfill scheduler
 * + the auto-transcription skip-reason holder it shares a battery gate with. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ThreadsBackfillModule {
    @Binds
    @Singleton
    abstract fun bindThreadsBackfillScheduler(
        impl: WorkManagerThreadsBackfillScheduler,
    ): ThreadsBackfillScheduler

    @Binds
    @Singleton
    abstract fun bindAutoTranscriptionSkipState(
        impl: DefaultAutoTranscriptionSkipState,
    ): AutoTranscriptionSkipState
}
