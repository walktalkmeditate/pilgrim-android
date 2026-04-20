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
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideConfig
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestScope
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestUrl
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePromptBaseUrl

/**
 * First-introduction HTTP + JSON infrastructure for Stage 5-C. The
 * `OkHttpClient` and `Json` here are project-wide `@Singleton`
 * instances so future Phase 8 work (Collective Counter, Share
 * Worker) can reuse them without re-establishing the stack.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /**
     * Default OkHttp client for read-only JSON manifest fetches.
     *
     * Timeouts are defensive defaults for a user-triggered background
     * sync: 10s connect, 30s read, 45s total. No retry interceptor —
     * OkHttp's built-in `retryOnConnectionFailure = true` (default)
     * handles transient DNS / connection resets once; exponential
     * backoff belongs in Phase 8's shared worker policy when
     * background uploads / writes arrive.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()

    /**
     * Project-wide `Json` instance with forward-compatibility defaults.
     *  - `ignoreUnknownKeys`: iOS may add new manifest fields without
     *    breaking Android — we decode what we recognize, skip the rest.
     *  - `explicitNulls = false`: missing optional fields in JSON
     *    (e.g., a pack without `meditationPrompts`) decode to their
     *    Kotlin default (`null`), instead of throwing.
     */
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Voice-guide manifest URL — qualified so tests can substitute. */
    @Provides
    @Singleton
    @VoiceGuideManifestUrl
    fun provideVoiceGuideManifestUrl(): String = VoiceGuideConfig.MANIFEST_URL

    /** Voice-guide prompt CDN base URL — `<base><r2Key>` per prompt. */
    @Provides
    @Singleton
    @VoiceGuidePromptBaseUrl
    fun provideVoiceGuidePromptBaseUrl(): String = VoiceGuideConfig.PROMPT_BASE_URL

    /**
     * Long-lived scope for
     * [org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestService]'s
     * sync coroutine. Lives for the app process; `SupervisorJob` so a
     * failed fetch doesn't tear the scope down. Lives in
     * [NetworkModule] (not `AudioModule`) because the manifest
     * fetcher is a network/data concern, not an audio one. See
     * [VoiceGuideManifestScope].
     */
    @Provides
    @Singleton
    @VoiceGuideManifestScope
    fun provideVoiceGuideManifestScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 30L
    private const val CALL_TIMEOUT_SEC = 45L
}
