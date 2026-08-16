// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.audio.MediaCodecShareAudioTranscoder
import org.walktalkmeditate.pilgrim.audio.ShareAudioTranscoder
import org.walktalkmeditate.pilgrim.data.share.AndroidSharePhotoEncoder
import org.walktalkmeditate.pilgrim.data.share.ShareBaseUrl
import org.walktalkmeditate.pilgrim.data.share.ShareConfig
import org.walktalkmeditate.pilgrim.data.share.ShareHttpClient
import org.walktalkmeditate.pilgrim.data.share.SharePhotoEncoder

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
    @Singleton
    @ShareBaseUrl
    fun provideShareBaseUrl(): String = ShareConfig.BASE_URL

    @Provides
    @Singleton
    fun provideSharePhotoEncoder(impl: AndroidSharePhotoEncoder): SharePhotoEncoder = impl

    /**
     * Phase 19 U8: the only interface in the interactive-share stack, so
     * the only one needing a binding here. `SharePrepStore`,
     * `TourPhotoExporter` and `ShareRepairStore` are all
     * `@Singleton class X @Inject constructor(...)` concrete types —
     * Hilt constructs them from their own constructors and a
     * `@Provides` here would be a second, drift-prone declaration of the
     * same graph edge (same reason `ShareService` has no entry either).
     */
    @Provides
    @Singleton
    fun provideShareAudioTranscoder(impl: MediaCodecShareAudioTranscoder): ShareAudioTranscoder = impl

    private const val SHARE_CALL_TIMEOUT_SEC = 90L
}
