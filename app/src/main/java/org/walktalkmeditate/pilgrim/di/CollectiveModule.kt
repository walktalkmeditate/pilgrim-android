// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.collective.CollectiveConfig
import org.walktalkmeditate.pilgrim.data.collective.CollectiveRepoScope
import org.walktalkmeditate.pilgrim.data.collective.CounterBaseUrl
import org.walktalkmeditate.pilgrim.data.collective.CounterHttpClient

/**
 * Stage 8-B: DI wiring for the Collective Counter — short-call HTTP
 * client, base URL, and a long-lived fire-and-forget repo scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object CollectiveModule {

    @Provides
    @Singleton
    @CounterHttpClient
    fun provideCounterHttpClient(default: OkHttpClient): OkHttpClient =
        default.newBuilder()
            .callTimeout(COUNTER_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @CounterBaseUrl
    fun provideCounterBaseUrl(): String = CollectiveConfig.BASE_URL

    /**
     * Long-lived scope for the repository's fire-and-forget recordWalk
     * POST. Outlives any individual VM (viewModelScope cancellation on
     * Walk Summary nav-away would drop an in-flight POST and lose the
     * contribution). SupervisorJob keeps siblings alive after a child
     * failure.
     */
    @Provides
    @Singleton
    @CollectiveRepoScope
    fun provideCollectiveRepoScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val COUNTER_CALL_TIMEOUT_SEC = 10L
}
