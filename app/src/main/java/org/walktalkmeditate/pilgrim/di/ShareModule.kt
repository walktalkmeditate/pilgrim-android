// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.share.ShareBaseUrl
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.data.share.ShareHttpClient

/**
 * Stage 8-A: DI wiring for Share Worker — extends [NetworkModule]
 * indirectly by depending on the shared singleton [OkHttpClient] +
 * `Json`.
 *
 * Provides:
 *  - `@ShareHttpClient` OkHttpClient — the default client rebuilt
 *    with a 90s call timeout (vs the default 45s) to accommodate the
 *    worker's server-side Mapbox image generation + R2 writes on
 *    slow connections. Reuses the default client's connection pool
 *    + dispatcher via `newBuilder()`, so no new thread pool is spun
 *    up.
 *  - `@ShareBaseUrl` String — the Cloudflare Worker origin.
 */
@Module
@InstallIn(SingletonComponent::class)
object ShareModule {

    @Provides
    @Singleton
    @ShareHttpClient
    fun provideShareHttpClient(default: OkHttpClient): OkHttpClient =
        default.newBuilder()
            .callTimeout(SHARE_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    @Provides
    @ShareBaseUrl
    fun provideShareBaseUrl(): String = ShareConfig.BASE_URL

    private const val SHARE_CALL_TIMEOUT_SEC = 90L
}
