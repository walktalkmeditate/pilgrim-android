// SPDX-License-Identifier: GPL-3.0-or-later
package org.walktalkmeditate.pilgrim.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.walktalkmeditate.pilgrim.data.audio.AudioConfig
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestScope
import org.walktalkmeditate.pilgrim.data.audio.AudioManifestUrl
import org.walktalkmeditate.pilgrim.data.soundscape.SoundscapeBaseUrl
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideConfig
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestScope
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuideManifestUrl
import org.walktalkmeditate.pilgrim.data.voiceguide.VoiceGuidePromptBaseUrl
import org.walktalkmeditate.pilgrim.data.weather.OpenMeteoClient
import org.walktalkmeditate.pilgrim.data.weather.WeatherFetching

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

    /** Unified audio manifest URL (Stage 5-F). */
    @Provides
    @Singleton
    @AudioManifestUrl
    fun provideAudioManifestUrl(): String = AudioConfig.MANIFEST_URL

    /**
     * Soundscape CDN base URL — `<base>/soundscape/<assetId>.aac`
     * per asset. Matches iOS's reconstructed-from-id convention.
     */
    @Provides
    @Singleton
    @SoundscapeBaseUrl
    fun provideSoundscapeBaseUrl(): String = AudioConfig.BASE_URL

    /**
     * Long-lived scope for [org.walktalkmeditate.pilgrim.data.audio.AudioManifestService]'s
     * sync coroutine. Same shape as the voice-guide manifest scope.
     */
    @Provides
    @Singleton
    @AudioManifestScope
    fun provideAudioManifestScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Stage 12-A: dedicated OkHttpClient for Open-Meteo current-weather
     * fetches. Tighter timeouts than the project default
     * ([provideOkHttpClient]) because this is on the walk-start
     * critical path — a slow weather fetch must NOT block the user
     * starting their walk. `retryOnConnectionFailure = true` is
     * OkHttp's default, restated here so the policy is explicit and
     * survives any future builder reshuffle.
     */
    @Provides
    @Singleton
    @WeatherHttpClient
    fun provideWeatherHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(WEATHER_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(WEATHER_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(WEATHER_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    private const val CONNECT_TIMEOUT_SEC = 10L
    private const val READ_TIMEOUT_SEC = 30L
    private const val CALL_TIMEOUT_SEC = 45L

    private const val WEATHER_CONNECT_TIMEOUT_SEC = 5L
    private const val WEATHER_READ_TIMEOUT_SEC = 5L
    private const val WEATHER_CALL_TIMEOUT_SEC = 10L

    /**
     * Stage 12-A: weather seam consumed by [WalkViewModel]'s `+2s` /
     * `+10s` retry policy. Production binding is [OpenMeteoClient];
     * tests substitute a fake. Same `@Provides`-style binding as the
     * Stage 11 `provideMilestoneStorage` / `provideMilestoneChecking`
     * seams in `CollectiveModule` so this `object` module stays
     * uniform without converting to an `abstract class`.
     */
    @Provides
    @Singleton
    fun provideWeatherFetching(impl: OpenMeteoClient): WeatherFetching = impl
}

/**
 * Stage 12-A qualifier for the Open-Meteo OkHttpClient. Lives at
 * file-level so [NetworkModule.provideWeatherHttpClient] and
 * `OpenMeteoClient` (in `data/weather/`) can share the annotation
 * without a circular import.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class WeatherHttpClient
